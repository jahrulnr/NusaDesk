package gh.nusashell.nusadesk.domain.webapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * One file request a hosted page made through the WebView's file chooser,
 * reduced to the platform-free decisions a picker needs (ADR-0059).
 *
 * <p>A page asks for a file in four shapes: open one, open many, open a folder,
 * or save a new one; since the 2026 platform release the File System Access API
 * ({@code showOpenFilePicker}/{@code showSaveFilePicker}/{@code showDirectoryPicker})
 * arrives through the same callback, and a writable request is expressed as a
 * permission mode rather than a separate mode. This object carries those
 * decisions — never a URL, a path, or a credential — so the picker layer can
 * stay an adapter.</p>
 *
 * <p>The {@code accept} attribute is page-controlled, so it is bounded and
 * normalized here (trimmed, de-duplicated, at most {@link #MAX_ACCEPT_TYPES}
 * entries) and reduced to a MIME filter through an injected
 * {@link MimeTypes} table: the platform's own extension table lives in
 * {@code android.webkit}, which this layer must not import.</p>
 *
 * <p>Immutable: every field is validated on construction.</p>
 */
public final class WebAppUploadRequest {

    /** What the page wants the picker to return. */
    public enum Mode {
        /** One existing file. */
        OPEN,
        /** One or more existing files. */
        OPEN_MULTIPLE,
        /** One existing folder. */
        OPEN_FOLDER,
        /** A file that may not exist yet, to be written by the page. */
        SAVE
    }

    /** How the page intends to use the returned URI. */
    public enum Permission {
        /** Read only: the page uploads the file. */
        READ,
        /** Read and write: the page edits the file through the returned URI. */
        READ_WRITE
    }

    /**
     * Extension-to-MIME lookup used to translate {@code accept=".pdf"} entries.
     *
     * <p>Supplied by the platform's own table in production; injected so the
     * filter rules stay pure and testable on the JVM.</p>
     */
    public interface MimeTypes {
        /**
         * @param extension lower-case extension without the leading dot
         * @return the MIME type for that extension, or {@code null} when unknown
         */
        String forExtension(String extension);
    }

    /** The MIME filter a picker should apply for one request. */
    public static final class Filter {
        /** Filter that accepts every MIME type. */
        public static final String ANY_MIME_TYPE = "*/*";

        private final String type;
        private final List<String> extraMimeTypes;

        private Filter(String type, List<String> extraMimeTypes) {
            this.type = type;
            this.extraMimeTypes = extraMimeTypes;
        }

        /** The MIME type to set on the picker intent; never null. */
        public String getType() {
            return type;
        }

        /**
         * The full MIME type list when more than one type is acceptable, else
         * empty. A picker applies it as {@code Intent.EXTRA_MIME_TYPES}.
         */
        public List<String> getExtraMimeTypes() {
            return extraMimeTypes;
        }
    }

    /** Upper bound of {@code accept} entries carried into a picker intent. */
    public static final int MAX_ACCEPT_TYPES = 16;
    /** Upper bound of one {@code accept} entry; a longer one is dropped. */
    public static final int MAX_ACCEPT_ENTRY_LENGTH = 128;
    /** Upper bound of a filename hint; a longer one is treated as absent. */
    public static final int MAX_FILENAME_HINT_LENGTH = 128;

    private final Mode mode;
    private final Permission permission;
    private final List<String> acceptTypes;
    private final boolean captureEnabled;
    private final String filenameHint;

    private WebAppUploadRequest(
            Mode mode,
            Permission permission,
            List<String> acceptTypes,
            boolean captureEnabled,
            String filenameHint) {
        this.mode = mode;
        this.permission = permission;
        this.acceptTypes = acceptTypes;
        this.captureEnabled = captureEnabled;
        this.filenameHint = filenameHint;
    }

    /**
     * @param mode           what the page asked for; never null
     * @param permission     how the page will use the result; never null
     * @param acceptTypes    raw {@code accept} entries, or null/empty for "any"
     * @param captureEnabled whether the input carried the {@code capture}
     *                       attribute (recorded, not yet honored: this product
     *                       has no camera picker, so a capture request opens the
     *                       ordinary file picker)
     * @param filenameHint   suggested name for {@link Mode#SAVE}, or null
     * @throws IllegalArgumentException when a required argument is missing
     */
    public static WebAppUploadRequest create(
            Mode mode,
            Permission permission,
            String[] acceptTypes,
            boolean captureEnabled,
            String filenameHint) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (permission == null) {
            throw new IllegalArgumentException("permission must not be null");
        }
        return new WebAppUploadRequest(mode, permission,
                normalizeAcceptTypes(acceptTypes), captureEnabled,
                normalizeFilenameHint(filenameHint));
    }

    /**
     * Trims and de-duplicates the accept entries (case-insensitively, first
     * spelling wins) and keeps at most {@link #MAX_ACCEPT_TYPES} of them, so a
     * page cannot inflate the picker intent without bound. An entry longer than
     * {@link #MAX_ACCEPT_ENTRY_LENGTH} is page-controlled junk (no MIME type or
     * extension is that long) and is dropped rather than shipped into a binder
     * transaction.
     */
    static List<String> normalizeAcceptTypes(String[] acceptTypes) {
        if (acceptTypes == null || acceptTypes.length == 0) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String entry : acceptTypes) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || trimmed.length() > MAX_ACCEPT_ENTRY_LENGTH) {
                continue;
            }
            boolean seen = false;
            for (String kept : unique) {
                if (kept.equalsIgnoreCase(trimmed)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                unique.add(trimmed);
            }
            if (unique.size() >= MAX_ACCEPT_TYPES) {
                break;
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    private static String normalizeFilenameHint(String filenameHint) {
        if (filenameHint == null) {
            return null;
        }
        String trimmed = filenameHint.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_FILENAME_HINT_LENGTH) {
            return null;
        }
        return trimmed;
    }

    public Mode getMode() {
        return mode;
    }

    public Permission getPermission() {
        return permission;
    }

    /** The normalized {@code accept} entries; empty means "any type". */
    public List<String> getAcceptTypes() {
        return acceptTypes;
    }

    public boolean isCaptureEnabled() {
        return captureEnabled;
    }

    /** The suggested name for a save request, or {@code null}. */
    public String getFilenameHint() {
        return filenameHint;
    }

    /** Whether the page placed no constraint on the MIME type. */
    public boolean allowsAnyMimeType() {
        if (acceptTypes.isEmpty()) {
            return true;
        }
        for (String entry : acceptTypes) {
            if (Filter.ANY_MIME_TYPE.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reduces the accept entries to the MIME filter a picker should apply.
     *
     * <p>A concrete MIME type is kept as-is, an extension is resolved through
     * the platform's table, an unresolvable extension is dropped, and a request
     * with nothing resolvable accepts any type — the honest outcome, since no
     * picker API can filter by an unknown extension.</p>
     *
     * <p>When more than one type survives, the intent type stays
     * {@code *&#47;*} and the whole list travels as the extra MIME types: that
     * is the combination the platform documents ("if multiple disjoint MIME
     * types are acceptable, define them in {@code EXTRA_MIME_TYPES} and
     * {@code setType()} to {@code *&#47;*}"), and a picker that intersects the
     * two would otherwise hide a type the page accepts.</p>
     *
     * @throws IllegalArgumentException when {@code mimeTypes} is null
     */
    public Filter filter(MimeTypes mimeTypes) {
        if (mimeTypes == null) {
            throw new IllegalArgumentException("mimeTypes must not be null");
        }
        if (allowsAnyMimeType()) {
            return new Filter(Filter.ANY_MIME_TYPE, Collections.emptyList());
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String entry : acceptTypes) {
            if (entry.indexOf('/') >= 0) {
                resolved.add(entry.toLowerCase(Locale.ROOT));
                continue;
            }
            if (entry.startsWith(".") && entry.length() > 1) {
                String mimeType =
                        mimeTypes.forExtension(entry.substring(1).toLowerCase(Locale.ROOT));
                if (mimeType != null && !mimeType.trim().isEmpty()) {
                    resolved.add(mimeType.trim());
                }
            }
        }
        if (resolved.isEmpty()) {
            return new Filter(Filter.ANY_MIME_TYPE, Collections.emptyList());
        }
        List<String> types = Collections.unmodifiableList(new ArrayList<>(resolved));
        if (types.size() == 1) {
            return new Filter(types.get(0), Collections.emptyList());
        }
        return new Filter(Filter.ANY_MIME_TYPE, types);
    }

    /**
     * Whether a still photo could satisfy this request, i.e. whether the camera
     * belongs among the sources offered.
     *
     * @throws IllegalArgumentException when {@code mimeTypes} is null
     */
    public boolean allowsStillImage(MimeTypes mimeTypes) {
        return allowsCategory(mimeTypes, "image/");
    }

    /**
     * Whether the request can be satisfied by a live capture of one category.
     *
     * <p>An unconstrained or wildcard request says yes; a request whose
     * resolved types include that category says yes; a request that asks only
     * for documents says no — a PDF input has no use for a camera. An accept
     * entry that resolves to nothing leaves the picker offering everything, so
     * a live capture is offered there too.</p>
     *
     * <p>When several types survive they are the constraint (the filter's type
     * is the documented {@code *&#47;*} placeholder), so the extras are what is
     * checked.</p>
     */
    private boolean allowsCategory(MimeTypes mimeTypes, String category) {
        Filter filter = filter(mimeTypes);
        if (filter.getExtraMimeTypes().isEmpty()) {
            return Filter.ANY_MIME_TYPE.equals(filter.getType())
                    || filter.getType().toLowerCase(Locale.ROOT).startsWith(category);
        }
        for (String type : filter.getExtraMimeTypes()) {
            if (type.toLowerCase(Locale.ROOT).startsWith(category)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "WebAppUploadRequest{mode=" + mode + ", permission=" + permission
                + ", accept=" + acceptTypes + ", capture=" + captureEnabled
                + ", hint=" + filenameHint + "}";
    }
}
