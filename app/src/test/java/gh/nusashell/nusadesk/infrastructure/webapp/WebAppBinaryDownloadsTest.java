package gh.nusashell.nusadesk.infrastructure.webapp;

import static org.junit.Assert.*;
import android.webkit.WebView;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import gh.nusashell.nusadesk.domain.webapp.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppBinaryDownloadsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String ORIGIN = "http://127.0.0.1:18995";
    private GuestWebAppDownloads downloads;
    private WebAppBinaryDownloads bridge;
    private WebView web;
    private Path rootfs;
    private final List<WebAppDownloadStatus> states = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private int seq;

    @Before public void setUp() throws Exception {
        rootfs = temp.newFolder("active").toPath(); Files.createDirectory(rootfs.resolve("root"));
        downloads = new GuestWebAppDownloads(rootfs, new WebAppDefinition(WebAppId.of("binary"), "Binary", null, 18995, 0, 0, 0), null);
        web = new WebView(RuntimeEnvironment.getApplication());
        bridge = new WebAppBinaryDownloads(web, ORIGIN, downloads, states::add);
    }
    @After public void tearDown() { bridge.close(); downloads.close(); web.destroy(); }

    @Test public void acknowledgedChunksPublishExactGuestBytes() throws Exception {
        send(new JSONObject().put("op", "start").put("size", 4).put("name", "export.bin"), ORIGIN);
        waitReplies(1);
        send(new JSONObject().put("op", "chunk").put("data", "AAEC/w=="), ORIGIN);
        waitReplies(2);
        send(new JSONObject().put("op", "finish"), ORIGIN);
        waitReplies(3);
        assertArrayEquals(new byte[] {0, 1, 2, (byte) 255}, Files.readAllBytes(rootfs.resolve("root/Downloads/export.bin")));
        assertEquals(WebAppDownloadStatus.State.SAVED, states.get(states.size()-1).getState());
    }

    @Test public void anotherOriginOrPortCannotStartAnExport() throws Exception {
        JSONObject start = new JSONObject().put("op", "start").put("size", 0).put("name", "escape");
        send(start, "http://127.0.0.1:18996"); send(start, "https://example.org");
        ShadowLooper.idleMainLooper();
        assertTrue(replies.isEmpty()); assertTrue(states.isEmpty());
        assertFalse(Files.exists(rootfs.resolve("root/Downloads")));
    }

    @Test public void unnamedEmptyExportUsesMimeWithoutCharsetForItsExtension() throws Exception {
        send(new JSONObject().put("op", "start").put("size", 0).put("mime", "text/plain;charset=UTF-8"), ORIGIN);
        waitReplies(1);
        send(new JSONObject().put("op", "finish"), ORIGIN); waitReplies(2);
        assertEquals(0, Files.size(rootfs.resolve("root/Downloads/download.txt")));
    }

    @Test public void invalidBytesAndDocumentNavigationCleanIncompleteFiles() throws Exception {
        send(new JSONObject().put("op", "start").put("size", 10).put("name", "broken.bin"), ORIGIN);
        waitReplies(1);
        send(new JSONObject().put("op", "chunk").put("data", "%%%"), ORIGIN);
        waitReplies(2);
        assertTrue(new JSONObject(replies.get(1)).has("error"));
        waitFailure(); assertEmptyDownloads();
    }

    @Test public void navigationAndCloseCancelRendererOwnedTransfers() throws Exception {
        send(new JSONObject().put("op", "start").put("size", 10).put("name", "leaving.bin"), ORIGIN);
        waitReplies(1); bridge.navigationStarted();
        waitFailure(); assertEmptyDownloads();
        bridge.close();
        int previous = states.size();
        send(new JSONObject().put("op", "start").put("size", 0).put("name", "late.bin"), ORIGIN);
        ShadowLooper.idleMainLooper(); assertEquals(previous, states.size());
    }

    private void assertEmptyDownloads() throws Exception {
        try (var paths = Files.list(rootfs.resolve("root/Downloads"))) { assertFalse(paths.findAny().isPresent()); }
    }
    private void waitFailure() throws Exception {
        for (int i = 0; i < 500; i++) {
            ShadowLooper.idleMainLooper();
            if (states.stream().anyMatch(s -> s.getState() == WebAppDownloadStatus.State.FAILED)) return;
            Thread.sleep(10);
        }
        fail("No failure reported");
    }
    private void waitReplies(int count) throws Exception {
        for (int i = 0; i < 500; i++) {
            ShadowLooper.idleMainLooper(); if (replies.size() >= count) return; Thread.sleep(10);
        }
        fail("No native acknowledgement");
    }
    private void send(JSONObject packet, String origin) throws Exception {
        packet.put("id", "fixture").put("seq", ++seq);
        bridge.receive(packet.toString(), origin, replies::add);
    }
}
