package gh.nusashell.nusadesk.infrastructure.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gh.nusashell.nusadesk.application.webapp.WebAppCredentialStore;
import gh.nusashell.nusadesk.application.webapp.WebAppDownloadPort;
import gh.nusashell.nusadesk.domain.webapp.DownloadFileName;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppDownloadStatus;
import gh.nusashell.nusadesk.domain.webapp.WebAppDownloadStatus.State;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;
import gh.nusashell.nusadesk.infrastructure.runtimehost.LoopbackNavigationPolicy;

/** Streams owned WebView GETs into the active guest's existing /root/Downloads. */
public final class GuestWebAppDownloads implements WebAppDownloadPort {
    private final Path rootfs;
    private final WebAppDefinition definition;
    private final WebAppCredentialStore credentials;
    private final LoopbackNavigationPolicy policy;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile HttpURLConnection active;
    private final java.util.Set<BinarySink> binary = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public GuestWebAppDownloads(Path rootfs, WebAppDefinition definition,
                               WebAppCredentialStore credentials) {
        this.rootfs = rootfs;
        this.definition = definition;
        this.credentials = credentials;
        this.policy = new LoopbackNavigationPolicy(definition.getEndpointUrl());
    }

    @Override public synchronized void download(String url, String userAgent, String cookie,
                                                String suggestedName, Listener listener) {
        if (closed) return;
        String name = DownloadFileName.safe(suggestedName);
        listener.onStatus(new WebAppDownloadStatus(State.QUEUED, name, 0, -1, null));
        worker.execute(() -> fetch(url, userAgent, cookie, name, listener));
    }

    private void fetch(String url, String userAgent, String cookie, String name, Listener listener) {
        try {
            String current = url;
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (closed) return;
                LoopbackNavigationPolicy.Decision decision = policy.classify(current);
                if (decision == LoopbackNavigationPolicy.Decision.EXTERNAL) {
                    listener.onExternal(current);
                    return;
                }
                if (decision != LoopbackNavigationPolicy.Decision.OWNED
                        || current.startsWith("about:")) {
                    listener.onBlocked(current);
                    return;
                }
                HttpURLConnection connection = open(current, userAgent, cookie);
                active = connection;
                try {
                    if (closed) return;
                    int code = connection.getResponseCode();
                    // The same app's saved Basic pair can answer a protected attachment.
                    if (code == 401 && credentials != null) {
                        WebAppSignInCredential credential = credentials.find(definition.getId());
                        if (credential != null) {
                            connection.disconnect();
                            connection = open(current, userAgent, cookie);
                            active = connection;
                            connection.setRequestProperty("Authorization", WebAppFaviconFetcher.basicAuthorizationHeader(
                                    credential.getUsername(), credential.getPassword()));
                            code = connection.getResponseCode();
                        }
                    }
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("Redirect has no Location");
                        current = URI.create(current).resolve(location).toString();
                        continue;
                    }
                    if (code != 200) throw new IOException("HTTP " + code);
                    if (closed) return;
                    long total = connection.getContentLengthLong();
                    long count = 0;
                    long lastReport = 0;
                    try (InputStream input = connection.getInputStream();
                         GuestDownloadFile output = new GuestDownloadFile(rootfs, name, total)) {
                        listener.onStatus(new WebAppDownloadStatus(State.DOWNLOADING, name, 0, total, null));
                        byte[] buffer = new byte[32 * 1024];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (closed || Thread.currentThread().isInterrupted()) return;
                            output.append(buffer, read);
                            count += read;
                            long now = System.nanoTime();
                            if (now - lastReport >= 200_000_000L) {
                                listener.onStatus(new WebAppDownloadStatus(State.DOWNLOADING, name, count, total, null));
                                lastReport = now;
                            }
                        }
                        if (closed) return;
                        String target = output.finish();
                        listener.onStatus(new WebAppDownloadStatus(State.SAVED, target, count, total, null));
                    }
                    return;
                } finally {
                    connection.disconnect();
                    active = null;
                }
            }
            throw new IOException("Too many redirects");
        } catch (IOException | RuntimeException failure) {
            if (!closed) listener.onStatus(new WebAppDownloadStatus(State.FAILED, name, 0, -1,
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    private static HttpURLConnection open(String url, String userAgent, String cookie) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (userAgent != null && !userAgent.isEmpty()) connection.setRequestProperty("User-Agent", userAgent);
        if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        return connection;
    }

    @Override public synchronized BinaryTransfer beginBinary(String suggestedName, long length,
                                                            Listener listener, Completion ready) {
        if (length < 0) throw new IllegalArgumentException("Binary length must be known");
        BinarySink sink = new BinarySink(DownloadFileName.safe(suggestedName), length, listener);
        if (closed) { ready.complete("Downloads closed"); return sink; }
        binary.add(sink);
        listener.onStatus(new WebAppDownloadStatus(State.QUEUED, sink.name, 0, length, null));
        worker.execute(() -> sink.start(ready));
        return sink;
    }

    private synchronized void submit(Runnable action) {
        if (!closed) worker.execute(action);
    }

    private final class BinarySink implements BinaryTransfer {
        private final String name;
        private final long length;
        private final Listener listener;
        private GuestDownloadFile file;
        private boolean ended;
        private long lastReport;

        BinarySink(String name, long length, Listener listener) {
            this.name = name; this.length = length; this.listener = listener;
        }

        synchronized void start(Completion ready) {
            if (closed || ended) return;
            try {
                file = new GuestDownloadFile(rootfs, name, length);
                listener.onStatus(new WebAppDownloadStatus(State.DOWNLOADING, name, 0, length, null));
                ready.complete(null);
            } catch (IOException failure) { fail(failure.getMessage(), ready); }
        }

        @Override public void append(byte[] chunk, Completion completion) {
            submit(() -> write(chunk, completion));
        }

        private synchronized void write(byte[] chunk, Completion completion) {
            if (closed || ended) { completion.complete("Download ended"); return; }
            try {
                if (chunk == null || chunk.length > 32768) throw new IOException("Invalid download chunk");
                file.append(chunk, chunk.length);
                long now = System.nanoTime();
                if (now - lastReport >= 200_000_000L) {
                    listener.onStatus(new WebAppDownloadStatus(State.DOWNLOADING, name, file.count(), length, null));
                    lastReport = now;
                }
                completion.complete(null);
            } catch (IOException failure) { fail(failure.getMessage(), completion); }
        }

        @Override public void finish(Completion completion) {
            submit(() -> complete(completion));
        }

        private synchronized void complete(Completion completion) {
            if (closed || ended) { completion.complete("Download ended"); return; }
            try {
                String target = file.finish();
                cleanup();
                listener.onStatus(new WebAppDownloadStatus(State.SAVED, target, length, length, null));
                completion.complete(null);
            } catch (IOException failure) { fail(failure.getMessage(), completion); }
        }

        private synchronized void fail(String reason, Completion completion) {
            cleanup();
            if (reason == null) reason = "Export failed";
            if (!closed) listener.onStatus(new WebAppDownloadStatus(State.FAILED, name, 0, length, reason));
            completion.complete(reason);
        }

        @Override public void abort(String reason) {
            submit(() -> fail(reason, error -> { }));
        }

        synchronized void cleanup() {
            ended = true;
            binary.remove(this);
            if (file != null) {
                try { file.close(); } catch (IOException ignored) { /* Best-effort teardown. */ }
                file = null;
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        worker.shutdownNow();
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
        for (BinarySink sink : binary) sink.cleanup();
    }
}
