package gh.nusashell.nusadesk.domain.compose;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ComposeRestartPolicyTest {

    @Test
    public void missingPolicyMapsToNo() {
        assertEquals(ComposeRestartPolicy.NO, ComposeRestartPolicy.fromComposeValue(null));
    }

    @Test
    public void parsesTheSupportedComposeValues() {
        assertEquals(ComposeRestartPolicy.NO, ComposeRestartPolicy.fromComposeValue("no"));
        assertEquals(ComposeRestartPolicy.ALWAYS, ComposeRestartPolicy.fromComposeValue("always"));
        assertEquals(ComposeRestartPolicy.UNLESS_STOPPED,
                ComposeRestartPolicy.fromComposeValue("unless-stopped"));
    }

    @Test
    public void trimsAndMatchesCaseInsensitively() {
        assertEquals(ComposeRestartPolicy.ALWAYS, ComposeRestartPolicy.fromComposeValue("  Always "));
        assertEquals(ComposeRestartPolicy.UNLESS_STOPPED,
                ComposeRestartPolicy.fromComposeValue("UNLESS-STOPPED"));
    }

    @Test
    public void rejectsBlankValues() {
        assertRejected("");
        assertRejected("   ");
    }

    @Test
    public void rejectsUnsupportedComposePolicies() {
        assertRejected("on-failure");
        assertRejected("on-failure:3");
        assertRejected("unless_stopped");
        assertRejected("yes");
    }

    @Test
    public void composeValueRoundTripsThroughTheParser() {
        for (ComposeRestartPolicy policy : ComposeRestartPolicy.values()) {
            assertEquals(policy,
                    ComposeRestartPolicy.fromComposeValue(policy.toComposeValue()));
        }
    }

    private static void assertRejected(String raw) {
        try {
            ComposeRestartPolicy.fromComposeValue(raw);
            fail("expected rejection of: " + raw);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
