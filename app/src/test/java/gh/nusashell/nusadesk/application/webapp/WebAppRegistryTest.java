package gh.nusashell.nusadesk.application.webapp;

import gh.nusashell.nusadesk.domain.webapp.GuestPortPolicy;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WebAppRegistryTest {
    private static final String ICON =
            "content://com.android.providers.media.documents/document/image%3A1000000123";

    private final InMemoryWebAppStore store = new InMemoryWebAppStore();
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final WebAppRegistry registry = new WebAppRegistry(store, clock::get);

    @Test
    public void addStoresAValidatedAppWithAGeneratedStableId() throws Exception {
        WebAppDefinition app = registry.add("  Notes  ", ICON, 8080);

        assertTrue("id must be a valid stable token", WebAppId.isValid(app.getId().value()));
        assertEquals("Notes", app.getDisplayName());
        assertEquals(ICON, app.getIconUri());
        assertEquals(8080, app.getGuestPort());
        assertEquals("http://127.0.0.1:8080/", app.getEndpointUrl());
        assertEquals(1_000L, app.getCreatedAtEpochMillis());
        assertEquals(1_000L, app.getUpdatedAtEpochMillis());
        assertEquals(0, app.getSortOrder());
        assertEquals(1, store.loadAll().size());
    }

    @Test
    public void idsAreGeneratedPerAppAndNotDerivedFromTheName() throws Exception {
        WebAppDefinition first = registry.add("Notes", null, 8080);
        WebAppDefinition second = registry.add("Notes", null, 8081);

        assertNotEquals(first.getId(), second.getId());
        assertTrue(WebAppId.isValid(first.getId().value()));
    }

    @Test
    public void addAssignsIncreasingLauncherOrder() throws Exception {
        registry.add("First", null, 8080);
        clock.set(2_000L);
        registry.add("Second", null, 8081);
        clock.set(3_000L);
        registry.add("Third", null, 8082);

        List<WebAppDefinition> listed = registry.list();
        assertEquals(3, listed.size());
        assertEquals("First", listed.get(0).getDisplayName());
        assertEquals("Second", listed.get(1).getDisplayName());
        assertEquals("Third", listed.get(2).getDisplayName());
        assertEquals(0, listed.get(0).getSortOrder());
        assertEquals(2, listed.get(2).getSortOrder());
    }

    @Test
    public void addPersistsAcrossRegistryInstances() throws Exception {
        registry.add("Notes", null, 8080);
        clock.set(9_000L);

        WebAppRegistry reopened = new WebAppRegistry(store, clock::get);
        List<WebAppDefinition> listed = reopened.list();
        assertEquals(1, listed.size());
        assertEquals("Notes", listed.get(0).getDisplayName());
        assertEquals(8080, listed.get(0).getGuestPort());
    }

    @Test
    public void addTreatsABlankIconAsNoIcon() throws Exception {
        assertNull(registry.add("Notes", null, 8080).getIconUri());
        assertNull(registry.add("Notes", "   ", 8081).getIconUri());
        assertFalse(registry.add("Notes", "", 8082).hasIcon());
    }

    @Test
    public void addRejectsInvalidFieldsWithAPreciseReason() {
        assertReason(WebAppRegistryException.Reason.INVALID_NAME,
                () -> registry.add("   ", null, 8080));
        assertReason(WebAppRegistryException.Reason.INVALID_ICON_URI,
                () -> registry.add("Notes", "file:///data/local/tmp/icon.png", 8080));
        assertReason(WebAppRegistryException.Reason.INVALID_PORT,
                () -> registry.add("Notes", null, 0));
        assertReason(WebAppRegistryException.Reason.INVALID_PORT,
                () -> registry.add("Notes", null, 65536));
        assertReason(WebAppRegistryException.Reason.PORT_RESERVED,
                () -> registry.add("Notes", null, GuestPortPolicy.RESERVED_GUEST_SSH_PORT));
        assertEquals("nothing may be persisted for a rejected add", 0, store.loadAll().size());
    }

    @Test
    public void addRejectsAPortAnotherAppAlreadyOwns() throws Exception {
        registry.add("Notes", null, 8080);
        assertReason(WebAppRegistryException.Reason.PORT_IN_USE,
                () -> registry.add("Journal", null, 8080));
        assertEquals(1, store.loadAll().size());
    }

    @Test
    public void deleteFreesThePortForAnotherApp() throws Exception {
        WebAppDefinition notes = registry.add("Notes", null, 8080);
        registry.delete(notes.getId().value());
        assertEquals(0, registry.list().size());

        WebAppDefinition journal = registry.add("Journal", null, 8080);
        assertEquals(8080, journal.getGuestPort());
    }

    @Test
    public void updateEditsEditableFieldsAndPreservesIdentityAndOrder() throws Exception {
        WebAppDefinition notes = registry.add("Notes", ICON, 8080);
        clock.set(5_000L);

        WebAppDefinition updated = registry.update(notes.getId().value(), "Journal", null, 9090);

        assertEquals(notes.getId(), updated.getId());
        assertEquals(notes.getCreatedAtEpochMillis(), updated.getCreatedAtEpochMillis());
        assertEquals(notes.getSortOrder(), updated.getSortOrder());
        assertEquals("Journal", updated.getDisplayName());
        assertNull(updated.getIconUri());
        assertEquals(9090, updated.getGuestPort());
        assertEquals(5_000L, updated.getUpdatedAtEpochMillis());
        assertEquals("http://127.0.0.1:9090/", updated.getEndpointUrl());
        assertEquals(1, registry.list().size());
    }

    @Test
    public void updateKeepsItsOwnPortWithoutReportingAConflict() throws Exception {
        WebAppDefinition notes = registry.add("Notes", null, 8080);
        clock.set(5_000L);
        assertEquals(8080, registry.update(notes.getId().value(), "Notes v2", null, 8080)
                .getGuestPort());
    }

    @Test
    public void updateRejectsAPortOwnedByADifferentApp() throws Exception {
        WebAppDefinition notes = registry.add("Notes", null, 8080);
        WebAppDefinition journal = registry.add("Journal", null, 9090);

        assertReason(WebAppRegistryException.Reason.PORT_IN_USE,
                () -> registry.update(journal.getId().value(), "Journal", null, 8080));
        assertEquals(8080, registry.list().get(0).getGuestPort());
        assertEquals(9090, registry.list().get(1).getGuestPort());
        assertEquals(notes.getId(), registry.list().get(0).getId());
    }

    @Test
    public void updateRejectsAnUnknownApp() {
        assertReason(WebAppRegistryException.Reason.UNKNOWN_APP,
                () -> registry.update("does-not-exist", "Notes", null, 8080));
    }

    @Test
    public void updateRejectsInvalidFields() throws Exception {
        WebAppDefinition notes = registry.add("Notes", null, 8080);
        String id = notes.getId().value();

        assertReason(WebAppRegistryException.Reason.INVALID_NAME,
                () -> registry.update(id, "  ", null, 8081));
        assertReason(WebAppRegistryException.Reason.INVALID_ICON_URI,
                () -> registry.update(id, "Notes", "https://example.com/icon.png", 8081));
        assertReason(WebAppRegistryException.Reason.INVALID_PORT,
                () -> registry.update(id, "Notes", null, -1));
        assertReason(WebAppRegistryException.Reason.PORT_RESERVED,
                () -> registry.update(id, "Notes", null, GuestPortPolicy.RESERVED_GUEST_SSH_PORT));
    }

    @Test
    public void updateRejectsAMalformedId() {
        assertReason(WebAppRegistryException.Reason.UNKNOWN_APP,
                () -> registry.update("not a valid id", "Notes", null, 8080));
    }

    @Test
    public void deleteKeepsTheRemainingLauncherOrder() throws Exception {
        WebAppDefinition first = registry.add("First", null, 8080);
        registry.add("Second", null, 8081);
        WebAppDefinition third = registry.add("Third", null, 8082);

        registry.delete(first.getId().value());

        List<WebAppDefinition> listed = registry.list();
        assertEquals(2, listed.size());
        assertEquals("Second", listed.get(0).getDisplayName());
        assertEquals("Third", listed.get(1).getDisplayName());
        assertEquals(third.getId(), listed.get(1).getId());
    }

    @Test
    public void deleteIsIdempotentForUnknownOrMalformedIds() throws Exception {
        registry.add("Notes", null, 8080);
        registry.delete("does-not-exist");
        registry.delete("not a valid id");
        registry.delete(null);
        assertEquals(1, registry.list().size());
    }

    @Test
    public void listIsUnmodifiableAndEmptyWhenNothingIsRegistered() throws Exception {
        assertTrue(registry.list().isEmpty());
        registry.add("Notes", null, 8080);
        WebAppDefinition journal = registry.add("Journal", null, 8081);
        try {
            registry.list().add(journal);
            fail();
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void saveFailureIsReportedAsAStorageFailure() {
        store.failOnSave = true;
        assertReason(WebAppRegistryException.Reason.STORAGE_FAILURE,
                () -> registry.add("Notes", null, 8080));
    }

    @Test
    public void rejectsANullStore() {
        try {
            new WebAppRegistry(null, clock::get);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void defaultsToTheSystemClockWhenNoneIsSupplied() throws Exception {
        WebAppRegistry wallClock = new WebAppRegistry(store);
        WebAppDefinition app = wallClock.add("Notes", null, 8080);
        assertTrue(app.getCreatedAtEpochMillis() > 0L);
    }

    private static void assertReason(
            WebAppRegistryException.Reason expected, ThrowingCall call) {
        try {
            call.run();
            fail("expected " + expected);
        } catch (WebAppRegistryException actual) {
            assertEquals(expected, actual.getReason());
            assertTrue("message must explain the failure",
                    actual.getMessage() != null && !actual.getMessage().trim().isEmpty());
        } catch (Exception unexpected) {
            fail("expected " + expected + " but threw " + unexpected);
        }
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    /** Deterministic in-memory stand-in for the persisted store. */
    private static final class InMemoryWebAppStore implements WebAppStore {
        private final Map<WebAppId, WebAppDefinition> records = new LinkedHashMap<>();
        private boolean failOnSave;

        @Override
        public List<WebAppDefinition> loadAll() {
            return new ArrayList<>(records.values());
        }

        @Override
        public void save(WebAppDefinition definition) {
            if (failOnSave) {
                throw new IllegalStateException("could not persist web app");
            }
            records.put(definition.getId(), definition);
        }

        @Override
        public void delete(WebAppId webAppId) {
            records.remove(webAppId);
        }
    }
}
