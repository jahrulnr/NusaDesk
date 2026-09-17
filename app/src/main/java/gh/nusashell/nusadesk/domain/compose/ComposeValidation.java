package gh.nusashell.nusadesk.domain.compose;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared validation helpers for the Compose-subset value objects.
 *
 * <p>Package-local on purpose. These rules exist so a validated name can be
 * embedded in generated container/unit identifiers without escaping, so a
 * guest path handed to the runtime cannot traverse out of its declared root,
 * and so an image reference or environment entry survives serialization into
 * argv/envp. They are not a general-purpose validation library.</p>
 */
final class ComposeValidation {
    private static final Pattern NAME_CHARSET = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern NAME_FIRST_CHAR = Pattern.compile("[A-Za-z0-9_]");
    private static final Pattern IMAGE_REF_CHARSET =
            Pattern.compile("[A-Za-z0-9._/:@-]+");
    private static final String IMAGE_DIGEST_PREFIX = "sha256:";
    private static final int IMAGE_DIGEST_HEX_LENGTH = 64;

    private ComposeValidation() {
    }

    /**
     * @return {@code value} verbatim (never trimmed — the caller decides what
     *         is an identifier and what is literal text)
     * @throws IllegalArgumentException when {@code value} is null or blank
     */
    static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /**
     * Validates an identifier-safe name: ASCII letters, digits, {@code .},
     * {@code _}, {@code -}, never beginning with {@code .} or {@code -}.
     * Surrounding whitespace is ignored; the trimmed text is returned.
     *
     * <p>The first-character rule keeps a name from looking like a hidden path
     * segment or a command-line flag when it is embedded in a generated
     * container or unit identifier.</p>
     *
     * @throws IllegalArgumentException when {@code value} is missing or unsafe
     */
    static String requireName(String value, String field) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (!NAME_CHARSET.matcher(text).matches()) {
            throw new IllegalArgumentException(field
                    + " may only contain ASCII letters, digits, '.', '_' and '-': " + text);
        }
        if (!NAME_FIRST_CHAR.matcher(text.substring(0, 1)).matches()) {
            throw new IllegalArgumentException(
                    field + " must not begin with '.' or '-': " + text);
        }
        return text;
    }

    /**
     * Validates a container image reference for safe serialization into argv
     * or envp: non-blank, no whitespace/control/NUL, and only the ASCII
     * punctuation an image reference can carry — letters, digits, {@code .},
     * {@code _}, {@code -}, {@code /}, {@code :}, {@code @}. Registry paths,
     * tags, and digests are all permitted ({@code nginx:alpine},
     * {@code ghcr.io/org/app:1.2}, {@code repo/app@sha256:<64 hex>}) because
     * this layer checks serialization safety, not curation — the reference is
     * never resolved, so no DNS or registry access happens here, and no image
     * is allowlisted at this layer.
     *
     * <p>Structural rules beyond the charset: never begins with {@code .},
     * {@code -}, or {@code /}; no {@code //} or {@code ..} path segment; no
     * empty path component; at most one {@code @}, which must be followed by
     * a {@code sha256:} digest of exactly 64 hex digits; and no empty
     * name/tag piece around a {@code :}. Surrounding whitespace is trimmed;
     * interior whitespace fails the charset.</p>
     *
     * @return the trimmed reference
     * @throws IllegalArgumentException when {@code value} is missing or unsafe
     */
    static String requireImageRef(String value, String field) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (!IMAGE_REF_CHARSET.matcher(text).matches()) {
            throw new IllegalArgumentException(field
                    + " may only contain ASCII letters, digits and './_-:@': " + text);
        }
        char first = text.charAt(0);
        if (first == '.' || first == '-' || first == '/') {
            throw new IllegalArgumentException(
                    field + " must not begin with '.', '-' or '/': " + text);
        }
        int at = text.indexOf('@');
        if (at >= 0 && text.indexOf('@', at + 1) >= 0) {
            throw new IllegalArgumentException(
                    field + " must not contain more than one '@': " + text);
        }
        String name = at >= 0 ? text.substring(0, at) : text;
        if (name.isEmpty()) {
            throw new IllegalArgumentException(field + " must name an image: " + text);
        }
        if (at >= 0) {
            requireImageDigest(text.substring(at + 1), field, text);
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException(
                        field + " must not contain an empty path component: " + text);
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        field + " must not contain '..' path segments: " + text);
            }
            int colon = segment.indexOf(':');
            if (colon == 0 || colon == segment.length() - 1
                    || (colon >= 0 && segment.indexOf(':', colon + 1) >= 0)) {
                throw new IllegalArgumentException(
                        field + " must not contain an empty name/tag piece: " + text);
            }
        }
        return text;
    }

    /**
     * Validates an environment key for {@code KEY=VALUE} serialization:
     * non-blank, NUL-free, and free of {@code '='}, which would corrupt the
     * key/value boundary of the serialized entry. The key is stored verbatim
     * (never trimmed).
     *
     * @throws IllegalArgumentException when {@code key} is blank, contains
     *                                  NUL, or contains {@code '='}
     */
    static String requireEnvironmentKey(String key, String field) {
        String text = requireNonBlank(key, field);
        requireNoNul(text, field);
        if (text.indexOf('=') >= 0) {
            throw new IllegalArgumentException(field + " must not contain '='");
        }
        return text;
    }

    private static void requireImageDigest(String digest, String field, String ref) {
        if (digest.startsWith(IMAGE_DIGEST_PREFIX)
                && digest.length() == IMAGE_DIGEST_PREFIX.length() + IMAGE_DIGEST_HEX_LENGTH
                && isHexDigits(digest.substring(IMAGE_DIGEST_PREFIX.length()))) {
            return;
        }
        throw new IllegalArgumentException(
                field + " digest must be 'sha256:' followed by 64 hex digits: " + ref);
    }

    private static boolean isHexDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates an absolute guest-side path: a leading {@code /}, no NUL, and
     * no {@code ..} path segment. The path is stored verbatim — it is never
     * canonicalised, so {@code //}, {@code .} segments, and trailing slashes
     * stay exactly as declared.
     *
     * @throws IllegalArgumentException when {@code value} is missing, relative,
     *                                  contains NUL, or traverses upward
     */
    static String requireAbsoluteGuestPath(String value, String field) {
        String text = requireNonBlank(value, field);
        requireNoNul(text, field);
        if (!text.startsWith("/")) {
            throw new IllegalArgumentException(
                    field + " must be an absolute guest path: " + text);
        }
        for (String segment : text.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        field + " must not contain '..' segments: " + text);
            }
        }
        return text;
    }

    /**
     * @throws IllegalArgumentException when {@code value} contains a NUL
     *         character, which can never survive an exec argv or envp entry
     */
    static void requireNoNul(String value, String field) {
        if (value != null && value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must not contain NUL");
        }
    }

    /**
     * @return an immutable copy of {@code values} in declared order
     * @throws IllegalArgumentException when {@code values} is null
     */
    static <T> List<T> immutableList(List<T> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * @return an immutable copy of {@code values} in declared order
     * @throws IllegalArgumentException when {@code values} is null
     */
    static <K, V> Map<K, V> immutableMap(Map<K, V> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
