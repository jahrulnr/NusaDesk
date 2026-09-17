package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Semaphore;

import gh.nusashell.nusadesk.infrastructure.proot.ProotBindMount;

/**
 * Session-scoped Android capability bridge.
 *
 * <p>The bridge binds an ephemeral TCP port on IPv4 loopback only and serves
 * one bounded, line-delimited JSON request per connection. Loopback is only a
 * reachability boundary: the generated token authenticates every request
 * because other apps can share the device loopback namespace. The bridge is
 * never LAN-bound and exposes no arbitrary command or Android class dispatch:
 * every method is a fixed allowlist string in
 * {@link AndroidCapabilityRequestHandler} and no method takes request
 * parameters.</p>
 *
 * <p>Shipped capabilities: Android BatteryManager projected through
 * {@code battery.status} and, when the fixed app-private projection can be
 * prepared, through a best-effort guest
 * {@code /sys/class/power_supply/battery} tree; one-shot
 * {@code sensor.accelerometer} / {@code sensor.gyroscope} reads; a foreground
 * one-shot {@code location.get} fix; read-only {@code contacts.list},
 * {@code calllog.list}, {@code sms.inbox}, {@code telephony.info},
 * {@code telephony.cellinfo}; a foreground-only pull location stream
 * ({@code location.stream.start} / {@code location.stream.poll} /
 * {@code location.stream.stop}); and the live-only unified media session
 * ({@code media.start} / {@code media.status} / {@code media.stop}) that
 * captures camera + microphone with hardware encoders and serves one
 * loopback RTSP stream. Media is live-only: nothing writes a capture
 * artifact, no artifact directory exists, and no artifact path or bind is
 * exposed to the guest. Background location, a location foreground-service
 * start, and permission activities are deliberately not wired in this
 * phase.</p>
 *
 * <p>Every capability source is constructed once per bridge session, owned by
 * the bridge, and closed in both {@link #close()} paths (a running session
 * and a session that never started).</p>
 */
public final class AndroidCapabilityBridge implements AutoCloseable {
    private static final String TAG = "AndroidCapabilityBridge";
    private static final int BACKLOG = 8;
    private static final int SOCKET_TIMEOUT_MILLIS = 15_000;
    private static final int MAX_CONNECTIONS = 4;
    private static final long SYSFS_REFRESH_SECONDS = 2L;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String ENV_ADDRESS = "NUSADESK_ANDROID_BRIDGE_ADDRESS";
    public static final String ENV_PORT = "NUSADESK_ANDROID_BRIDGE_PORT";
    public static final String ENV_TOKEN = "NUSADESK_ANDROID_BRIDGE_TOKEN";
    public static final String ENV_PROTOCOL = "NUSADESK_ANDROID_BRIDGE_PROTOCOL";
    /** Guest-visible app-owned session metadata file, strict-bound at /run. */
    public static final String GUEST_SESSION_CONFIG_PATH =
            "/run/nusadesk/android-bridge.env";
    private static final String SESSION_CONFIG_RELATIVE_PATH =
            "linux-wrapper/state/android-bridge/android-bridge.env";
    private static final String SESSION_CONFIG_CONTENT_PREFIX =
            "# NusaDesk Android capability bridge; session-scoped\n";

    private final Context context;
    private final Path sessionConfig;
    private final GuestBatterySysfs batterySysfs;
    private final AndroidSensorManagerSource sensorSource;
    private final AndroidLocationManagerSource locationSource;
    private final ContactsSource contactsSource;
    private final CallLogSource callLogSource;
    private final SmsSource smsSource;
    private final TelephonyInfoSource telephonyInfoSource;
    private final TelephonyCellSource telephonyCellSource;
    private final LocationStreamSession locationStream;
    private final LiveMediaController mediaController;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);
    private final ExecutorService connectionExecutor = Executors.newFixedThreadPool(
            MAX_CONNECTIONS, namedFactory("android-capability-client"));
    private final ScheduledExecutorService refreshExecutor = Executors.newSingleThreadScheduledExecutor(
            namedFactory("android-capability-refresh"));

    private volatile ServerSocket serverSocket;
    private volatile AndroidCapabilityRequestHandler requestHandler;
    private volatile Thread acceptThread;
    private volatile String token;
    private volatile int port = -1;
    private volatile boolean sysfsReady;
    private boolean closed;

    public AndroidCapabilityBridge(Context context) {
        this(context, new AndroidLocationStreamSession(context),
                new AndroidLiveMediaController(context));
    }

    /**
     * Build the bridge with an explicit closable location stream adapter;
     * every other adapter is constructed from the app context. Package-private
     * so tests can substitute a fake and observe close ownership; production
     * uses {@link #AndroidCapabilityBridge(Context)}.
     */
    AndroidCapabilityBridge(Context context, LocationStreamSession locationStream) {
        this(context, locationStream, new AndroidLiveMediaController(context));
    }

    /**
     * Build the bridge with explicit closable location stream and live media
     * controller adapters; every other adapter is constructed from the app
     * context. Package-private so tests can substitute fakes and observe
     * close ownership.
     */
    AndroidCapabilityBridge(Context context, LocationStreamSession locationStream,
                            LiveMediaController mediaController) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (locationStream == null) {
            throw new IllegalArgumentException("locationStream must not be null");
        }
        if (mediaController == null) {
            throw new IllegalArgumentException("mediaController must not be null");
        }
        this.context = context.getApplicationContext();
        this.sessionConfig = this.context.getFilesDir().toPath()
                .resolve(SESSION_CONFIG_RELATIVE_PATH);
        this.batterySysfs = new GuestBatterySysfs(
                this.context.getFilesDir().toPath(),
                new AndroidBatteryStatusProvider(this.context));
        this.sensorSource = new AndroidSensorManagerSource(this.context);
        this.locationSource = new AndroidLocationManagerSource(this.context);
        this.contactsSource = new AndroidContactsSource(this.context);
        this.callLogSource = new AndroidCallLogSource(this.context);
        this.smsSource = new AndroidSmsSource(this.context);
        this.telephonyInfoSource = new AndroidTelephonyInfoSource(this.context);
        this.telephonyCellSource = new AndroidTelephonyCellSource(this.context);
        this.locationStream = locationStream;
        this.mediaController = mediaController;
    }

    /** Start the session bridge; each bridge instance is single-use and never replaces a live token. */
    public synchronized void start() throws IOException {
        if (running.get()) {
            throw new IllegalStateException("Android capability bridge is already running");
        }
        if (closed) {
            throw new IllegalStateException("Android capability bridge instances are single-use");
        }
        String nextToken = randomToken();
        AndroidCapabilityRequestHandler nextHandler = new AndroidCapabilityRequestHandler(
                nextToken, new AndroidBatteryStatusProvider(context), sensorSource,
                locationSource, contactsSource, callLogSource, smsSource,
                telephonyInfoSource, telephonyCellSource, locationStream,
                mediaController);
        ServerSocket nextServer = new ServerSocket();
        try {
            // Explicit IPv4 loopback: binding wildcard or an IPv6-any address
            // would weaken the documented reachability boundary.
            nextServer.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), BACKLOG);
            nextServer.setSoTimeout(500);
            token = nextToken;
            port = nextServer.getLocalPort();
            requestHandler = nextHandler;
            serverSocket = nextServer;
            sysfsReady = batterySysfs.prepare();
            writeSessionConfig(nextToken, port);
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "android-capability-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            refreshExecutor.scheduleWithFixedDelay(batterySysfs::refresh,
                    SYSFS_REFRESH_SECONDS, SYSFS_REFRESH_SECONDS, TimeUnit.SECONDS);
        } catch (IOException | RuntimeException e) {
            try {
                nextServer.close();
            } catch (IOException ignored) {
                // Preserve the original start failure.
            }
            running.set(false);
            token = null;
            port = -1;
            requestHandler = null;
            serverSocket = null;
            closeCapabilitySources();
            deleteSessionConfig();
            batterySysfs.clear();
            throw e;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return port;
    }

    /** Session token used only to construct the guest process environment. */
    public String getToken() {
        return token;
    }

    public boolean hasBatterySysfsProjection() {
        return sysfsReady;
    }

    /** Build the fixed guest environment for the current bridge session. */
    public Map<String, String> environment() {
        if (!running.get() || token == null || port < 1) {
            return Collections.emptyMap();
        }
        Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put(ENV_ADDRESS, "127.0.0.1");
        environment.put(ENV_PORT, String.valueOf(port));
        environment.put(ENV_TOKEN, token);
        environment.put(ENV_PROTOCOL, String.valueOf(AndroidCapabilityProtocol.VERSION));
        return environment;
    }

    /**
     * Prepare the safe guest parents and return the product-owned binds: the
     * session config file and the best-effort sysfs projection. The RPC
     * bridge stays usable if an optional projection cannot be mounted. Media
     * (camera/microphone) is intentionally not wired in this phase, so no
     * artifact directories are created or bound.
     */
    public List<ProotBindMount> requiredBinds(java.nio.file.Path rootfs) {
        if (!running.get()) {
            return Collections.emptyList();
        }
        List<ProotBindMount> binds = new ArrayList<>();
        if (rootfs != null
                && Files.isRegularFile(sessionConfig, LinkOption.NOFOLLOW_LINKS)
                && createGuestParentDirs(rootfs, rootfs.resolve("run/nusadesk"))) {
            binds.add(ProotBindMount.ofStrict(
                    sessionConfig.toString(), GUEST_SESSION_CONFIG_PATH));
        }
        if (sysfsReady) {
            binds.addAll(batterySysfs.requiredBinds(rootfs));
        }
        return binds;
    }

    private void writeSessionConfig(String sessionToken, int sessionPort) throws IOException {
        Path parent = sessionConfig.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("unsafe Android bridge state directory");
        }
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(sessionConfig)) {
            throw new IOException("unsafe Android bridge session config");
        }
        String content = SESSION_CONFIG_CONTENT_PREFIX
                + ENV_ADDRESS + "=127.0.0.1\n"
                + ENV_PORT + "=" + sessionPort + "\n"
                + ENV_TOKEN + "=" + sessionToken + "\n"
                + ENV_PROTOCOL + "=" + AndroidCapabilityProtocol.VERSION + "\n";
        Path temporary = sessionConfig.resolveSibling("." + sessionConfig.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.US_ASCII));
            try {
                Files.setPosixFilePermissions(temporary,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IllegalArgumentException
                     | SecurityException ignored) {
                // The app-private files provider may not expose POSIX modes.
            }
            try {
                Files.move(temporary, sessionConfig, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                try {
                    Files.move(temporary, sessionConfig, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackFailed) {
                    fallbackFailed.addSuppressed(atomicFailed);
                    throw fallbackFailed;
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void deleteSessionConfig() {
        try {
            if (!Files.isSymbolicLink(sessionConfig)) {
                Files.deleteIfExists(sessionConfig);
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not clear Android bridge session config", e);
        }
    }

    private static boolean createGuestParentDirs(Path rootfs, Path guestParent) {
        if (rootfs == null || guestParent == null || !guestParent.startsWith(rootfs)) {
            return false;
        }
        Path cursor = rootfs;
        for (Path segment : rootfs.relativize(guestParent)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)
                    || (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))) {
                return false;
            }
        }
        try {
            Files.createDirectories(guestParent);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            ServerSocket server = serverSocket;
            if (server == null) {
                return;
            }
            try {
                Socket client = server.accept();
                if (!connectionSlots.tryAcquire()) {
                    closeQuietly(client);
                    continue;
                }
                try {
                    connectionExecutor.execute(() -> {
                        try {
                            serve(client);
                        } finally {
                            connectionSlots.release();
                        }
                    });
                } catch (RuntimeException rejected) {
                    connectionSlots.release();
                    closeQuietly(client);
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // Poll running so close() does not depend on interrupting accept.
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "capability bridge accept failed", e);
                }
                return;
            } catch (RuntimeException e) {
                if (running.get()) {
                    Log.w(TAG, "capability bridge client dispatch failed", e);
                }
            }
        }
    }

    private void serve(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            String frame = readFrame(socket);
            AndroidCapabilityProtocol.Request request =
                    AndroidCapabilityProtocol.decodeRequest(frame);
            AndroidCapabilityProtocol.Response response = requestHandler.handle(request);
            String encoded = AndroidCapabilityProtocol.encodeResponse(response);
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(encoded);
            writer.write('\n');
            writer.flush();
        } catch (IOException | RuntimeException e) {
            // A disconnect, malformed frame, or client timeout is scoped to one
            // connection; it must not stop the bridge or leak platform details.
        }
    }

    private static String readFrame(Socket socket) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        java.io.InputStream input = socket.getInputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                break;
            }
            if (bytes.size() >= AndroidCapabilityProtocol.MAX_FRAME_BYTES) {
                // Keep the returned frame bounded; the decoder will reject it.
                return "{";
            }
            bytes.write(value);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeCapabilitySources();
        if (!running.getAndSet(false)) {
            // The bridge never started (or start failed): the sources were
            // still constructed for the session and must be closed too.
            deleteSessionConfig();
            batterySysfs.clear();
            return;
        }
        ServerSocket server = serverSocket;
        serverSocket = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                Log.w(TAG, "could not close capability bridge socket", e);
            }
        }
        Thread accept = acceptThread;
        acceptThread = null;
        if (accept != null) {
            accept.interrupt();
        }
        refreshExecutor.shutdownNow();
        connectionExecutor.shutdownNow();
        batterySysfs.clear();
        deleteSessionConfig();
        requestHandler = null;
        token = null;
        port = -1;
        sysfsReady = false;
    }

    /**
     * Close every capability source owned by this bridge session, including
     * the location stream and the live media session (which stops its
     * foreground service and tears the pipeline down). Idempotent and safe in
     * both {@link #close()} paths (running and never-started).
     */
    private void closeCapabilitySources() {
        sensorSource.close();
        locationSource.close();
        locationStream.close();
        mediaController.close();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ThreadFactory namedFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort rejection path.
        }
    }
}
