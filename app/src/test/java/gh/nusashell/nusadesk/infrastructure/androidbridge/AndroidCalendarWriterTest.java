package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guarded calendar write behavior on the JVM: the write grant is resolved
 * first, only a writable calendar is ever targeted, updates and deletes resolve
 * the event before touching it, every failure stays a typed code, and the audit
 * log line never carries event content.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidCalendarWriterTest {

    private static final long NOW = 1_760_000_000_000L;
    private static final long HOUR = 60L * 60L * 1_000L;
    private static final int CONTRIBUTOR = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
    private static final int READ_ONLY = CalendarContract.Calendars.CAL_ACCESS_READ;

    private FakeCalendarProvider provider;

    @Before
    public void setUp() {
        provider = new FakeCalendarProvider();
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider);
    }

    private Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private AndroidCalendarWriter writer(CapabilityPermission permission) {
        return new AndroidCalendarWriter(context(), androidPermission -> permission);
    }

    private static CalendarWriteRequest insertRequest(String title) {
        return CalendarWriteRequest.insert(java.util.Map.of(
                "title", title,
                "begin_ms", NOW + HOUR,
                "end_ms", NOW + 2 * HOUR), NOW).getRequest();
    }

    private static CalendarWriteRequest updateRequest(Object... pairs) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.put((String) pairs[i], pairs[i + 1]);
        }
        return CalendarWriteRequest.update(params, NOW).getRequest();
    }

    private static CalendarWriteRequest deleteRequest(long eventId) {
        return CalendarWriteRequest.delete(java.util.Map.of("event_id", eventId)).getRequest();
    }

    @Test
    public void theWriteGrantIsResolvedBeforeAnyProviderCall() {
        CalendarWriteResult required = writer(CapabilityPermission.REQUIRED)
                .insert(insertRequest("Rapat"));
        assertEquals(CalendarWriteResult.ERROR_PERMISSION_REQUIRED, required.getErrorCode());

        CalendarWriteResult denied = writer(CapabilityPermission.DENIED)
                .delete(deleteRequest(500L));
        assertEquals(CalendarWriteResult.ERROR_PERMISSION_DENIED, denied.getErrorCode());

        assertEquals("no insert without a grant", 0, provider.insertCount);
        assertEquals("no delete without a grant", 0, provider.deleteCount);
        assertEquals("no provider query without a grant", 0, provider.queryCount);
    }

    @Test
    public void insertTargetsThePrimaryWritableCalendarWithBoundedValues() {
        provider.calendar(1L, CONTRIBUTOR);
        provider.calendar(2L, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        provider.primaryCalendarId = 2L;
        provider.timezones.put(2L, "Asia/Jakarta");

        CalendarWriteResult result = writer(CapabilityPermission.GRANTED)
                .insert(insertRequest("Rapat"));

        assertEquals(true, result.isOk());
        assertEquals(500L, result.getEventId());
        assertEquals(1, provider.insertCount);
        ContentValues written = provider.lastInsert;
        assertEquals(Long.valueOf(2L),
                written.getAsLong(CalendarContract.Events.CALENDAR_ID));
        assertEquals("Rapat", written.getAsString(CalendarContract.Events.TITLE));
        assertEquals(Long.valueOf(NOW + HOUR),
                written.getAsLong(CalendarContract.Events.DTSTART));
        assertEquals(Long.valueOf(NOW + 2 * HOUR),
                written.getAsLong(CalendarContract.Events.DTEND));
        assertEquals(Integer.valueOf(0), written.getAsInteger(CalendarContract.Events.ALL_DAY));
        assertEquals("Asia/Jakarta",
                written.getAsString(CalendarContract.Events.EVENT_TIMEZONE));
        assertEquals("only the bounded columns are written", 6, written.size());
    }

    @Test
    public void insertFallsBackToAnyWritableCalendarWhenNoneIsPrimary() {
        provider.calendar(5L, CONTRIBUTOR);
        provider.primaryCalendarId = 0L;

        CalendarWriteResult result = writer(CapabilityPermission.GRANTED)
                .insert(insertRequest("Rapat"));

        assertEquals(true, result.isOk());
        assertEquals(Long.valueOf(5L),
                provider.lastInsert.getAsLong(CalendarContract.Events.CALENDAR_ID));
    }

    @Test
    public void insertRefusesAReadOnlyTargetCalendar() {
        provider.calendar(2L, READ_ONLY);
        provider.primaryCalendarId = 2L;

        CalendarWriteResult result = writer(CapabilityPermission.GRANTED)
                .insert(insertRequest("Rapat"));

        assertEquals(CalendarWriteResult.ERROR_READ_ONLY, result.getErrorCode());
        assertEquals("a read-only calendar is never written", 0, provider.insertCount);
    }

    @Test
    public void insertWithoutAWritableCalendarIsUnavailable() {
        CalendarWriteResult result = writer(CapabilityPermission.GRANTED)
                .insert(insertRequest("Rapat"));

        assertEquals(CalendarWriteResult.ERROR_UNAVAILABLE, result.getErrorCode());
        assertEquals(0, provider.insertCount);
    }

    @Test
    public void updateWritesOnlyTheProvidedFields() {
        provider.calendar(2L, CONTRIBUTOR);
        provider.event(500L, 2L, false);

        CalendarWriteResult result = writer(CapabilityPermission.GRANTED)
                .update(updateRequest("event_id", 500L, "title", "Rapat baru"));

        assertEquals(true, result.isOk());
        assertEquals(500L, result.getEventId());
        ContentValues written = provider.lastUpdate;
        assertEquals("Rapat baru", written.getAsString(CalendarContract.Events.TITLE));
        assertEquals("untouched fields are not rewritten", 1, written.size());
    }

    @Test
    public void updateOnAMissingEventIsNotFound() {
        provider.calendar(2L, CONTRIBUTOR);

        CalendarWriteResult result = writer(CapabilityPermission.GRANTED)
                .update(updateRequest("event_id", 999L, "title", "Rapat"));

        assertEquals(CalendarWriteResult.ERROR_NOT_FOUND, result.getErrorCode());
        assertEquals(0, provider.updateCount);
    }

    @Test
    public void aReadOnlyEventCalendarRefusesUpdateAndDelete() {
        provider.calendar(2L, READ_ONLY);
        provider.event(500L, 2L, false);

        assertEquals(CalendarWriteResult.ERROR_READ_ONLY,
                writer(CapabilityPermission.GRANTED)
                        .update(updateRequest("event_id", 500L, "title", "X")).getErrorCode());
        assertEquals(CalendarWriteResult.ERROR_READ_ONLY,
                writer(CapabilityPermission.GRANTED).delete(deleteRequest(500L)).getErrorCode());
        assertEquals(0, provider.updateCount);
        assertEquals(0, provider.deleteCount);
    }

    @Test
    public void deleteReportsRowCountsAsTypedResults() {
        provider.calendar(2L, CONTRIBUTOR);
        provider.event(500L, 2L, false);

        CalendarWriteResult deleted = writer(CapabilityPermission.GRANTED)
                .delete(deleteRequest(500L));
        assertEquals(true, deleted.isOk());
        assertEquals(500L, deleted.getEventId());
        assertEquals(1, provider.deleteCount);

        provider.deleteRows = 0;
        CalendarWriteResult gone = writer(CapabilityPermission.GRANTED)
                .delete(deleteRequest(500L));
        assertEquals(CalendarWriteResult.ERROR_NOT_FOUND, gone.getErrorCode());
    }

    @Test
    public void aFailedInsertUriStaysATypedCode() {
        provider.calendar(2L, CONTRIBUTOR);
        provider.primaryCalendarId = 2L;
        provider.insertReturnsNull = true;

        assertEquals(CalendarWriteResult.ERROR_FAILED,
                writer(CapabilityPermission.GRANTED).insert(insertRequest("Rapat"))
                        .getErrorCode());
    }

    @Test
    public void providerFailuresBecomeTypedCodes() {
        provider.calendar(2L, CONTRIBUTOR);
        provider.primaryCalendarId = 2L;
        provider.failure = new IllegalStateException("provider detail must not cross");

        assertEquals("a broken calendar lookup cannot invent a target",
                CalendarWriteResult.ERROR_UNAVAILABLE,
                writer(CapabilityPermission.GRANTED).insert(insertRequest("Rapat"))
                        .getErrorCode());

        provider.failure = new SecurityException("revoked between check and write");
        assertEquals("a provider-side refusal stays a permission outcome",
                CalendarWriteResult.ERROR_PERMISSION_DENIED,
                writer(CapabilityPermission.GRANTED).insert(insertRequest("Rapat"))
                        .getErrorCode());

        provider.failure = null;
        provider.insertFailure = new IllegalStateException("insert refused");
        assertEquals(CalendarWriteResult.ERROR_FAILED,
                writer(CapabilityPermission.GRANTED).insert(insertRequest("Rapat"))
                        .getErrorCode());
    }

    @Test
    public void aRequestOfTheWrongOperationIsRejected() {
        provider.calendar(2L, CONTRIBUTOR);

        CalendarWriteResult result = writer(CapabilityPermission.GRANTED)
                .insert(updateRequest("event_id", 500L, "title", "Rapat"));

        assertEquals(CalendarWriteRequest.ERROR_INVALID_ARGUMENT, result.getErrorCode());
        assertEquals(0, provider.insertCount);
        assertEquals(0, provider.queryCount);
    }

    @Test
    public void insertWithoutAReadableCalendarTimezoneStillCarriesOne() {
        provider.calendar(2L, CONTRIBUTOR);
        provider.primaryCalendarId = 2L;
        // No timezone entry for calendar 2: the write must not depend on it.
        CalendarWriteResult result = writer(CapabilityPermission.GRANTED)
                .insert(insertRequest("Rapat"));

        assertEquals(true, result.isOk());
        String timezone = provider.lastInsert.getAsString(
                CalendarContract.Events.EVENT_TIMEZONE);
        assertTrue("an event without a timezone is rejected by the provider",
                timezone != null && !timezone.isEmpty());
    }

    @Test
    public void anAllDayInsertCarriesUtcAndTheDayFlag() {
        provider.calendar(2L, CONTRIBUTOR);
        provider.primaryCalendarId = 2L;
        long day = 24L * 60L * 60L * 1_000L;
        long midnight = (NOW / day) * day;

        CalendarWriteRequest request = CalendarWriteRequest.insert(java.util.Map.of(
                "title", "Cuti",
                "begin_ms", midnight,
                "end_ms", midnight + day,
                "all_day", true), NOW).getRequest();

        assertEquals(true, writer(CapabilityPermission.GRANTED).insert(request).isOk());
        assertEquals(Integer.valueOf(1),
                provider.lastInsert.getAsInteger(CalendarContract.Events.ALL_DAY));
        assertEquals("UTC", provider.lastInsert.getAsString(
                CalendarContract.Events.EVENT_TIMEZONE));
    }

    @Test
    public void updateToAllDayRewritesTheTimezoneAsWell() {
        provider.calendar(2L, CONTRIBUTOR);
        provider.event(500L, 2L, false);
        long day = 24L * 60L * 60L * 1_000L;
        long midnight = (NOW / day) * day;

        CalendarWriteResult result = writer(CapabilityPermission.GRANTED)
                .update(updateRequest("event_id", 500L, "begin_ms", midnight,
                        "end_ms", midnight + day, "all_day", true));

        assertEquals(true, result.isOk());
        assertEquals(Integer.valueOf(1),
                provider.lastUpdate.getAsInteger(CalendarContract.Events.ALL_DAY));
        assertEquals("UTC", provider.lastUpdate.getAsString(
                CalendarContract.Events.EVENT_TIMEZONE));
    }

    @Test
    public void theAuditLogLineCarriesNoEventContent() {
        provider.calendar(2L, CONTRIBUTOR);
        provider.primaryCalendarId = 2L;

        writer(CapabilityPermission.GRANTED).insert(insertRequest("Rahasia"));

        List<ShadowLog.LogItem> lines = ShadowLog.getLogsForTag("AndroidCalendarWriter");
        assertFalse("a successful write logs one bounded line", lines.isEmpty());
        for (ShadowLog.LogItem line : lines) {
            assertFalse("the title never reaches the log", line.msg.contains("Rahasia"));
            assertTrue("the log line names the operation and ids",
                    line.msg.contains("op=insert") && line.msg.contains("event="));
        }
    }

    /**
     * A write-capable provider double: calendars with an access level, events
     * with their owning calendar, and a recorded insert/update/delete outcome.
     */
    private static final class FakeCalendarProvider extends ContentProvider {
        private final Map<Long, Integer> accessLevels = new LinkedHashMap<>();
        private final Map<Long, String> timezones = new LinkedHashMap<>();
        private final Map<Long, long[]> events = new LinkedHashMap<>();
        long primaryCalendarId;
        long nextInsertedId = 500L;
        int updateRows = 1;
        int deleteRows = 1;
        boolean insertReturnsNull;
        boolean returnNullCursor;
        RuntimeException failure;
        RuntimeException insertFailure;
        ContentValues lastInsert;
        ContentValues lastUpdate;
        int insertCount;
        int updateCount;
        int deleteCount;
        int queryCount;

        void calendar(long calendarId, int accessLevel) {
            accessLevels.put(calendarId, accessLevel);
        }

        void event(long eventId, long calendarId, boolean allDay) {
            events.put(eventId, new long[] {calendarId, allDay ? 1L : 0L});
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                            String[] selectionArgs, String sortOrder) {
            queryCount++;
            if (failure != null) {
                throw failure;
            }
            if (returnNullCursor) {
                return null;
            }
            String kind = uri.getPathSegments().get(0);
            List<Object[]> rows = new ArrayList<>();
            if (kind.equals(CalendarContract.Calendars.CONTENT_URI.getPathSegments().get(0))) {
                if (uri.getPathSegments().size() == 1) {
                    for (Long calendarId : sortedCalendarIds()) {
                        rows.add(new Object[] {calendarId,
                                calendarId == primaryCalendarId ? 1 : 0});
                    }
                    if (sortOrder != null && sortOrder.contains("IS_PRIMARY DESC")) {
                        rows.sort((left, right) ->
                                Long.compare((Long) right[1], (Long) left[1]));
                    }
                } else {
                    long calendarId = Long.parseLong(uri.getPathSegments().get(1));
                    if (hasColumn(projection, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                            && accessLevels.containsKey(calendarId)) {
                        rows.add(new Object[] {accessLevels.get(calendarId)});
                    } else if (hasColumn(projection,
                            CalendarContract.Calendars.CALENDAR_TIME_ZONE)
                            && timezones.containsKey(calendarId)) {
                        rows.add(new Object[] {timezones.get(calendarId)});
                    }
                }
            } else if (uri.getPathSegments().size() >= 2) {
                long eventId = Long.parseLong(uri.getPathSegments().get(1));
                long[] event = events.get(eventId);
                if (event != null) {
                    rows.add(new Object[] {event[0], event[1]});
                }
            }
            MatrixCursor cursor = new MatrixCursor(projection == null ? new String[0] : projection);
            for (Object[] row : rows) {
                cursor.addRow(row);
            }
            return cursor;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            insertCount++;
            lastInsert = values == null ? null : new ContentValues(values);
            if (failure != null) {
                throw failure;
            }
            if (insertFailure != null) {
                throw insertFailure;
            }
            if (insertReturnsNull) {
                return null;
            }
            long eventId = nextInsertedId++;
            Long calendarId = values == null
                    ? null : values.getAsLong(CalendarContract.Events.CALENDAR_ID);
            Integer allDay = values == null
                    ? null : values.getAsInteger(CalendarContract.Events.ALL_DAY);
            events.put(eventId, new long[] {calendarId == null ? 0L : calendarId,
                    allDay == null ? 0L : allDay});
            return ContentUris.withAppendedId(uri, eventId);
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection,
                          String[] selectionArgs) {
            updateCount++;
            lastUpdate = values == null ? null : new ContentValues(values);
            if (failure != null) {
                throw failure;
            }
            return updateRows;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            deleteCount++;
            if (failure != null) {
                throw failure;
            }
            return deleteRows;
        }

        @Override
        public String getType(Uri uri) {
            throw new UnsupportedOperationException("not used by the bounded adapter");
        }

        private List<Long> sortedCalendarIds() {
            List<Long> ids = new ArrayList<>(accessLevels.keySet());
            ids.sort(Long::compare);
            return ids;
        }

        private static boolean hasColumn(String[] projection, String column) {
            if (projection == null) {
                return false;
            }
            for (String candidate : projection) {
                if (column.equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
    }
}
