package gh.nusashell.nusadesk.domain.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * The pure rules of one upload request (ADR-0059): what is normalized, what is
 * bounded, and how an {@code accept} attribute becomes the MIME filter a picker
 * can actually apply.
 */
public class WebAppUploadRequestTest {

    /** Extension table stub: only the extensions a case cares about resolve. */
    private static final WebAppUploadRequest.MimeTypes TABLE = extension -> {
        if ("pdf".equals(extension)) {
            return "application/pdf";
        }
        if ("zip".equals(extension)) {
            return "application/zip";
        }
        if ("jpg".equals(extension)) {
            return "image/jpeg";
        }
        if ("docx".equals(extension)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return null;
    };

    @Test
    public void createRejectsMissingModeOrPermission() {
        assertThrows(IllegalArgumentException.class, () -> WebAppUploadRequest.create(
                null, WebAppUploadRequest.Permission.READ, new String[0], false, null));
        assertThrows(IllegalArgumentException.class, () -> WebAppUploadRequest.create(
                WebAppUploadRequest.Mode.OPEN, null, new String[0], false, null));
    }

    @Test
    public void acceptEntriesAreTrimmedDeduplicatedAndBounded() {
        String[] raw = {" image/* ", "", null, "IMAGE/*", "application/pdf"};
        WebAppUploadRequest request = request(WebAppUploadRequest.Mode.OPEN, raw);

        assertEquals(Arrays.asList("image/*", "application/pdf"),
                request.getAcceptTypes());

        String[] many = new String[WebAppUploadRequest.MAX_ACCEPT_TYPES + 5];
        for (int index = 0; index < many.length; index++) {
            many[index] = "type/" + index;
        }
        assertEquals(WebAppUploadRequest.MAX_ACCEPT_TYPES,
                request(WebAppUploadRequest.Mode.OPEN, many).getAcceptTypes().size());
    }

    @Test
    public void filenameHintIsTrimmedAndABlankOrOverlongOneIsAbsent() {
        assertEquals("notes.txt", request(WebAppUploadRequest.Mode.SAVE,
                new String[0], "  notes.txt  ").getFilenameHint());
        assertNull(request(WebAppUploadRequest.Mode.SAVE,
                new String[0], "   ").getFilenameHint());
        assertNull(request(WebAppUploadRequest.Mode.SAVE,
                new String[0], "x".repeat(WebAppUploadRequest.MAX_FILENAME_HINT_LENGTH + 1))
                .getFilenameHint());
        assertNull(request(WebAppUploadRequest.Mode.OPEN, new String[0], null)
                .getFilenameHint());
    }

    @Test
    public void anEmptyOrWildcardAcceptAllowsAnyMimeType() {
        assertTrue(request(WebAppUploadRequest.Mode.OPEN, new String[0]).allowsAnyMimeType());
        assertTrue(request(WebAppUploadRequest.Mode.OPEN, new String[]{" */* "})
                .allowsAnyMimeType());
        assertFalse(request(WebAppUploadRequest.Mode.OPEN, new String[]{"image/*"})
                .allowsAnyMimeType());
    }

    @Test
    public void oneLiteralMimeTypeIsTheWholeFilter() {
        WebAppUploadRequest.Filter filter =
                request(WebAppUploadRequest.Mode.OPEN, new String[]{"image/*"}).filter(TABLE);

        assertEquals("image/*", filter.getType());
        assertTrue(filter.getExtraMimeTypes().isEmpty());
    }

    @Test
    public void severalTypesAskForAnyTypeAndPassThemAllAsExtras() {
        WebAppUploadRequest.Filter filter =
                request(WebAppUploadRequest.Mode.OPEN_MULTIPLE,
                        new String[]{"image/*", ".pdf"}).filter(TABLE);

        assertEquals("the documented combination is */* plus the extra list, so a "
                        + "picker that intersects the two cannot hide an accepted type",
                WebAppUploadRequest.Filter.ANY_MIME_TYPE, filter.getType());
        assertEquals(Arrays.asList("image/*", "application/pdf"),
                filter.getExtraMimeTypes());
    }

    @Test
    public void anOverlongAcceptEntryIsDroppedInsteadOfShippedToThePicker() {
        String junk = "mime/" + "x".repeat(WebAppUploadRequest.MAX_ACCEPT_ENTRY_LENGTH + 1);
        WebAppUploadRequest request = request(WebAppUploadRequest.Mode.OPEN,
                new String[]{junk, "image/*"});

        assertEquals(java.util.Collections.singletonList("image/*"),
                request.getAcceptTypes());
    }

    @Test
    public void anExtensionResolvesThroughThePlatformTable() {
        WebAppUploadRequest.Filter filter =
                request(WebAppUploadRequest.Mode.OPEN, new String[]{".PDF"}).filter(TABLE);

        assertEquals("application/pdf", filter.getType());
        assertTrue(filter.getExtraMimeTypes().isEmpty());
    }

    @Test
    public void anUnknownExtensionFallsBackToAnyTypeInsteadOfFilteringWrong() {
        WebAppUploadRequest.Filter filter =
                request(WebAppUploadRequest.Mode.OPEN, new String[]{".nusadesk"}).filter(TABLE);

        assertEquals(WebAppUploadRequest.Filter.ANY_MIME_TYPE, filter.getType());
        assertTrue(filter.getExtraMimeTypes().isEmpty());
    }

    @Test
    public void anUnknownExtensionNextToAKnownOneIsDroppedNotGuessed() {
        WebAppUploadRequest.Filter filter =
                request(WebAppUploadRequest.Mode.OPEN,
                        new String[]{".nusadesk", "image/*"}).filter(TABLE);

        assertEquals("image/*", filter.getType());
        assertTrue(filter.getExtraMimeTypes().isEmpty());
    }

    @Test
    public void duplicateTypesDoNotGrowTheExtraList() {
        WebAppUploadRequest.Filter filter =
                request(WebAppUploadRequest.Mode.OPEN,
                        new String[]{".pdf", "APPLICATION/PDF"}).filter(TABLE);

        assertEquals("application/pdf", filter.getType());
        assertTrue(filter.getExtraMimeTypes().isEmpty());
    }

    @Test
    public void filterRejectsAMissingTable() {
        assertThrows(IllegalArgumentException.class,
                () -> request(WebAppUploadRequest.Mode.OPEN, new String[0]).filter(null));
    }

    @Test
    public void emptyAcceptMeansTheFilterAcceptsAnything() {
        WebAppUploadRequest.Filter filter =
                request(WebAppUploadRequest.Mode.OPEN, new String[0]).filter(TABLE);

        assertEquals(WebAppUploadRequest.Filter.ANY_MIME_TYPE, filter.getType());
        assertTrue(filter.getExtraMimeTypes().isEmpty());
    }

    @Test
    public void separateRequestsDoNotShareAcceptState() {
        List<String> first = request(WebAppUploadRequest.Mode.OPEN,
                new String[]{"image/*"}).getAcceptTypes();
        List<String> second = request(WebAppUploadRequest.Mode.OPEN,
                new String[]{"application/pdf"}).getAcceptTypes();

        assertEquals(Collections.singletonList("image/*"), first);
        assertEquals(Collections.singletonList("application/pdf"), second);
    }

    @Test
    public void aLiveCaptureIsOfferedOnlyWhereItCanSatisfyTheAccept() {
        WebAppUploadRequest unconstrained =
                request(WebAppUploadRequest.Mode.OPEN, new String[0]);
        WebAppUploadRequest image =
                request(WebAppUploadRequest.Mode.OPEN, new String[]{"image/*"});
        WebAppUploadRequest imageByExtension =
                request(WebAppUploadRequest.Mode.OPEN, new String[]{".jpg"});
        WebAppUploadRequest both =
                request(WebAppUploadRequest.Mode.OPEN, new String[]{"image/*", "video/*"});
        WebAppUploadRequest document =
                request(WebAppUploadRequest.Mode.OPEN, new String[]{"application/pdf", ".docx"});
        WebAppUploadRequest unknownExtension =
                request(WebAppUploadRequest.Mode.OPEN, new String[]{".nusadesk"});

        assertTrue(unconstrained.allowsStillImage(TABLE));
        assertTrue(image.allowsStillImage(TABLE));
        assertTrue("an image extension resolves to an image type",
                imageByExtension.allowsStillImage(TABLE));
        assertTrue(both.allowsStillImage(TABLE));
        assertFalse("a document input has no use for a camera",
                document.allowsStillImage(TABLE));
        assertTrue("an unresolvable accept leaves the picker open to anything",
                unknownExtension.allowsStillImage(TABLE));
    }

    private static WebAppUploadRequest request(
            WebAppUploadRequest.Mode mode, String[] acceptTypes) {
        return WebAppUploadRequest.create(
                mode, WebAppUploadRequest.Permission.READ, acceptTypes, false, null);
    }

    private static WebAppUploadRequest request(
            WebAppUploadRequest.Mode mode, String[] acceptTypes, String filenameHint) {
        return WebAppUploadRequest.create(
                mode, WebAppUploadRequest.Permission.READ, acceptTypes, false, filenameHint);
    }
}
