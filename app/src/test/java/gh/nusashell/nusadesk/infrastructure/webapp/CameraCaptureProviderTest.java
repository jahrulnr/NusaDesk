package gh.nusashell.nusadesk.infrastructure.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The camera capture store (ADR-0059): it serves exactly the cache files a
 * capture wrote, refuses everything else — a different directory, a name with a
 * path in it, an entry that escapes the directory — and offers the two columns a
 * camera app asks for.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class CameraCaptureProviderTest {

    private Context context;
    private CameraCaptureProvider provider;
    private File directory;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        provider = Robolectric.buildContentProvider(CameraCaptureProvider.class).create().get();
        directory = CameraCaptureProvider.directory(context);
        assertTrue(directory.isDirectory() || directory.mkdirs());
    }

    @Test
    public void aCaptureFileIsWritableThroughItsUriAndReadableBack() throws Exception {
        File target = new File(directory, "photo-1.jpg");
        Uri uri = CameraCaptureProvider.uriFor(context, target);

        try (ParcelFileDescriptor write =
                     provider.openFile(uri, "w")) {
            try (FileOutputStream out = new FileOutputStream(write.getFileDescriptor())) {
                out.write("jpeg-bytes".getBytes(StandardCharsets.UTF_8));
            }
        }

        assertEquals("jpeg-bytes".getBytes(StandardCharsets.UTF_8).length, target.length());
        try (ParcelFileDescriptor read = provider.openFile(uri, "r")) {
            assertNotNull(read);
        }
        assertEquals("image/jpeg", provider.getType(uri));
        assertEquals("video/mp4",
                provider.getType(CameraCaptureProvider.uriFor(
                        context, new File(directory, "clip.mp4"))));
    }

    @Test
    public void theColumnsACameraAppAsksForAreAnswered() throws Exception {
        File target = new File(directory, "photo-2.jpg");
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(new byte[7]);
        }

        Cursor cursor = provider.query(
                CameraCaptureProvider.uriFor(context, target), null, null, null, null);

        assertNotNull(cursor);
        assertTrue(cursor.moveToFirst());
        assertEquals("photo-2.jpg",
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
        assertEquals(7,
                cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
    }

    @Test
    public void anythingOutsideTheCaptureDirectoryIsRefused() {
        String authority = context.getPackageName() + ".camera";
        Uri otherDirectory = Uri.parse("content://" + authority + "/documents/photo.jpg");
        Uri traversal = Uri.parse("content://" + authority + "/camera/..%2F..%2Fdatabases%2Fx");
        Uri emptyName = Uri.parse("content://" + authority + "/camera/");

        assertNull(provider.getType(otherDirectory));
        assertNull(provider.getType(traversal));
        assertNull(provider.getType(emptyName));
        assertThrows(java.io.FileNotFoundException.class,
                () -> provider.openFile(otherDirectory, "r"));
        assertThrows(java.io.FileNotFoundException.class,
                () -> provider.openFile(traversal, "w"));
    }

    @Test
    public void theStoreHasNoInsertUpdateOrDeleteContract() {
        Uri uri = CameraCaptureProvider.uriFor(context, new File(directory, "photo-3.jpg"));

        assertThrows(UnsupportedOperationException.class,
                () -> provider.insert(uri, new android.content.ContentValues()));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.update(uri, new android.content.ContentValues(), null, null));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.delete(uri, null, null));
    }

    @Test
    public void aCaptureTargetIsFreshAndOldArtifactsAreSwept() throws Exception {
        File stale = new File(directory, "photo-stale.jpg");
        try (FileOutputStream out = new FileOutputStream(stale)) {
            out.write(new byte[1]);
        }
        assertTrue(stale.setLastModified(
                System.currentTimeMillis() - CameraCapture.MAX_AGE_MILLIS - 60_000));

        File fresh = CameraCapture.newTarget(context);

        assertTrue("a capture target is a jpg",
                fresh.getName().endsWith(".jpg") && fresh.getName().startsWith("photo-"));
        assertTrue("an old capture is swept", !stale.exists());
        assertEquals(CameraCaptureProvider.directory(context), fresh.getParentFile());
    }
}
