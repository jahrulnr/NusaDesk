package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Pure model behind the launcher grid: what the launcher lists, in what order,
 * and what a search query leaves standing.
 *
 * <p>The grid is one flat list, ordered the way a home screen reads: the
 * {@code Add app} action first, then the Linux surfaces this build ships, then
 * the user's registered apps. Registrations live in two stores — web apps and
 * terminal commands — and the grid lists each store in its own given order,
 * web apps first, rather than merging them into one user-app pile. There are
 * no category sections: with two built-in surfaces and a handful of user apps,
 * a group header per category would be chrome, not information.</p>
 *
 * <p>Search is a case-insensitive substring match over the label the user sees.
 * The caller supplies the already-localised label, which keeps the matching rule
 * testable without a resource table and keeps the view free of matching policy.
 * An empty query returns the input list unchanged, in launcher order.</p>
 */
public final class LauncherModel {

    private LauncherModel() {
    }

    /**
     * The launcher grid for the currently registered user apps.
     *
     * @param webApps     registered web-app definitions in launcher order;
     *                    {@code null} is treated as "no web apps registered yet"
     * @param commandApps registered terminal-command apps in launcher order;
     *                    {@code null} is treated as "none registered yet"
     */
    public static List<LauncherEntry> entries(
            List<WebAppDefinition> webApps, List<TerminalCommandApp> commandApps) {
        List<LauncherEntry> entries = new ArrayList<>();
        entries.add(LauncherEntry.addApp());
        for (DesktopApp app : DesktopApp.curated()) {
            entries.add(LauncherEntry.curated(app));
        }
        if (webApps != null) {
            for (WebAppDefinition definition : webApps) {
                if (definition != null) {
                    entries.add(LauncherEntry.webApp(definition));
                }
            }
        }
        if (commandApps != null) {
            for (TerminalCommandApp app : commandApps) {
                if (app != null) {
                    entries.add(LauncherEntry.terminalApp(app));
                }
            }
        }
        return Collections.unmodifiableList(entries);
    }

    /** True only when launcher app surfaces are safe to expose. */
    public static boolean shouldShowApps(boolean systemReady, boolean terminalComponentReady) {
        return systemReady && terminalComponentReady;
    }

    /** Trimmed, lower-cased query used for every comparison. */
    public static String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    /** True when {@code query} would actually filter the grid. */
    public static boolean isFiltering(String query) {
        return !normalize(query).isEmpty();
    }

    /**
     * Filters the grid in its existing order.
     *
     * @param entries the full grid
     * @param query   the raw search field text
     * @param labelOf localised label resolver, the only text a query matches
     */
    public static List<LauncherEntry> filter(
            List<LauncherEntry> entries, String query, Function<LauncherEntry, String> labelOf) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return entries;
        }
        List<LauncherEntry> matches = new ArrayList<>();
        for (LauncherEntry entry : entries) {
            String label = labelOf.apply(entry);
            if (label != null && label.toLowerCase(Locale.ROOT).contains(normalized)) {
                matches.add(entry);
            }
        }
        return Collections.unmodifiableList(matches);
    }
}
