package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, permission-aware SMS inbox adapter.
 *
 * <p>Each read first resolves the read-sms grant; a missing or denied grant
 * is an explicit typed state and never a permission prompt. The query is the
 * only guest input: the fixed projection carries address, date, read state,
 * and body only (the body is reduced to a bounded single-line snippet), the
 * read stops one row past {@code query.getLimit()} (newest first), and the
 * sort order carries no SQL {@code LIMIT} token because some providers reject
 * it, so the Java-side bound is the only row cap. An optional query
 * filters on address with a LIKE-escaped literal. Person, subject, service
 * center, status, error code, thread id, creator, and the full body are never
 * projected or read. Sending SMS is a side-effecting operation and is out of
 * scope for this read-only adapter.</p>
 */
public final class AndroidSmsSource implements SmsSource {
    private static final String[] PROJECTION = {
            Telephony.TextBasedSmsColumns.ADDRESS,
            Telephony.TextBasedSmsColumns.DATE,
            Telephony.TextBasedSmsColumns.READ,
            Telephony.TextBasedSmsColumns.BODY
    };

    private final Context context;
    private final MessagingPermissionChecker permissionChecker;

    public AndroidSmsSource(Context context) {
        this(context, new AndroidMessagingPermissionChecker(context));
    }

    AndroidSmsSource(Context context, MessagingPermissionChecker permissionChecker) {
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
    public SmsSnapshot read(MessagingQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        CapabilityPermission permission = permissionChecker.check(Manifest.permission.READ_SMS);
        if (permission == CapabilityPermission.REQUIRED) {
            return SmsSnapshot.permissionRequired();
        }
        if (permission == CapabilityPermission.DENIED) {
            return SmsSnapshot.permissionDenied();
        }
        Cursor cursor = null;
        try {
            String selection = null;
            String[] selectionArgs = null;
            if (query.hasQuery()) {
                selection = Telephony.TextBasedSmsColumns.ADDRESS + " LIKE ? ESCAPE '\\'";
                selectionArgs = new String[]{query.likePattern()};
            }
            // The provider may reject a SQL LIMIT token in the sort order (see
            // AndroidCallLogSource), so the row bound is enforced in Java only;
            // the cursor is read at most one row past the cap.
            String sortOrder = Telephony.Sms.DEFAULT_SORT_ORDER;
            cursor = context.getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI, PROJECTION, selection,
                    selectionArgs, sortOrder);
            if (cursor == null) {
                return SmsSnapshot.unavailable();
            }
            List<SmsSnapshot.SmsEntry> entries = new ArrayList<>();
            boolean truncated = false;
            while (cursor.moveToNext()) {
                if (entries.size() >= query.getLimit()) {
                    truncated = true;
                    break;
                }
                SmsSnapshot.SmsEntry entry = mapRow(cursor);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return SmsSnapshot.reading(entries, truncated);
        } catch (SecurityException e) {
            // The provider refused the read (for example a grant revoked
            // between the check and the query); typed as a denial, never as
            // data and never as a fabricated value.
            return SmsSnapshot.permissionDenied();
        } catch (RuntimeException e) {
            return SmsSnapshot.error();
        } finally {
            closeQuietly(cursor);
        }
    }

    /**
     * Map one provider row into the bounded contract; rows the provider
     * filled with unusable values (no address or no timestamp) are skipped
     * rather than fabricated. The body is reduced to a single-line snippet by
     * the policy and never crosses the boundary in full.
     */
    private static SmsSnapshot.SmsEntry mapRow(Cursor cursor) {
        String address = trimToNull(cursor.getString(0));
        long timestamp = cursor.getLong(1);
        boolean read = cursor.getInt(2) != 0;
        String body = cursor.getString(3);
        if (address == null || timestamp <= 0L) {
            return null;
        }
        return new SmsSnapshot.SmsEntry(address, timestamp, read, body);
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
