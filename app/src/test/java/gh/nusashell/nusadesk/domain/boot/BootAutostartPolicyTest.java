package gh.nusashell.nusadesk.domain.boot;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Boot-start requires rootfs, SSH and the service bridge, never optional toolkits. */
public class BootAutostartPolicyTest {

    @Test
    public void optedOutAlwaysSkips() {
        assertEquals(BootAutostartDecision.SKIP_OPT_OUT,
                BootAutostartPolicy.decide(false, true, true, true));
        assertEquals(BootAutostartDecision.SKIP_OPT_OUT,
                BootAutostartPolicy.decide(false, false, false, false));
    }

    @Test
    public void allRequiredComponentsPresentStarts() {
        assertEquals(BootAutostartDecision.START,
                BootAutostartPolicy.decide(true, true, true, true));
    }

    @Test
    public void missingComponentsHaveDistinctReasonsInOrder() {
        assertEquals(BootAutostartDecision.SKIP_PAYLOAD_NOT_READY,
                BootAutostartPolicy.decide(true, false, false, false));
        assertEquals(BootAutostartDecision.SKIP_SSH_ADDON_MISSING,
                BootAutostartPolicy.decide(true, true, false, false));
        assertEquals(BootAutostartDecision.SKIP_BRIDGE_NOT_SETTLED,
                BootAutostartPolicy.decide(true, true, true, false));
    }
}
