package gh.nusashell.nusadesk.domain.boot;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Truth table for the boot-start decision (ADR-0037): the consent gate first,
 * then each install gate with its own typed skip reason. The policy never
 * repairs anything — every missing input is a skip, not a start.
 */
public class BootAutostartPolicyTest {

    @Test
    public void optedOutAlwaysSkipsWithOptOut() {
        assertEquals(BootAutostartDecision.SKIP_OPT_OUT,
                BootAutostartPolicy.decide(false, true, true, true));
        assertEquals(BootAutostartDecision.SKIP_OPT_OUT,
                BootAutostartPolicy.decide(false, false, false, false));
    }

    @Test
    public void optedInWithEveryGateSetStarts() {
        assertEquals(BootAutostartDecision.START,
                BootAutostartPolicy.decide(true, true, true, true));
    }

    @Test
    public void payloadNotReadySkipsWithItsOwnReason() {
        assertEquals(BootAutostartDecision.SKIP_PAYLOAD_NOT_READY,
                BootAutostartPolicy.decide(true, false, true, true));
        assertEquals(BootAutostartDecision.SKIP_PAYLOAD_NOT_READY,
                BootAutostartPolicy.decide(true, false, false, false));
    }

    @Test
    public void missingSshAddonSkipsWithItsOwnReason() {
        assertEquals(BootAutostartDecision.SKIP_SSH_ADDON_MISSING,
                BootAutostartPolicy.decide(true, true, false, true));
        assertEquals("the bridge input cannot rescue a missing terminal component",
                BootAutostartDecision.SKIP_SSH_ADDON_MISSING,
                BootAutostartPolicy.decide(true, true, false, false));
    }

    @Test
    public void unsettledBridgeSkipsWithItsOwnReason() {
        assertEquals(BootAutostartDecision.SKIP_BRIDGE_NOT_SETTLED,
                BootAutostartPolicy.decide(true, true, true, false));
    }

    @Test
    public void skipReasonsDoNotOverlap() {
        // Each gate reports its own reason; nothing collapses into a generic
        // failure, so the receiver log can say exactly what is missing.
        assertEquals(BootAutostartDecision.SKIP_SSH_ADDON_MISSING,
                BootAutostartPolicy.decide(true, true, false, false));
        assertEquals(BootAutostartDecision.SKIP_PAYLOAD_NOT_READY,
                BootAutostartPolicy.decide(true, false, false, true));
    }
}
