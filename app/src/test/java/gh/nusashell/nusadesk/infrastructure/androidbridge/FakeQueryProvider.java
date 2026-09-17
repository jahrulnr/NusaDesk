package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Robolectric stand-in for a provider-backed table (contacts, SMS, call log).
 *
 * <p>It records the bounded query the adapter sent (selection, arguments,
 * sort order, projection) and returns test-supplied rows, a {@code null}
 * cursor, or throws, so the adapter's fixed-projection contract, LIKE-escape
 * plumbing, row caps, and typed failure states are verifiable on the JVM.
 * Rows must be {@link Object} arrays aligned with the projection the adapter
 * sends. It deliberately ignores the SQL cap in the sort order, which doubles
 * as the "provider ignores the LIMIT" case the Java-side caps must survive.</p>
 */
final class FakeQueryProvider extends ContentProvider {
    private final List<Object[]> rows = new ArrayList<>();
    private boolean returnNullCursor;
    private RuntimeException failure;
    private int queryCount;
    private String lastSelection;
    private String[] lastSelectionArgs;
    private String lastSortOrder;
    private String[] lastProjection;

    void setRows(Object[]... rows) {
        this.rows.clear();
        if (rows != null) {
            for (Object[] row : rows) {
                this.rows.add(row);
            }
        }
    }

    void setReturnNullCursor() {
        this.returnNullCursor = true;
    }

    void setFailure(RuntimeException failure) {
        this.failure = failure;
    }

    int getQueryCount() {
        return queryCount;
    }

    String getLastSelection() {
        return lastSelection;
    }

    String[] getLastSelectionArgs() {
        return lastSelectionArgs;
    }

    String getLastSortOrder() {
        return lastSortOrder;
    }

    String[] getLastProjection() {
        return lastProjection;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        queryCount++;
        lastProjection = projection;
        lastSelection = selection;
        lastSelectionArgs = selectionArgs;
        lastSortOrder = sortOrder;
        if (failure != null) {
            throw failure;
        }
        if (returnNullCursor) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(projection == null ? new String[0] : projection);
        for (Object[] row : rows) {
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException("not used by the bounded adapters");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only slice");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only slice");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only slice");
    }
}
