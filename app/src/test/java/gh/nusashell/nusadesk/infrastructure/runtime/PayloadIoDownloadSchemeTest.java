package gh.nusashell.nusadesk.infrastructure.runtime;

import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression coverage for the HTTPS-only invariant of
 * {@link PayloadIo#download}: a non-HTTPS catalog URL must be rejected at the
 * URI scheme gate, before any network connection is opened. The previous
 * implementation only checked that the connection was an
 * {@code HttpURLConnection}, which silently accepted {@code http://}.
 */
public class PayloadIoDownloadSchemeTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private static final PayloadIo.ProgressSink NO_PROGRESS = percent -> { };

    @Test
    public void rejectsHttpSchemeBeforeNetwork() throws Exception {
        Path partial = temp.newFile("partial").toPath();
        try {
            PayloadIo.download("http://127.0.0.1:1/payload", 1L, partial, NO_PROGRESS);
            fail("http:// must be rejected before any network access");
        } catch (RuntimeInstallationException expected) {
            assertTrue(
                    "rejection must cite the HTTPS requirement: " + expected.getMessage(),
                    expected.getMessage().contains("HTTPS"));
        }
    }

    @Test
    public void rejectsFileScheme() throws Exception {
        Path partial = temp.newFile("partial").toPath();
        try {
            PayloadIo.download("file:///etc/passwd", 1L, partial, NO_PROGRESS);
            fail("file:// must be rejected");
        } catch (RuntimeInstallationException expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        }
    }

    @Test
    public void rejectsMissingScheme() throws Exception {
        Path partial = temp.newFile("partial").toPath();
        try {
            PayloadIo.download("127.0.0.1/payload", 1L, partial, NO_PROGRESS);
            fail("a URL without a scheme must be rejected");
        } catch (RuntimeInstallationException expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        }
    }

    @Test
    public void httpsSchemeIsNotRejectedAtTheGate() throws Exception {
        // An HTTPS URL must pass the scheme gate and proceed to the connection
        // logic. Nothing listens on 127.0.0.1:1, so the call fails after the
        // gate — but never with the scheme-rejection message, proving the
        // HTTPS path is preserved.
        Path partial = temp.newFile("partial").toPath();
        try {
            PayloadIo.download("https://127.0.0.1:1/payload", 1L, partial, NO_PROGRESS);
            fail("https:// to a closed port must still fail");
        } catch (RuntimeInstallationException expected) {
            assertTrue(
                    "HTTPS must not be rejected at the scheme gate: "
                            + expected.getMessage(),
                    !expected.getMessage().contains("HTTPS"));
        } catch (Exception expected) {
            // Connection-level failure (e.g. ConnectException) is acceptable and
            // proves the HTTPS URL reached the network layer, not the gate.
        }
    }
}
