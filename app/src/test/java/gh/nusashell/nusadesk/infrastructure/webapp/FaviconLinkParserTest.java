package gh.nusashell.nusadesk.infrastructure.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * Icon discovery from an app's own document (ADR-0015, amended 2026-09-21).
 *
 * <p>Measured against the real NusaShell tile: its page declares
 * {@code <link rel="icon" href="./nusashell-mark.png" type="image/png">} and
 * answers 404 for {@code /favicon.ico}, so the fetcher found nothing while a
 * valid 512×512 PNG sat one attribute away.</p>
 */
public class FaviconLinkParserTest {

    private static final String ORIGIN = "http://127.0.0.1:10994/";

    @Test
    public void aRelativeDeclarationResolvesAgainstTheAppsOwnOrigin() {
        List<String> urls = FaviconLinkParser.iconUrls(
                "<head><link rel=\"icon\" href=\"./nusashell-mark.png\" type=\"image/png\"></head>",
                ORIGIN);

        assertEquals(1, urls.size());
        assertEquals("http://127.0.0.1:10994/nusashell-mark.png", urls.get(0));
    }

    @Test
    public void theDeclarationOrderIsKeptAndDuplicatesDropped() {
        List<String> urls = FaviconLinkParser.iconUrls(
                "<link rel=\"apple-touch-icon\" href=\"/apple.png\">"
                        + "<link rel=\"icon\" href=\"/icon.png\">"
                        + "<link rel='icon' href='/icon.png'>",
                ORIGIN);

        assertEquals("[http://127.0.0.1:10994/apple.png, http://127.0.0.1:10994/icon.png]",
                urls.toString());
    }

    @Test
    public void attributeOrderQuotingAndCaseAreAllHandled() {
        List<String> urls = FaviconLinkParser.iconUrls(
                "<LINK HREF=\"/a.png\" REL=\"Icon\">"
                        + "<link href=/b.png rel='shortcut icon'>",
                ORIGIN);

        assertEquals("[http://127.0.0.1:10994/a.png, http://127.0.0.1:10994/b.png]",
                urls.toString());
    }

    @Test
    public void onlySameOriginDeclarationsSurvive() {
        List<String> urls = FaviconLinkParser.iconUrls(
                "<link rel=\"icon\" href=\"http://example.com/evil.png\">"
                        + "<link rel=\"icon\" href=\"https://127.0.0.1:10994/tls.png\">"
                        + "<link rel=\"icon\" href=\"//127.0.0.1:1/other-port.png\">"
                        + "<link rel=\"icon\" href=\"/good.png\">",
                ORIGIN);

        assertEquals("only the app's own scheme, host, and port are kept",
                "[http://127.0.0.1:10994/good.png]", urls.toString());
    }

    @Test
    public void vectorAndInlineCandidatesAreSkippedBecauseTheDecoderCannotReadThem() {
        List<String> urls = FaviconLinkParser.iconUrls(
                "<link rel=\"icon\" href=\"/mark.svg\" type=\"image/svg+xml\">"
                        + "<link rel=\"mask-icon\" href=\"/mask.svg\">"
                        + "<link rel=\"icon\" href=\"data:image/png;base64,AAAA\">"
                        + "<link rel=\"icon\" href=\"/real.png\">",
                ORIGIN);

        assertEquals("[http://127.0.0.1:10994/real.png]", urls.toString());
    }

    @Test
    public void nonIconLinksAndEmptyInputsYieldNothing() {
        assertTrue(FaviconLinkParser.iconUrls(
                "<link rel=\"manifest\" href=\"/manifest.webmanifest\">"
                        + "<link rel=\"stylesheet\" href=\"/app.css\">"
                        + "<link rel=\"icon\">",
                ORIGIN).isEmpty());
        assertTrue(FaviconLinkParser.iconUrls(null, ORIGIN).isEmpty());
        assertTrue(FaviconLinkParser.iconUrls("<link rel=\"icon\" href=\"/x.png\">", null).isEmpty());
    }

    @Test
    public void atMostThreeCandidatesAreOffered() {
        List<String> urls = FaviconLinkParser.iconUrls(
                "<link rel=\"icon\" href=\"/1.png\"><link rel=\"icon\" href=\"/2.png\">"
                        + "<link rel=\"icon\" href=\"/3.png\"><link rel=\"icon\" href=\"/4.png\">",
                ORIGIN);

        assertEquals("one fetch must stay bounded", FaviconLinkParser.MAX_CANDIDATES, urls.size());
        assertEquals("http://127.0.0.1:10994/3.png", urls.get(urls.size() - 1));
    }
}
