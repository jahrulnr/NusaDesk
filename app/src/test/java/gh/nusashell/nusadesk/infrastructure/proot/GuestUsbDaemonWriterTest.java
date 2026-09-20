package gh.nusashell.nusadesk.infrastructure.proot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Focused tests for the generated USB driver daemon ({@code nusadesk-usbd}):
 * the locked shim protocol it serves and its installation through the shared
 * guest-file ensure path.
 */
public class GuestUsbDaemonWriterTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installsDaemonThroughSharedEnsurePath() throws Exception {
        Path rootfs = temporary.newFolder("rootfs").toPath();
        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0"));

        Path daemon = rootfs.resolve(GuestUsbDaemonWriter.GUEST_DAEMON_RELATIVE_PATH);
        assertTrue("daemon must exist", Files.isRegularFile(daemon));
        assertTrue("daemon must be executable", Files.isExecutable(daemon));
        String content = new String(Files.readAllBytes(daemon), StandardCharsets.UTF_8);
        assertEquals("same canonical text as the writer advertises",
                GuestUsbDaemonWriter.scriptContent("0.1.0"), content);
        assertTrue(content.startsWith("#!/usr/bin/env python3"));
        assertTrue(content.contains("App version: 0.1.0"));

        assertEquals(GuestAwarenessReadmeWriter.Result.UNCHANGED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.1.0"));
        Files.write(daemon, "stale".getBytes(StandardCharsets.UTF_8));
        assertEquals(GuestAwarenessReadmeWriter.Result.UPDATED,
                GuestAwarenessReadmeWriter.ensure(rootfs, "0.2.0"));
        assertTrue(new String(Files.readAllBytes(daemon), StandardCharsets.UTF_8)
                .contains("App version: 0.2.0"));
    }

    @Test
    public void scriptPinsTheLockedShimProtocol() {
        String content = GuestUsbDaemonWriter.scriptContent("0.1.0");

        // Same session transport as every other guest CLI.
        assertTrue(content.contains("ENV_FILE = '/run/nusadesk/android-bridge.env'"));
        assertTrue(content.contains("PROTOCOL_VERSION = 1"));
        assertTrue(content.contains("call('usb.list'"));  // matches bridge_call('usb.list'…

        // The shim-facing socket and its request/response vocabulary.
        assertTrue(content.contains("SOCKET_NAME = 'nusadesk-usbd'"));
        assertTrue(content.contains("'LIST'"));
        assertTrue(content.contains("'DEV %04x %04x'"));
        assertTrue(content.contains("'DEV %04x %04x %04x %02x %02x %02x %02x %02x %02x %02x %02x'"));
        assertTrue(content.contains("'END'"));
        assertTrue(content.contains("'OPEN '"));
        assertTrue(content.contains("'OK %04x %04x\\n'"));
        assertTrue(content.contains("b'ERR bad-request\\n'"));
        assertTrue(content.contains("'ERR %s\\n'"));
        assertTrue(content.contains("'PING'"));
        assertTrue(content.contains("b'PONG\\n'"));

        // fd hand-off to the shim and the descriptor cache behind DEV rows.
        assertTrue(content.contains("SCM_RIGHTS"));
        assertTrue(content.contains("sendmsg("));
        assertTrue(content.contains("CMSG_SPACE"));
        assertTrue(content.contains("def is_adb_device(device):"));
        assertTrue(content.contains("'ff4201' in interfaces.split(',')"));
        assertTrue(content.contains("USBDEVFS_CONTROL = 0xC0185500"));
        assertTrue(content.contains("def read_descriptor(fd):"));
        assertTrue(content.contains("USB_OPEN_TIMEOUT_SECONDS = 75.0"));

        // Detached autostart used by the generated adb wrapper.
        assertTrue(content.contains("'--ensure'"));
        assertTrue(content.contains("os.fork()"));
        assertTrue(content.contains("os.setsid()"));
        assertTrue(content.contains("def can_connect():"));

        // One thread per request: an OPEN can block on the host dialog.
        assertTrue(content.contains("threading.Thread(target=handle_connection"));

        // It never runs a shell and never prints the session token.
        String lower = content.toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("shell=true"));
        assertFalse(lower.contains("os.system"));
        assertFalse(lower.contains("os.popen"));
        assertFalse(content.contains("print(token"));
    }
}
