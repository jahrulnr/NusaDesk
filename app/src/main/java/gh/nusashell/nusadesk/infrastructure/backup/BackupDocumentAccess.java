package gh.nusashell.nusadesk.infrastructure.backup;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import gh.nusashell.nusadesk.domain.backup.BackupMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Storage Access Framework plumbing for guest backups: builds the create and
 * open document intents and opens the returned document's stream. The engine
 * streams the archive directly into/out of the document — no temporary copy —
 * so {@code openForWrite}/{@code openForRead} are the only SAF surface.
 */
public final class BackupDocumentAccess {

    /** Activity request code for the export create-document picker. */
    public static final int REQUEST_CREATE_BACKUP = 0x5704;
    /** Activity request code for the import open-document picker. */
    public static final int REQUEST_OPEN_BACKUP = 0x5705;

    private static final String MIME_GZIP = "application/gzip";

    private final Context context;

    public BackupDocumentAccess(Context context) {
        this.context = context.getApplicationContext();
    }

    /** @return an {@code ACTION_CREATE_DOCUMENT} intent for a .tar.gz export. */
    public Intent createDocumentIntent(String suggestedName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(MIME_GZIP);
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        return intent;
    }

    /**
     * @return an {@code ACTION_OPEN_DOCUMENT} intent filtered to gzip-like
     *         payloads. {@code *} as the base type keeps providers that
     *         mislabel {@code .tar.gz} selectable; content validation happens
     *         in the reader regardless.
     */
    public Intent openDocumentIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                MIME_GZIP, "application/x-gzip", "application/x-tar",
                "application/octet-stream"});
        return intent;
    }

    /** Opens the picked document for writing; the caller closes it. */
    public OutputStream openForWrite(Uri uri) throws IOException {
        return context.getContentResolver().openOutputStream(uri, "w");
    }

    /** Opens the picked document for reading; the caller closes it. */
    public InputStream openForRead(Uri uri) throws IOException {
        return context.getContentResolver().openInputStream(uri);
    }

    /** @return the document's display name, or {@code null} when unavailable. */
    public String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // A provider that refuses the query simply leaves no display name.
        }
        return null;
    }

    /** Suggested export filename: {@code nusadesk-backup-<mode>-<yyyyMMdd-HHmm>.tar.gz}. */
    public static String suggestedFileName(BackupMode mode, long nowEpochMs) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                .format(new Date(nowEpochMs));
        return "nusadesk-backup-" + mode.getWireValue() + "-" + stamp + ".tar.gz";
    }
}
