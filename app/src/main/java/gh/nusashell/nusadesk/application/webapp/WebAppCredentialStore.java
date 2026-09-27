package gh.nusashell.nusadesk.application.webapp;

import gh.nusashell.nusadesk.domain.webapp.WebAppId;
import gh.nusashell.nusadesk.domain.webapp.WebAppSignInCredential;

/**
 * Where one web app's HTTP sign-in pair is kept between launches.
 *
 * <p>The port is deliberately per app, not per host or per realm: a
 * registered web app owns exactly one loopback port, so the app's id already
 * names the only endpoint the pair may ever be sent to. That keeps the
 * blast radius of a stored secret equal to the single app the user saved it
 * for (ADR-0058).</p>
 *
 * <p>Implementations are the only place a secret may reach durable storage, and
 * they must not return plaintext through {@link #toString()}, a log, or an
 * exception message. Reads happen while a WebView is waiting on an auth
 * challenge, so a lookup must be safe to call from the thread that made the
 * request; the shipped implementation decrypts and returns, it never blocks on
 * user input.</p>
 */
public interface WebAppCredentialStore {

    /**
     * The stored pair for an app.
     *
     * @param webAppId the app to look up
     * @return the credential, or {@code null} when the app has none stored
     * @throws IllegalArgumentException when {@code webAppId} is null
     */
    WebAppSignInCredential find(WebAppId webAppId);

    /**
     * Encrypts and stores a pair, replacing any previous one for the same app.
     *
     * @param credential the pair to persist
     * @throws IllegalArgumentException when {@code credential} is null
     */
    void save(WebAppSignInCredential credential);

    /**
     * Forgets an app's pair. Removing an app from the launcher must not leave
     * its password behind.
     *
     * @param webAppId the app to forget
     * @throws IllegalArgumentException when {@code webAppId} is null
     */
    void clear(WebAppId webAppId);

}
