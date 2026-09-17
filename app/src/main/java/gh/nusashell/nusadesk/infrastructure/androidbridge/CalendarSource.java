package gh.nusashell.nusadesk.infrastructure.androidbridge;

/** Read-only calendar port; the adapter is Android-specific. */
public interface CalendarSource {

    /**
     * Read the events that overlap the bounded window. Never throws: every
     * failure is an explicit {@link CalendarEventSnapshot} state.
     */
    CalendarEventSnapshot read(CalendarQuery query);
}
