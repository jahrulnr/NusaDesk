package gh.nusashell.nusadesk.application.terminal;

import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandAppId;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TerminalCommandRegistryTest {
    private static final String ICON =
            "content://com.android.providers.media.documents/document/image%3A1000000123";
    private static final String COMMAND = "docker exec -it codex bash";

    private final InMemoryTerminalCommandStore store = new InMemoryTerminalCommandStore();
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final TerminalCommandRegistry registry =
            new TerminalCommandRegistry(store, clock::get);

    @Test
    public void addStoresAValidatedAppWithAGeneratedStableId() throws Exception {
        TerminalCommandApp app = registry.add("  Codex  ", ICON, " " + COMMAND + " ");

        assertTrue("id must be a valid stable token",
                TerminalCommandAppId.isValid(app.getId().value()));
        assertEquals("Codex", app.getDisplayName());
        assertEquals(ICON, app.getIconUri());
        assertEquals(COMMAND, app.getCommand().value());
        assertEquals(1_000L, app.getCreatedAtEpochMillis());
        assertEquals(1_000L, app.getUpdatedAtEpochMillis());
        assertEquals(0, app.getSortOrder());
        assertEquals(1, store.loadAll().size());
    }

    @Test
    public void idsAreGeneratedPerAppAndNotDerivedFromTheName() throws Exception {
        TerminalCommandApp first = registry.add("Codex", null, COMMAND);
        TerminalCommandApp second = registry.add("Codex", null, COMMAND);

        assertNotEquals(first.getId(), second.getId());
        assertTrue(TerminalCommandAppId.isValid(first.getId().value()));
    }

    @Test
    public void addAssignsIncreasingLauncherOrder() throws Exception {
        registry.add("First", null, "one");
        clock.set(2_000L);
        registry.add("Second", null, "two");
        clock.set(3_000L);
        registry.add("Third", null, "three");

        List<TerminalCommandApp> listed = registry.list();
        assertEquals(3, listed.size());
        assertEquals("First", listed.get(0).getDisplayName());
        assertEquals("Second", listed.get(1).getDisplayName());
        assertEquals("Third", listed.get(2).getDisplayName());
        assertEquals(0, listed.get(0).getSortOrder());
        assertEquals(2, listed.get(2).getSortOrder());
    }

    @Test
    public void addPersistsAcrossRegistryInstances() throws Exception {
        registry.add("Codex", null, COMMAND);
        clock.set(9_000L);

        TerminalCommandRegistry reopened = new TerminalCommandRegistry(store, clock::get);
        List<TerminalCommandApp> listed = reopened.list();
        assertEquals(1, listed.size());
        assertEquals("Codex", listed.get(0).getDisplayName());
        assertEquals(COMMAND, listed.get(0).getCommand().value());
    }

    @Test
    public void addTreatsABlankIconAsNoIcon() throws Exception {
        assertNull(registry.add("Codex", null, COMMAND).getIconUri());
        assertNull(registry.add("Codex", "   ", COMMAND).getIconUri());
        assertNull(registry.add("Codex", "", COMMAND).getIconUri());
    }

    @Test
    public void addStoresTheCommandVerbatim() throws Exception {
        String complex = "sh -c 'echo \"a b\" && exit' | tee /tmp/x";
        assertEquals(complex, registry.add("Codex", null, complex).getCommand().value());
    }

    @Test
    public void addRejectsInvalidFieldsWithAPreciseReason() {
        assertReason(TerminalCommandRegistryException.Reason.INVALID_NAME,
                () -> registry.add("   ", null, COMMAND));
        assertReason(TerminalCommandRegistryException.Reason.INVALID_ICON_URI,
                () -> registry.add("Codex", "file:///data/local/tmp/icon.png", COMMAND));
        assertReason(TerminalCommandRegistryException.Reason.INVALID_COMMAND,
                () -> registry.add("Codex", null, null));
        assertReason(TerminalCommandRegistryException.Reason.INVALID_COMMAND,
                () -> registry.add("Codex", null, "   "));
        assertReason(TerminalCommandRegistryException.Reason.INVALID_COMMAND,
                () -> registry.add("Codex", null, "echo one\necho two"));
        assertReason(TerminalCommandRegistryException.Reason.INVALID_COMMAND,
                () -> registry.add("Codex", null, repeated('a', 513)));
        assertEquals("nothing may be persisted for a rejected add",
                0, store.loadAll().size());
    }

    @Test
    public void updateEditsEditableFieldsAndPreservesIdentityAndOrder() throws Exception {
        TerminalCommandApp codex = registry.add("Codex", ICON, COMMAND);
        clock.set(5_000L);

        TerminalCommandApp updated =
                registry.update(codex.getId().value(), "Monitor", null, "htop");

        assertEquals(codex.getId(), updated.getId());
        assertEquals(codex.getCreatedAtEpochMillis(), updated.getCreatedAtEpochMillis());
        assertEquals(codex.getSortOrder(), updated.getSortOrder());
        assertEquals("Monitor", updated.getDisplayName());
        assertNull(updated.getIconUri());
        assertEquals(TerminalCommand.of("htop"), updated.getCommand());
        assertEquals(5_000L, updated.getUpdatedAtEpochMillis());
        assertEquals(1, registry.list().size());
    }

    @Test
    public void updateRejectsAnUnknownApp() {
        assertReason(TerminalCommandRegistryException.Reason.UNKNOWN_APP,
                () -> registry.update("does-not-exist", "Codex", null, COMMAND));
    }

    @Test
    public void updateRejectsAMalformedId() {
        assertReason(TerminalCommandRegistryException.Reason.UNKNOWN_APP,
                () -> registry.update("not a valid id", "Codex", null, COMMAND));
    }

    @Test
    public void updateRejectsInvalidFields() throws Exception {
        TerminalCommandApp codex = registry.add("Codex", null, COMMAND);
        String id = codex.getId().value();

        assertReason(TerminalCommandRegistryException.Reason.INVALID_NAME,
                () -> registry.update(id, "  ", null, COMMAND));
        assertReason(TerminalCommandRegistryException.Reason.INVALID_ICON_URI,
                () -> registry.update(id, "Codex", "https://example.com/icon.png", COMMAND));
        assertReason(TerminalCommandRegistryException.Reason.INVALID_COMMAND,
                () -> registry.update(id, "Codex", null, ""));
    }

    @Test
    public void deleteKeepsTheRemainingLauncherOrder() throws Exception {
        TerminalCommandApp first = registry.add("First", null, "one");
        registry.add("Second", null, "two");
        TerminalCommandApp third = registry.add("Third", null, "three");

        registry.delete(first.getId().value());

        List<TerminalCommandApp> listed = registry.list();
        assertEquals(2, listed.size());
        assertEquals("Second", listed.get(0).getDisplayName());
        assertEquals("Third", listed.get(1).getDisplayName());
        assertEquals(third.getId(), listed.get(1).getId());
    }

    @Test
    public void deleteIsIdempotentForUnknownOrMalformedIds() throws Exception {
        registry.add("Codex", null, COMMAND);
        registry.delete("does-not-exist");
        registry.delete("not a valid id");
        registry.delete(null);
        assertEquals(1, registry.list().size());
    }

    @Test
    public void listIsUnmodifiableAndEmptyWhenNothingIsRegistered() throws Exception {
        assertTrue(registry.list().isEmpty());
        registry.add("Codex", null, COMMAND);
        TerminalCommandApp monitor = registry.add("Monitor", null, "htop");
        try {
            registry.list().add(monitor);
            fail();
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void listReadsTheStoreEveryTimeAndBreaksOrderTiesDeterministically() {
        // Written straight into the store, bypassing add(), to prove list()
        // never caches and orders by sortOrder, then createdAt, then id.
        TerminalCommandApp later = new TerminalCommandApp(
                TerminalCommandAppId.of("b-app"), "Later", null,
                TerminalCommand.of("two"), 200L, 200L, 5);
        TerminalCommandApp earlier = new TerminalCommandApp(
                TerminalCommandAppId.of("a-app"), "Earlier", null,
                TerminalCommand.of("one"), 100L, 100L, 5);
        TerminalCommandApp sameTime = new TerminalCommandApp(
                TerminalCommandAppId.of("c-app"), "SameTime", null,
                TerminalCommand.of("three"), 100L, 100L, 5);
        store.save(later);
        store.save(sameTime);
        store.save(earlier);

        List<TerminalCommandApp> listed = registry.list();
        assertEquals(3, listed.size());
        assertEquals("Earlier", listed.get(0).getDisplayName());
        assertEquals("SameTime", listed.get(1).getDisplayName());
        assertEquals("Later", listed.get(2).getDisplayName());
    }

    @Test
    public void saveFailureIsReportedAsAStorageFailure() throws Exception {
        TerminalCommandApp codex = registry.add("Codex", null, COMMAND);
        store.failOnSave = true;
        assertReason(TerminalCommandRegistryException.Reason.STORAGE_FAILURE,
                () -> registry.add("Monitor", null, "htop"));
        assertReason(TerminalCommandRegistryException.Reason.STORAGE_FAILURE,
                () -> registry.update(codex.getId().value(), "Codex", null, COMMAND));
    }

    @Test
    public void rejectsANullStoreOrClock() {
        try {
            new TerminalCommandRegistry(null, clock::get);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new TerminalCommandRegistry(store, null);
            fail();
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void defaultsToTheSystemClockWhenNoneIsSupplied() throws Exception {
        TerminalCommandRegistry wallClock = new TerminalCommandRegistry(store);
        TerminalCommandApp app = wallClock.add("Codex", null, COMMAND);
        assertTrue(app.getCreatedAtEpochMillis() > 0L);
    }

    private static String repeated(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static void assertReason(
            TerminalCommandRegistryException.Reason expected, ThrowingCall call) {
        try {
            call.run();
            fail("expected " + expected);
        } catch (TerminalCommandRegistryException actual) {
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
    private static final class InMemoryTerminalCommandStore implements TerminalCommandStore {
        private final Map<TerminalCommandAppId, TerminalCommandApp> records =
                new LinkedHashMap<>();
        private boolean failOnSave;

        @Override
        public List<TerminalCommandApp> loadAll() {
            return new ArrayList<>(records.values());
        }

        @Override
        public void save(TerminalCommandApp app) {
            if (failOnSave) {
                throw new IllegalStateException("could not persist terminal-command app");
            }
            records.put(app.getId(), app);
        }

        @Override
        public void delete(TerminalCommandAppId appId) {
            records.remove(appId);
        }
    }
}
