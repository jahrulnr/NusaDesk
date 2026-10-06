package gh.nusashell.nusadesk.infrastructure.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import java.util.Arrays;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import gh.nusashell.nusadesk.domain.webapp.WebAppUploadRequest;

/**
 * The intent a page's upload request turns into (ADR-0059). The action mapping
 * and the MIME filter mirror the WebView provider's own:
 * {@code ACTION_GET_CONTENT} for a read-only open (more providers answer it),
 * {@code ACTION_OPEN_DOCUMENT} when the page needs write access,
 * {@code ACTION_CREATE_DOCUMENT} for a save, and the tree picker for a folder.
 * The extension table is injected so the filter rules are asserted without
 * depending on the platform's static MIME map.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppUploadIntentsTest {

    private static final WebAppUploadRequest.MimeTypes TABLE = extension -> {
        if ("pdf".equals(extension)) {
            return "application/pdf";
        }
        if ("zip".equals(extension)) {
            return "application/zip";
        }
        return null;
    };

    @Test
    public void readOnlyOpenStaysGetContentBecauseMoreProvidersAnswerIt() {
        Intent intent = intent(WebAppUploadRequest.Mode.OPEN,
                WebAppUploadRequest.Permission.READ, new String[]{"image/*"}, null);

        assertEquals(Intent.ACTION_GET_CONTENT, intent.getAction());
        assertTrue(intent.getCategories().contains(Intent.CATEGORY_OPENABLE));
        assertEquals("image/*", intent.getType());
        assertNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
        assertEquals(Boolean.FALSE,
                intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test
    public void aWritableOpenAsksForADocumentAndForWriteAccess() {
        Intent intent = intent(WebAppUploadRequest.Mode.OPEN,
                WebAppUploadRequest.Permission.READ_WRITE, new String[]{"text/plain"}, null);

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.getAction());
        assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test
    public void aMultipleRequestAllowsMultipleSelection() {
        Intent intent = intent(WebAppUploadRequest.Mode.OPEN_MULTIPLE,
                WebAppUploadRequest.Permission.READ, new String[]{"image/*"}, null);

        assertEquals(Intent.ACTION_GET_CONTENT, intent.getAction());
        assertEquals(Boolean.TRUE,
                intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
    }

    @Test
    public void aSaveRequestCreatesADocumentAndCarriesTheNameHint() {
        Intent intent = intent(WebAppUploadRequest.Mode.SAVE,
                WebAppUploadRequest.Permission.READ_WRITE,
                new String[]{".pdf"}, "draft.pdf");

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
        assertEquals("application/pdf", intent.getType());
        assertEquals("draft.pdf", intent.getStringExtra(Intent.EXTRA_TITLE));
        assertEquals(Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test
    public void aFolderRequestUsesTheTreePickerWithNoFilter() {
        Intent intent = intent(WebAppUploadRequest.Mode.OPEN_FOLDER,
                WebAppUploadRequest.Permission.READ, new String[]{"image/*"}, null);

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.getAction());
        assertNull(intent.getType());
        assertNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                intent.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
    }

    @Test
    public void severalAcceptTypesBecomeTheExtraMimeTypesListUnderAnyType() {
        Intent intent = intent(WebAppUploadRequest.Mode.OPEN,
                WebAppUploadRequest.Permission.READ,
                new String[]{"image/*", ".pdf"}, null);

        assertEquals("the platform documents */* together with EXTRA_MIME_TYPES",
                WebAppUploadRequest.Filter.ANY_MIME_TYPE, intent.getType());
        assertEquals(Arrays.asList("image/*", "application/pdf"),
                Arrays.asList(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)));
    }

    @Test
    public void anUnknownExtensionAsksForAnyTypeInsteadOfFilteringWrong() {
        Intent intent = intent(WebAppUploadRequest.Mode.OPEN,
                WebAppUploadRequest.Permission.READ, new String[]{".nusadesk"}, null);

        assertEquals(WebAppUploadRequest.Filter.ANY_MIME_TYPE, intent.getType());
        assertNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
    }

    @Test
    public void aMissingRequestIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> WebAppUploadIntents.pickerIntent(null, TABLE));
    }

    private static Intent intent(
            WebAppUploadRequest.Mode mode,
            WebAppUploadRequest.Permission permission,
            String[] acceptTypes,
            String filenameHint) {
        WebAppUploadRequest request = WebAppUploadRequest.create(
                mode, permission, acceptTypes, false, filenameHint);
        return WebAppUploadIntents.pickerIntent(request, TABLE);
    }
}
