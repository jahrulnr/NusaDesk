package gh.nusashell.nusadesk.infrastructure.sshserver;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Pure validation tests for {@link SshBridgeStartRequest}. No I/O, no Android.
 */
public class SshBridgeStartRequestTest {

    @Test
    public void preservesValidParameters() {
        SshBridgeStartRequest request = new SshBridgeStartRequest(
                "ssh-bridge", "0.1.0", "sess-1", 2_000L, 1_000L);
        assertEquals("ssh-bridge", request.getAppId());
        assertEquals("0.1.0", request.getAppVersion());
        assertEquals("sess-1", request.getSessionId());
        assertEquals(2_000L, request.getHealthTimeoutMillis());
        assertEquals(1_000L, request.getShellDestroyGraceMillis());
    }

    @Test
    public void rejectsBlankAppId() {
        try {
            new SshBridgeStartRequest(" ", "0.1.0", "sess-1", 0L, 0L);
            fail("expected rejection of blank appId");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsBlankAppVersion() {
        try {
            new SshBridgeStartRequest("ssh-bridge", null, "sess-1", 0L, 0L);
            fail("expected rejection of null appVersion");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsBlankSessionId() {
        try {
            new SshBridgeStartRequest("ssh-bridge", "0.1.0", "", 0L, 0L);
            fail("expected rejection of blank sessionId");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNegativeTimeouts() {
        try {
            new SshBridgeStartRequest("ssh-bridge", "0.1.0", "sess-1", -1L, 0L);
            fail("expected rejection of negative healthTimeoutMillis");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new SshBridgeStartRequest("ssh-bridge", "0.1.0", "sess-1", 0L, -1L);
            fail("expected rejection of negative shellDestroyGraceMillis");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void acceptsZeroTimeouts() {
        SshBridgeStartRequest request = new SshBridgeStartRequest(
                "ssh-bridge", "0.1.0", "sess-1", 0L, 0L);
        assertEquals(0L, request.getHealthTimeoutMillis());
        assertEquals(0L, request.getShellDestroyGraceMillis());
    }
}
