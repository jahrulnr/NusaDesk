package gh.nusashell.nusadesk.presentation.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins the packaged-asset MIME map the terminal page depends on. The math-art
 * backdrop broke silently once already if the SVG mapping is dropped — the
 * WebView interceptor would serve {@code math-art.svg} as
 * {@code application/octet-stream} and the page would lose its backdrop — so
 * the mapping is locked here in plain JUnit.
 */
public class TerminalBridgeViewMimeTypeTest {

    @Test
    public void mathArtSvgIsServedAsSvg() {
        assertEquals("image/svg+xml", TerminalBridgeView.mimeTypeFor("math-art.svg"));
        assertEquals("image/svg+xml",
                TerminalBridgeView.mimeTypeFor("MATH-ART.SVG"));
    }

    @Test
    public void pageAssetsKeepTheirMappings() {
        assertEquals("text/html", TerminalBridgeView.mimeTypeFor("terminal.html"));
        assertEquals("application/javascript", TerminalBridgeView.mimeTypeFor("xterm.js"));
        assertEquals("text/css", TerminalBridgeView.mimeTypeFor("xterm.css"));
        assertEquals("application/json", TerminalBridgeView.mimeTypeFor("data.json"));
        assertEquals("text/plain", TerminalBridgeView.mimeTypeFor("THIRD_PARTY_NOTICES.txt"));
    }

    @Test
    public void unknownAssetsAreServedOpaquely() {
        assertEquals("application/octet-stream",
                TerminalBridgeView.mimeTypeFor("no-extension"));
        assertEquals("application/octet-stream",
                TerminalBridgeView.mimeTypeFor("archive.bin"));
    }
}
