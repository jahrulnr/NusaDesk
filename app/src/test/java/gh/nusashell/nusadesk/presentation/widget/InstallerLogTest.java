package gh.nusashell.nusadesk.presentation.widget;

import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure tests for the installer terminal log: component-aware phase-to-line
 * mapping, download percent parsing and stripping, deduplication by
 * (component, state), bounded history, new-attempt detection for the rootfs
 * only, and the active-install gating that keeps an initial load from logging
 * a spurious ready line. No Android dependencies — plain JUnit.
 */
public class InstallerLogTest {

    private static RuntimeSnapshot snapshot(RuntimeState state, String detail) {
        return new RuntimeSnapshot("ubuntu-base-arm64", state, detail, 0, 1_000L);
    }

    private static RuntimeSnapshot addonSnapshot(RuntimeState state, String detail) {
        return new RuntimeSnapshot("guest-ssh", state, detail, 0, 1_000L);
    }

    private static InstallPhaseSnapshot rootfs(RuntimeState state, String detail) {
        return InstallPhaseSnapshot.rootfs(snapshot(state, detail));
    }

    private static InstallPhaseSnapshot addon(RuntimeState state, String detail) {
        return InstallPhaseSnapshot.addon(addonSnapshot(state, detail));
    }

    // ---- percent parsing ----

    @Test
    public void parsesPercentFromDownloadDetail() {
        assertEquals(45, InstallerLog.parsePercent("Downloading Ubuntu Base · 45%"));
        assertEquals(0, InstallerLog.parsePercent("Downloading Ubuntu Base · 0%"));
        assertEquals(100, InstallerLog.parsePercent("Downloading Ubuntu Base · 100%"));
    }

    @Test
    public void returnsNegativeOneWhenNoPercent() {
        assertEquals(-1, InstallerLog.parsePercent("Downloading Ubuntu Base"));
        assertEquals(-1, InstallerLog.parsePercent(null));
        assertEquals(-1, InstallerLog.parsePercent(""));
    }

    // ---- download percent stripping ----

    @Test
    public void stripsDownloadPercentSuffix() {
        assertEquals("Downloading Ubuntu Base",
                InstallerLog.stripDownloadPercent("Downloading Ubuntu Base · 45%"));
        assertEquals("Downloading Ubuntu Base",
                InstallerLog.stripDownloadPercent("Downloading Ubuntu Base · 100%"));
    }

    @Test
    public void leavesDetailWithoutPercentUnchanged() {
        assertEquals("Downloading Ubuntu Base",
                InstallerLog.stripDownloadPercent("Downloading Ubuntu Base"));
        assertEquals("", InstallerLog.stripDownloadPercent(null));
    }

    // ---- rootfs line mapping ----

    @Test
    public void downloadLineStripsPercent() {
        String line = InstallerLog.lineFor(rootfs(
                RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 45%"));
        assertEquals("download  Downloading Ubuntu Base", line);
    }

    @Test
    public void verifyLineUsesDetail() {
        String line = InstallerLog.lineFor(rootfs(
                RuntimeState.VERIFYING, "Verifying the curated payload"));
        assertEquals("verify  Verifying the curated payload", line);
    }

    @Test
    public void extractLineUsesDetail() {
        String line = InstallerLog.lineFor(rootfs(
                RuntimeState.EXTRACTING, "Preparing the private runtime files"));
        assertEquals("extract  Preparing the private runtime files", line);
    }

    @Test
    public void readyLineUsesDetail() {
        String line = InstallerLog.lineFor(rootfs(
                RuntimeState.READY, "Ubuntu Base 24.04.5 ARM64 1.0.0 installed"));
        assertEquals("ready  Ubuntu Base 24.04.5 ARM64 1.0.0 installed", line);
    }

    @Test
    public void failedLineUsesDetail() {
        String line = InstallerLog.lineFor(rootfs(
                RuntimeState.FAILED, "payload digest does not match the catalog"));
        assertEquals("error  payload digest does not match the catalog", line);
    }

    @Test
    public void notInstalledProducesNoLine() {
        assertNull(InstallerLog.lineFor(rootfs(RuntimeState.NOT_INSTALLED, "")));
    }

    @Test
    public void postInstallStatesProduceNoLine() {
        for (RuntimeState state : new RuntimeState[]{
                RuntimeState.STARTING, RuntimeState.RUNNING,
                RuntimeState.STOPPING, RuntimeState.STOPPED,
                RuntimeState.RECOVERING}) {
            assertNull(state.name(), InstallerLog.lineFor(rootfs(state, "detail")));
        }
    }

    @Test
    public void prepareLineWrapsContext() {
        assertEquals("prepare  A pinned Ubuntu Base system",
                InstallerLog.prepareLine("A pinned Ubuntu Base system"));
    }

    // ---- add-on line mapping ----

    @Test
    public void addonDownloadLineUsesSshTagAndStripsPercent() {
        String line = InstallerLog.lineFor(addon(
                RuntimeState.DOWNLOADING, "Downloading OpenSSH 1/7 · 45%"));
        assertEquals("ssh  Downloading OpenSSH 1/7", line);
    }

    @Test
    public void addonVerifyLineUsesSshTag() {
        String line = InstallerLog.lineFor(addon(
                RuntimeState.VERIFYING, "Verifying OpenSSH 1/7"));
        assertEquals("ssh  Verifying OpenSSH 1/7", line);
    }

    @Test
    public void addonExtractLineUsesSshTag() {
        String line = InstallerLog.lineFor(addon(
                RuntimeState.EXTRACTING, "Unpacking OpenSSH 1/7"));
        assertEquals("ssh  Unpacking OpenSSH 1/7", line);
    }

    @Test
    public void addonReadyLineUsesSshTag() {
        String line = InstallerLog.lineFor(addon(
                RuntimeState.READY, "OpenSSH 9.6 1.0.0 installed"));
        assertEquals("ssh  OpenSSH 9.6 1.0.0 installed", line);
    }

    @Test
    public void addonFailedLineUsesSshErrorTag() {
        String line = InstallerLog.lineFor(addon(
                RuntimeState.FAILED, "network timeout"));
        assertEquals("ssh error  network timeout", line);
    }

    @Test
    public void addonNotInstalledProducesNoLine() {
        assertNull(InstallerLog.lineFor(addon(RuntimeState.NOT_INSTALLED, "")));
    }

    // ---- deduplication by (component, state) ----

    @Test
    public void appendsOneLinePerRootfsStateTransition() {
        InstallerLog log = new InstallerLog();

        assertTrue(log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 5%")));
        assertFalse(log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 45%")));
        assertFalse(log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 90%")));
        assertTrue(log.append(rootfs(RuntimeState.VERIFYING, "Verifying the curated payload")));
        assertTrue(log.append(rootfs(RuntimeState.EXTRACTING, "Preparing the private runtime files")));
        assertTrue(log.append(rootfs(RuntimeState.READY, "Ubuntu Base 1.0.0 installed")));

        List<String> lines = log.lines();
        assertEquals(4, lines.size());
        assertEquals("download  Downloading Ubuntu Base", lines.get(0));
        assertEquals("verify  Verifying the curated payload", lines.get(1));
        assertEquals("extract  Preparing the private runtime files", lines.get(2));
        assertEquals("ready  Ubuntu Base 1.0.0 installed", lines.get(3));
    }

    @Test
    public void appendsFailedLineAfterDownload() {
        InstallerLog log = new InstallerLog();

        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 45%"));
        assertTrue(log.append(rootfs(RuntimeState.FAILED, "network timeout")));

        List<String> lines = log.lines();
        assertEquals(2, lines.size());
        assertEquals("download  Downloading Ubuntu Base", lines.get(0));
        assertEquals("error  network timeout", lines.get(1));
    }

    @Test
    public void rootfsAndAddonDownloadAreDistinctPhases() {
        InstallerLog log = new InstallerLog();

        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 5%"));
        log.append(rootfs(RuntimeState.VERIFYING, "Verifying the curated payload"));
        log.append(rootfs(RuntimeState.READY, "Ubuntu Base 1.0.0 installed"));
        // The add-on download is a different (component, state) than the rootfs
        // download, so it appends even though both are DOWNLOADING.
        assertTrue(log.append(addon(RuntimeState.DOWNLOADING, "Downloading OpenSSH 1/7 · 10%")));

        List<String> lines = log.lines();
        assertEquals(4, log.size());
        assertEquals("download  Downloading Ubuntu Base", lines.get(0));
        assertEquals("verify  Verifying the curated payload", lines.get(1));
        assertEquals("ready  Ubuntu Base 1.0.0 installed", lines.get(2));
        assertEquals("ssh  Downloading OpenSSH 1/7", lines.get(3));
    }

    @Test
    public void addonArtifactsProduceOneLinePerPhaseTransition() {
        InstallerLog log = new InstallerLog();
        // Seed the log as active via a rootfs install.
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base"));
        log.append(rootfs(RuntimeState.READY, "Ubuntu Base 1.0.0 installed"));

        // Two artifacts: each cycles DOWNLOADING -> VERIFYING -> EXTRACTING.
        for (int i = 1; i <= 2; i++) {
            log.append(addon(RuntimeState.DOWNLOADING, "Downloading OpenSSH " + i + "/7 · 5%"));
            log.append(addon(RuntimeState.VERIFYING, "Verifying OpenSSH " + i + "/7"));
            log.append(addon(RuntimeState.EXTRACTING, "Unpacking OpenSSH " + i + "/7"));
        }

        List<String> lines = log.lines();
        // rootfs download + ready + 2 artifacts * 3 phases = 8 lines.
        assertEquals(8, lines.size());
        assertEquals("download  Downloading Ubuntu Base", lines.get(0));
        assertEquals("ready  Ubuntu Base 1.0.0 installed", lines.get(1));
        assertEquals("ssh  Downloading OpenSSH 1/7", lines.get(2));
        assertEquals("ssh  Verifying OpenSSH 1/7", lines.get(3));
        assertEquals("ssh  Unpacking OpenSSH 1/7", lines.get(4));
        assertEquals("ssh  Downloading OpenSSH 2/7", lines.get(5));
        assertEquals("ssh  Verifying OpenSSH 2/7", lines.get(6));
        assertEquals("ssh  Unpacking OpenSSH 2/7", lines.get(7));
    }

    // ---- new attempt detection (rootfs only) ----

    @Test
    public void firstRootfsDownloadIsNewAttempt() {
        assertTrue(InstallerLog.isNewAttempt(null, rootfs(RuntimeState.DOWNLOADING, "d")));
        assertTrue(InstallerLog.isNewAttempt(
                rootfs(RuntimeState.NOT_INSTALLED, ""), rootfs(RuntimeState.DOWNLOADING, "d")));
        assertTrue(InstallerLog.isNewAttempt(
                rootfs(RuntimeState.FAILED, "e"), rootfs(RuntimeState.DOWNLOADING, "d")));
    }

    @Test
    public void consecutiveRootfsDownloadIsNotNewAttempt() {
        assertFalse(InstallerLog.isNewAttempt(
                rootfs(RuntimeState.DOWNLOADING, "d"), rootfs(RuntimeState.DOWNLOADING, "d")));
        assertFalse(InstallerLog.isNewAttempt(
                rootfs(RuntimeState.VERIFYING, "v"), rootfs(RuntimeState.DOWNLOADING, "d")));
    }

    @Test
    public void addonDownloadIsNeverNewAttempt() {
        for (InstallPhaseSnapshot previous : new InstallPhaseSnapshot[]{
                null, rootfs(RuntimeState.NOT_INSTALLED, ""),
                rootfs(RuntimeState.FAILED, "e"), rootfs(RuntimeState.READY, "r"),
                addon(RuntimeState.FAILED, "e")}) {
            assertFalse(previous == null ? "null" : previous.getComponent().name(),
                    InstallerLog.isNewAttempt(previous, addon(RuntimeState.DOWNLOADING, "d")));
        }
    }

    @Test
    public void nonDownloadIsNeverNewAttempt() {
        for (RuntimeState current : new RuntimeState[]{
                RuntimeState.VERIFYING, RuntimeState.READY, RuntimeState.FAILED}) {
            assertFalse(current.name(),
                    InstallerLog.isNewAttempt(null, rootfs(current, "d")));
        }
    }

    // ---- active-install gating ----

    @Test
    public void rootfsNotInstalledClearsAndDeactivates() {
        InstallerLog log = new InstallerLog();
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base"));
        assertEquals(1, log.size());

        log.append(rootfs(RuntimeState.NOT_INSTALLED, ""));
        assertEquals(0, log.size());
        // After deactivation, a stray READY snapshot does not append.
        assertFalse(log.append(rootfs(RuntimeState.READY, "installed")));
        assertEquals(0, log.size());
    }

    @Test
    public void initialLoadWithRootfsReadyDoesNotLogSpuriousReadyLine() {
        InstallerLog log = new InstallerLog();
        // No install has run this session; the rootfs is already active.
        assertFalse(log.append(rootfs(RuntimeState.READY, "Ubuntu Base 1.0.0 installed")));
        assertEquals(0, log.size());
    }

    @Test
    public void addonDownloadActivatesLogWhenRootfsAlreadyReady() {
        InstallerLog log = new InstallerLog();
        // The view initializes the prepare line before the first append, so the
        // soft-start path has a session context to activate against.
        log.prependPrepare("A pinned Ubuntu Base system");
        // Rootfs already active, no install this session: the add-on
        // auto-continues. The log activates on the first add-on download.
        log.append(rootfs(RuntimeState.READY, "installed"));
        assertEquals(1, log.size());
        assertTrue(log.append(addon(RuntimeState.DOWNLOADING, "Downloading OpenSSH 1/7 · 5%")));
        assertEquals(2, log.size());
        assertEquals("prepare  A pinned Ubuntu Base system", log.lines().get(0));
        assertEquals("ssh  Downloading OpenSSH 1/7", log.lines().get(1));
    }

    // ---- bounded history ----

    @Test
    public void historyIsBoundedToMaxLines() {
        InstallerLog log = new InstallerLog();
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base"));
        // Alternate between VERIFYING and EXTRACTING to force a new line each
        // append (dedup is by state, so toggling states produces one line each).
        for (int i = 0; i < InstallerLog.MAX_LINES + 20; i++) {
            RuntimeState state = (i % 2 == 0) ? RuntimeState.VERIFYING : RuntimeState.EXTRACTING;
            log.append(rootfs(state, "line " + i));
        }
        assertEquals(InstallerLog.MAX_LINES, log.size());
    }

    // ---- clear and prepend ----

    @Test
    public void clearResetsLog() {
        InstallerLog log = new InstallerLog();
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base"));
        assertEquals(1, log.size());

        log.clear();
        assertEquals(0, log.size());
        // After clear, the same state appends again (dedup state was reset).
        assertTrue(log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base")));
    }

    @Test
    public void prependPrepareAddsLineAtTop() {
        InstallerLog log = new InstallerLog();
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base"));
        log.prependPrepare("A pinned Ubuntu Base system");

        List<String> lines = log.lines();
        assertEquals(2, lines.size());
        assertEquals("prepare  A pinned Ubuntu Base system", lines.get(0));
        assertEquals("download  Downloading Ubuntu Base", lines.get(1));
    }

    @Test
    public void joinedReturnsLinesSeparatedByNewlines() {
        InstallerLog log = new InstallerLog();
        log.prependPrepare("context");
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base"));

        assertEquals("prepare  context\ndownload  Downloading Ubuntu Base", log.joined());
    }

    // ---- full combined lifecycle simulation ----

    @Test
    public void fullCombinedLifecycleProducesOrderedRootfsThenAddonLines() {
        InstallerLog log = new InstallerLog();
        log.prependPrepare("A pinned Ubuntu Base 24.04.5 ARM64 system is downloaded over HTTPS.");
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 5%"));
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 50%"));
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 100%"));
        log.append(rootfs(RuntimeState.VERIFYING, "Verifying the curated payload"));
        log.append(rootfs(RuntimeState.EXTRACTING, "Preparing the private runtime files"));
        log.append(rootfs(RuntimeState.READY, "Ubuntu Base 24.04.5 ARM64 1.0.0 installed"));
        log.append(addon(RuntimeState.DOWNLOADING, "Downloading OpenSSH 1/7 · 5%"));
        log.append(addon(RuntimeState.VERIFYING, "Verifying OpenSSH 1/7"));
        log.append(addon(RuntimeState.EXTRACTING, "Unpacking OpenSSH 1/7"));
        log.append(addon(RuntimeState.READY, "OpenSSH 9.6 1.0.0 installed"));

        List<String> lines = log.lines();
        assertEquals(9, lines.size());
        assertTrue(lines.get(0).startsWith("prepare  "));
        assertTrue(lines.get(1).startsWith("download  "));
        assertTrue(lines.get(2).startsWith("verify  "));
        assertTrue(lines.get(3).startsWith("extract  "));
        assertTrue(lines.get(4).startsWith("ready  "));
        assertTrue(lines.get(5).startsWith("ssh  Downloading OpenSSH 1/7"));
        assertTrue(lines.get(6).startsWith("ssh  Verifying OpenSSH 1/7"));
        assertTrue(lines.get(7).startsWith("ssh  Unpacking OpenSSH 1/7"));
        assertTrue(lines.get(8).startsWith("ssh  OpenSSH 9.6"));
        // Download percent is in the bar, not the log.
        assertFalse(lines.get(1).contains("50%"));
        assertFalse(lines.get(1).contains("100%"));
    }

    @Test
    public void retryClearsHistoryAndStartsFresh() {
        InstallerLog log = new InstallerLog();
        log.prependPrepare("context");
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 45%"));
        log.append(rootfs(RuntimeState.FAILED, "network timeout"));
        assertEquals(3, log.size());

        // Retry: clear and start fresh.
        log.clear();
        log.prependPrepare("context");
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base · 5%"));

        List<String> lines = log.lines();
        assertEquals(2, log.size());
        assertEquals("prepare  context", lines.get(0));
        assertEquals("download  Downloading Ubuntu Base", lines.get(1));
    }

    @Test
    public void addonRetryPreservesRootfsHistory() {
        InstallerLog log = new InstallerLog();
        log.prependPrepare("context");
        log.append(rootfs(RuntimeState.DOWNLOADING, "Downloading Ubuntu Base"));
        log.append(rootfs(RuntimeState.READY, "Ubuntu Base 1.0.0 installed"));
        log.append(addon(RuntimeState.DOWNLOADING, "Downloading OpenSSH 1/7 · 5%"));
        log.append(addon(RuntimeState.FAILED, "network timeout"));
        assertEquals(5, log.size());

        // Retry only the add-on: the rootfs history stays, the add-on attempt
        // appends after the failure (no clear, no rootfs re-download).
        assertTrue(log.append(addon(RuntimeState.DOWNLOADING, "Downloading OpenSSH 1/7 · 5%")));

        List<String> lines = log.lines();
        assertEquals(6, log.size());
        assertEquals("download  Downloading Ubuntu Base", lines.get(1));
        assertEquals("ready  Ubuntu Base 1.0.0 installed", lines.get(2));
        assertEquals("ssh error  network timeout", lines.get(4));
        assertEquals("ssh  Downloading OpenSSH 1/7", lines.get(5));
    }
}
