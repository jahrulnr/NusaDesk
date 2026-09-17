package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Focused contract tests for the guest-facing docs the writer generates:
 * the /root/README.md link block and the /root/docs/*.md topic docs.
 */
public class GuestAwarenessGeneratedDocsTest {

    @Test
    public void readmeLinksToAllFiveFixedDocs() {
        String readme = GuestAwarenessReadmeWriter.content("0.1.0");
        assertTrue(readme.contains("(docs/README.md)"));
        assertTrue(readme.contains("(docs/bridge.md)"));
        assertTrue(readme.contains("(docs/media.md)"));
        assertTrue(readme.contains("(docs/battery-sensors-location.md)"));
        assertTrue(readme.contains("(docs/messaging-telephony.md)"));
        assertTrue(readme.contains("App version: 0.1.0"));
        assertTrue(lineCount(readme) < 32);
    }

    @Test
    public void docsIndexListsEveryTopicAndTheCli() {
        String index = GuestAwarenessReadmeWriter.docsIndexContent("0.1.0");
        assertTrue(index.contains("(bridge.md)"));
        assertTrue(index.contains("(media.md)"));
        assertTrue(index.contains("(battery-sensors-location.md)"));
        assertTrue(index.contains("(messaging-telephony.md)"));
        assertTrue(index.contains("/usr/local/bin/nusadesk-android"));
        assertTrue(index.contains("nusadesk-android media start|status|stop"));
        assertTrue(index.contains("nusadesk-android bridge info"));
    }

    @Test
    public void bridgeDocDocumentsEnvProtocolMethodsAndCliBoundary() {
        String bridge = GuestAwarenessReadmeWriter.bridgeDocContent("0.1.0");
        assertTrue(bridge.contains("/run/nusadesk/android-bridge.env"));
        assertTrue(bridge.contains("NUSADESK_ANDROID_BRIDGE_ADDRESS"));
        assertTrue(bridge.contains("NUSADESK_ANDROID_BRIDGE_PORT"));
        assertTrue(bridge.contains("NUSADESK_ANDROID_BRIDGE_TOKEN"));
        assertTrue(bridge.contains("NUSADESK_ANDROID_BRIDGE_PROTOCOL"));
        assertTrue(bridge.contains("loopback"));
        assertTrue(bridge.contains("nusadesk-android call <method>"));
        assertTrue(bridge.contains("bridge.info"));
        assertTrue(bridge.contains("media.start"));
        assertTrue(bridge.contains("media.status"));
        assertTrue(bridge.contains("media.stop"));
        assertTrue(bridge.contains("token is never"));
        assertTrue(bridge.contains("16 KiB"));
    }

    @Test
    public void mediaDocDocumentsLiveOnlyLoopbackContract() {
        String media = GuestAwarenessReadmeWriter.mediaDocContent("0.1.0");

        // Live-only: no capture files, no artifact paths, no persistence.
        assertTrue(media.contains("live-only"));
        assertTrue(media.contains("never writes a capture"));
        assertTrue(media.contains("Save a file yourself from the stream"));
        assertTrue(media.contains("camera.snapshot"));
        assertTrue(media.contains("mic.record"));
        assertTrue(media.contains("retired"));

        // Loopback-only RTSP over TCP with a bounded client cap.
        assertTrue(media.contains("loopback-only"));
        assertTrue(media.contains("rtsp://127.0.0.1:<port>/"));
        assertTrue(media.contains("client_limit"));
        assertTrue(media.contains("binds only to `127.0.0.1`"));

        // Codecs and target metadata fixed by the bridge contract.
        assertTrue(media.contains("H.264"));
        assertTrue(media.contains("AAC-LC"));
        assertTrue(media.contains("video_codec` = `h264"));
        assertTrue(media.contains("audio_codec` = `aac"));

        // Explicit states, commands, and lifecycle.
        for (String state : new String[]{"stopped", "starting", "running", "failed"}) {
            assertTrue("media doc must state " + state, media.contains("`" + state + "`"));
        }
        assertTrue(media.contains("nusadesk-android media start"));
        assertTrue(media.contains("nusadesk-android media status"));
        assertTrue(media.contains("nusadesk-android media stop"));
        assertTrue(media.contains("foreground media service"));

        // Consumer examples for Linux guests.
        assertTrue(media.contains("ffplay -rtsp_transport tcp"));
        assertTrue(media.contains("ffmpeg -rtsp_transport tcp -i"));
        assertTrue(media.contains("OpenCV"));

        // All typed media errors are documented.
        for (String error : new String[]{
                "media-permission-required", "media-permission-denied",
                "media-foreground-required", "media-unavailable", "media-busy",
                "media-encoder-unavailable", "media-start-failed"}) {
            assertTrue("media doc must list " + error, media.contains("`" + error + "`"));
        }

        // No claim that the stream was physically verified on a device.
        String lower = media.toLowerCase();
        assertFalse(lower.contains("verified"));
        assertFalse(lower.contains("device-tested"));
        assertTrue(lineCount(media) < 130);
    }

    @Test
    public void batterySensorsLocationDocCoversAllSurfaces() {
        String doc = GuestAwarenessReadmeWriter.batterySensorsLocationDocContent("0.1.0");
        assertTrue(doc.contains("battery.status"));
        assertTrue(doc.contains("/sys/class/power_supply/battery"));
        assertTrue(doc.contains("sensor.accelerometer"));
        assertTrue(doc.contains("sensor.gyroscope"));
        assertTrue(doc.contains("location.get"));
        assertTrue(doc.contains("location.stream.start"));
        assertTrue(doc.contains("location.stream.poll"));
        assertTrue(doc.contains("location.stream.stop"));
        assertTrue(doc.contains("foreground-only"));
        assertTrue(doc.contains("5 second interval"));
        assertTrue(doc.contains("background location"));
        assertTrue(doc.contains("automatic permission prompt"));
    }

    @Test
    public void messagingTelephonyDocCoversReadSurfacesAndRetiredSideEffects() {
        String doc = GuestAwarenessReadmeWriter.messagingTelephonyDocContent("0.1.0");
        for (String method : new String[]{
                "contacts.list", "calllog.list", "sms.inbox",
                "telephony.info", "telephony.cellinfo"}) {
            assertTrue("doc must list " + method, doc.contains(method));
        }
        assertTrue(doc.contains("sms.send"));
        assertTrue(doc.contains("phone.call"));
        assertTrue(doc.contains("never dispatched"));
        assertTrue(doc.contains("count"));
        assertTrue(doc.contains("truncated"));
        assertTrue(doc.contains("Read-only"));
    }

    @Test
    public void everyGeneratedDocIsBoundedAndVersioned() {
        String version = "0.1.0";
        String[] docs = {
                GuestAwarenessReadmeWriter.docsIndexContent(version),
                GuestAwarenessReadmeWriter.bridgeDocContent(version),
                GuestAwarenessReadmeWriter.mediaDocContent(version),
                GuestAwarenessReadmeWriter.batterySensorsLocationDocContent(version),
                GuestAwarenessReadmeWriter.messagingTelephonyDocContent(version)
        };
        for (String doc : docs) {
            assertTrue("docs must carry the app version", doc.contains("version " + version));
            assertTrue("docs must carry the managed-file notice",
                    doc.contains("Docs generated by the NusaDesk Android app"));
            assertTrue("docs must warn about regeneration",
                    doc.contains("Manual edits may be overwritten"));
            assertTrue("docs must stay bounded", lineCount(doc) < 120);
        }
    }

    private static int lineCount(String text) {
        return text.split("\\n", -1).length;
    }
}