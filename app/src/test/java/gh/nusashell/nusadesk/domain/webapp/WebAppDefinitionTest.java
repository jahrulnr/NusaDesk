package gh.nusashell.nusadesk.domain.webapp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WebAppDefinitionTest {
    private static final String ICON =
            "content://com.android.providers.media.documents/document/image%3A1000000123";

    private static WebAppDefinition definition(
            String id, String name, String iconUri, int port,
            long createdAt, long updatedAt, int sortOrder) {
        return new WebAppDefinition(
                WebAppId.of(id), name, iconUri, port, createdAt, updatedAt, sortOrder);
    }

    private static WebAppDefinition valid() {
        return definition("app-1", "Notes", ICON, 8080, 100L, 100L, 0);
    }

    @Test
    public void buildsTheExactLoopbackEndpointForTheFixedPort() {
        assertEquals("http://127.0.0.1:8080/", valid().getEndpointUrl());
        assertEquals("http://127.0.0.1:1/",
                definition("a", "A", null, 1, 0L, 0L, 0).getEndpointUrl());
        assertEquals("http://127.0.0.1:65535/",
                definition("a", "A", null, 65535, 0L, 0L, 0).getEndpointUrl());
    }

    @Test
    public void trimsTheDisplayName() {
        assertEquals("Notes", definition("a", "  Notes  ", null, 8080, 0L, 0L, 0).getDisplayName());
    }

    @Test
    public void rejectsBlankDisplayName() {
        assertRejected(() -> definition("a", null, null, 8080, 0L, 0L, 0));
        assertRejected(() -> definition("a", "", null, 8080, 0L, 0L, 0));
        assertRejected(() -> definition("a", "   ", null, 8080, 0L, 0L, 0));
    }

    @Test
    public void rejectsDisplayNameLongerThanTheMaximum() {
        String tooLong = repeated('n', WebAppDefinition.MAX_DISPLAY_NAME_LENGTH + 1);
        assertRejected(() -> definition("a", tooLong, null, 8080, 0L, 0L, 0));
    }

    @Test
    public void rejectsNullId() {
        try {
            new WebAppDefinition(null, "Notes", null, 8080, 0L, 0L, 0);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void treatsBlankIconAsAbsent() {
        assertNull(definition("a", "A", null, 8080, 0L, 0L, 0).getIconUri());
        assertNull(definition("a", "A", "", 8080, 0L, 0L, 0).getIconUri());
        assertNull(definition("a", "A", "   ", 8080, 0L, 0L, 0).getIconUri());
        assertFalse(definition("a", "A", null, 8080, 0L, 0L, 0).hasIcon());
    }

    @Test
    public void trimsAndKeepsAValidatedContentUriToken() {
        WebAppDefinition app = definition("a", "A", "  " + ICON + "  ", 8080, 0L, 0L, 0);
        assertEquals(ICON, app.getIconUri());
        assertTrue(app.hasIcon());
    }

    @Test
    public void rejectsIconTokensThatAreNotContentUris() {
        assertRejected(() -> definition("a", "A", "file:///data/local/tmp/icon.png", 8080, 0L, 0L, 0));
        assertRejected(() -> definition("a", "A", "http://example.com/icon.png", 8080, 0L, 0L, 0));
        assertRejected(() -> definition("a", "A", "https://example.com/icon.png", 8080, 0L, 0L, 0));
        assertRejected(() -> definition("a", "A", "/data/local/tmp/icon.png", 8080, 0L, 0L, 0));
        assertRejected(() -> definition("a", "A", "content://", 8080, 0L, 0L, 0));
        assertRejected(() -> definition("a", "A", "ht tp://broken", 8080, 0L, 0L, 0));
    }

    @Test
    public void rejectsIconTokensLongerThanTheMaximum() {
        String tooLong = "content://provider/" + repeated('x', WebAppDefinition.MAX_ICON_URI_LENGTH);
        assertRejected(() -> definition("a", "A", tooLong, 8080, 0L, 0L, 0));
    }

    @Test
    public void rejectsReservedAndOutOfRangePorts() {
        assertRejected(() -> definition("a", "A", null, 0, 0L, 0L, 0));
        assertRejected(() -> definition("a", "A", null, -1, 0L, 0L, 0));
        assertRejected(() -> definition("a", "A", null, 65536, 0L, 0L, 0));
        assertRejected(() -> definition("a", "A", null,
                GuestPortPolicy.RESERVED_GUEST_SSH_PORT, 0L, 0L, 0));
    }

    @Test
    public void rejectsNegativeTimestampsAndInvertedUpdateTime() {
        assertRejected(() -> definition("a", "A", null, 8080, -1L, 0L, 0));
        assertRejected(() -> definition("a", "A", null, 8080, 0L, -1L, 0));
        assertRejected(() -> definition("a", "A", null, 8080, 100L, 99L, 0));
    }

    @Test
    public void rejectsNegativeSortOrder() {
        assertRejected(() -> definition("a", "A", null, 8080, 0L, 0L, -1));
    }

    @Test
    public void withDetailsKeepsIdentityOrderAndCreationTime() {
        WebAppDefinition original = valid();
        WebAppDefinition updated = original.withDetails("Journal", null, 9090, 500L);

        assertEquals(original.getId(), updated.getId());
        assertEquals(original.getCreatedAtEpochMillis(), updated.getCreatedAtEpochMillis());
        assertEquals(original.getSortOrder(), updated.getSortOrder());
        assertEquals("Journal", updated.getDisplayName());
        assertNull(updated.getIconUri());
        assertEquals(9090, updated.getGuestPort());
        assertEquals(500L, updated.getUpdatedAtEpochMillis());
        assertEquals("http://127.0.0.1:9090/", updated.getEndpointUrl());
    }

    @Test
    public void withDetailsStillValidatesTheNewPortAndName() {
        assertRejected(() -> valid().withDetails("Journal", null,
                GuestPortPolicy.RESERVED_GUEST_SSH_PORT, 500L));
        assertRejected(() -> valid().withDetails("  ", null, 9090, 500L));
    }

    @Test
    public void equalityIsByValue() {
        assertEquals(valid(), valid());
        assertEquals(valid().hashCode(), valid().hashCode());
        assertNotEquals(valid(), definition("app-2", "Notes", ICON, 8080, 100L, 100L, 0));
        assertNotEquals(valid(), definition("app-1", "Notes", ICON, 8081, 100L, 100L, 0));
    }

    private static String repeated(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static void assertRejected(Runnable construction) {
        try {
            construction.run();
            fail("expected construction to be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
