package gh.nusashell.nusadesk.domain.webapp;
import org.junit.Test;
import static org.junit.Assert.*;
public class WebAppStartupPolicyTest {
    @Test public void retriesOnlyCombinedAppsDuringStartupBeforeHttpReadiness() {
        assertTrue(WebAppStartupPolicy.shouldRetry(true, false, 0));
        assertTrue(WebAppStartupPolicy.shouldRetry(true, false, 29_999));
        assertFalse(WebAppStartupPolicy.shouldRetry(true, false, 30_000));
        assertFalse(WebAppStartupPolicy.shouldRetry(true, true, 1));
        assertFalse(WebAppStartupPolicy.shouldRetry(false, false, 1));
    }
}
