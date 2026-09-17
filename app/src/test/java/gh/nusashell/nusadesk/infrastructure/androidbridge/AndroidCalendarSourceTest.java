package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.provider.CalendarContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bounded calendar read behavior on the JVM: the grant is resolved before any
 * provider query, the fixed window is the only guest-visible input, the
 * projection stays minimal, the row cap is enforced in Java, and every
 * provider failure maps to a typed state instead of a partial answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidCalendarSourceTest {

    private static final long NOW = 1_760_000_000_000L;

    private FakeQueryProvider provider;

    @Before
    public void setUp() {
        provider = new FakeQueryProvider();
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider);
    }

    private Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private AndroidCalendarSource source(CapabilityPermission permission) {
        return new AndroidCalendarSource(context(), androidPermission -> permission);
    }

    private static Object[] row(long eventId, String title, long begin, long end) {
        return new Object[] {eventId, title, begin, end, 0, 3L, "Pribadi",
                "Asia/Jakarta", "Ruang 1"};
    }

    @Test
    public void aMissingGrantStopsBeforeAnyProviderQuery() {
        CalendarEventSnapshot required = source(CapabilityPermission.REQUIRED)
                .read(CalendarQuery.upcoming(NOW));
        assertEquals(MessagingReadState.PERMISSION_REQUIRED, required.getState());

        CalendarEventSnapshot denied = source(CapabilityPermission.DENIED)
                .read(CalendarQuery.upcoming(NOW));
        assertEquals(MessagingReadState.PERMISSION_DENIED, denied.getState());

        assertEquals("no provider query without a grant", 0, provider.getQueryCount());
    }

    @Test
    public void aGrantedReadMapsRowsInsideTheFixedWindow() {
        provider.setRows(
                row(11L, "Rapat", NOW + 1_000L, NOW + 2_000L),
                row(12L, "Cuti", NOW + 3_000L, NOW + 4_000L));

        CalendarEventSnapshot snapshot = source(CapabilityPermission.GRANTED)
                .read(CalendarQuery.upcoming(NOW));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(2, snapshot.getEntries().size());
        CalendarEventSnapshot.EventEntry first = snapshot.getEntries().get(0);
        assertEquals(11L, first.getEventId());
        assertEquals("Rapat", first.getTitle());
        assertEquals("Pribadi", first.getCalendarName());
        assertEquals("Asia/Jakarta", first.getTimezone());
        assertEquals("Ruang 1", first.getLocation());

        // The Instances window travels in the URI path, which is the only shape
        // the platform provider accepts for that table.
        List<String> segments = provider.getLastUri().getPathSegments();
        assertEquals("instances", segments.get(0));
        assertEquals("when", segments.get(1));
        assertEquals(4, segments.size());
        assertEquals(String.valueOf(NOW), segments.get(2));
        assertEquals(String.valueOf(NOW + CalendarQuery.DEFAULT_WINDOW_MILLIS),
                segments.get(3));
        org.junit.Assert.assertNull("the window is not a selection argument",
                provider.getLastSelection());
        String sortOrder = provider.getLastSortOrder();
        assertTrue("events are ordered by start",
                sortOrder.toUpperCase(Locale.ROOT).contains("BEGIN ASC"));
        assertFalse("a SQL LIMIT token is not portable across providers",
                sortOrder.toUpperCase(Locale.ROOT).contains("LIMIT"));
    }

    @Test
    public void theProjectionCarriesNoPersonalColumns() {
        provider.setRows(row(11L, "Rapat", NOW + 1_000L, NOW + 2_000L));

        source(CapabilityPermission.GRANTED).read(CalendarQuery.upcoming(NOW));

        String projection = String.join(",", provider.getLastProjection());
        assertTrue(projection.contains("title"));
        assertTrue(projection.contains("calendar_displayName"));
        for (String forbidden : new String[] {"description", "attendee", "organizer",
                "rrule", "reminders", "customAppPackage"}) {
            assertFalse("the projection must not read " + forbidden,
                    projection.contains(forbidden));
        }
    }

    @Test
    public void unusableRowsAreSkippedNotFabricated() {
        provider.setRows(
                row(0L, "No id", NOW + 1_000L, NOW + 2_000L),
                row(12L, "   ", NOW + 1_000L, NOW + 2_000L),
                row(13L, "Valid", NOW + 1_000L, NOW + 2_000L));

        CalendarEventSnapshot snapshot = source(CapabilityPermission.GRANTED)
                .read(CalendarQuery.upcoming(NOW));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(1, snapshot.getEntries().size());
        assertEquals(13L, snapshot.getEntries().get(0).getEventId());
    }

    @Test
    public void theRowCapIsEnforcedEvenWhenTheProviderIgnoresIt() {
        Object[][] rows = new Object[5][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = row(20L + i, "Event " + i, NOW + i, NOW + i + 1L);
        }
        provider.setRows(rows);

        CalendarEventSnapshot snapshot = source(CapabilityPermission.GRANTED)
                .read(CalendarQuery.of(NOW, NOW + 60_000L, 2));

        assertEquals(2, snapshot.getEntries().size());
        assertTrue("dropped rows must be reported", snapshot.isTruncated());
    }

    @Test
    public void providerFailuresBecomeTypedStates() {
        provider.setReturnNullCursor();
        assertEquals(MessagingReadState.UNAVAILABLE,
                source(CapabilityPermission.GRANTED).read(CalendarQuery.upcoming(NOW)).getState());

        provider.setFailure(new IllegalStateException("provider detail must not cross"));
        assertEquals(MessagingReadState.ERROR,
                source(CapabilityPermission.GRANTED).read(CalendarQuery.upcoming(NOW)).getState());

        provider.setFailure(new SecurityException("revoked between check and read"));
        assertEquals(MessagingReadState.PERMISSION_DENIED,
                source(CapabilityPermission.GRANTED).read(CalendarQuery.upcoming(NOW)).getState());
    }

    @Test
    public void aNullQueryIsRejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> source(CapabilityPermission.GRANTED).read(null));
    }
}
