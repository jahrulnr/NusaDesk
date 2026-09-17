package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.provider.CallLog;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, permission-aware {@link CallLog} adapter.
 *
 * <p>Each read first resolves the read-call-log grant; a missing or denied
 * grant is an explicit typed state and never a permission prompt. The query
 * is the only guest input: the fixed projection carries number, cached name,
 * type, date, and duration only, the read stops one row past
 * {@code query.getLimit()} (newest first), and the sort order carries no SQL
 * {@code LIMIT} token because some providers reject it. The Java-side bound is
 * therefore the only row cap and it holds even when a provider returns more.
 * An optional query filters on number with a LIKE-escaped
 * literal. Geocoded location, phone account, subscription, presentation, and
 * every provider id are never projected or read.</p>
 */
public final class AndroidCallLogSource implements CallLogSource {
    private static final String TAG = "AndroidCallLogSource";
    private static final String[] PROJECTION = {
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
    };

    private final Context context;
    private final MessagingPermissionChecker permissionChecker;

    public AndroidCallLogSource(Context context) {
        this(context, new AndroidMessagingPermissionChecker(context));
    }

    AndroidCallLogSource(Context context, MessagingPermissionChecker permissionChecker) {
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
    public CallLogSnapshot read(MessagingQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        CapabilityPermission permission =
                permissionChecker.check(Manifest.permission.READ_CALL_LOG);
        if (permission == CapabilityPermission.REQUIRED) {
            return CallLogSnapshot.permissionRequired();
        }
        if (permission == CapabilityPermission.DENIED) {
            return CallLogSnapshot.permissionDenied();
        }
        Cursor cursor = null;
        try {
            String selection = null;
            String[] selectionArgs = null;
            if (query.hasQuery()) {
                selection = CallLog.Calls.NUMBER + " LIKE ? ESCAPE '\\'";
                selectionArgs = new String[]{query.likePattern()};
            }
            // A SQL LIMIT token in the sort order is not portable: Samsung's
            // CallLog provider answers it with IllegalArgumentException
            // ("Invalid token LIMIT"). The row bound is therefore enforced in
            // Java only, and the cursor is read at most one row past the cap
            // so truncation stays observable without scanning the whole log.
            String sortOrder = CallLog.Calls.DATE + " DESC";
            cursor = context.getContentResolver().query(
                    CallLog.Calls.CONTENT_URI, PROJECTION, selection,
                    selectionArgs, sortOrder);
            if (cursor == null) {
                return CallLogSnapshot.unavailable();
            }
            List<CallLogSnapshot.CallLogEntry> entries = new ArrayList<>();
            boolean truncated = false;
            while (cursor.moveToNext()) {
                if (entries.size() >= query.getLimit()) {
                    truncated = true;
                    break;
                }
                CallLogSnapshot.CallLogEntry entry = mapRow(cursor);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return CallLogSnapshot.reading(entries, truncated);
        } catch (SecurityException e) {
            // The provider refused the read (for example a grant revoked
            // between the check and the query); typed as a denial, never as
            // data and never as a fabricated value.
            return CallLogSnapshot.permissionDenied();
        } catch (RuntimeException e) {
            // Bounded diagnostics only: the guest still sees just the typed
            // error. The fixed read carries no guest input, so no query text
            // or filter value can leak here.
            Log.w(TAG, "call log read failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return CallLogSnapshot.error();
        } finally {
            closeQuietly(cursor);
        }
    }

    /**
     * Map one provider row into the bounded contract; rows the provider
     * filled with unusable values (no number or no timestamp) are skipped
     * rather than fabricated.
     */
    private static CallLogSnapshot.CallLogEntry mapRow(Cursor cursor) {
        String number = trimToNull(cursor.getString(0));
        String cachedName = trimToNull(cursor.getString(1));
        int typeValue = cursor.getInt(2);
        long timestamp = cursor.getLong(3);
        long duration = cursor.getLong(4);
        if (number == null || timestamp <= 0L) {
            return null;
        }
        return new CallLogSnapshot.CallLogEntry(number, cachedName,
                mapType(typeValue), timestamp, Math.max(0L, duration));
    }

    private static CallLogSnapshot.CallType mapType(int value) {
        switch (value) {
            case CallLog.Calls.INCOMING_TYPE:
                return CallLogSnapshot.CallType.INCOMING;
            case CallLog.Calls.OUTGOING_TYPE:
                return CallLogSnapshot.CallType.OUTGOING;
            case CallLog.Calls.MISSED_TYPE:
                return CallLogSnapshot.CallType.MISSED;
            case CallLog.Calls.REJECTED_TYPE:
                return CallLogSnapshot.CallType.REJECTED;
            case CallLog.Calls.BLOCKED_TYPE:
                return CallLogSnapshot.CallType.BLOCKED;
            case CallLog.Calls.VOICEMAIL_TYPE:
                return CallLogSnapshot.CallType.VOICEMAIL;
            case CallLog.Calls.ANSWERED_EXTERNALLY_TYPE:
                return CallLogSnapshot.CallType.ANSWERED_EXTERNALLY;
            default:
                return CallLogSnapshot.CallType.UNKNOWN;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void closeQuietly(Cursor cursor) {
        try {
            cursor.close();
        } catch (RuntimeException ignored) {
            // Best-effort close; the read outcome is already settled.
        }
    }
}
