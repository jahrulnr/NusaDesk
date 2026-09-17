package gh.nusashell.nusadesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guards which events may start each CI workflow.
 *
 * <p>The two workflows must not run in parallel on the same push: the debug
 * workflow validates feature branches only, while master is covered by
 * pull-request runs and by the version-gated release workflow. These are
 * workflow-file contracts rather than framework behaviour, so the cheap source
 * check is the right tool here — there is no runtime to execute.</p>
 */
public class CiWorkflowTriggerTest {

    private static String workflow(String fileName) throws Exception {
        // Gradle runs unit tests from the module directory, so the repository
        // root is one level up; the repository-relative path stays as a fallback.
        String[] candidates = {
                "../.github/workflows/" + fileName,
                ".github/workflows/" + fileName,
        };
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("workflow not found: " + fileName);
    }

    /**
     * The top-level {@code on:} block alone, so an unrelated mention of a branch
     * name or of {@code VERSION} deeper in the file cannot satisfy a check.
     */
    private static String triggers(String yaml) {
        StringBuilder block = new StringBuilder();
        boolean inside = false;
        for (String line : yaml.split("\n", -1)) {
            if (!inside) {
                if (line.startsWith("on:")) {
                    inside = true;
                    block.append(line).append('\n');
                }
                continue;
            }
            if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
                break; // the next top-level key ends the trigger block
            }
            block.append(line).append('\n');
        }
        return block.toString();
    }

    @Test
    public void debugWorkflowValidatesFeatureBranchesAndNotMasterPushes() throws Exception {
        String triggers = triggers(workflow("nusadesk-debug.yml"));

        assertTrue("feature-branch pushes must be validated",
                triggers.contains("branches-ignore: [\"master\"]"));
        assertFalse("a push to master must not start the debug workflow as well",
                triggers.contains("branches: [\"master\"]"));
        assertTrue("pull requests stay validated", triggers.contains("pull_request:"));
        assertTrue("the workflow stays manually dispatchable",
                triggers.contains("workflow_dispatch:"));
    }

    @Test
    public void releaseWorkflowStillReleasesFromMasterVersionChanges() throws Exception {
        String triggers = triggers(workflow("nusadesk-release.yml"));

        assertTrue("a release still triggers on a master push",
                triggers.contains("branches: [\"master\"]"));
        assertTrue("only a changed VERSION may start a release",
                triggers.contains("- VERSION"));
    }
}
