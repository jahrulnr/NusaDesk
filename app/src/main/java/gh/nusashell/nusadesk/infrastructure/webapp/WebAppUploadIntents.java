package gh.nusashell.nusadesk.infrastructure.webapp;

import android.content.Intent;
import android.webkit.MimeTypeMap;

import gh.nusashell.nusadesk.domain.webapp.WebAppUploadRequest;

/**
 * Builds the platform document-picker intent for one {@link WebAppUploadRequest}
 * (ADR-0059).
 *
 * <p>The action mapping mirrors the WebView provider's own
 * ({@code AwContentsClient.FileChooserParamsImpl.createIntent}): a folder request
 * is the tree picker with no filter, a save request is
 * {@code ACTION_CREATE_DOCUMENT}, a writable open is
 * {@code ACTION_OPEN_DOCUMENT} (it can return a writable grant), and a read-only
 * open stays {@code ACTION_GET_CONTENT} because more providers answer it. The
 * MIME filter follows the same shape: one type is set as the intent type, and
 * several types are passed whole as {@code EXTRA_MIME_TYPES}.</p>
 *
 * <p>Every URI this picker returns is a {@code content://} URI owned by the
 * document provider the user chose — never a path this app invents.</p>
 */
public final class WebAppUploadIntents {

    private WebAppUploadIntents() {
    }

    /**
     * @param request the page's request
     * @return an intent for {@code startActivityForResult}
     * @throws IllegalArgumentException when {@code request} is null
     */
    public static Intent pickerIntent(WebAppUploadRequest request) {
        return pickerIntent(request, new PlatformMimeTypes());
    }

    /**
     * Testable form: the extension table is supplied by the caller, so the
     * filter rules can be asserted without the platform's static table.
     */
    static Intent pickerIntent(
            WebAppUploadRequest request, WebAppUploadRequest.MimeTypes mimeTypes) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getMode() == WebAppUploadRequest.Mode.OPEN_FOLDER) {
            // A folder has no MIME type, and the tree picker takes no type or
            // extra: it returns the folder the user marked as "use this folder".
            return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        boolean writable = request.getPermission() == WebAppUploadRequest.Permission.READ_WRITE
                || request.getMode() == WebAppUploadRequest.Mode.SAVE;
        Intent intent;
        if (request.getMode() == WebAppUploadRequest.Mode.SAVE) {
            intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        } else if (request.getPermission() == WebAppUploadRequest.Permission.READ_WRITE) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (request.getMode() == WebAppUploadRequest.Mode.OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        WebAppUploadRequest.Filter filter = request.filter(mimeTypes);
        intent.setType(filter.getType());
        if (!filter.getExtraMimeTypes().isEmpty()) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    filter.getExtraMimeTypes().toArray(new String[0]));
        }
        if (request.getMode() == WebAppUploadRequest.Mode.SAVE
                && request.getFilenameHint() != null) {
            intent.putExtra(Intent.EXTRA_TITLE, request.getFilenameHint());
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (writable) {
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        return intent;
    }
}
