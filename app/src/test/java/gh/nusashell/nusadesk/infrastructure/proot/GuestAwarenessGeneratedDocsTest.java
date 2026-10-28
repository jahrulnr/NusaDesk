package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Focused contract tests for the guest-facing docs the writer generates:
 * the /root/README.md link block and the /root/docs/*.md topic docs.
 */
public class GuestAwarenessGeneratedDocsTest {

    @Test
    public void readmeLinksToEveryTopicDoc() throws IOException {
        String readme = GuestDocsFixture.doc("README.md", "0.1.0");
        assertTrue(readme.contains("(docs/README.md)"));
        assertTrue(readme.contains("(docs/environment.md)"));
        assertTrue(readme.contains("(docs/bridge.md)"));
        assertTrue(readme.contains("(docs/media.md)"));
        assertTrue(readme.contains("(docs/battery-sensors-location.md)"));
        assertTrue(readme.contains("(docs/messaging-telephony.md)"));
        assertTrue(readme.contains("(docs/calendar.md)"));
        assertTrue(readme.contains("(docs/desktop.md)"));
        // The Termux topic doc ships only with the optional Termux toolkit
        // (ADR-0057), so the README names it conditionally instead of linking
        // a file a core-only install does not have.
        assertTrue(readme.contains("termux-compat.md"));
        assertTrue(readme.contains("optional toolkit"));
        assertFalse(readme.contains("(docs/termux-compat.md)"));
        assertTrue(readme.contains("App version: 0.1.0"));
        // One line per topic doc plus the managed footer; keep the README a
        // single compact screen.
        assertTrue(lineCount(readme) < 36);
    }

    @Test
    public void docsIndexListsEveryTopicAndTheCli() throws IOException {
        String index = GuestDocsFixture.doc("docs/README.md", "0.1.0");
        assertTrue(index.contains("(bridge.md)"));
        assertTrue(index.contains("(media.md)"));
        assertTrue(index.contains("(environment.md)"));
        assertTrue(index.contains("(battery-sensors-location.md)"));
        assertTrue(index.contains("(messaging-telephony.md)"));
        assertTrue(index.contains("(calendar.md)"));
        assertTrue(index.contains("(desktop.md)"));
        assertTrue(index.contains("(termux-compat.md)"));
        assertTrue(index.contains("/usr/local/bin/nusadesk-android"));
        assertTrue(index.contains("android-cli"));
        assertTrue(index.contains("nusadesk-android calendar list"));
        assertTrue(index.contains("nusadesk-android calendar add --title"));
        assertTrue(index.contains("nusadesk-android calendar delete EVENT_ID"));
        assertTrue(index.contains("nusadesk-android media start [--camera|--microphone]"));
        assertTrue(index.contains("nusadesk-android media outputs|route DEVICE_ID|route clear"));
        assertTrue(index.contains("nusadesk-bt hid start|status|connect|type|key|mouse|stop"));
        assertTrue(index.contains("nusadesk-bt audio status|voice start|voice stop"));
        assertTrue(index.contains("nusadesk-android bridge info"));
    }

    @Test
    public void bridgeDocDocumentsEnvProtocolMethodsAndCliBoundary() throws IOException {
        String bridge = GuestDocsFixture.doc("docs/bridge.md", "0.1.0");
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
        assertTrue(bridge.contains("mediaplayer.outputs"));
        assertTrue(bridge.contains("mediaplayer.route.clear"));
        // Capture, sensors catalog, background location, native extras, and
        // USB are real methods and must be named, not left to imagination.
        assertTrue(bridge.contains("camera.photo"));
        assertTrue(bridge.contains("microphone.record.start"));
        assertTrue(bridge.contains("sensor.list"));
        assertTrue(bridge.contains("sensor.stream.start|poll|stop"));
        assertTrue(bridge.contains("location.background.start|poll|stop"));
        assertTrue(bridge.contains("wifi.hotspot.*"));
        assertTrue(bridge.contains("packages.*"));
        assertTrue(bridge.contains("usb.list"));
        assertTrue(bridge.contains("usb.open"));
        assertTrue(bridge.contains("bt.hid.start"));
        assertTrue(bridge.contains("keyboard/mouse HID device"));
        assertTrue(bridge.contains("bt.audio.voice.start|stop"));
        assertTrue(bridge.contains("voice-recognition control"));
        assertTrue(bridge.contains("output device ids are temporary"));
        assertTrue(bridge.contains("token is never"));
        assertTrue(bridge.contains("64 KiB"));
        // The envelope is no longer param-free: the methods that declare
        // parameters carry one bounded params object, and the doc must state
        // the v2 bounds instead of the retired claims.
        assertTrue(bridge.contains("## Bounded params"));
        assertTrue(bridge.contains("at most 16 keys"));
        assertTrue(bridge.contains("`invalid-argument`"));
        assertFalse("the retired param-free claim must not come back",
                bridge.contains("no method takes request parameters"));
    }

    @Test
    public void mediaDocDocumentsLiveOnlyLoopbackContract() throws IOException {
        String media = GuestDocsFixture.doc("docs/media.md", "0.1.0");

        // Live stream is live-only; capture files are a separate surface.
        assertTrue(media.contains("live-only"));
        assertTrue(media.contains("camera.snapshot"));
        assertTrue(media.contains("mic.record"));
        assertTrue(media.contains("retired"));
        // The capture contract must be named: methods, grant, bound, clients.
        assertTrue(media.contains("camera.info"));
        assertTrue(media.contains("camera.photo"));
        assertTrue(media.contains("microphone.record.start|info|stop"));
        assertTrue(media.contains("RECORD_AUDIO"));
        assertTrue(media.contains("0..900"));
        assertTrue(media.contains("termux-camera-photo"));
        assertTrue(media.contains("termux-microphone-record"));
        assertFalse("the retired no-capture-anywhere claim must not come back",
                media.contains("no guest capture file is produced anywhere"));

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
                "media-mode-conflict", "media-encoder-unavailable",
                "media-start-failed"}) {
            assertTrue("media doc must list " + error, media.contains("`" + error + "`"));
        }

        // All three track modes are documented, including the CLI flags and the
        // rule that a status/notification never claims a track that is off.
        assertTrue(media.contains("media start --camera"));
        assertTrue(media.contains("media start --microphone"));
        assertTrue(media.contains("`mode`"));
        assertTrue(media.contains("A microphone-only session therefore reports no video size"));
        assertTrue(media.contains("camera-only session reports no audio codec"));

        // No claim that the stream was physically verified on a device.
        String lower = media.toLowerCase();
        assertFalse(lower.contains("verified"));
        assertFalse(lower.contains("device-tested"));
        assertTrue(lineCount(media) < 170);
    }

    @Test
    public void batterySensorsLocationDocCoversAllSurfaces() throws IOException {
        String doc = GuestDocsFixture.doc("docs/battery-sensors-location.md", "0.1.0");
        assertTrue(doc.contains("battery.status"));
        assertTrue(doc.contains("/sys/class/power_supply/battery"));
        assertTrue(doc.contains("sensor.accelerometer"));
        assertTrue(doc.contains("sensor.gyroscope"));
        assertTrue(doc.contains("sensor.list"));
        assertTrue(doc.contains("sensor.read"));
        assertTrue(doc.contains("sensor.stream.start|poll|stop"));
        assertTrue(doc.contains("location.get"));
        assertTrue(doc.contains("location.stream.start"));
        assertTrue(doc.contains("location.stream.poll"));
        assertTrue(doc.contains("location.stream.stop"));
        assertTrue(doc.contains("5 second interval"));
        // Background location is a real opt-in surface behind nusadesk-loc.
        assertTrue(doc.contains("location.background.start|poll|stop"));
        assertTrue(doc.contains("nusadesk-loc"));
        assertTrue(doc.contains("Allow all the time"));
        assertTrue(doc.contains("location-background-permission-required"));
        assertFalse("the retired foreground-only claim must not come back",
                doc.contains("never opens a background location"));
    }

    @Test
    public void messagingTelephonyDocCoversReadsAndSideEffectingGrants() throws IOException {
        String doc = GuestDocsFixture.doc("docs/messaging-telephony.md", "0.1.0");
        for (String method : new String[]{
                "contacts.list", "calllog.list", "sms.inbox",
                "telephony.info", "telephony.cellinfo"}) {
            assertTrue("doc must list " + method, doc.contains(method));
        }
        assertTrue(doc.contains("sms.send"));
        assertTrue(doc.contains("phone.call"));
        // The side-effecting methods are served with their own per-call
        // grants; the retired "never dispatched" claim must not come back.
        assertFalse("the retired never-dispatched claim must not come back",
                doc.contains("never dispatched"));
        assertTrue(doc.contains("per-call grant"));
        assertTrue(doc.contains("SEND_SMS"));
        assertTrue(doc.contains("CALL_PHONE"));
        assertTrue(doc.contains("unconfirmed"));
        assertTrue(doc.contains("count"));
        assertTrue(doc.contains("truncated"));
        assertTrue(doc.contains("Read-only"));
    }

    @Test
    public void calendarDocCoversReadWriteBoundsAndPermissions() throws IOException {
        String doc = GuestDocsFixture.doc("docs/calendar.md", "0.1.0");

        for (String method : new String[]{
                "calendar.list", "calendar.insert", "calendar.update", "calendar.delete"}) {
            assertTrue("doc must list " + method, doc.contains(method));
        }
        assertTrue(doc.contains("READ_CALENDAR"));
        assertTrue(doc.contains("WRITE_CALENDAR"));
        assertTrue(doc.contains("seven days"));
        assertTrue(doc.contains("count"));
        assertTrue(doc.contains("truncated"));
        for (String error : new String[]{
                "calendar-permission-required", "calendar-permission-denied",
                "calendar-unavailable", "calendar-invalid-argument",
                "calendar-read-only", "calendar-not-found", "calendar-failed"}) {
            assertTrue("doc must list " + error, doc.contains("`" + error + "`"));
        }
        assertTrue("the bounded write rules must be stated",
                doc.contains("at most 24 hours long"));
        assertTrue(doc.contains("contributor or better"));
        assertTrue("attendee writes stay out of contract",
                doc.contains("never sends an invitation"));
        assertTrue(doc.contains("nusadesk-android calendar add --title"));
        assertTrue(doc.contains("nusadesk-android calendar update"));
        assertTrue(doc.contains("nusadesk-android calendar delete"));
        assertFalse("no claim that calendar access was device-verified",
                doc.toLowerCase().contains("verified"));
    }

    @Test
    public void environmentDocCoversTheNusaDeskSpecificContracts() throws IOException {
        String doc = GuestDocsFixture.doc("docs/environment.md", "0.1.0");
        assertTrue(doc.contains("127.0.0.1:22022"));
        assertTrue(doc.contains("/tmp"));
        assertTrue(doc.contains("/root/nusadesk"));
        assertTrue(doc.contains("systemctl3"));
        assertTrue(doc.contains("systemctl --user"));
        assertTrue(doc.contains("udocker compose"));
        assertTrue(doc.contains("ports:"));
        assertTrue(doc.contains("lw-compose-supervisor"));
        assertTrue(doc.contains("nusadesk-serve"));
        assertTrue(doc.contains("nusadesk-usb"));
        assertTrue(doc.contains("android-cli"));
        assertTrue(doc.contains("termux-*"));
        assertFalse("no claim that the guest is a VM",
                doc.contains("full Docker environment"));
    }

    @Test
    public void everyGeneratedDocIsBoundedAndVersioned() throws IOException {
        String version = "0.1.0";
        String[] docs = {
                GuestDocsFixture.doc("docs/README.md", version),
                GuestDocsFixture.doc("docs/bridge.md", version),
                GuestDocsFixture.doc("docs/media.md", version),
                GuestDocsFixture.doc("docs/battery-sensors-location.md", version),
                GuestDocsFixture.doc("docs/messaging-telephony.md", version),
                GuestDocsFixture.doc("docs/calendar.md", version),
                GuestDocsFixture.doc("docs/environment.md", version)
        };
        for (String doc : docs) {
            assertTrue("docs must carry the app version", doc.contains("version " + version));
            assertTrue("docs must carry the managed-file notice",
                    doc.contains("Docs generated by the NusaDesk Android app"));
            assertTrue("docs must warn about regeneration",
                    doc.contains("Manual edits may be overwritten"));
            // The media doc is the largest topic (live stream plus the
            // capture surface), so the single bound leaves headroom without
            // letting a doc run away.
            assertTrue("docs must stay bounded", lineCount(doc) < 170);
        }
    }

    /**
     * The shipped asset tree and the writer constants must agree: a bundled
     * file no list installs (or a list entry nobody ships) is a silent doc
     * regression, so keep the two sets equal.
     */
    @Test
    public void assetTreeAndWriterListsStayInSync() throws IOException {
        Set<String> docAssets = new TreeSet<>();
        Set<String> agentAssets = new TreeSet<>();
        try (java.util.stream.Stream<Path> walk =
                Files.walk(GuestDocsFixture.ASSET_DIR)) {
            walk.filter(Files::isRegularFile)
                    .map(path -> GuestDocsFixture.ASSET_DIR.relativize(path).toString())
                    .forEach(asset -> (asset.startsWith("agents/")
                            ? agentAssets : docAssets).add(asset));
        }
        // The Termux-compat doc is a template owned by GuestTermuxCompatWriter.
        docAssets.remove("docs/termux-compat.md");
        assertEquals(docAssets,
                new TreeSet<>(Arrays.asList(GuestAwarenessReadmeWriter.DOC_ASSETS)));

        // agents/<path> installs at root/.agents/<path>.
        Set<String> expectedSeeds = new TreeSet<>();
        for (String asset : agentAssets) {
            expectedSeeds.add("root/." + asset);
        }
        assertEquals(expectedSeeds,
                new TreeSet<>(GuestAgentSeedWriter.seededRelativePaths()));
    }

    private static int lineCount(String text) {
        return text.split("\\n", -1).length;
    }
}
