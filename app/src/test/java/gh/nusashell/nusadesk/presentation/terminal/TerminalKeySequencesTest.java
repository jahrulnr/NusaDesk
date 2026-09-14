package gh.nusashell.nusadesk.presentation.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The accessory key row's encoding is pure logic and is what a screenshot
 * cannot verify, so every key and modifier combination that can be produced by
 * the row is asserted here.
 */
public class TerminalKeySequencesTest {

    private static final String ESC = "\u001b";

    @Test
    public void keysSendTheSameSequencesAsAHardwareKeyboard() {
        assertEquals(ESC, TerminalKeySequences.forKey(TerminalKey.ESC, false, false));
        assertEquals("\t", TerminalKeySequences.forKey(TerminalKey.TAB, false, false));
        assertEquals(ESC + "[A", TerminalKeySequences.forKey(TerminalKey.ARROW_UP, false, false));
        assertEquals(ESC + "[B", TerminalKeySequences.forKey(TerminalKey.ARROW_DOWN, false, false));
        assertEquals(ESC + "[C", TerminalKeySequences.forKey(TerminalKey.ARROW_RIGHT, false, false));
        assertEquals(ESC + "[D", TerminalKeySequences.forKey(TerminalKey.ARROW_LEFT, false, false));
        assertEquals(ESC + "[H", TerminalKeySequences.forKey(TerminalKey.HOME, false, false));
        assertEquals(ESC + "[F", TerminalKeySequences.forKey(TerminalKey.END, false, false));
        assertEquals(ESC + "[5~", TerminalKeySequences.forKey(TerminalKey.PAGE_UP, false, false));
        assertEquals(ESC + "[6~", TerminalKeySequences.forKey(TerminalKey.PAGE_DOWN, false, false));
    }

    @Test
    public void stickyModifiersUseTheXtermModifierParameter() {
        assertEquals(ESC + "[1;5A", TerminalKeySequences.forKey(TerminalKey.ARROW_UP, true, false));
        assertEquals(ESC + "[1;3C", TerminalKeySequences.forKey(TerminalKey.ARROW_RIGHT, false, true));
        assertEquals(ESC + "[1;7D", TerminalKeySequences.forKey(TerminalKey.ARROW_LEFT, true, true));
        assertEquals(ESC + "[1;5H", TerminalKeySequences.forKey(TerminalKey.HOME, true, false));
        assertEquals(ESC + "[1;5F", TerminalKeySequences.forKey(TerminalKey.END, true, false));
        assertEquals(ESC + "[5;5~", TerminalKeySequences.forKey(TerminalKey.PAGE_UP, true, false));
        assertEquals(ESC + "[6;7~", TerminalKeySequences.forKey(TerminalKey.PAGE_DOWN, true, true));
    }

    @Test
    public void keysWithoutAModifiedFormAreSentUnchanged() {
        assertEquals(ESC, TerminalKeySequences.forKey(TerminalKey.ESC, true, true));
        assertEquals("\t", TerminalKeySequences.forKey(TerminalKey.TAB, true, true));
    }

    @Test
    public void nullKeyProducesNothing() {
        assertEquals("", TerminalKeySequences.forKey(null, true, true));
    }

    @Test
    public void typedInputIsUnchangedWithoutModifiers() {
        assertEquals("c", TerminalKeySequences.forTypedInput("c", false, false));
        assertEquals("ls -la", TerminalKeySequences.forTypedInput("ls -la", false, false));
        assertEquals("", TerminalKeySequences.forTypedInput("", true, true));
        assertEquals(null, TerminalKeySequences.forTypedInput(null, true, true));
    }

    @Test
    public void ctrlFoldsTheTypedCharacterIntoItsControlCode() {
        assertEquals("\u0003", TerminalKeySequences.forTypedInput("c", true, false));
        assertEquals("\u0003", TerminalKeySequences.forTypedInput("C", true, false));
        assertEquals("\u0000", TerminalKeySequences.forTypedInput(" ", true, false));
        assertEquals("\u001b", TerminalKeySequences.forTypedInput("[", true, false));
        assertEquals("\u0004", TerminalKeySequences.forTypedInput("d", true, false));
    }

    @Test
    public void altPrefixesTypedInputWithEscape() {
        assertEquals(ESC + "x", TerminalKeySequences.forTypedInput("x", false, true));
        assertEquals(ESC + "\u0003", TerminalKeySequences.forTypedInput("c", true, true));
    }

    @Test
    public void onlyTheFirstCharacterOfPastedInputIsModified() {
        assertEquals("\u0001bc", TerminalKeySequences.forTypedInput("abc", true, false));
        assertEquals(ESC + "ls -la", TerminalKeySequences.forTypedInput("ls -la", false, true));
    }

    @Test
    public void nonAsciiInputIsNeverFoldedIntoAControlCharacter() {
        assertEquals("é", TerminalKeySequences.forTypedInput("é", true, false));
    }
}
