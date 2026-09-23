package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Wifi extras capability domain: the local-only hotspot, network
 * suggestions, and a high-performance wifi lock. There is no upstream
 * Termux:API counterpart; the {@code wifi.hotspot.*}, {@code wifi.suggest.*},
 * and {@code wifi.lock.*} surface is NusaDesk-native. The wifi radio toggle
 * stays with {@link WifiModule}'s {@code wifi.set} (typed-absent on Android
 * 10+), saved-network enumeration has no public API, and internet tethering
 * is {@code TetheringManager}'s {@code @SystemApi} — none of those are
 * reachable here by design.
 *
 * <p>{@code wifi.hotspot.start} (no params) runs
 * {@link WifiManager#startLocalOnlyHotspot}: a soft AP with no internet
 * backhaul, one reservation at a time. The call needs {@code CHANGE_WIFI_STATE}
 * plus the location grant (fine location through API 32,
 * {@code NEARBY_WIFI_DEVICES} on API 33+), the same gate as
 * {@code wifi.scaninfo}; a missing grant answers {@code wifi-permission-required}
 * / {@code wifi-permission-denied}. A reservation this module already holds —
 * or the platform's {@code ERROR_INCOMPATIBLE_MODE}, which is the only public
 * signal for an occupied/incompatible softap (another hotspot or tethering is
 * active, or the app is not foreground) — answers {@code wifi-hotspot-in-use}.
 * {@code ERROR_TETHERING_DISALLOWED} means the device policy forbids hotspot
 * creation and answers
 * {@code wifi-hotspot-unsupported:this device does not support a local-only hotspot};
 * the remaining codes are {@code wifi-hotspot-failed:<reason>}. The bridge
 * call blocks the connection thread until the platform callback resolves,
 * bounded at {@link #hotspotTimeoutMillis}. On success the fields are
 * {@code ssid}, {@code passphrase}, {@code security_type}, and
 * {@code running=true}.</p>
 *
 * <p>{@code wifi.hotspot.stop} (no params) closes the held reservation and is
 * idempotent: {@code stopped=true} always, {@code was_running} whether a live
 * reservation was actually closed. {@code wifi.hotspot.status} (no params)
 * reports {@code running} plus {@code ssid}/{@code passphrase} (empty strings
 * when nothing runs). A platform-side stop (wifi switched off, process death
 * of the reservation) flips {@code running} to false without an error.</p>
 *
 * <p>{@code wifi.suggest.*} wraps the network-suggestion API, which is gated
 * to API 30+: below it the three methods answer
 * {@code wifi-suggest-unsupported:requires Android 11}. {@code wifi.suggest.add}
 * (params {@code ssid} required &le;64 chars, {@code passphrase} optional
 * &le;128 chars — absent means an open-network suggestion — {@code priority}
 * optional 0..1000, {@code is_hidden} optional bool) calls
 * {@code addNetworkSuggestions} and answers {@code added=true} +
 * {@code ssid}; a platform refusal is
 * {@code wifi-suggest-failed:<reason>}. {@code wifi.suggest.remove}
 * (param {@code ssid}) removes every stored suggestion for that SSID and
 * answers {@code removed=true}. The platform matches removal on the whole
 * stored suggestion object — SSID, passphrase, security type, flags — not
 * the SSID alone, so the objects submitted to
 * {@code removeNetworkSuggestions} are the exact entries read back from
 * {@code getNetworkSuggestions}; an SSID with no stored suggestion
 * answers {@code wifi-suggest-failed:nothing to remove} without a
 * platform call.
 * {@code wifi.suggest.list} (no params) reports {@code suggestions_json}
 * (bounded to {@value #MAX_SUGGESTION_ROWS} rows of {@code ssid},
 * {@code priority}, {@code is_app_interactive} with a {@code truncated}
 * flag) and {@code count}.</p>
 *
 * <p>{@code wifi.lock.acquire} (param {@code tag} optional &le;64 chars)
 * acquires one {@code WIFI_MODE_FULL_HIGH_PERF} {@link WifiManager.WifiLock}
 * behind the {@code WAKE_LOCK} grant; a second acquire while held answers
 * {@code wifi-lock-already-held}. {@code wifi.lock.release} is idempotent:
 * {@code held=false} always, {@code was_held} whether this call released a
 * held lock. {@link #close()} releases the hotspot reservation and the lock.</p>
 */
public final class WifiExtrasModule implements CapabilityModule {
    private static final String TAG = "WifiExtrasModule";

    private static final String METHOD_HOTSPOT_START = "wifi.hotspot.start";
    private static final String METHOD_HOTSPOT_STOP = "wifi.hotspot.stop";
    private static final String METHOD_HOTSPOT_STATUS = "wifi.hotspot.status";
    private static final String METHOD_SUGGEST_ADD = "wifi.suggest.add";
    private static final String METHOD_SUGGEST_REMOVE = "wifi.suggest.remove";
    private static final String METHOD_SUGGEST_LIST = "wifi.suggest.list";
    private static final String METHOD_LOCK_ACQUIRE = "wifi.lock.acquire";
    private static final String METHOD_LOCK_RELEASE = "wifi.lock.release";

    private static final int MAX_SSID_CHARS = 64;
    private static final int MAX_PASSPHRASE_CHARS = 128;
    private static final int MAX_TAG_CHARS = 64;
    private static final int MAX_SUGGESTION_ROWS = 20;
    private static final String DEFAULT_LOCK_TAG = "nusadesk";
    private static final long HOTSPOT_TIMEOUT_MS = 15_000L;

    /**
     * The platform calls this module owns, seamed for tests:
     * {@code startLocalOnlyHotspot}, the network-suggestion trio, and
     * {@code createWifiLock}. {@link WifiManager}'s constructor is package
     * private so the class cannot be faked directly; the seam also hides the
     * unconstructible {@code LocalOnlyHotspotReservation} and {@code WifiLock}
     * behind the module's own {@link HotspotReservation} / {@link WifiLockHandle}.
     */
    interface WifiBackend {
        /**
         * Begin a local-only hotspot; {@code listener} is invoked exactly once
         * with {@code onStarted} or {@code onFailed}, then {@code onStopped}
         * when the reservation dies. Implementations may throw
         * {@link SecurityException} or {@link RuntimeException} synchronously.
         */
        void startHotspot(HotspotListener listener);
        int addSuggestions(List<WifiNetworkSuggestion> suggestions);
        int removeSuggestions(List<WifiNetworkSuggestion> suggestions);
        List<WifiNetworkSuggestion> suggestions();
        WifiLockHandle createWifiLock(String tag);
    }

    /** Lifecycle of one local-only-hotspot request, delivered off the bridge thread. */
    interface HotspotListener {
        void onStarted(HotspotReservation reservation);
        void onStopped();
        void onFailed(int reason);
    }

    /** A live local-only-hotspot reservation with its advertised credentials. */
    interface HotspotReservation {
        String ssid();
        String passphrase();
        String securityType();
        void close();
    }

    /** The {@link WifiManager.WifiLock} surface the module uses. */
    interface WifiLockHandle {
        void acquire();
        void release();
        boolean isHeld();
    }

    /** The live hotspot reservation plus the credentials it advertised. */
    private static final class HotspotSession {
        final HotspotReservation reservation;
        final String ssid;
        final String passphrase;
        final String securityType;
        volatile boolean running = true;

        HotspotSession(HotspotReservation reservation) {
            this.reservation = reservation;
            this.ssid = reservation.ssid();
            this.passphrase = reservation.passphrase();
            this.securityType = reservation.securityType();
        }
    }

    /**
     * The listener for one in-flight {@code wifi.hotspot.start}: the first
     * terminal callback unblocks the bridge thread, {@code onStopped} is
     * forwarded into the stored session, and a reservation that arrives after
     * the start already timed out is closed immediately instead of leaking.
     */
    private final class PendingStart implements HotspotListener {
        private final CountDownLatch done = new CountDownLatch(1);
        private HotspotReservation reservation;
        private Integer failure;
        private HotspotSession session;
        private boolean abandoned;
        private boolean stopped;

        @Override
        public synchronized void onStarted(HotspotReservation started) {
            if (abandoned) {
                try {
                    started.close();
                } catch (RuntimeException e) {
                    Log.w(TAG, "abandoned hotspot reservation close failed", e);
                }
                return;
            }
            reservation = started;
            done.countDown();
        }

        @Override
        public void onStopped() {
            HotspotSession current;
            synchronized (this) {
                stopped = true;
                current = session;
            }
            if (current != null) {
                current.running = false;
            }
            done.countDown();
        }

        @Override
        public synchronized void onFailed(int reason) {
            failure = reason;
            done.countDown();
        }

        synchronized HotspotReservation reservation() {
            return reservation;
        }

        synchronized Integer failure() {
            return failure;
        }

        synchronized boolean stoppedBeforeResolved() {
            return stopped;
        }

        synchronized void attach(HotspotSession attached) {
            session = attached;
            if (stopped) {
                attached.running = false;
            }
        }

        synchronized void abandon() {
            abandoned = true;
            if (reservation != null) {
                try {
                    reservation.close();
                } catch (RuntimeException e) {
                    Log.w(TAG, "abandoned hotspot reservation close failed", e);
                }
            }
            done.countDown();
        }

        synchronized boolean isAbandoned() {
            return abandoned;
        }

        boolean await(long timeoutMillis) throws InterruptedException {
            return done.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }

    private final Context context;
    private final AndroidPermissionChecker permissions;
    private final WifiBackend backend;
    private final long hotspotTimeoutMillis;
    private final Object stateGuard = new Object();
    private HotspotSession hotspot;
    /** Non-null while a {@code wifi.hotspot.start} is awaiting the platform verdict. */
    private PendingStart pendingStart;
    private WifiLockHandle wifiLock;
    private String wifiLockTag;
    private volatile boolean closed;

    public WifiExtrasModule(Context context) {
        this(context, platformBackend(context), HOTSPOT_TIMEOUT_MS);
    }

    /**
     * Package-private seam constructor: the backend and the hotspot bound are
     * injectable so the method contract is testable without a device.
     */
    WifiExtrasModule(Context context, WifiBackend backend, long hotspotTimeoutMillis) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.permissions = new AndroidPermissionChecker(this.context);
        this.backend = backend;
        this.hotspotTimeoutMillis = hotspotTimeoutMillis;
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_HOTSPOT_START, METHOD_HOTSPOT_STOP,
                METHOD_HOTSPOT_STATUS, METHOD_SUGGEST_ADD, METHOD_SUGGEST_REMOVE,
                METHOD_SUGGEST_LIST, METHOD_LOCK_ACQUIRE, METHOD_LOCK_RELEASE);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_SUGGEST_ADD, METHOD_SUGGEST_REMOVE, METHOD_LOCK_ACQUIRE);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_HOTSPOT_START:
                return hotspotStart(request);
            case METHOD_HOTSPOT_STOP:
                return hotspotStop(request);
            case METHOD_HOTSPOT_STATUS:
                return hotspotStatus(request);
            case METHOD_SUGGEST_ADD:
                return suggestAdd(request);
            case METHOD_SUGGEST_REMOVE:
                return suggestRemove(request);
            case METHOD_SUGGEST_LIST:
                return suggestList(request);
            case METHOD_LOCK_ACQUIRE:
                return lockAcquire(request);
            case METHOD_LOCK_RELEASE:
                return lockRelease(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (stateGuard) {
            PendingStart pending = pendingStart;
            if (pending != null) {
                pending.abandon();
            }
            HotspotSession session = hotspot;
            hotspot = null;
            if (session != null) {
                try {
                    session.reservation.close();
                } catch (RuntimeException e) {
                    Log.w(TAG, "hotspot reservation close failed", e);
                }
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                try {
                    wifiLock.release();
                } catch (RuntimeException e) {
                    Log.w(TAG, "wifi lock release failed", e);
                }
            }
        }
    }

    /**
     * {@code wifi.hotspot.start} — begin the one permitted local-only-hotspot
     * reservation and block until the platform verdict arrives.
     */
    private AndroidCapabilityProtocol.Response hotspotStart(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        if (backend == null) {
            return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
        }
        String grantError = changeWifiStateError();
        if (grantError == null) {
            grantError = wifiGrantError();
        }
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        PendingStart pending = new PendingStart();
        synchronized (stateGuard) {
            if (closed) {
                return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
            }
            if ((hotspot != null && hotspot.running) || pendingStart != null) {
                return AndroidCapabilityProtocol.Response.error(id, "wifi-hotspot-in-use");
            }
            pendingStart = pending;
        }
        try {
            return hotspotStartAwait(id, pending);
        } finally {
            synchronized (stateGuard) {
                pendingStart = null;
            }
        }
    }

    /** The blocking half of {@code wifi.hotspot.start}: the platform verdict. */
    private AndroidCapabilityProtocol.Response hotspotStartAwait(
            String id, PendingStart pending) {
        try {
            backend.startHotspot(pending);
        } catch (SecurityException e) {
            Log.w(TAG, "wifi.hotspot.start refused", e);
            String refreshed = wifiGrantError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "wifi-hotspot-failed:permission refused");
        } catch (RuntimeException e) {
            Log.w(TAG, "wifi.hotspot.start failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    id, "wifi-hotspot-failed:start request rejected");
        }
        boolean completed;
        try {
            completed = pending.await(hotspotTimeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.abandon();
            return AndroidCapabilityProtocol.Response.error(
                    id, "wifi-hotspot-failed:start interrupted");
        }
        if (!completed) {
            pending.abandon();
            return AndroidCapabilityProtocol.Response.error(
                    id, "wifi-hotspot-failed:start timed out");
        }
        if (pending.isAbandoned()) {
            // close() won the race; the reservation, if any, is already closed.
            return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
        }
        Integer failure = pending.failure();
        if (failure != null) {
            return AndroidCapabilityProtocol.Response.error(id, hotspotFailureError(failure));
        }
        HotspotReservation reservation = pending.reservation();
        if (reservation == null || pending.stoppedBeforeResolved()) {
            if (reservation != null) {
                // The hotspot came up and died before the verdict was read.
                try {
                    reservation.close();
                } catch (RuntimeException e) {
                    Log.w(TAG, "stopped hotspot reservation close failed", e);
                }
            }
            return AndroidCapabilityProtocol.Response.error(
                    id, "wifi-hotspot-failed:hotspot stopped");
        }
        HotspotSession session = new HotspotSession(reservation);
        synchronized (stateGuard) {
            hotspot = session;
        }
        pending.attach(session);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ssid", session.ssid);
        fields.put("passphrase", session.passphrase);
        fields.put("security_type", session.securityType);
        fields.put("running", true);
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /** {@code wifi.hotspot.stop} — close the held reservation; idempotent. */
    private AndroidCapabilityProtocol.Response hotspotStop(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        HotspotSession session;
        synchronized (stateGuard) {
            session = hotspot;
            hotspot = null;
        }
        boolean wasRunning = session != null && session.running;
        if (session != null) {
            try {
                session.reservation.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "wifi.hotspot.stop close failed", e);
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("stopped", true);
        fields.put("was_running", wasRunning);
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /** {@code wifi.hotspot.status} — live reservation state and credentials. */
    private AndroidCapabilityProtocol.Response hotspotStatus(
            AndroidCapabilityProtocol.Request request) {
        HotspotSession session;
        synchronized (stateGuard) {
            session = hotspot != null && hotspot.running ? hotspot : null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("running", session != null);
        fields.put("ssid", session != null ? session.ssid : "");
        fields.put("passphrase", session != null ? session.passphrase : "");
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code wifi.suggest.add} — params {@code ssid}, {@code passphrase},
     * {@code priority}, {@code is_hidden}. The suggestion API exists on
     * API 29 but the bounded surface is gated to API 30+, matching the rest
     * of the suggestion family.
     */
    @SuppressLint({"MissingPermission", "NewApi"})
    private AndroidCapabilityProtocol.Response suggestAdd(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("ssid", "passphrase", "priority", "is_hidden"));
        String ssid = params.requireString("ssid", MAX_SSID_CHARS);
        if (ssid.isEmpty()) {
            throw new CapabilityParams.Invalid("parameter must not be empty: ssid");
        }
        String passphrase = params.optionalString("passphrase", MAX_PASSPHRASE_CHARS, "");
        boolean hasPriority = params.has("priority");
        long priority = params.optionalLong("priority", 0, 1000, 0);
        boolean isHidden = params.optionalBoolean("is_hidden", false);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AndroidCapabilityProtocol.Response.error(
                    id, "wifi-suggest-unsupported:requires Android 11");
        }
        if (backend == null) {
            return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
        }
        String grantError = changeWifiStateError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        WifiNetworkSuggestion suggestion;
        try {
            WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid);
            if (!passphrase.isEmpty()) {
                builder.setWpa2Passphrase(passphrase);
            }
            if (hasPriority) {
                builder.setPriority((int) priority);
            }
            builder.setIsHiddenSsid(isHidden);
            suggestion = builder.build();
        } catch (RuntimeException e) {
            throw new CapabilityParams.Invalid("invalid wifi network suggestion");
        }
        int status;
        try {
            status = backend.addSuggestions(List.of(suggestion));
        } catch (SecurityException e) {
            Log.w(TAG, "wifi.suggest.add refused", e);
            String refreshed = changeWifiStateError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "wifi-suggest-failed:permission refused");
        } catch (RuntimeException e) {
            Log.w(TAG, "wifi.suggest.add failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "wifi-suggest-failed:call failed");
        }
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("added", true);
            fields.put("ssid", ssid);
            return AndroidCapabilityProtocol.Response.success(id, fields);
        }
        return AndroidCapabilityProtocol.Response.error(id, suggestionStatusError(status));
    }

    /**
     * {@code wifi.suggest.remove} — param {@code ssid}. The platform matches
     * removal on the whole stored suggestion object — SSID, passphrase,
     * security type, flags — not the SSID alone, so a freshly built
     * suggestion for the same SSID answers {@code REMOVE_INVALID}. The
     * objects submitted to {@code removeNetworkSuggestions} are therefore
     * the exact entries read back from {@code getNetworkSuggestions}; when
     * no stored suggestion carries the requested SSID the platform call is
     * skipped and the same typed {@code wifi-suggest-failed:nothing to
     * remove} error answers.
     */
    @SuppressLint({"MissingPermission", "NewApi"})
    private AndroidCapabilityProtocol.Response suggestRemove(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("ssid"));
        String ssid = params.requireString("ssid", MAX_SSID_CHARS);
        if (ssid.isEmpty()) {
            throw new CapabilityParams.Invalid("parameter must not be empty: ssid");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AndroidCapabilityProtocol.Response.error(
                    id, "wifi-suggest-unsupported:requires Android 11");
        }
        if (backend == null) {
            return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
        }
        String grantError = changeWifiStateError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        List<WifiNetworkSuggestion> installed;
        try {
            installed = backend.suggestions();
        } catch (SecurityException e) {
            Log.w(TAG, "wifi.suggest.remove list refused", e);
            String refreshed = changeWifiStateError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "wifi-suggest-failed:permission refused");
        } catch (RuntimeException e) {
            Log.w(TAG, "wifi.suggest.remove list failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "wifi-suggest-failed:call failed");
        }
        if (installed == null) {
            return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
        }
        List<WifiNetworkSuggestion> matches = new ArrayList<>();
        for (WifiNetworkSuggestion candidate : installed) {
            if (candidate != null && suggestionSsidEquals(ssid, candidate.getSsid())) {
                matches.add(candidate);
            }
        }
        if (matches.isEmpty()) {
            return AndroidCapabilityProtocol.Response.error(
                    id, "wifi-suggest-failed:nothing to remove");
        }
        int status;
        try {
            status = backend.removeSuggestions(matches);
        } catch (SecurityException e) {
            Log.w(TAG, "wifi.suggest.remove refused", e);
            String refreshed = changeWifiStateError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "wifi-suggest-failed:permission refused");
        } catch (RuntimeException e) {
            Log.w(TAG, "wifi.suggest.remove failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "wifi-suggest-failed:call failed");
        }
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("removed", true);
            fields.put("ssid", ssid);
            return AndroidCapabilityProtocol.Response.success(id, fields);
        }
        return AndroidCapabilityProtocol.Response.error(id, suggestionStatusError(status));
    }

    /** {@code wifi.suggest.list} — the app's live suggestions as a bounded JSON array. */
    @SuppressLint({"MissingPermission", "NewApi"})
    private AndroidCapabilityProtocol.Response suggestList(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AndroidCapabilityProtocol.Response.error(
                    id, "wifi-suggest-unsupported:requires Android 11");
        }
        if (backend == null) {
            return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
        }
        String grantError = changeWifiStateError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        List<WifiNetworkSuggestion> suggestions;
        try {
            suggestions = backend.suggestions();
        } catch (SecurityException e) {
            Log.w(TAG, "wifi.suggest.list refused", e);
            String refreshed = changeWifiStateError();
            return AndroidCapabilityProtocol.Response.error(id,
                    refreshed != null ? refreshed : "wifi-suggest-failed:permission refused");
        } catch (RuntimeException e) {
            Log.w(TAG, "wifi.suggest.list failed", e);
            return AndroidCapabilityProtocol.Response.error(id, "wifi-suggest-failed:call failed");
        }
        if (suggestions == null) {
            return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
        }
        int count = Math.min(suggestions.size(), MAX_SUGGESTION_ROWS);
        StringBuilder json = new StringBuilder(count * 96);
        json.append('[');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(suggestionRowJson(suggestions.get(i)));
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("suggestions_json", json.toString());
        fields.put("count", (long) count);
        if (suggestions.size() > count) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /** {@code wifi.lock.acquire} — param {@code tag} optional. */
    private AndroidCapabilityProtocol.Response lockAcquire(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("tag"));
        String tag = params.optionalString("tag", MAX_TAG_CHARS, DEFAULT_LOCK_TAG);
        if (tag.isEmpty()) {
            tag = DEFAULT_LOCK_TAG;
        }
        if (backend == null) {
            return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
        }
        String grantError = wakeLockError();
        if (grantError != null) {
            return AndroidCapabilityProtocol.Response.error(id, grantError);
        }
        synchronized (stateGuard) {
            if (closed) {
                return AndroidCapabilityProtocol.Response.error(id, "wifi-unavailable");
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                return AndroidCapabilityProtocol.Response.error(id, "wifi-lock-already-held");
            }
            if (wifiLock == null || !tag.equals(wifiLockTag)) {
                try {
                    wifiLock = backend.createWifiLock(tag);
                } catch (SecurityException e) {
                    Log.w(TAG, "wifi.lock.acquire refused", e);
                    String refreshed = wakeLockError();
                    return AndroidCapabilityProtocol.Response.error(id,
                            refreshed != null ? refreshed : "wifi-lock-failed:permission refused");
                } catch (RuntimeException e) {
                    Log.w(TAG, "wifi.lock.acquire lock creation failed", e);
                    return AndroidCapabilityProtocol.Response.error(
                            id, "wifi-lock-failed:lock unavailable");
                }
                wifiLockTag = tag;
            }
            try {
                wifiLock.acquire();
            } catch (SecurityException e) {
                Log.w(TAG, "wifi.lock.acquire refused", e);
                String refreshed = wakeLockError();
                return AndroidCapabilityProtocol.Response.error(id,
                        refreshed != null ? refreshed : "wifi-lock-failed:permission refused");
            } catch (RuntimeException e) {
                Log.w(TAG, "wifi.lock.acquire failed", e);
                return AndroidCapabilityProtocol.Response.error(id, "wifi-lock-failed:acquire failed");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("held", true);
            fields.put("tag", wifiLockTag);
            return AndroidCapabilityProtocol.Response.success(id, fields);
        }
    }

    /** {@code wifi.lock.release} — release the held lock; idempotent. */
    private AndroidCapabilityProtocol.Response lockRelease(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        boolean wasHeld;
        synchronized (stateGuard) {
            wasHeld = wifiLock != null && wifiLock.isHeld();
            if (wasHeld) {
                try {
                    wifiLock.release();
                } catch (RuntimeException e) {
                    Log.w(TAG, "wifi.lock.release failed", e);
                }
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("held", false);
        fields.put("was_held", wasHeld);
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /** The platform's suggestion status code as a typed {@code wifi-suggest-failed} error. */
    private static String suggestionStatusError(int status) {
        switch (status) {
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL:
                return "wifi-suggest-failed:internal error";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED:
                return "wifi-suggest-failed:suggestions disallowed for this app";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE:
                return "wifi-suggest-failed:duplicate suggestion";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP:
                return "wifi-suggest-failed:suggestion limit reached";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID:
                return "wifi-suggest-failed:nothing to remove";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED:
                return "wifi-suggest-failed:suggestion not allowed";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID:
                return "wifi-suggest-failed:invalid suggestion";
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_RESTRICTED_BY_ADMIN:
                return "wifi-suggest-failed:restricted by admin";
            default:
                return "wifi-suggest-failed:status " + status;
        }
    }

    /** A {@code LocalOnlyHotspotCallback} failure reason as a typed error. */
    private static String hotspotFailureError(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                // The platform's only "occupied" signal: another hotspot or
                // tethering is active, or the app was not foreground.
                return "wifi-hotspot-in-use";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "wifi-hotspot-unsupported:this device does not support a local-only hotspot";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "wifi-hotspot-failed:no channel available";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
                return "wifi-hotspot-failed:generic error";
            default:
                return "wifi-hotspot-failed:platform error " + reason;
        }
    }

    /**
     * The {@code CHANGE_WIFI_STATE} gate for the hotspot and suggestion calls;
     * {@code null} when granted, else the typed {@code wifi-permission-*} error.
     */
    private String changeWifiStateError() {
        return permissionGateError(Manifest.permission.CHANGE_WIFI_STATE);
    }

    /** The {@code WAKE_LOCK} gate for the wifi-lock calls. */
    private String wakeLockError() {
        return permissionGateError(Manifest.permission.WAKE_LOCK);
    }

    private String permissionGateError(String permission) {
        CapabilityPermission state = permissions.check(permission);
        if (state == CapabilityPermission.GRANTED) {
            return null;
        }
        return state == CapabilityPermission.DENIED
                ? "wifi-permission-denied:grant " + permission + " in app settings"
                : "wifi-permission-required:grant " + permission + " via permission.request";
    }

    /**
     * The grant needed for the local-only hotspot: fine location, or
     * {@code NEARBY_WIFI_DEVICES} on API 33+. Mirrors
     * {@code WifiModule}'s {@code wifiGrantError} so the wifi surface answers
     * one consistent {@code wifi-permission-*} contract.
     */
    private String wifiGrantError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && permissions.check(Manifest.permission.NEARBY_WIFI_DEVICES)
                        == CapabilityPermission.GRANTED) {
            return null;
        }
        CapabilityPermission fine =
                permissions.check(Manifest.permission.ACCESS_FINE_LOCATION);
        if (fine == CapabilityPermission.GRANTED) {
            return null;
        }
        return fine == CapabilityPermission.DENIED
                ? "wifi-permission-denied:grant the location permission in app settings"
                : "wifi-permission-required:grant ACCESS_FINE_LOCATION"
                        + " via permission.request";
    }

    /** One suggestion row in the documented field order. */
    @SuppressLint("NewApi") // callers hold the API 30 gate
    private static String suggestionRowJson(WifiNetworkSuggestion suggestion) {
        return "{\"ssid\":" + AndroidCapabilityProtocol.encodeStringValue(
                        suggestion.getSsid() == null ? "" : suggestion.getSsid())
                + ",\"priority\":" + suggestion.getPriority()
                + ",\"is_app_interactive\":" + suggestion.isAppInteractionRequired()
                + "}";
    }

    /**
     * Whether a stored suggestion's SSID is the requested one. The platform
     * can report stored SSIDs in their quoted form, so both sides are
     * compared with the quoting stripped.
     */
    private static boolean suggestionSsidEquals(String requested, String stored) {
        return stored != null && unquote(requested).equals(unquote(stored));
    }

    private static WifiBackend platformBackend(Context context) {
        WifiManager wifi;
        try {
            wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
        return wifi == null ? null : new PlatformWifiBackend(context, wifi);
    }

    /** Production {@link WifiBackend} delegating to the platform {@link WifiManager}. */
    private static final class PlatformWifiBackend implements WifiBackend {
        private final Context context;
        private final WifiManager wifi;

        PlatformWifiBackend(Context context, WifiManager wifi) {
            this.context = context;
            this.wifi = wifi;
        }

        @Override
        @SuppressLint("MissingPermission")
        public void startHotspot(HotspotListener listener) {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    listener.onStarted(new PlatformReservation(reservation));
                }

                @Override
                public void onStopped() {
                    listener.onStopped();
                }

                @Override
                public void onFailed(int reason) {
                    listener.onFailed(reason);
                }
            }, new Handler(context.getMainLooper()));
        }

        @Override
        @SuppressLint({"MissingPermission", "NewApi"})
        public int addSuggestions(List<WifiNetworkSuggestion> suggestions) {
            return wifi.addNetworkSuggestions(suggestions);
        }

        @Override
        @SuppressLint({"MissingPermission", "NewApi"})
        public int removeSuggestions(List<WifiNetworkSuggestion> suggestions) {
            return wifi.removeNetworkSuggestions(suggestions);
        }

        @Override
        @SuppressLint({"MissingPermission", "NewApi"})
        public List<WifiNetworkSuggestion> suggestions() {
            return wifi.getNetworkSuggestions();
        }

        @Override
        @SuppressLint("MissingPermission")
        public WifiLockHandle createWifiLock(String tag) {
            WifiManager.WifiLock lock = wifi.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag);
            lock.setReferenceCounted(false);
            return new PlatformWifiLock(lock);
        }
    }

    /**
     * A live {@link WifiManager.LocalOnlyHotspotReservation}; credentials are
     * read eagerly at wrap time — {@code getSoftApConfiguration} on API 30+,
     * the deprecated {@code getWifiConfiguration} below it.
     */
    private static final class PlatformReservation implements HotspotReservation {
        private final WifiManager.LocalOnlyHotspotReservation reservation;
        private final String ssid;
        private final String passphrase;
        private final String securityType;

        @SuppressLint({"NewApi", "Deprecation"})
        PlatformReservation(WifiManager.LocalOnlyHotspotReservation reservation) {
            this.reservation = reservation;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SoftApConfiguration config = reservation.getSoftApConfiguration();
                ssid = config != null && config.getSsid() != null ? config.getSsid() : "";
                passphrase = config != null && config.getPassphrase() != null
                        ? config.getPassphrase() : "";
                securityType = config != null ? securityTypeName(config.getSecurityType()) : "unknown";
            } else {
                WifiConfiguration config = reservation.getWifiConfiguration();
                ssid = config != null && config.SSID != null ? unquote(config.SSID) : "";
                passphrase = config != null && config.preSharedKey != null
                        ? unquote(config.preSharedKey) : "";
                securityType = configSecurityType(config);
            }
        }

        @Override
        public String ssid() {
            return ssid;
        }

        @Override
        public String passphrase() {
            return passphrase;
        }

        @Override
        public String securityType() {
            return securityType;
        }

        @Override
        public void close() {
            reservation.close();
        }
    }

    /** {@link WifiLockHandle} over a real {@link WifiManager.WifiLock}. */
    private static final class PlatformWifiLock implements WifiLockHandle {
        private final WifiManager.WifiLock lock;

        PlatformWifiLock(WifiManager.WifiLock lock) {
            this.lock = lock;
        }

        @Override
        @SuppressLint("WakelockTimeout")
        public void acquire() {
            lock.acquire();
        }

        @Override
        public void release() {
            lock.release();
        }

        @Override
        public boolean isHeld() {
            return lock.isHeld();
        }
    }

    /** A {@link SoftApConfiguration} security type as a stable string. */
    private static String securityTypeName(int securityType) {
        switch (securityType) {
            case SoftApConfiguration.SECURITY_TYPE_OPEN:
                return "open";
            case SoftApConfiguration.SECURITY_TYPE_WPA2_PSK:
                return "wpa2_psk";
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION:
                return "wpa3_sae_transition";
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE:
                return "wpa3_sae";
            case SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION:
                return "wpa3_owe_transition";
            case SoftApConfiguration.SECURITY_TYPE_WPA3_OWE:
                return "wpa3_owe";
            default:
                return "unknown";
        }
    }

    /** The API 29 fallback: a {@link WifiConfiguration} key-management set as a security type. */
    @SuppressLint("Deprecation")
    private static String configSecurityType(WifiConfiguration config) {
        if (config == null || config.allowedKeyManagement == null) {
            return "unknown";
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) {
            return "wpa3_sae";
        }
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
                || config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            return "wpa2_psk";
        }
        return config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                ? "open" : "unknown";
    }

    /** Strip the platform's quoting around {@link WifiConfiguration} SSID/key strings. */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
