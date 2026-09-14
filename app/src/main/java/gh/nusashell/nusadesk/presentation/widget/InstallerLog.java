package gh.nusashell.nusadesk.presentation.widget;

import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps combined rootfs + guest-SSH add-on install snapshots to an append-only,
 * bounded, deduplicated terminal log for the single setup pipeline. Pure Java
 * with no Android imports so the mapping, deduplication, and progress parsing
 * are testable in plain JUnit.
 *
 * <p>Each install phase produces one log line. Rootfs phases use the phase
 * word ({@code download}, {@code verify}, {@code extract}, {@code ready},
 * {@code error}); add-on phases use the {@code ssh} component tag so the two
 * components never blur together in the same terminal. Consecutive snapshots
 * in the same component and phase do not produce duplicate lines; the download
 * percent is parsed from the detail and surfaced through the progress bar
 * instead of spamming the log. History is bounded to {@link #MAX_LINES} lines
 * so the viewport never grows unbounded.</p>
 *
 * <p>The log is gated by an active-install flag. It activates on a new rootfs
 * attempt (the first rootfs download after idle or failure) or on an add-on
 * download that starts without a rootfs install this session (rootfs already
 * active, add-on auto-continued). Before activation it only carries the
 * prepare-context line, so an initial load with the rootfs already ready never
 * appends a spurious {@code ready} line for an install that did not happen.</p>
 */
final class InstallerLog {

    /** Maximum retained log lines; older lines are dropped from the top. */
    static final int MAX_LINES = 80;

    private static final Pattern PERCENT = Pattern.compile("(\\d+)\\s*%");
    private static final Pattern DOWNLOAD_PERCENT_SUFFIX =
            Pattern.compile("\\s*·\\s*\\d+\\s*%\\s*$");

    private final List<String> lines = new ArrayList<>();
    private InstallPhaseSnapshot lastPhase = null;
    private InstallPhaseSnapshot lastAppended = null;
    private boolean active = false;
    private String prepareContext = null;

    /**
     * Parses an integer download percent from snapshot detail, or {@code -1}
     * when the detail carries no percent. Both installers publish download
     * progress as {@code "Downloading <name> · <percent>%"}.
     */
    static int parsePercent(String detail) {
        if (detail == null) {
            return -1;
        }
        Matcher matcher = PERCENT.matcher(detail);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /**
     * Strips a trailing {@code "· N%"} download-progress suffix so the log line
     * reads as the stable phase label, not a percent that belongs in the bar.
     */
    static String stripDownloadPercent(String detail) {
        if (detail == null) {
            return "";
        }
        return DOWNLOAD_PERCENT_SUFFIX.matcher(detail).replaceFirst("").trim();
    }

    /**
     * Derives the terminal log line for a phase. Returns {@code null} for
     * states that produce no progress line (NOT_INSTALLED and post-install
     * runtime states that are not part of the setup lifecycle).
     */
    static String lineFor(InstallPhaseSnapshot phase) {
        RuntimeState state = phase.getState();
        String detail = phase.getDetail();
        if (phase.getComponent() == InstallPhaseSnapshot.Component.ADDON) {
            switch (state) {
                case DOWNLOADING:
                    return "ssh  " + stripDownloadPercent(detail);
                case VERIFYING:
                case EXTRACTING:
                case READY:
                    return "ssh  " + nullToEmpty(detail);
                case FAILED:
                    return "ssh error  " + nullToEmpty(detail);
                default:
                    return null;
            }
        }
        switch (state) {
            case DOWNLOADING:
                return "download  " + stripDownloadPercent(detail);
            case VERIFYING:
                return "verify  " + nullToEmpty(detail);
            case EXTRACTING:
                return "extract  " + nullToEmpty(detail);
            case READY:
                return "ready  " + nullToEmpty(detail);
            case FAILED:
                return "error  " + nullToEmpty(detail);
            default:
                return null;
        }
    }

    /** Builds the fixed prepare-context line shown at the top of a fresh log. */
    static String prepareLine(String context) {
        return "prepare  " + nullToEmpty(context);
    }

    /**
     * Returns {@code true} when transitioning to {@code current} starts a
     * fresh rootfs install attempt — the first rootfs download after the
     * initial idle state or after a rootfs failure (retry). The view uses this
     * to clear and reinitialize the log so a retry does not carry the failed
     * attempt's lines. An add-on download is never a new attempt: it continues
     * the same setup, preserving the rootfs history and any prior add-on
     * attempt, so retrying only the add-on never redownloads the rootfs.
     */
    static boolean isNewAttempt(InstallPhaseSnapshot previous, InstallPhaseSnapshot current) {
        if (current.getState() != RuntimeState.DOWNLOADING) {
            return false;
        }
        if (current.getComponent() != InstallPhaseSnapshot.Component.ROOTFS) {
            return false;
        }
        if (previous == null) {
            return true;
        }
        RuntimeState prev = previous.getState();
        return prev == RuntimeState.NOT_INSTALLED || prev == RuntimeState.FAILED;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /**
     * Feeds one phase snapshot into the log, applying the active-install
     * gating, new-attempt clearing, and component-aware deduplication.
     *
     * @return {@code true} if a new line was appended.
     */
    boolean append(InstallPhaseSnapshot phase) {
        if (phase.getComponent() == InstallPhaseSnapshot.Component.ROOTFS
                && phase.getState() == RuntimeState.NOT_INSTALLED) {
            clear();
            lastPhase = phase;
            active = false;
            return false;
        }
        if (isNewAttempt(lastPhase, phase)) {
            clear();
            reapplyPrepare();
            lastPhase = phase;
            active = true;
            return appendLine(phase);
        }
        if (!active
                && phase.getComponent() == InstallPhaseSnapshot.Component.ADDON
                && phase.getState() == RuntimeState.DOWNLOADING) {
            // Add-on auto-continued without a rootfs install this session.
            if (prepareContext == null) {
                return false;
            }
            lastPhase = phase;
            active = true;
            return appendLine(phase);
        }
        lastPhase = phase;
        if (!active) {
            return false;
        }
        return appendLine(phase);
    }

    /**
     * Appends a line for the phase if the (component, state) changed. Returns
     * {@code true} if a new line was appended.
     */
    private boolean appendLine(InstallPhaseSnapshot phase) {
        if (samePhase(lastAppended, phase)) {
            return false;
        }
        String line = lineFor(phase);
        lastAppended = phase;
        if (line == null) {
            return false;
        }
        lines.add(line);
        if (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
        return true;
    }

    private static boolean samePhase(InstallPhaseSnapshot a, InstallPhaseSnapshot b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getComponent() == b.getComponent() && a.getState() == b.getState();
    }

    /** Clears all lines and resets the dedup + active state, keeping the prepare context. */
    void clear() {
        lines.clear();
        lastPhase = null;
        lastAppended = null;
        active = false;
    }

    /** Re-applies the stored prepare-context line after a clear, if any. */
    private void reapplyPrepare() {
        if (prepareContext != null) {
            lines.add(prepareLine(prepareContext));
        }
    }

    /** Stores and inserts the prepare-context line at the top of a fresh log. */
    void prependPrepare(String context) {
        this.prepareContext = context;
        lines.add(0, prepareLine(context));
        if (lines.size() > MAX_LINES) {
            lines.remove(lines.size() - 1);
        }
    }

    /** Unmodifiable view of the current log lines. */
    List<String> lines() {
        return Collections.unmodifiableList(lines);
    }

    /** All lines joined by newlines for rendering in a single TextView. */
    String joined() {
        return String.join("\n", lines);
    }

    int size() {
        return lines.size();
    }

    boolean isEmpty() {
        return lines.isEmpty();
    }
}
