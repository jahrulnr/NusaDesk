package gh.nusashell.nusadesk.infrastructure.ssh;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SshSessionConfigTest {

    private static SshSessionConfig base() {
        return new SshSessionConfig(
                "127.0.0.1", 2222, "user", "cred",
                5000, 5000, 5000, 0,
                SshSessionConfig.DEFAULT_TERMINAL_TYPE, 80, 24);
    }

    @Test
    public void acceptsValidConfig() {
        SshSessionConfig cfg = base();
        assertEquals("127.0.0.1", cfg.getHost());
        assertEquals(2222, cfg.getPort());
        assertEquals("user", cfg.getUsername());
        assertEquals("cred", cfg.getCredentialId());
        assertEquals(80, cfg.getInitialCols());
        assertEquals(24, cfg.getInitialRows());
        assertEquals("127.0.0.1:2222", cfg.hostKeyScope());
    }

    @Test
    public void rejectsBlankHost() {
        assertThrows(IllegalArgumentException.class, () ->
                new SshSessionConfig(" ", 22, "u", "c", 1, 1, 1, 0, "xterm", 80, 24));
    }

    @Test
    public void rejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class, () ->
                new SshSessionConfig("h", 0, "u", "c", 1, 1, 1, 0, "xterm", 80, 24));
        assertThrows(IllegalArgumentException.class, () ->
                new SshSessionConfig("h", 70000, "u", "c", 1, 1, 1, 0, "xterm", 80, 24));
    }

    @Test
    public void rejectsBlankCredentialId() {
        assertThrows(IllegalArgumentException.class, () ->
                new SshSessionConfig("h", 22, "u", "", 1, 1, 1, 0, "xterm", 80, 24));
    }

    @Test
    public void rejectsNegativeTimeouts() {
        assertThrows(IllegalArgumentException.class, () ->
                new SshSessionConfig("h", 22, "u", "c", -1, 1, 1, 0, "xterm", 80, 24));
    }

    @Test
    public void rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () ->
                new SshSessionConfig("h", 22, "u", "c", 1, 1, 1, 0, "xterm", 0, 24));
        assertThrows(IllegalArgumentException.class, () ->
                new SshSessionConfig("h", 22, "u", "c", 1, 1, 1, 0, "xterm", 80, 0));
    }
}
