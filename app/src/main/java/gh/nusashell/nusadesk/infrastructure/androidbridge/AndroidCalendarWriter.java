package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

/**
 * Bounded calendar write adapter.
 *
 * <p>Every write runs the same guard chain before it touches the provider: the
 * write-calendar grant must be present, a target calendar must exist, and the
 * app's access level for it must allow contributions — so an event can never be
 * created in, or removed from, a calendar the user only reads. Updates and
 * deletes resolve the event's own calendar first and answer
 * {@code calendar-not-found} when the event is gone.</p>
 *
 * <p>This slice deliberately never touches attendees: no attendee rows are
 * written and no invitation is sent, so a guest script cannot turn a local
 * automation into mail to other people. Each write logs one bounded line with
 * the operation, event id, and calendar id — never the title, location, or any
 * other content.</p>
 */
public final class AndroidCalendarWriter implements CalendarWriter {
    private static final String TAG = "AndroidCalendarWriter";
    private static final int MIN_WRITABLE_ACCESS =
            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;

    private final Context context;
    private final MessagingPermissionChecker permissionChecker;

    public AndroidCalendarWriter(Context context) {
        this(context, new AndroidMessagingPermissionChecker(context));
    }

    AndroidCalendarWriter(Context context, MessagingPermissionChecker permissionChecker) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (permissionChecker == null) {
            throw new IllegalArgumentException("permissionChecker must not be null");
        }
        this.context = context.getApplicationContext();
        this.permissionChecker = permissionChecker;
    }

    @Override
    public CalendarWriteResult insert(CalendarWriteRequest request) {
        if (request == null || request.getOp() != CalendarWriteRequest.Op.INSERT) {
            return CalendarWriteResult.failed(CalendarWriteRequest.ERROR_INVALID_ARGUMENT);
        }
        String permissionError = checkWritePermission();
        if (permissionError != null) {
            return CalendarWriteResult.failed(permissionError);
        }
        try {
            long calendarId = request.getCalendarId() == null
                    ? primaryWritableCalendarId() : request.getCalendarId();
            if (calendarId <= 0L) {
                return CalendarWriteResult.failed(CalendarWriteResult.ERROR_UNAVAILABLE);
            }
            if (calendarAccess(calendarId) < MIN_WRITABLE_ACCESS) {
                return CalendarWriteResult.failed(CalendarWriteResult.ERROR_READ_ONLY);
            }
            ContentValues values = new ContentValues();
            values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
            values.put(CalendarContract.Events.TITLE, request.getTitle());
            values.put(CalendarContract.Events.DTSTART, request.getBeginMillis());
            values.put(CalendarContract.Events.DTEND, request.getEndMillis());
            values.put(CalendarContract.Events.ALL_DAY, request.isAllDay() ? 1 : 0);
            // The provider rejects an event without a timezone, so a write
            // never depends on the optional calendar read: an all-day event is
            // UTC by contract, and a timed one falls back to the device zone.
            values.put(CalendarContract.Events.EVENT_TIMEZONE,
                    eventTimezone(calendarId, request.isAllDay()));
            if (!request.getLocation().isEmpty()) {
                values.put(CalendarContract.Events.EVENT_LOCATION, request.getLocation());
            }
            Uri inserted = resolver().insert(CalendarContract.Events.CONTENT_URI, values);
            long eventId = inserted == null ? -1L : ContentUris.parseId(inserted);
            if (eventId <= 0L) {
                return CalendarWriteResult.failed(CalendarWriteResult.ERROR_FAILED);
            }
            Log.i(TAG, "calendar write op=insert event=" + eventId
                    + " calendar=" + calendarId);
            return CalendarWriteResult.ok(eventId);
        } catch (SecurityException e) {
            return CalendarWriteResult.failed(CalendarWriteResult.ERROR_PERMISSION_DENIED);
        } catch (RuntimeException e) {
            Log.w(TAG, "calendar insert failed", e);
            return CalendarWriteResult.failed(CalendarWriteResult.ERROR_FAILED);
        }
    }

    @Override
    public CalendarWriteResult update(CalendarWriteRequest request) {
        if (request == null || request.getOp() != CalendarWriteRequest.Op.UPDATE) {
            return CalendarWriteResult.failed(CalendarWriteRequest.ERROR_INVALID_ARGUMENT);
        }
        String permissionError = checkWritePermission();
        if (permissionError != null) {
            return CalendarWriteResult.failed(permissionError);
        }
        try {
            EventTarget target = eventTarget(request.getEventId());
            if (target == null) {
                return CalendarWriteResult.failed(CalendarWriteResult.ERROR_NOT_FOUND);
            }
            if (calendarAccess(target.calendarId) < MIN_WRITABLE_ACCESS) {
                return CalendarWriteResult.failed(CalendarWriteResult.ERROR_READ_ONLY);
            }
            ContentValues values = new ContentValues();
            if (!request.getTitle().isEmpty()) {
                values.put(CalendarContract.Events.TITLE, request.getTitle());
            }
            if (!request.getLocation().isEmpty()) {
                values.put(CalendarContract.Events.EVENT_LOCATION, request.getLocation());
            }
            if (request.getBeginMillis() != null) {
                // An all-day event keeps UTC-midnight boundaries: a timed range
                // on it would be an invalid provider state.
                boolean staysAllDay = request.setsAllDay()
                        ? request.isAllDay() : target.allDay;
                if (staysAllDay && !isUtcMidnightAligned(request.getBeginMillis())) {
                    return CalendarWriteResult.failed(
                            CalendarWriteRequest.ERROR_INVALID_ARGUMENT);
                }
                values.put(CalendarContract.Events.DTSTART, request.getBeginMillis());
                values.put(CalendarContract.Events.DTEND, request.getEndMillis());
            }
            if (request.setsAllDay()) {
                values.put(CalendarContract.Events.ALL_DAY, request.isAllDay() ? 1 : 0);
                // Keep the timezone coherent with the all-day rule: the
                // provider expects UTC on an all-day event, and a timed event
                // must carry a real zone again.
                values.put(CalendarContract.Events.EVENT_TIMEZONE,
                        eventTimezone(target.calendarId, request.isAllDay()));
            }
            if (values.size() == 0) {
                return CalendarWriteResult.failed(
                        CalendarWriteRequest.ERROR_INVALID_ARGUMENT);
            }
            Uri eventUri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI, request.getEventId());
            int rows = resolver().update(eventUri, values, null, null);
            if (rows <= 0) {
                return CalendarWriteResult.failed(CalendarWriteResult.ERROR_NOT_FOUND);
            }
            Log.i(TAG, "calendar write op=update event=" + request.getEventId()
                    + " calendar=" + target.calendarId);
            return CalendarWriteResult.ok(request.getEventId());
        } catch (SecurityException e) {
            return CalendarWriteResult.failed(CalendarWriteResult.ERROR_PERMISSION_DENIED);
        } catch (RuntimeException e) {
            Log.w(TAG, "calendar update failed", e);
            return CalendarWriteResult.failed(CalendarWriteResult.ERROR_FAILED);
        }
    }

    @Override
    public CalendarWriteResult delete(CalendarWriteRequest request) {
        if (request == null || request.getOp() != CalendarWriteRequest.Op.DELETE) {
            return CalendarWriteResult.failed(CalendarWriteRequest.ERROR_INVALID_ARGUMENT);
        }
        String permissionError = checkWritePermission();
        if (permissionError != null) {
            return CalendarWriteResult.failed(permissionError);
        }
        try {
            EventTarget target = eventTarget(request.getEventId());
            if (target == null) {
                return CalendarWriteResult.failed(CalendarWriteResult.ERROR_NOT_FOUND);
            }
            if (calendarAccess(target.calendarId) < MIN_WRITABLE_ACCESS) {
                return CalendarWriteResult.failed(CalendarWriteResult.ERROR_READ_ONLY);
            }
            Uri eventUri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI, request.getEventId());
            int rows = resolver().delete(eventUri, null, null);
            if (rows <= 0) {
                return CalendarWriteResult.failed(CalendarWriteResult.ERROR_NOT_FOUND);
            }
            Log.i(TAG, "calendar write op=delete event=" + request.getEventId()
                    + " calendar=" + target.calendarId);
            return CalendarWriteResult.ok(request.getEventId());
        } catch (SecurityException e) {
            return CalendarWriteResult.failed(CalendarWriteResult.ERROR_PERMISSION_DENIED);
        } catch (RuntimeException e) {
            Log.w(TAG, "calendar delete failed", e);
            return CalendarWriteResult.failed(CalendarWriteResult.ERROR_FAILED);
        }
    }

    /** Typed permission failure, or {@code null} when the grant is present. */
    private String checkWritePermission() {
        CapabilityPermission permission =
                permissionChecker.check(Manifest.permission.WRITE_CALENDAR);
        if (permission == CapabilityPermission.REQUIRED) {
            return CalendarWriteResult.ERROR_PERMISSION_REQUIRED;
        }
        if (permission == CapabilityPermission.DENIED) {
            return CalendarWriteResult.ERROR_PERMISSION_DENIED;
        }
        return null;
    }

    /** The primary writable calendar, else any writable one; zero when none. */
    private long primaryWritableCalendarId() {
        long fallback = 0L;
        Cursor cursor = null;
        try {
            cursor = resolver().query(CalendarContract.Calendars.CONTENT_URI,
                    new String[] {
                            CalendarContract.Calendars._ID,
                            CalendarContract.Calendars.IS_PRIMARY
                    },
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= ?",
                    new String[] {String.valueOf(MIN_WRITABLE_ACCESS)},
                    CalendarContract.Calendars.IS_PRIMARY + " DESC, "
                            + CalendarContract.Calendars._ID + " ASC");
            if (cursor == null) {
                return 0L;
            }
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                boolean primary = cursor.getInt(1) != 0;
                if (primary) {
                    return id;
                }
                if (fallback == 0L) {
                    fallback = id;
                }
            }
        } catch (SecurityException e) {
            // A provider-side refusal is a permission outcome, not a missing
            // calendar; the caller maps it to calendar-permission-denied.
            throw e;
        } catch (RuntimeException e) {
            return 0L;
        } finally {
            closeQuietly(cursor);
        }
        return fallback;
    }

    /** The app's access level for one calendar, or -1 when it is unknown. */
    private int calendarAccess(long calendarId) {
        Cursor cursor = null;
        try {
            cursor = resolver().query(
                    ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,
                            calendarId),
                    new String[] {CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL},
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return -1;
            }
            return cursor.getInt(0);
        } catch (SecurityException e) {
            // As above: a refusal is a permission outcome, not a read-only or
            // missing calendar.
            throw e;
        } catch (RuntimeException e) {
            return -1;
        } finally {
            closeQuietly(cursor);
        }
    }

    /**
     * The timezone one write must carry: the calendar's own zone when it can be
     * read, else UTC for an all-day event and the device zone for a timed one.
     * Never {@code null}, because the provider rejects an event without one.
     */
    private String eventTimezone(long calendarId, boolean allDay) {
        if (allDay) {
            // The provider requires UTC on an all-day event.
            return "UTC";
        }
        String timezone = calendarTimezone(calendarId);
        if (timezone != null) {
            return timezone;
        }
        return java.util.TimeZone.getDefault().getID();
    }

    /** The calendar's timezone, or {@code null} when it cannot be read. */
    private String calendarTimezone(long calendarId) {
        Cursor cursor = null;
        try {
            cursor = resolver().query(
                    ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI,
                            calendarId),
                    new String[] {CalendarContract.Calendars.CALENDAR_TIME_ZONE},
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String timezone = cursor.getString(0);
            return timezone == null || timezone.trim().isEmpty() ? null : timezone.trim();
        } catch (RuntimeException e) {
            return null;
        } finally {
            closeQuietly(cursor);
        }
    }

    /** The event's calendar and all-day flag, or {@code null} when it is gone. */
    private EventTarget eventTarget(long eventId) {
        Cursor cursor = null;
        try {
            cursor = resolver().query(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    new String[] {
                            CalendarContract.Events.CALENDAR_ID,
                            CalendarContract.Events.ALL_DAY
                    },
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            long calendarId = cursor.getLong(0);
            if (calendarId <= 0L) {
                return null;
            }
            return new EventTarget(calendarId, cursor.getInt(1) != 0);
        } catch (RuntimeException e) {
            return null;
        } finally {
            closeQuietly(cursor);
        }
    }

    private static boolean isUtcMidnightAligned(long millis) {
        return millis % (24L * 60L * 60L * 1_000L) == 0L;
    }

    private ContentResolver resolver() {
        return context.getContentResolver();
    }

    private static void closeQuietly(Cursor cursor) {
        if (cursor == null) {
            return;
        }
        try {
            cursor.close();
        } catch (RuntimeException ignored) {
            // Best-effort close; the outcome is already settled.
        }
    }

    /** Minimal event facts a guarded write needs. */
    private static final class EventTarget {
        private final long calendarId;
        private final boolean allDay;

        EventTarget(long calendarId, boolean allDay) {
            this.calendarId = calendarId;
            this.allDay = allDay;
        }
    }
}
