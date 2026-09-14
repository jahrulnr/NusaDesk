package gh.nusashell.nusadesk.domain.session;

import gh.nusashell.nusadesk.domain.network.RuntimePort;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ReadinessFrameTest {
    private RuntimePort endpoint() {
        return new RuntimePort("127.0.0.1", 10994);
    }

    @Test
    public void healthyFrameIsReady() {
        ReadinessFrame frame = new ReadinessFrame(
                ReadinessFrame.SUPPORTED_SCHEMA,
                "ubuntu-base-arm64",
                "0.1.0",
                "sess-1",
                endpoint(),
                ReadinessHealth.HEALTHY,
                100L);

        assertTrue(frame.isReady());
        assertEquals("sess-1", frame.getSessionId());
        assertEquals("0.1.0", frame.getAppVersion());
    }

    @Test
    public void unhealthyFrameIsStructurallyValidButNotReady() {
        ReadinessFrame frame = new ReadinessFrame(
                ReadinessFrame.SUPPORTED_SCHEMA,
                "ubuntu-base-arm64",
                "0.1.0",
                "sess-1",
                endpoint(),
                ReadinessHealth.UNHEALTHY,
                100L);

        assertFalse(frame.isReady());
    }

    @Test
    public void rejectsUnsupportedSchemaVersion() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReadinessFrame(
                        99,
                        "ubuntu-base-arm64",
                        "0.1.0",
                        "sess-1",
                        endpoint(),
                        ReadinessHealth.HEALTHY,
                        100L));
    }

    @Test
    public void rejectsBlankAppId() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReadinessFrame(
                        ReadinessFrame.SUPPORTED_SCHEMA,
                        "  ",
                        "0.1.0",
                        "sess-1",
                        endpoint(),
                        ReadinessHealth.HEALTHY,
                        100L));
    }

    @Test
    public void rejectsBlankAppVersion() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReadinessFrame(
                        ReadinessFrame.SUPPORTED_SCHEMA,
                        "ubuntu-base-arm64",
                        "",
                        "sess-1",
                        endpoint(),
                        ReadinessHealth.HEALTHY,
                        100L));
    }

    @Test
    public void rejectsBlankSessionId() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReadinessFrame(
                        ReadinessFrame.SUPPORTED_SCHEMA,
                        "ubuntu-base-arm64",
                        "0.1.0",
                        null,
                        endpoint(),
                        ReadinessHealth.HEALTHY,
                        100L));
    }

    @Test
    public void rejectsNullEndpoint() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReadinessFrame(
                        ReadinessFrame.SUPPORTED_SCHEMA,
                        "ubuntu-base-arm64",
                        "0.1.0",
                        "sess-1",
                        null,
                        ReadinessHealth.HEALTHY,
                        100L));
    }

    @Test
    public void rejectsNullHealth() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReadinessFrame(
                        ReadinessFrame.SUPPORTED_SCHEMA,
                        "ubuntu-base-arm64",
                        "0.1.0",
                        "sess-1",
                        endpoint(),
                        null,
                        100L));
    }

    @Test
    public void rejectsNegativeEmitTime() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReadinessFrame(
                        ReadinessFrame.SUPPORTED_SCHEMA,
                        "ubuntu-base-arm64",
                        "0.1.0",
                        "sess-1",
                        endpoint(),
                        ReadinessHealth.HEALTHY,
                        -1L));
    }

    @Test
    public void endpointMustBeStrictLoopback() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReadinessFrame(
                        ReadinessFrame.SUPPORTED_SCHEMA,
                        "ubuntu-base-arm64",
                        "0.1.0",
                        "sess-1",
                        new RuntimePort("0.0.0.0", 10994),
                        ReadinessHealth.HEALTHY,
                        100L));
    }
}
