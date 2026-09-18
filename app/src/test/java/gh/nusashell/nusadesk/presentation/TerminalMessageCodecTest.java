package gh.nusashell.nusadesk.presentation;

import gh.nusashell.nusadesk.presentation.terminal.TerminalMessage;
import gh.nusashell.nusadesk.presentation.terminal.TerminalMessageCodec;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Android-free tests for the terminal bridge message codec. These exercise the
 * exact validation the native side applies to messages arriving from the trusted
 * xterm page over a {@code WebMessagePort}, including malformed/oversized input.
 */
public class TerminalMessageCodecTest {

    // ---- encode (host -> page) ----

    @Test
    public void encodesWriteCommand() {
        String json = TerminalMessageCodec.encode(
                TerminalMessage.text(TerminalMessage.Type.WRITE, "hello\n"));
        assertEquals("{\"t\":\"write\",\"d\":\"hello\\n\"}", json);
    }

    @Test
    public void encodesWriteStderrCommand() {
        String json = TerminalMessageCodec.encode(
                TerminalMessage.text(TerminalMessage.Type.WRITE_STDERR, "err"));
        assertEquals("{\"t\":\"writeStderr\",\"d\":\"err\"}", json);
    }

    @Test
    public void encodesSetSizeCommand() {
        String json = TerminalMessageCodec.encode(
                TerminalMessage.size(TerminalMessage.Type.SET_SIZE, 120, 40));
        assertEquals("{\"t\":\"setSize\",\"c\":120,\"r\":40}", json);
    }

    @Test
    public void encodesFitAndFocusSignals() {
        assertEquals("{\"t\":\"fit\"}",
                TerminalMessageCodec.encode(TerminalMessage.signal(TerminalMessage.Type.FIT)));
        assertEquals("{\"t\":\"focus\"}",
                TerminalMessageCodec.encode(TerminalMessage.signal(TerminalMessage.Type.FOCUS)));
    }

    @Test
    public void encodesScrollBottomCommand() {
        assertEquals("{\"t\":\"scrollBottom\"}",
                TerminalMessageCodec.encode(
                        TerminalMessage.signal(TerminalMessage.Type.SCROLL_BOTTOM)));
    }

    @Test
    public void encodesResetCommand() {
        // The Logs surface switches files on one terminal: the reset command
        // is what clears the previous file's output first.
        assertEquals("{\"t\":\"reset\"}",
                TerminalMessageCodec.encode(
                        TerminalMessage.signal(TerminalMessage.Type.RESET)));
    }

    @Test
    public void resetIsNotAcceptedFromThePage() {
        // Host-originated tags are never accepted back from the page.
        assertNull(TerminalMessageCodec.decode("{\"t\":\"reset\"}"));
    }

    @Test
    public void encodesFontSizeCommand() {
        // The Logs viewer renders denser than the shell; fontSize carries the
        // px value on the wire so the packaged page can refit the grid.
        assertEquals("{\"t\":\"fontSize\",\"d\":11}",
                TerminalMessageCodec.encode(
                        TerminalMessage.size(TerminalMessage.Type.FONT_SIZE, 11, 0)));
    }

    @Test
    public void fontSizeIsNotAcceptedFromThePage() {
        assertNull(TerminalMessageCodec.decode("{\"t\":\"fontSize\",\"d\":11}"));
    }

    @Test
    public void encodeEscapesQuotesBackslashesAndControlChars() {
        String json = TerminalMessageCodec.encode(
                TerminalMessage.text(TerminalMessage.Type.WRITE, "a\"b\\c\t\u0001"));
        assertEquals("{\"t\":\"write\",\"d\":\"a\\\"b\\\\c\\t\\u0001\"}", json);
    }

    @Test
    public void encodeRejectsPageOriginatedTypes() {
        try {
            TerminalMessageCodec.encode(TerminalMessage.signal(TerminalMessage.Type.READY));
            fail("expected rejection of page-originated type");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    // ---- decode (page -> host) ----

    @Test
    public void decodesReady() {
        TerminalMessage m = TerminalMessageCodec.decode("{\"t\":\"ready\"}");
        assertEquals(TerminalMessage.Type.READY, m.getType());
    }

    @Test
    public void decodesInput() {
        TerminalMessage m = TerminalMessageCodec.decode("{\"t\":\"input\",\"d\":\"ls -la\\n\"}");
        assertEquals(TerminalMessage.Type.INPUT, m.getType());
        assertEquals("ls -la\n", m.getData());
    }

    @Test
    public void decodesResize() {
        TerminalMessage m = TerminalMessageCodec.decode("{\"t\":\"resize\",\"c\":80,\"r\":24}");
        assertEquals(TerminalMessage.Type.RESIZE, m.getType());
        assertEquals(80, m.getCols());
        assertEquals(24, m.getRows());
    }

    @Test
    public void decodesScrollState() {
        TerminalMessage away = TerminalMessageCodec.decode("{\"t\":\"scrollState\",\"d\":1}");
        assertEquals(TerminalMessage.Type.SCROLL_STATE, away.getType());
        assertEquals(true, away.isScrolledBack());
        TerminalMessage live = TerminalMessageCodec.decode("{\"t\":\"scrollState\",\"d\":0}");
        assertEquals(TerminalMessage.Type.SCROLL_STATE, live.getType());
        assertEquals(false, live.isScrolledBack());
    }

    @Test
    public void decodeRejectsBadScrollState() {
        assertNull(TerminalMessageCodec.decode("{\"t\":\"scrollState\"}")); // no d
        assertNull(TerminalMessageCodec.decode("{\"t\":\"scrollState\",\"d\":\"1\"}")); // d not number
        assertNull(TerminalMessageCodec.decode("{\"t\":\"scrollState\",\"d\":2}")); // only 0/1 valid
    }

    @Test
    public void decodeRoundTripsEmbeddedQuotesAndEscapes() {
        String tricky = "he said \"hi\" \\ path\tnewline";
        String json = TerminalMessageCodec.encode(
                TerminalMessage.text(TerminalMessage.Type.WRITE, tricky));
        // Re-implement a page->host style decode by swapping the tag, then decode.
        String asInput = json.replace("\"write\"", "\"input\"");
        TerminalMessage m = TerminalMessageCodec.decode(asInput);
        assertEquals(TerminalMessage.Type.INPUT, m.getType());
        assertEquals(tricky, m.getData());
    }

    @Test
    public void decodeRejectsHostOriginatedTagsFromPage() {
        // A page must never send write/writeStderr/setSize/fit/focus.
        assertNull(TerminalMessageCodec.decode("{\"t\":\"write\",\"d\":\"x\"}"));
        assertNull(TerminalMessageCodec.decode("{\"t\":\"setSize\",\"c\":80,\"r\":24}"));
        assertNull(TerminalMessageCodec.decode("{\"t\":\"fit\"}"));
    }

    @Test
    public void decodeRejectsUnknownTag() {
        assertNull(TerminalMessageCodec.decode("{\"t\":\"bogus\"}"));
    }

    @Test
    public void decodeRejectsMissingOrWrongTypedFields() {
        assertNull(TerminalMessageCodec.decode("{\"t\":\"input\"}")); // no d
        assertNull(TerminalMessageCodec.decode("{\"t\":\"input\",\"d\":5}")); // d not string
        assertNull(TerminalMessageCodec.decode("{\"t\":\"resize\",\"c\":80}")); // no r
        assertNull(TerminalMessageCodec.decode("{\"t\":\"resize\",\"c\":\"80\",\"r\":24}")); // c not number
    }

    @Test
    public void decodeRejectsOutOfRangeSize() {
        assertNull(TerminalMessageCodec.decode("{\"t\":\"resize\",\"c\":0,\"r\":24}"));
        assertNull(TerminalMessageCodec.decode("{\"t\":\"resize\",\"c\":80,\"r\":5000}"));
    }

    @Test
    public void decodeRejectsMalformedJson() {
        assertNull(TerminalMessageCodec.decode(null));
        assertNull(TerminalMessageCodec.decode(""));
        assertNull(TerminalMessageCodec.decode("not json"));
        assertNull(TerminalMessageCodec.decode("{"));
        assertNull(TerminalMessageCodec.decode("{\"t\":"));
        assertNull(TerminalMessageCodec.decode("{\"t\":\"input\",\"d\":}"));
        assertNull(TerminalMessageCodec.decode("{\"t\":\"input\",\"d\":\"unterminated"));
    }

    @Test
    public void decodeRejectsUnescapedControlCharsInString() {
        assertNull(TerminalMessageCodec.decode("{\"t\":\"input\",\"d\":\"a\tb\"}"));
    }

    @Test
    public void decodeRejectsOversizedMessage() {
        StringBuilder sb = new StringBuilder(TerminalMessageCodec.MAX_MESSAGE_BYTES + 1);
        sb.append("{\"t\":\"input\",\"d\":\"");
        for (int i = 0; i < TerminalMessageCodec.MAX_MESSAGE_BYTES; i++) {
            sb.append('a');
        }
        sb.append("\"}");
        assertNull(TerminalMessageCodec.decode(sb.toString()));
    }

    @Test
    public void decodeHandlesUnicodeEscape() {
        TerminalMessage m = TerminalMessageCodec.decode("{\"t\":\"input\",\"d\":\"\\u00e9\"}");
        assertEquals("é", m.getData());
    }
}
