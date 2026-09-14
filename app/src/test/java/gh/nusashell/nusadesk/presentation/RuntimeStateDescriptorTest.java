package gh.nusashell.nusadesk.presentation;

import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

import org.junit.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeStateDescriptorTest {
    @Test
    public void everyDomainStateHasAccessibleCopy() {
        for (RuntimeState state : RuntimeState.values()) {
            RuntimeStateDescriptor descriptor = RuntimeStateDescriptor.forState(state);

            assertFalse(descriptor.getLabel().trim().isEmpty());
            assertFalse(descriptor.getTitle().trim().isEmpty());
            assertFalse(descriptor.getSummary().trim().isEmpty());
            assertFalse(descriptor.getDetail().trim().isEmpty());
        }
    }

    @Test
    public void everyStateHasADistinctLabelSoTwoStatesCannotRenderAlike() {
        Set<String> labels = new HashSet<>();
        for (RuntimeState state : RuntimeState.values()) {
            assertTrue("duplicate label for " + state,
                    labels.add(RuntimeStateDescriptor.forState(state).getLabel()));
        }
    }

    @Test
    public void notInstalledExplainsTheTruthfulSetupBoundary() {
        RuntimeStateDescriptor descriptor =
                RuntimeStateDescriptor.forState(RuntimeState.NOT_INSTALLED);

        assertTrue(descriptor.getTitle().contains("not installed"));
        assertTrue(descriptor.getDetail().contains("Setup"));
    }

    @Test
    public void runningCopyTiesReadinessToAHealthCheckNotAProcess() {
        RuntimeStateDescriptor descriptor =
                RuntimeStateDescriptor.forState(RuntimeState.RUNNING);

        assertTrue(descriptor.getSummary().contains("ready loopback endpoint"));
        assertTrue(descriptor.getDetail().contains("health check"));
    }

    /**
     * Regression guard for the shipped contradiction: the install surface used
     * to claim the foundation "does not run a Linux process" while a real guest
     * shell was running behind it, and the RUNNING summary used to say a payload
     * "would" be fetched. Product copy must describe the product, not a preview.
     */
    @Test
    public void noStateDescribesTheProductAsAPreviewOrAScaffold() {
        for (RuntimeState state : RuntimeState.values()) {
            RuntimeStateDescriptor descriptor = RuntimeStateDescriptor.forState(state);
            String copy = (descriptor.getLabel() + " " + descriptor.getTitle() + " "
                    + descriptor.getSummary() + " " + descriptor.getDetail())
                    .toLowerCase(Locale.ROOT);

            assertFalse(state.name(), copy.contains("design preview"));
            assertFalse(state.name(), copy.contains("does not run a linux process"));
            assertFalse(state.name(), copy.contains("intentionally not implemented"));
            assertFalse(state.name(), copy.contains("this phase"));
            assertFalse(state.name(), copy.contains("would be"));
        }
    }
}
