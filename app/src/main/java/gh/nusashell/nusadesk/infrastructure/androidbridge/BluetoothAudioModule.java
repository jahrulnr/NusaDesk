package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public Bluetooth audio profile status and the already-connected HFP voice
 * path. This module does not connect profiles or capture audio: Android owns
 * both decisions through the public profile APIs.
 */
public final class BluetoothAudioModule implements CapabilityModule {
    private static final String METHOD_STATUS = "bt.audio.status";
    private static final String METHOD_VOICE_START = "bt.audio.voice.start";
    private static final String METHOD_VOICE_STOP = "bt.audio.voice.stop";

    private static final long PROFILE_TIMEOUT_MILLIS = 2_500L;
    private static final int MAX_PROFILE_DEVICES = 8;
    private static final int MAX_OUTPUT_DEVICES = 32;
    private static final int MAX_NAME_CHARS = 128;
    private static final int ADDRESS_MAX_CHARS = 17;
    private static final Pattern MAC_ADDRESS =
            Pattern.compile("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}");

    private final BluetoothAdapter adapter;
    private final AndroidPermissionChecker permissions;
    private final ProfileProvider profiles;
    private final AudioOutputProvider outputs;
    private final Object voiceLock = new Object();
    private ProfileDevice startedVoice;

    /** A public-profile seam; production uses {@link AndroidProfileProvider}. */
    interface ProfileProvider {
        ProfileLease acquire(int profileId, long timeoutMillis);

        void closeProfileProxy(int profileId, ProfileLease profile);
    }

    /** The small operation surface used by this module for one profile proxy. */
    interface ProfileLease {
        List<ProfileDevice> connectedDevices();

        boolean startVoiceRecognition(ProfileDevice device);

        boolean stopVoiceRecognition(ProfileDevice device);
    }

    /** One bounded profile device row, with the platform handle kept private. */
    static final class ProfileDevice {
        private final BluetoothDevice platformDevice;
        private final String name;
        private final String address;
        private final int state;

        ProfileDevice(BluetoothDevice platformDevice, String name, String address, int state) {
            this.platformDevice = platformDevice;
            this.name = name;
            this.address = address == null ? "" : address;
            this.state = state;
        }

        String getName() {
            return name;
        }

        String getAddress() {
            return address;
        }

        int getState() {
            return state;
        }
    }

    /** Testable source of the current Android output devices. */
    interface AudioOutputProvider {
        List<AudioOutput> outputs();
    }

    /** One current public AudioManager output row. */
    static final class AudioOutput {
        private final int id;
        private final int type;
        private final String name;
        private final boolean sink;

        AudioOutput(int id, int type, String name, boolean sink) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.sink = sink;
        }
    }

    public BluetoothAudioModule(Context context) {
        this(context, defaultAdapter(context), null, null);
    }

    /** Package-private seams keep JVM tests independent of profile shadows. */
    BluetoothAudioModule(Context context, BluetoothAdapter adapter,
                         ProfileProvider profiles, AudioOutputProvider outputs) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context application = context.getApplicationContext();
        this.adapter = adapter;
        this.permissions = new AndroidPermissionChecker(application);
        this.profiles = profiles == null
                ? new AndroidProfileProvider(application, adapter) : profiles;
        this.outputs = outputs == null ? new AndroidAudioOutputProvider(application) : outputs;
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_STATUS, METHOD_VOICE_START, METHOD_VOICE_STOP);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_VOICE_START, METHOD_VOICE_STOP);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        try {
            switch (request.getMethod()) {
                case METHOD_STATUS:
                    return status(request);
                case METHOD_VOICE_START:
                    return voiceStart(request);
                case METHOD_VOICE_STOP:
                    return voiceStop(request);
                default:
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "unsupported-method");
            }
        } catch (CapabilityParams.Invalid invalid) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), "invalid-argument");
        } catch (RuntimeException failure) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "bt-audio-unavailable");
        }
    }

    /** {@code bt.audio.status}: public profile proxies plus Bluetooth outputs. */
    @SuppressLint("InlinedApi")
    private AndroidCapabilityProtocol.Response status(
            AndroidCapabilityProtocol.Request request) {
        String gate = gate();
        if (gate != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), gate);
        }
        String adapterError = adapterError();
        if (adapterError != null) {
            return AndroidCapabilityProtocol.Response.error(request.getId(), adapterError);
        }

        StringBuilder profileJson = new StringBuilder(512);
        profileJson.append('[');
        appendProfile(profileJson, BluetoothProfile.A2DP, "a2dp");
        appendProfile(profileJson, BluetoothProfile.HEADSET, "headset");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appendProfile(profileJson, BluetoothProfile.LE_AUDIO, "le_audio");
        }
        profileJson.append(']');

        List<AudioOutput> outputList = outputs.outputs();
        if (outputList == null) {
            throw new IllegalStateException("audio output list unavailable");
        }
        StringBuilder outputJson = new StringBuilder(512);
        outputJson.append('[');
        int count = 0;
        for (AudioOutput output : outputList) {
            if (output == null || !isBluetoothOutputType(output.type)) {
                continue;
            }
            if (count == MAX_OUTPUT_DEVICES) {
                break;
            }
            if (count++ > 0) {
                outputJson.append(',');
            }
            outputJson.append("{\"id\":").append(output.id)
                    .append(",\"type\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(
                            outputTypeName(output.type)))
                    .append(",\"name\":")
                    .append(encodeNullable(sanitizeName(output.name)))
                    .append(",\"is_sink\":").append(output.sink).append('}');
        }
        outputJson.append(']');

        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("profiles_json", profileJson.toString());
        fields.put("outputs_json", outputJson.toString());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private void appendProfile(StringBuilder json, int profileId, String name) {
        if (json.length() > 1) {
            json.append(',');
        }
        ProfileLease lease = null;
        boolean available = false;
        List<ProfileDevice> devices = null;
        try {
            lease = profiles.acquire(profileId, PROFILE_TIMEOUT_MILLIS);
            if (lease != null) {
                available = true;
                devices = lease.connectedDevices();
            }
        } catch (RuntimeException ignored) {
            available = false;
        } finally {
            if (lease != null) {
                try {
                    profiles.closeProfileProxy(profileId, lease);
                } catch (RuntimeException ignored) {
                    // A close failure must not turn a truthful status into a throw.
                }
            }
        }
        json.append("{\"profile\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(name))
                .append(",\"available\":").append(available)
                .append(",\"connected_devices\":");
        appendDevices(json, devices);
        json.append('}');
    }

    private static void appendDevices(StringBuilder json, List<ProfileDevice> devices) {
        json.append('[');
        if (devices != null) {
            int count = 0;
            for (ProfileDevice device : devices) {
                if (device == null || count == MAX_PROFILE_DEVICES) {
                    if (count == MAX_PROFILE_DEVICES) {
                        break;
                    }
                    continue;
                }
                if (count++ > 0) {
                    json.append(',');
                }
                json.append("{\"name\":").append(encodeNullable(sanitizeName(device.name)))
                        .append(",\"address\":")
                        .append(AndroidCapabilityProtocol.encodeStringValue(device.address))
                        .append(",\"state\":").append(device.state).append('}');
            }
        }
        json.append(']');
    }

    /** Start voice recognition only for an already connected HFP device. */
    private synchronized AndroidCapabilityProtocol.Response voiceStart(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("address"));
        String address = params.optionalString("address", ADDRESS_MAX_CHARS, "")
                .trim().toUpperCase(java.util.Locale.ROOT);
        if (!address.isEmpty() && !MAC_ADDRESS.matcher(address).matches()) {
            throw new CapabilityParams.Invalid("invalid bluetooth address");
        }
        String gate = gate();
        if (gate != null) {
            return error(request, gate);
        }
        String adapterError = adapterError();
        if (adapterError != null) {
            return error(request, adapterError);
        }
        ProfileLease lease;
        try {
            lease = profiles.acquire(BluetoothProfile.HEADSET, PROFILE_TIMEOUT_MILLIS);
        } catch (RuntimeException failure) {
            return error(request, "bt-audio-unavailable");
        }
        if (lease == null) {
            return error(request, "bt-audio-no-headset");
        }
        try {
            List<ProfileDevice> connectedDevices = lease.connectedDevices();
            ProfileDevice active;
            synchronized (voiceLock) {
                active = startedVoice;
            }
            if (active != null) {
                ProfileDevice stillConnected = findDevice(connectedDevices, active.address);
                if (stillConnected == null) {
                    synchronized (voiceLock) {
                        startedVoice = null;
                    }
                } else if (address.isEmpty()
                        || stillConnected.address.equalsIgnoreCase(address)) {
                    return voiceResult(request.getId(), "started",
                            stillConnected.address, true);
                } else {
                    return error(request, "bt-audio-voice-refused");
                }
            }
            ProfileDevice connected;
            if (address.isEmpty()) {
                if (connectedDevices == null || connectedDevices.isEmpty()) {
                    return error(request, "bt-audio-no-headset");
                }
                if (connectedDevices.size() != 1) {
                    return error(request, "bt-audio-voice-ambiguous");
                }
                connected = connectedDevices.get(0);
            } else {
                connected = findDevice(connectedDevices, address);
            }
            if (connected == null) {
                return error(request, "bt-audio-not-connected");
            }
            if (!lease.startVoiceRecognition(connected)) {
                return error(request, "bt-audio-voice-refused");
            }
            synchronized (voiceLock) {
                startedVoice = connected;
            }
            return voiceResult(request.getId(), "started", connected.address, true);
        } catch (RuntimeException failure) {
            return error(request, "bt-audio-unavailable");
        } finally {
            closeProfile(BluetoothProfile.HEADSET, lease);
        }
    }

    /** Stop only the HFP path this module started; no path is an idempotent no-op. */
    private synchronized AndroidCapabilityProtocol.Response voiceStop(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("address"));
        String requested = params.optionalString("address", ADDRESS_MAX_CHARS, "")
                .trim().toUpperCase(java.util.Locale.ROOT);
        if (!requested.isEmpty() && !MAC_ADDRESS.matcher(requested).matches()) {
            throw new CapabilityParams.Invalid("invalid bluetooth address");
        }
        ProfileDevice active;
        synchronized (voiceLock) {
            active = startedVoice;
        }
        if (active == null) {
            if (!requested.isEmpty()) {
                return error(request, "bt-audio-voice-unknown");
            }
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("stopped", false);
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        }
        if (!requested.isEmpty() && !active.address.equalsIgnoreCase(requested)) {
            return error(request, "bt-audio-voice-unknown");
        }
        String gate = gate();
        if (gate != null) {
            return error(request, gate);
        }
        String adapterError = adapterError();
        if (adapterError != null) {
            return error(request, adapterError);
        }

        ProfileLease lease;
        try {
            lease = profiles.acquire(BluetoothProfile.HEADSET, PROFILE_TIMEOUT_MILLIS);
        } catch (RuntimeException failure) {
            return error(request, "bt-audio-unavailable");
        }
        if (lease == null) {
            return error(request, "bt-audio-no-headset");
        }
        try {
            if (findDevice(lease.connectedDevices(), active.address) == null) {
                synchronized (voiceLock) {
                    startedVoice = null;
                }
                Map<String, Object> fields = new java.util.LinkedHashMap<>();
                fields.put("stopped", false);
                fields.put("disconnected", true);
                return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
            }
            if (!lease.stopVoiceRecognition(active)) {
                return error(request, "bt-audio-voice-refused");
            }
            synchronized (voiceLock) {
                startedVoice = null;
            }
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("stopped", true);
            fields.put("address", active.address);
            return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
        } catch (RuntimeException failure) {
            return error(request, "bt-audio-unavailable");
        } finally {
            closeProfile(BluetoothProfile.HEADSET, lease);
        }
    }

    /** Best-effort release of the HFP path this module started and every proxy it acquires. */
    @Override
    public synchronized void close() {
        ProfileDevice active;
        synchronized (voiceLock) {
            active = startedVoice;
        }
        if (active == null) {
            return;
        }
        ProfileLease lease = null;
        try {
            lease = profiles.acquire(BluetoothProfile.HEADSET, PROFILE_TIMEOUT_MILLIS);
            if (lease != null) {
                lease.stopVoiceRecognition(active);
            }
        } catch (RuntimeException ignored) {
            // Teardown is best effort; bridge close must never throw.
        } finally {
            if (lease != null) {
                closeProfile(BluetoothProfile.HEADSET, lease);
            }
            synchronized (voiceLock) {
                startedVoice = null;
            }
        }
    }

    private void closeProfile(int profileId, ProfileLease lease) {
        try {
            profiles.closeProfileProxy(profileId, lease);
        } catch (RuntimeException ignored) {
            // Closing is best effort, and handle() still must not throw.
        }
    }

    private static ProfileDevice findDevice(List<ProfileDevice> devices, String address) {
        if (devices == null) {
            return null;
        }
        for (ProfileDevice device : devices) {
            if (device != null && address.equalsIgnoreCase(device.address)) {
                return device;
            }
        }
        return null;
    }

    private String gate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        CapabilityPermission state = permissions.check(Manifest.permission.BLUETOOTH_CONNECT);
        if (state == CapabilityPermission.GRANTED) {
            return null;
        }
        return state == CapabilityPermission.DENIED
                ? "bt-permission-denied:grant BLUETOOTH_CONNECT in app settings"
                : "bt-permission-required:grant BLUETOOTH_CONNECT via permission.request";
    }

    @SuppressLint("MissingPermission")
    private String adapterError() {
        if (adapter == null) {
            return "bt-audio-unavailable";
        }
        try {
            return adapter.isEnabled() ? null : "bt-unavailable:bluetooth is off";
        } catch (RuntimeException failure) {
            return "bt-audio-unavailable";
        }
    }

    private static AndroidCapabilityProtocol.Response error(
            AndroidCapabilityProtocol.Request request, String error) {
        return AndroidCapabilityProtocol.Response.error(request.getId(), error);
    }

    private static AndroidCapabilityProtocol.Response voiceResult(
            String id, String action, String address, boolean value) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put(action, value);
        fields.put("address", address);
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    @SuppressLint("InlinedApi")
    private static boolean isBluetoothOutputType(int type) {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_HEARING_AID) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        return type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                || type == AudioDeviceInfo.TYPE_BLE_HEARING_AID;
    }

    @SuppressLint("InlinedApi")
    private static String outputTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "bluetooth_a2dp";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "bluetooth_sco";
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return "hearing_aid";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "ble_headset";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return "ble_speaker";
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return "ble_broadcast";
            case AudioDeviceInfo.TYPE_BLE_HEARING_AID:
                return "ble_hearing_aid";
            default:
                return "unknown";
        }
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_NAME_CHARS));
        for (int i = 0; i < value.length() && clean.length() < MAX_NAME_CHARS; i++) {
            char c = value.charAt(i);
            clean.append(Character.isISOControl(c) ? ' ' : c);
        }
        return clean.toString().trim();
    }

    private static String encodeNullable(String value) {
        return value == null ? "null" : AndroidCapabilityProtocol.encodeStringValue(value);
    }

    private static BluetoothAdapter defaultAdapter(Context context) {
        try {
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            return manager == null ? null : manager.getAdapter();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** Public API adapter for profile proxies and HFP voice recognition. */
    private static final class AndroidProfileProvider implements ProfileProvider {
        private final Context context;
        private final BluetoothAdapter adapter;

        AndroidProfileProvider(Context context, BluetoothAdapter adapter) {
            this.context = context;
            this.adapter = adapter;
        }

        @Override
        @SuppressLint("MissingPermission")
        public ProfileLease acquire(int profileId, long timeoutMillis) {
            if (adapter == null) {
                return null;
            }
            CountDownLatch ready = new CountDownLatch(1);
            AtomicReference<BluetoothProfile> result = new AtomicReference<>();
            AtomicBoolean timedOut = new AtomicBoolean(false);
            BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (timedOut.get()) {
                        closeLate(profile, proxy);
                        return;
                    }
                    result.set(proxy);
                    if (timedOut.get() && result.compareAndSet(proxy, null)) {
                        closeLate(profile, proxy);
                        return;
                    }
                    ready.countDown();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    ready.countDown();
                }
            };
            boolean requested;
            try {
                requested = adapter.getProfileProxy(context, listener, profileId);
            } catch (RuntimeException failure) {
                timedOut.set(true);
                return null;
            }
            if (!requested) {
                timedOut.set(true);
                return null;
            }
            boolean completed;
            try {
                completed = ready.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                completed = false;
            }
            if (!completed) {
                timedOut.set(true);
                BluetoothProfile late = result.getAndSet(null);
                if (late != null) {
                    closeLate(profileId, late);
                }
                return null;
            }
            BluetoothProfile proxy = result.get();
            return proxy == null ? null : new AndroidProfileLease(proxy);
        }

        @SuppressLint("MissingPermission")
        private void closeLate(int profileId, BluetoothProfile proxy) {
            if (proxy == null) {
                return;
            }
            try {
                adapter.closeProfileProxy(profileId, proxy);
            } catch (RuntimeException ignored) {
                // No throw from a late platform callback.
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void closeProfileProxy(int profileId, ProfileLease profile) {
            if (!(profile instanceof AndroidProfileLease) || adapter == null) {
                return;
            }
            BluetoothProfile proxy = ((AndroidProfileLease) profile).proxy;
            try {
                adapter.closeProfileProxy(profileId, proxy);
            } catch (RuntimeException ignored) {
                // The owning module has already completed its typed response.
            }
        }
    }

    private static final class AndroidProfileLease implements ProfileLease {
        private final BluetoothProfile proxy;

        AndroidProfileLease(BluetoothProfile proxy) {
            this.proxy = proxy;
        }

        @Override
        @SuppressLint("MissingPermission")
        public List<ProfileDevice> connectedDevices() {
            List<BluetoothDevice> devices = proxy.getConnectedDevices();
            if (devices == null) {
                return List.of();
            }
            List<ProfileDevice> result = new ArrayList<>(devices.size());
            for (BluetoothDevice device : devices) {
                if (device == null) {
                    continue;
                }
                String name;
                String address;
                int state;
                try {
                    name = device.getName();
                } catch (RuntimeException failure) {
                    name = null;
                }
                try {
                    address = device.getAddress();
                } catch (RuntimeException failure) {
                    address = "";
                }
                try {
                    state = proxy.getConnectionState(device);
                } catch (RuntimeException failure) {
                    state = BluetoothProfile.STATE_DISCONNECTED;
                }
                result.add(new ProfileDevice(device, name, address, state));
            }
            return result;
        }

        @Override
        @SuppressLint("MissingPermission")
        public boolean startVoiceRecognition(ProfileDevice device) {
            return proxy instanceof BluetoothHeadset && device.platformDevice != null
                    && ((BluetoothHeadset) proxy).startVoiceRecognition(device.platformDevice);
        }

        @Override
        @SuppressLint("MissingPermission")
        public boolean stopVoiceRecognition(ProfileDevice device) {
            return proxy instanceof BluetoothHeadset && device.platformDevice != null
                    && ((BluetoothHeadset) proxy).stopVoiceRecognition(device.platformDevice);
        }
    }

    /** Public API adapter for {@link AudioManager#getDevices(int)}. */
    private static final class AndroidAudioOutputProvider implements AudioOutputProvider {
        private final Context context;

        AndroidAudioOutputProvider(Context context) {
            this.context = context;
        }

        @Override
        public List<AudioOutput> outputs() {
            AudioManager manager = context.getSystemService(AudioManager.class);
            if (manager == null) {
                return List.of();
            }
            AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (devices == null) {
                return List.of();
            }
            List<AudioOutput> result = new ArrayList<>(devices.length);
            for (AudioDeviceInfo device : devices) {
                if (device == null) {
                    continue;
                }
                CharSequence product = device.getProductName();
                result.add(new AudioOutput(device.getId(), device.getType(),
                        product == null ? null : product.toString(), device.isSink()));
            }
            return result;
        }
    }
}
