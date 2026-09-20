package gh.nusashell.nusadesk.infrastructure.webapp;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The icon URLs a web app's own HTML declares (ADR-0015, amended 2026-09-21).
 *
 * <p>The convention {@code /favicon.ico} is not what most apps actually publish:
 * the NusaShell tile this was measured against declares
 * {@code <link rel="icon" href="./nusashell-mark.png" type="image/png">} and
 * answers 404 for {@code /favicon.ico}, so the tile kept its monogram while a
 * perfectly good PNG sat one attribute away. This parser reads those
 * declarations; the fetch policy still decides what may be downloaded.</p>
 *
 * <p>Bounded and pure: a regular expression over {@code <link>} tags (a full
 * HTML parser would be a dependency for one attribute pair, and every failure
 * here degrades to "no declared icon", never to a wrong image), same-origin
 * resolution only — a declaration can never point the fetch at another host,
 * scheme, or port — and at most {@link #MAX_CANDIDATES} URLs, in the order the
 * document lists them. Scalable-vector candidates are skipped because the
 * platform decoder cannot rasterise them.</p>
 */
public final class FaviconLinkParser {

    /** How many declared candidates one fetch may try. */
    public static final int MAX_CANDIDATES = 3;

    /** {@code rel} values that name an icon, most specific first. */
    private static final List<String> ICON_RELS = Collections.unmodifiableList(Arrays.asList(
            "icon", "shortcut icon", "apple-touch-icon", "apple-touch-icon-precomposed"));

    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL_ATTRIBUTE =
            Pattern.compile("\\brel\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_ATTRIBUTE =
            Pattern.compile("\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);

    private FaviconLinkParser() {
    }

    /**
     * @param html      the app's own document; may be {@code null} or partial
     * @param originUrl the app's generated origin, e.g. {@code http://127.0.0.1:10994/}
     * @return same-origin, decodable icon URLs in declaration order; never null
     */
    public static List<String> iconUrls(String html, String originUrl) {
        if (html == null || html.isEmpty() || originUrl == null) {
            return Collections.emptyList();
        }
        URI origin;
        try {
            origin = new URI(originUrl);
        } catch (URISyntaxException malformed) {
            return Collections.emptyList();
        }
        List<String> urls = new ArrayList<>();
        Matcher tags = LINK_TAG.matcher(html);
        while (tags.find() && urls.size() < MAX_CANDIDATES) {
            String tag = tags.group();
            String rel = normalizeRel(attribute(REL_ATTRIBUTE, tag));
            if (rel == null || !ICON_RELS.contains(rel)) {
                continue;
            }
            String href = attribute(HREF_ATTRIBUTE, tag);
            if (href == null || href.isEmpty() || isVector(href)) {
                continue;
            }
            String resolved = sameOriginUrl(origin, href);
            if (resolved != null && !urls.contains(resolved)) {
                urls.add(resolved);
            }
        }
        return Collections.unmodifiableList(urls);
    }

    private static String attribute(Pattern pattern, String tag) {
        Matcher matcher = pattern.matcher(tag);
        if (!matcher.find()) {
            return null;
        }
        for (int group = 2; group <= 4; group++) {
            if (matcher.group(group) != null) {
                return matcher.group(group).trim();
            }
        }
        return null;
    }

    /** {@code rel} is a space-separated token list; compare it case-insensitively. */
    private static String normalizeRel(String rel) {
        if (rel == null) {
            return null;
        }
        return rel.toLowerCase(Locale.US).trim().replaceAll("\\s+", " ");
    }

    private static boolean isVector(String href) {
        String path = href.toLowerCase(Locale.US);
        return path.endsWith(".svg") || path.startsWith("data:");
    }

    /**
     * Resolves {@code href} against the origin and returns it only when it stays
     * on that origin: the same scheme, host, and port. Anything else — another
     * host, a bare scheme, a data URL — is refused rather than followed.
     */
    private static String sameOriginUrl(URI origin, String href) {
        try {
            URI resolved = origin.resolve(href);
            if (resolved.getScheme() == null || resolved.getHost() == null) {
                return null;
            }
            boolean sameScheme = resolved.getScheme().equalsIgnoreCase(origin.getScheme());
            boolean sameHost = resolved.getHost().equalsIgnoreCase(origin.getHost());
            boolean samePort = resolved.getPort() == origin.getPort();
            return sameScheme && sameHost && samePort ? resolved.toString() : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
