package gh.nusashell.nusadesk.infrastructure.ssh;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SshReconnectPolicyTest {

    @Test
    public void exponentialBackoffCapped() {
        SshReconnectPolicy policy = new SshReconnectPolicy(4, 100L, 400L);
        assertEquals(100L, policy.delayForAttempt(1));
        assertEquals(200L, policy.delayForAttempt(2));
        assertEquals(400L, policy.delayForAttempt(3));
        // capped at maxDelay
        assertEquals(400L, policy.delayForAttempt(4));
    }

    @Test
    public void zeroBaseYieldsZeroDelay() {
        SshReconnectPolicy policy = new SshReconnectPolicy(3, 0L, 0L);
        assertEquals(0L, policy.delayForAttempt(1));
    }

    @Test
    public void shouldRetryUpToMaxAttempts() {
        SshReconnectPolicy policy = new SshReconnectPolicy(3, 10L, 100L);
        assertTrue(policy.shouldRetry(0));
        assertTrue(policy.shouldRetry(1));
        assertTrue(policy.shouldRetry(2));
        assertFalse(policy.shouldRetry(3));
    }

    @Test
    public void rejectsBadConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new SshReconnectPolicy(-1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SshReconnectPolicy(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SshReconnectPolicy(1, 10, 5));
    }

    @Test
    public void attemptMustBeAtLeastOne() {
        SshReconnectPolicy policy = SshReconnectPolicy.DEFAULT;
        assertThrows(IllegalArgumentException.class, () -> policy.delayForAttempt(0));
    }

    @Test
    public void defaultPolicyIsBounded() {
        assertTrue(SshReconnectPolicy.DEFAULT.getMaxAttempts() > 0);
        assertTrue(SshReconnectPolicy.DEFAULT.shouldRetry(0));
    }
}
