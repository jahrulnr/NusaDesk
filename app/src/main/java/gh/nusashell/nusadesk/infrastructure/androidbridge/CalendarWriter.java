package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Write port for the calendar surface.
 *
 * <p>Only already-validated {@link CalendarWriteRequest} values reach an
 * implementation, so an adapter never has to re-check guest input. Every
 * method returns a {@link CalendarWriteResult}: no exception escapes, and no
 * write is attempted without the write-calendar grant and a writable target
 * calendar.</p>
 */
public interface CalendarWriter {

    /** Create one event; never adds attendees or sends invitations. */
    CalendarWriteResult insert(CalendarWriteRequest request);

    /** Change only the fields the request carries. */
    CalendarWriteResult update(CalendarWriteRequest request);

    /** Remove one event. */
    CalendarWriteResult delete(CalendarWriteRequest request);
}
