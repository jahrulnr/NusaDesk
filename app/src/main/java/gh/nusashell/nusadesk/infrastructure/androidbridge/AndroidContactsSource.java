package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, permission-aware {@link ContactsContract} adapter.
 *
 * <p>Each read first resolves the read-contacts grant; a missing or denied
 * grant is an explicit typed state and never a permission prompt. The query
 * is the only guest input: the fixed projection carries contact id, display
 * name, and phone number only, rows are ordered by contact id so each
 * contact's numbers are contiguous, and scanning stops once
 * {@code query.getLimit()} contacts are collected (or a defensive scan cap is
 * hit), so a provider can never push an unbounded address book. An optional
 * query filters on display name or number with a LIKE-escaped literal.
 * Email, postal addresses, photos, notes, and every provider id are never
 * projected or read.</p>
 */
public final class AndroidContactsSource implements ContactsSource {
    /**
     * Stable {@code contact_id} column of the {@code data} table
     * (ContactsContract.Data schema). The public constant was removed from
     * newer SDK API surfaces while the provider column itself remains stable.
     */
    private static final String COLUMN_CONTACT_ID = "contact_id";
    private static final String ORDER_BY_CONTACT_ID = COLUMN_CONTACT_ID + " ASC";
    private static final String[] PROJECTION = {
            COLUMN_CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
    };

    private final Context context;
    private final MessagingPermissionChecker permissionChecker;

    public AndroidContactsSource(Context context) {
        this(context, new AndroidMessagingPermissionChecker(context));
    }

    AndroidContactsSource(Context context, MessagingPermissionChecker permissionChecker) {
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
    public ContactsSnapshot read(MessagingQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        CapabilityPermission permission =
                permissionChecker.check(Manifest.permission.READ_CONTACTS);
        if (permission == CapabilityPermission.REQUIRED) {
            return ContactsSnapshot.permissionRequired();
        }
        if (permission == CapabilityPermission.DENIED) {
            return ContactsSnapshot.permissionDenied();
        }
        Cursor cursor = null;
        try {
            String selection = null;
            String[] selectionArgs = null;
            if (query.hasQuery()) {
                // Literal match only: the query is LIKE-escaped, and the
                // projection, URI, and ordering stay fixed.
                String pattern = query.likePattern();
                selection = "(" + ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                        + " LIKE ? ESCAPE '\\' OR "
                        + ContactsContract.CommonDataKinds.Phone.NUMBER
                        + " LIKE ? ESCAPE '\\')";
                selectionArgs = new String[]{pattern, pattern};
            }
            cursor = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    PROJECTION, selection, selectionArgs, ORDER_BY_CONTACT_ID);
            if (cursor == null) {
                return ContactsSnapshot.unavailable();
            }
            return groupContacts(cursor, query.getLimit());
        } catch (SecurityException e) {
            // The provider refused the read (for example a grant revoked
            // between the check and the query); typed as a denial, never as
            // data and never as a fabricated value.
            return ContactsSnapshot.permissionDenied();
        } catch (RuntimeException e) {
            return ContactsSnapshot.error();
        } finally {
            closeQuietly(cursor);
        }
    }

    /**
     * Group provider rows by contact id, keeping at most {@code limit}
     * contacts and at most {@link MessagingReadPolicy#MAX_NUMBERS_PER_CONTACT}
     * numbers per contact. Ordering by contact id makes each contact's rows
     * contiguous, so the scan can stop once the cap is reached; a defensive
     * scan bound covers a provider that ignores ordering. Any capping sets
     * the truncated flag.
     */
    private static ContactsSnapshot groupContacts(Cursor cursor, int limit) {
        Map<Long, MutableContact> byId = new LinkedHashMap<>();
        int scanned = 0;
        int maxScan = limit * (MessagingReadPolicy.MAX_NUMBERS_PER_CONTACT + 1);
        boolean truncated = false;
        while (cursor.moveToNext() && scanned < maxScan) {
            scanned++;
            long contactId = cursor.getLong(0);
            String name = trimToNull(cursor.getString(1));
            String number = trimToNull(cursor.getString(2));
            if (number == null) {
                // A number row without a number carries nothing the contract needs.
                continue;
            }
            MutableContact existing = byId.get(contactId);
            if (existing != null) {
                truncated |= existing.addNumber(number);
            } else if (byId.size() >= limit) {
                truncated = true;
                break;
            } else {
                byId.put(contactId, new MutableContact(name, number));
            }
        }
        if (scanned >= maxScan) {
            truncated = true;
        }
        List<ContactsSnapshot.ContactEntry> entries = new ArrayList<>(byId.size());
        for (MutableContact contact : byId.values()) {
            entries.add(contact.build());
        }
        return ContactsSnapshot.reading(entries, truncated);
    }

    private static final class MutableContact {
        private final String name;
        private final List<String> numbers = new ArrayList<>();

        MutableContact(String name, String number) {
            this.name = name == null ? "" : name;
            numbers.add(number);
        }

        /** Returns true when the number was dropped because the per-contact cap was reached. */
        boolean addNumber(String number) {
            if (numbers.size() >= MessagingReadPolicy.MAX_NUMBERS_PER_CONTACT) {
                return true;
            }
            numbers.add(number);
            return false;
        }

        ContactsSnapshot.ContactEntry build() {
            return new ContactsSnapshot.ContactEntry(name, numbers);
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
