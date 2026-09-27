package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Pure encoding of one web app's sign-in pair into the opaque byte blob the
 * credential vault encrypts, and back.
 *
 * <p>Split out from the Android glue for one reason, the same reason
 * {@code HostKeyRecordCodec} and {@code SessionMetadataCodec} are split out:
 * a JVM unit test cannot open a Keystore, so the wire format — the part that
 * can silently corrupt a password and turn into a wrong-password loop — has to
 * be testable on its own.</p>
 *
 * <p>The format is {@code username + U+0000 + password}, UTF-8. {@code NUL} is
 * the separator because it is the one character a username may not contain
 * ({@link WebAppSignInCredential#normalizeUsername(String)} rejects control
 * characters), which makes the split unambiguous while leaving the password
 * completely opaque: a password may contain {@code NUL}, newlines, or
 * anything else, and it is reproduced exactly, because only the <em>first</em>
 * {@code NUL} is treated as the separator.</p>
 *
 * <p>No encryption happens here. This class only shapes bytes; the keystore
 * wrapper is {@link KeystoreWebAppCredentialStore}'s job.</p>
 */
public final class WebAppSignInCodec {

    private static final char SEPARATOR = '\0';

    private WebAppSignInCodec() {
    }

    /**
     * Encodes a pair for the vault.
     *
     * @param credential the pair to encode
     * @return the UTF-8 bytes to hand to the vault
     * @throws IllegalArgumentException when {@code credential} is null
     */
    public static byte[] encode(WebAppSignInCredential credential) {
        if (credential == null) {
            throw new IllegalArgumentException("credential must not be null");
        }
        String joined = credential.getUsername() + SEPARATOR + credential.getPassword();
        return joined.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes a pair from the vault.
     *
     * <p>Returns {@code null} rather than throwing for a blob that cannot be a
     * credential. That is a corrupt or foreign entry, and the honest answer
     * for it is "no credential is stored" — the same answer the user would get
     * for an app that never had one, which sends them to the sign-in card
     * instead of surfacing a decode error nobody can act on.</p>
     *
     * @param webAppId the app the blob belongs to
     * @param encoded  the decrypted blob
     * @return the pair, or {@code null} when the blob is not one
     * @throws IllegalArgumentException when {@code webAppId} is null
     */
    public static WebAppSignInCredential decode(WebAppId webAppId, byte[] encoded) {
        if (webAppId == null) {
            throw new IllegalArgumentException("webAppId must not be null");
        }
        if (encoded == null || encoded.length == 0) {
            return null;
        }
        String joined = new String(encoded, StandardCharsets.UTF_8);
        int separator = joined.indexOf(SEPARATOR);
        if (separator <= 0 || separator == joined.length() - 1) {
            return null;
        }
        try {
            return new WebAppSignInCredential(
                    webAppId,
                    joined.substring(0, separator),
                    joined.substring(separator + 1));
        } catch (IllegalArgumentException notACredential) {
            return null;
        }
    }

    /**
     * Wipes a decoded blob once it has been turned back into strings. The
     * vault returns a fresh array per read, so clearing it here does not
     * corrupt anything the caller still owns, and it keeps the plaintext from
     * sitting in a pool longer than the call needs it.
     */
    public static void wipe(byte[] encoded) {
        if (encoded != null) {
            Arrays.fill(encoded, (byte) 0);
        }
    }
}
