package gh.nusashell.nusadesk.domain.webapp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GuestPortPolicyTest {

    @Test
    public void acceptsTheFullPortRange() {
        assertTrue(GuestPortPolicy.isAllowed(1));
        assertTrue(GuestPortPolicy.isAllowed(65535));
        assertTrue(GuestPortPolicy.isAllowed(8080));
    }

    @Test
    public void rejectsPortsOutsideTheTcpRange() {
        assertFalse(GuestPortPolicy.isAllowed(0));
        assertFalse(GuestPortPolicy.isAllowed(-1));
        assertFalse(GuestPortPolicy.isAllowed(65536));
        assertFalse(GuestPortPolicy.isInRange(0));
        assertTrue(GuestPortPolicy.isInRange(65535));
    }

    @Test
    public void reservesTheGuestSshPort() {
        assertEquals(22022, GuestPortPolicy.RESERVED_GUEST_SSH_PORT);
        assertTrue(GuestPortPolicy.isReserved(GuestPortPolicy.RESERVED_GUEST_SSH_PORT));
        assertFalse(GuestPortPolicy.isReserved(22021));
        assertFalse(GuestPortPolicy.isReserved(22023));
        assertFalse("the reserved port must never be allowed for a web app",
                GuestPortPolicy.isAllowed(GuestPortPolicy.RESERVED_GUEST_SSH_PORT));
    }

    @Test
    public void validateNamesTheReservedPortExplicitly() {
        try {
            GuestPortPolicy.validate(GuestPortPolicy.RESERVED_GUEST_SSH_PORT);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains(Integer.toString(GuestPortPolicy.RESERVED_GUEST_SSH_PORT)));
        }
    }

    @Test
    public void validateNamesTheRangeWhenOutOfBounds() {
        try {
            GuestPortPolicy.validate(0);
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("1"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("65535"));
        }
    }

    @Test
    public void validateReturnsTheAcceptedPort() {
        assertEquals(8080, GuestPortPolicy.validate(8080));
    }
}
