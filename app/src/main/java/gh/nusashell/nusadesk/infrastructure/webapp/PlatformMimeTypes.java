package gh.nusashell.nusadesk.infrastructure.webapp;

import android.webkit.MimeTypeMap;

import gh.nusashell.nusadesk.domain.webapp.WebAppUploadRequest;

/**
 * The platform's own extension table, adapted to the one lookup the upload
 * domain asks for (ADR-0059).
 *
 * <p>Shared by the picker intent builder and the camera decision so both
 * resolve {@code accept=".jpg"} the same way: the domain stays Android-free,
 * and there is exactly one place that knows the table comes from
 * {@code android.webkit}.</p>
 */
public final class PlatformMimeTypes implements WebAppUploadRequest.MimeTypes {

    @Override
    public String forExtension(String extension) {
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
    }
}
