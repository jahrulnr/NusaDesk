package gh.nusashell.nusadesk.infrastructure.webapp;

import android.content.Context;

import gh.nusashell.nusadesk.application.webapp.WebAppCredentialStore;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;
import gh.nusashell.nusadesk.infrastructure.session.CredentialType;
import gh.nusashell.nusadesk.infrastructure.session.CryptoException;
import gh.nusashell.nusadesk.infrastructure.session.KeystoreCredentialVault;

/**
 * {@link WebAppCredentialStore} backed by the existing
 * {@link KeystoreCredentialVault} (ADR-0058).
 *
 * <p>This is the only place a user's web-app password reaches durable storage.
 * The pair is shaped into bytes by the pure {@link WebAppSignInCodec}, handed
 * to the vault, and the plaintext buffer is wiped before the call returns, so
 * only the AES-256-GCM-encrypted blob is ever written — under the credential id
 * {@code webapp.<webAppId>} inside the vault's own app-private store. No key,
 * cipher, or storage location is added here: the vault already keeps SSH
 * secrets under a non-exportable {@code AndroidKeyStore} key, and a web-app
 * sign-in pair is the same kind of secret.</p>
 *
 * <p>A keystore or persistence failure is deliberately never propagated. A
 * sign-in that cannot be remembered is still a valid sign-in, and a store that
 * cannot read or write is indistinguishable from one that holds nothing — so
 * the caller always sees the honest answer "no stored credential", which sends
 * the user back to the sign-in card on the next open instead of crashing the
 * WebView thread or surfacing an error nobody can act on. {@link CryptoException}
 * covers a keystore that is unusable, and the vault's
 * {@code IllegalStateException} covers a commit that did not land; neither can
 * carry a secret, because both keep their messages generic. For the same
 * reason {@link #find(WebAppId)} reports a stored but undecodable blob as
 * absent, matching the codec's fail-closed contract.</p>
 */
public final class KeystoreWebAppCredentialStore implements WebAppCredentialStore {

    private static final String CREDENTIAL_ID_PREFIX = "webapp.";

    private final KeystoreCredentialVault vault;

    /**
     * @param vault the keystore vault that holds the encrypted blob
     * @throws IllegalArgumentException when {@code vault} is null
     */
    public KeystoreWebAppCredentialStore(KeystoreCredentialVault vault) {
        if (vault == null) {
            throw new IllegalArgumentException("vault must not be null");
        }
        this.vault = vault;
    }

    /**
     * App-private factory: the vault's SharedPreferences file and its
     * AndroidKeyStore key live in app-private storage. Named after the existing
     * convention in {@code FileSshServerHostKeyStore.inAppStorage(Context)}.
     */
    public static KeystoreWebAppCredentialStore inAppStorage(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return new KeystoreWebAppCredentialStore(new KeystoreCredentialVault(context));
    }

    @Override
    public WebAppSignInCredential find(WebAppId webAppId) {
        String credentialId = credentialId(webAppId);
        byte[] plaintext;
        try {
            plaintext = vault.load(credentialId);
        } catch (CryptoException | IllegalStateException storeUnavailable) {
            // An unreadable store reads as an empty one: the user is asked to
            // sign in again rather than handed a keystore failure.
            return null;
        }
        if (plaintext == null) {
            return null;
        }
        try {
            return WebAppSignInCodec.decode(webAppId, plaintext);
        } finally {
            WebAppSignInCodec.wipe(plaintext);
        }
    }

    @Override
    public void save(WebAppSignInCredential credential) {
        if (credential == null) {
            throw new IllegalArgumentException("credential must not be null");
        }
        byte[] plaintext = WebAppSignInCodec.encode(credential);
        try {
            vault.store(credentialId(credential.getWebAppId()),
                    CredentialType.PASSWORD, plaintext);
        } catch (CryptoException | IllegalStateException storageUnavailable) {
            // The pair is simply not remembered: the current sign-in already
            // answered the challenge, and the next find()/has() reports none.
        } finally {
            WebAppSignInCodec.wipe(plaintext);
        }
    }

    @Override
    public void clear(WebAppId webAppId) {
        vault.delete(credentialId(webAppId));
    }

    /**
     * The vault key for one app's pair: {@code webapp.<webAppId>}.
     * Package-private so the unit test pins the exact on-disk key the
     * {@code WebAppCredentialStore} contract promises.
     */
    static String credentialId(WebAppId webAppId) {
        if (webAppId == null) {
            throw new IllegalArgumentException("webAppId must not be null");
        }
        return CREDENTIAL_ID_PREFIX + webAppId.value();
    }
}
