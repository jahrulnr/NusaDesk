package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, permission-aware {@link CalendarContract.Instances} adapter.
 *
 * <p>Each read first resolves the read-calendar grant; a missing or denied
 * grant is an explicit typed state and never a permission prompt. The query
 * runs against the provider's {@code Instances} table inside the bounded
 * window, which is what makes recurring events correct: the provider expands
 * the recurrence rule and its exceptions, so this adapter never reimplements
 * calendar math.</p>
 *
 * <p>The projection is the minimal contract (event id, title, begin, end,
 * all-day, calendar id/name, timezone, location). Description, attendees,
 * organizer address, reminders, and every other provider column are never
 * read. The sort order carries no SQL {@code LIMIT} token — some providers
 * reject it — so the row cap is enforced in Java, and the read stops one row
 * past the cap to report truncation without scanning the whole calendar.</p>
 */
public final class AndroidCalendarSource implements CalendarSource {
    private static final String[] PROJECTION = {
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.EVENT_LOCATION
    };

    private final Context context;
    private final MessagingPermissionChecker permissionChecker;

    public AndroidCalendarSource(Context context) {
        this(context, new AndroidMessagingPermissionChecker(context));
    }

    AndroidCalendarSource(Context context, MessagingPermissionChecker permissionChecker) {
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
    public CalendarEventSnapshot read(CalendarQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        CapabilityPermission permission =
                permissionChecker.check(Manifest.permission.READ_CALENDAR);
        if (permission == CapabilityPermission.REQUIRED) {
            return CalendarEventSnapshot.permissionRequired();
        }
        if (permission == CapabilityPermission.DENIED) {
            return CalendarEventSnapshot.permissionDenied();
        }
        Cursor cursor = null;
        try {
            // The Instances table is not a plain table: the window is part of
            // the URI path (two appended ids) and the provider expands
            // recurrences inside it. A bare `.../instances/when` query is
            // rejected by the provider, so the window never travels as a
            // selection argument.
            Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(builder, query.getBeginMillis());
            ContentUris.appendId(builder, query.getEndMillis());
            String sortOrder = CalendarContract.Instances.BEGIN + " ASC";
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(builder.build(), PROJECTION, null, null, sortOrder);
            if (cursor == null) {
                return CalendarEventSnapshot.unavailable();
            }
            List<CalendarEventSnapshot.EventEntry> entries = new ArrayList<>();
            boolean truncated = false;
            while (cursor.moveToNext()) {
                if (entries.size() >= query.getLimit()) {
                    truncated = true;
                    break;
                }
                CalendarEventSnapshot.EventEntry entry = mapRow(cursor);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return CalendarEventSnapshot.reading(entries, truncated);
        } catch (SecurityException e) {
            // The provider refused the read (for example a grant revoked
            // between the check and the query); typed as a denial.
            return CalendarEventSnapshot.permissionDenied();
        } catch (RuntimeException e) {
            return CalendarEventSnapshot.error();
        } finally {
            closeQuietly(cursor);
        }
    }

    /**
     * Map one instance row; rows without an id, a title, or a usable begin are
     * skipped rather than fabricated.
     */
    private static CalendarEventSnapshot.EventEntry mapRow(Cursor cursor) {
        long eventId = cursor.getLong(0);
        String title = trimToNull(cursor.getString(1));
        long begin = cursor.getLong(2);
        long end = cursor.getLong(3);
        boolean allDay = cursor.getInt(4) != 0;
        long calendarId = cursor.getLong(5);
        String calendarName = cursor.getString(6);
        String timezone = cursor.getString(7);
        String location = cursor.getString(8);
        if (eventId <= 0L || title == null || begin <= 0L) {
            return null;
        }
        return new CalendarEventSnapshot.EventEntry(eventId, title, begin,
                Math.max(begin, end <= 0L ? begin : end), allDay, calendarId,
                calendarName, timezone, location);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void closeQuietly(Cursor cursor) {
        if (cursor == null) {
            return;
        }
        try {
            cursor.close();
        } catch (RuntimeException ignored) {
            // Best-effort close; the read outcome is already settled.
        }
    }
}
