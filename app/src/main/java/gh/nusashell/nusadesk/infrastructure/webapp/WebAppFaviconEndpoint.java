package gh.nusashell.nusadesk.infrastructure.webapp;

import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;

/**
 * The conventional favicon URL of a registered web app — the fallback the
 * fetcher tries after the icons the app's own document declares (ADR-0048).
 *
 * <p>A registered app's icon can come from three places: the image the user
 * chose for it, an icon its document declares, or — when the document declares
 * none — the favicon at the conventional path. This one must not widen the
 * app's reach: the URL is not user input and not a stored field, it is the
 * origin {@link WebAppDefinition#getEndpointUrl()} already generates with the
 * one favicon path appended, so it is exactly
 * {@code http://127.0.0.1:<guestPort>/favicon.ico}. A record therefore cannot
 * turn this into a request to another host, scheme, port, or path.</p>
 *
 * <p>Pure: it builds a string and validates its input; it opens nothing.</p>
 */
public final class WebAppFaviconEndpoint {

    /**
     * The conventional favicon path, relative to the generated origin — which
     * already ends in {@code /}, so the full URL is exactly
     * {@code http://127.0.0.1:<guestPort>/favicon.ico}.
     */
    public static final String FAVICON_PATH = "favicon.ico";

    private WebAppFaviconEndpoint() {
    }

    /**
     * The conventional favicon URL of one registered app, derived from the same
     * generated origin the WebView is allowed to load.
     *
     * @param definition the registered app; its port is already validated by
     *                   {@code GuestPortPolicy}
     * @return exactly {@code http://127.0.0.1:<guestPort>/favicon.ico}
     * @throws IllegalArgumentException when no definition is given
     */
    public static String forDefinition(WebAppDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        return definition.getEndpointUrl() + FAVICON_PATH;
    }
}
