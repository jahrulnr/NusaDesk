package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothAdapter;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM contract tests using fake profile and output providers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class BluetoothAudioModuleTest {
    private static final String ADDRESS = "00:11:22:33:AA:BB";

    private Context context;
    private BluetoothAdapter adapter;
    private FakeProfiles profiles;
    private FakeOutputs outputs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        adapter = BluetoothAdapter.getDefaultAdapter();
        ShadowBluetoothAdapter shadow = Shadow.extract(adapter);
        shadow.setState(BluetoothAdapter.STATE_ON);
        profiles = new FakeProfiles();
        outputs = new FakeOutputs();
    }

    @Test
    public void declaresMethodsAndParameterSet() {
        BluetoothAudioModule module = module();
        assertEquals(List.of("bt.audio.status", "bt.audio.voice.start", "bt.audio.voice.stop"),
                module.methods());
        assertEquals(Set.of("bt.audio.voice.start", "bt.audio.voice.stop"),
                module.parameterMethods());
    }

    @Test
    public void api31PermissionGatePreventsProfileAndOutputCalls() {
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.status"));

        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-permission-required"));
        assertTrue(profiles.requested.isEmpty());
    }

    @Test
    public void adapterOffIsTypedUnavailable() {
        grantConnect();
        Shadow.<ShadowBluetoothAdapter>extract(adapter).setState(BluetoothAdapter.STATE_OFF);

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.status"));

        assertFalse(response.isOk());
        assertTrue(response.getError().startsWith("bt-unavailable"));
    }

    @Test
    public void api31ExcludesLeProfileAndReportsOnlyBluetoothOutputs() {
        grantConnect();
        profiles.leAudio = lease(new BluetoothAudioModule.ProfileDevice(
                null, "le", "00:11:22:33:AA:CC", BluetoothProfile.STATE_CONNECTED));
        profiles.profile(BluetoothProfile.A2DP).devices.add(device("a2dp"));
        profiles.profile(BluetoothProfile.HEADSET).devices.add(
                device("hfp\u0007" + "x".repeat(140)));
        outputs.devices.add(new BluetoothAudioModule.AudioOutput(
                1, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "A2DP", true));
        outputs.devices.add(new BluetoothAudioModule.AudioOutput(
                2, AudioDeviceInfo.TYPE_BLE_HEADSET, "BLE headset", true));
        outputs.devices.add(new BluetoothAudioModule.AudioOutput(
                3, AudioDeviceInfo.TYPE_BLE_SPEAKER, "BLE speaker", true));
        outputs.devices.add(new BluetoothAudioModule.AudioOutput(
                4, AudioDeviceInfo.TYPE_BLE_BROADCAST, "BLE broadcast", true));
        outputs.devices.add(new BluetoothAudioModule.AudioOutput(
                5, AudioDeviceInfo.TYPE_BLE_HEARING_AID, "BLE hearing aid", true));
        outputs.devices.add(new BluetoothAudioModule.AudioOutput(
                6, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "phone", true));
        outputs.devices.add(new BluetoothAudioModule.AudioOutput(
                7, AudioDeviceInfo.TYPE_HEARING_AID, "classic hearing aid", true));

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.status"));

        assertTrue(response.isOk());
        String profilesJson = (String) response.getFields().get("profiles_json");
        assertTrue(profilesJson.contains("\"profile\":\"a2dp\",\"available\":true"));
        assertTrue(profilesJson.contains("\"profile\":\"headset\",\"available\":true"));
        assertTrue(profilesJson.contains("\"name\":\"hfp "));
        assertFalse(profilesJson.contains("\u0007"));
        assertFalse(profilesJson.contains("le_audio"));
        String outputsJson = (String) response.getFields().get("outputs_json");
        assertTrue(outputsJson.contains("\"id\":2,\"type\":\"ble_headset\""));
        assertTrue(outputsJson.contains("\"id\":5,\"type\":\"ble_hearing_aid\""));
        assertTrue(outputsJson.contains("\"id\":7,\"type\":\"hearing_aid\""));
        assertFalse(outputsJson.contains("phone"));
        assertFalse(profiles.requested.contains(BluetoothProfile.LE_AUDIO));
    }

    @Test
    @Config(sdk = 33)
    public void api33IncludesLeAudioProfile() {
        grantConnect();
        profiles.profile(BluetoothProfile.A2DP);
        profiles.profile(BluetoothProfile.HEADSET);
        profiles.leAudio = lease(device("le"));

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.status"));

        assertTrue(response.isOk());
        String json = (String) response.getFields().get("profiles_json");
        assertTrue(json.contains("\"profile\":\"le_audio\",\"available\":true"));
        assertTrue(profiles.requested.contains(BluetoothProfile.LE_AUDIO));
    }

    @Test
    public void statusIsBoundedMapsNullProxiesAndAlwaysClosesAcquiredProxies() {
        grantConnect();
        FakeLease a2dp = profiles.profile(BluetoothProfile.A2DP);
        for (int i = 0; i < 9; i++) {
            a2dp.devices.add(device("a2dp-" + i));
        }
        profiles.profile(BluetoothProfile.HEADSET);
        profiles.timeoutProfiles.add(BluetoothProfile.HEADSET);
        outputs.devices.add(new BluetoothAudioModule.AudioOutput(
                1, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "a2dp", true));
        for (int i = 2; i < 35; i++) {
            outputs.devices.add(new BluetoothAudioModule.AudioOutput(
                    i, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "sco-" + i, true));
        }

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.status"));

        assertTrue(response.isOk());
        String profilesJson = (String) response.getFields().get("profiles_json");
        assertEquals(8, occurrences(profilesJson, "\"address\":"));
        assertTrue(profilesJson.contains("\"profile\":\"headset\",\"available\":false"));
        String outputsJson = (String) response.getFields().get("outputs_json");
        assertEquals(32, occurrences(outputsJson, "\"id\":"));
        assertEquals(2_500L, profiles.timeoutMillis);
        assertTrue(profiles.closed.contains(BluetoothProfile.A2DP));
        assertFalse(profiles.closed.contains(BluetoothProfile.HEADSET));
    }

    @Test
    public void voiceStartWithoutConnectedHfpIsTyped() {
        grantConnect();
        profiles.profile(BluetoothProfile.HEADSET);

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.voice.start", "{\"address\":\"" + ADDRESS + "\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-audio-not-connected", response.getError());
        assertTrue(profiles.closed.contains(BluetoothProfile.HEADSET));
    }

    @Test
    public void voiceStartStopRefusalAndIdempotencyUseOnlyModulePath() {
        grantConnect();
        FakeLease headset = profiles.profile(BluetoothProfile.HEADSET);
        headset.devices.add(device(ADDRESS));
        BluetoothAudioModule module = module();

        AndroidCapabilityProtocol.Response started = module.handle(
                request("1", "bt.audio.voice.start", "{\"address\":\"" + ADDRESS + "\"}"));
        assertTrue(started.isOk());
        assertEquals(Boolean.TRUE, started.getFields().get("started"));
        assertEquals(1, headset.startCalls);
        assertTrue(profiles.closed.contains(BluetoothProfile.HEADSET));

        AndroidCapabilityProtocol.Response duplicate = module.handle(
                request("2", "bt.audio.voice.start", "{\"address\":\"" + ADDRESS + "\"}"));
        assertTrue(duplicate.isOk());
        assertEquals(1, headset.startCalls);

        AndroidCapabilityProtocol.Response mismatch = module.handle(
                request("3", "bt.audio.voice.stop", "{\"address\":\"00:11:22:33:AA:CC\"}"));
        assertFalse(mismatch.isOk());
        assertEquals("bt-audio-voice-unknown", mismatch.getError());

        AndroidCapabilityProtocol.Response stopped = module.handle(
                request("4", "bt.audio.voice.stop"));
        assertTrue(stopped.isOk());
        assertEquals(Boolean.TRUE, stopped.getFields().get("stopped"));
        assertEquals(1, headset.stopCalls);
        assertTrue(profiles.closed.contains(BluetoothProfile.HEADSET));

        AndroidCapabilityProtocol.Response again = module.handle(
                request("5", "bt.audio.voice.stop"));
        assertTrue(again.isOk());
        assertEquals(Boolean.FALSE, again.getFields().get("stopped"));
    }

    @Test
    public void voiceStartWithoutAddressUsesOnlyHeadsetAndCloseStopsVoicePath() {
        grantConnect();
        FakeLease headset = profiles.profile(BluetoothProfile.HEADSET);
        headset.devices.add(device(ADDRESS));
        BluetoothAudioModule module = module();

        AndroidCapabilityProtocol.Response response = module.handle(
                request("1", "bt.audio.voice.start"));

        assertTrue(response.isOk());
        assertEquals(Boolean.TRUE, response.getFields().get("started"));
        assertEquals(ADDRESS, response.getFields().get("address"));
        module.close();
        assertEquals(1, headset.stopCalls);
        assertTrue(profiles.closed.contains(BluetoothProfile.HEADSET));
    }

    @Test
    public void voiceStartWithoutAddressRefusesAmbiguousHeadsets() {
        grantConnect();
        FakeLease headset = profiles.profile(BluetoothProfile.HEADSET);
        headset.devices.add(device(ADDRESS));
        headset.devices.add(new BluetoothAudioModule.ProfileDevice(
                null, "second", "00:11:22:33:AA:CC", BluetoothProfile.STATE_CONNECTED));

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.voice.start"));

        assertFalse(response.isOk());
        assertEquals("bt-audio-voice-ambiguous", response.getError());
        assertEquals(0, headset.startCalls);
        assertTrue(profiles.closed.contains(BluetoothProfile.HEADSET));
    }

    @Test
    public void voiceStateRecoversAfterHeadsetDisconnects() {
        grantConnect();
        FakeLease headset = profiles.profile(BluetoothProfile.HEADSET);
        headset.devices.add(device(ADDRESS));
        BluetoothAudioModule module = module();
        assertTrue(module.handle(request("1", "bt.audio.voice.start")).isOk());

        headset.devices.clear();
        headset.devices.add(new BluetoothAudioModule.ProfileDevice(
                null, "replacement", "00:11:22:33:AA:CC", BluetoothProfile.STATE_CONNECTED));
        AndroidCapabilityProtocol.Response replacement = module.handle(
                request("2", "bt.audio.voice.start",
                        "{\"address\":\"00:11:22:33:AA:CC\"}"));

        assertTrue(replacement.isOk());
        assertEquals("00:11:22:33:AA:CC", replacement.getFields().get("address"));
        assertEquals(2, headset.startCalls);
    }

    @Test
    public void voiceStopTreatsDisconnectedHeadsetAsAlreadyStopped() {
        grantConnect();
        FakeLease headset = profiles.profile(BluetoothProfile.HEADSET);
        headset.devices.add(device(ADDRESS));
        BluetoothAudioModule module = module();
        assertTrue(module.handle(request("1", "bt.audio.voice.start")).isOk());
        headset.devices.clear();

        AndroidCapabilityProtocol.Response stopped = module.handle(
                request("2", "bt.audio.voice.stop"));

        assertTrue(stopped.isOk());
        assertEquals(Boolean.FALSE, stopped.getFields().get("stopped"));
        assertEquals(Boolean.TRUE, stopped.getFields().get("disconnected"));
        assertEquals(0, headset.stopCalls);
    }

    @Test
    public void voiceStartRefusalIsTyped() {
        grantConnect();
        FakeLease headset = profiles.profile(BluetoothProfile.HEADSET);
        headset.devices.add(device(ADDRESS));
        headset.startResult = false;

        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.voice.start", "{\"address\":\"" + ADDRESS + "\"}"));

        assertFalse(response.isOk());
        assertEquals("bt-audio-voice-refused", response.getError());
        assertEquals(1, headset.startCalls);
    }

    @Test
    public void voiceStartValidatesAddressAndHandleDoesNotThrow() {
        grantConnect();
        String tooLong = "00:11:22:33:AA:BBB";
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.voice.start", "{\"address\":\"" + tooLong + "\"}"));
        assertFalse(response.isOk());
        assertEquals("invalid-argument", response.getError());
    }

    @Test
    public void nullHeadsetProxyIsNoHeadset() {
        grantConnect();
        AndroidCapabilityProtocol.Response response = module().handle(
                request("1", "bt.audio.voice.start", "{\"address\":\"" + ADDRESS + "\"}"));
        assertFalse(response.isOk());
        assertEquals("bt-audio-no-headset", response.getError());
        assertEquals(2_500L, profiles.timeoutMillis);
    }

    private BluetoothAudioModule module() {
        return new BluetoothAudioModule(context, adapter, profiles, outputs);
    }

    private void grantConnect() {
        Shadow.<ShadowContextWrapper>extract(context)
                .grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
    }

    private static BluetoothAudioModule.ProfileDevice device(String name) {
        return new BluetoothAudioModule.ProfileDevice(null, name, ADDRESS,
                BluetoothProfile.STATE_CONNECTED);
    }

    private static BluetoothAudioModule.ProfileLease lease(
            BluetoothAudioModule.ProfileDevice device) {
        FakeLease lease = new FakeLease();
        lease.devices.add(device);
        return lease;
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static AndroidCapabilityProtocol.Request request(
            String id, String method) {
        return request(id, method, null);
    }

    private static AndroidCapabilityProtocol.Request request(
            String id, String method, String params) {
        String frame = "{\"v\":1,\"id\":\"" + id
                + "\",\"token\":\"t\",\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
        return AndroidCapabilityProtocol.decodeRequest(frame);
    }

    private static final class FakeProfiles implements BluetoothAudioModule.ProfileProvider {
        private final Map<Integer, FakeLease> leases = new HashMap<>();
        private final List<Integer> requested = new ArrayList<>();
        private final Set<Integer> closed = new HashSet<>();
        private final Set<Integer> timeoutProfiles = new HashSet<>();
        private long timeoutMillis;
        private BluetoothAudioModule.ProfileLease leAudio;

        FakeLease profile(int id) {
            FakeLease lease = leases.get(id);
            if (lease == null) {
                lease = new FakeLease();
                leases.put(id, lease);
            }
            return lease;
        }

        @Override
        public BluetoothAudioModule.ProfileLease acquire(int profileId, long timeoutMillis) {
            requested.add(profileId);
            this.timeoutMillis = timeoutMillis;
            if (timeoutProfiles.contains(profileId)) {
                return null;
            }
            if (profileId == BluetoothProfile.LE_AUDIO) {
                return leAudio;
            }
            return leases.get(profileId);
        }

        @Override
        public void closeProfileProxy(int profileId, BluetoothAudioModule.ProfileLease profile) {
            closed.add(profileId);
        }
    }

    private static final class FakeLease implements BluetoothAudioModule.ProfileLease {
        private final List<BluetoothAudioModule.ProfileDevice> devices = new ArrayList<>();
        private boolean startResult = true;
        private boolean stopResult = true;
        private int startCalls;
        private int stopCalls;

        @Override
        public List<BluetoothAudioModule.ProfileDevice> connectedDevices() {
            return devices;
        }

        @Override
        public boolean startVoiceRecognition(BluetoothAudioModule.ProfileDevice device) {
            startCalls++;
            return startResult;
        }

        @Override
        public boolean stopVoiceRecognition(BluetoothAudioModule.ProfileDevice device) {
            stopCalls++;
            return stopResult;
        }
    }

    private static final class FakeOutputs implements BluetoothAudioModule.AudioOutputProvider {
        private final List<BluetoothAudioModule.AudioOutput> devices = new ArrayList<>();

        @Override
        public List<BluetoothAudioModule.AudioOutput> outputs() {
            return devices;
        }
    }
}
