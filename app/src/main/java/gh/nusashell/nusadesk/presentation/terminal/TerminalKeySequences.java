package gh.nusashell.nusadesk.presentation.terminal;

/**
 * Encodes the terminal accessory keys and the sticky CTRL/ALT modifiers into
 * the exact bytes a terminal expects.
 *
 * <p>This is pure logic on purpose: it is the part of the mobile key row that
 * can be wrong in a way no screenshot reveals, so it is unit tested instead of
 * being buried in a click listener. It produces input only — it never decides
 * whether a shell exists, and it owns no session state.</p>
 *
 * <p>Navigation keys use the standard xterm modifier parameter
 * {@code 1 + shift(1) + alt(2) + ctrl(4)}, which is what the guest shell
 * already understands from a hardware keyboard.</p>
 */
public final class TerminalKeySequences {

    private TerminalKeySequences() {
    }

    /** Bytes to send for one accessory key, with the sticky modifiers applied. */
    public static String forKey(TerminalKey key, boolean ctrl, boolean alt) {
        if (key == null) {
            return "";
        }
        if (!ctrl && !alt) {
            return key.getBaseSequence();
        }
        int modifier = modifierCode(ctrl, alt);
        switch (key) {
            case ARROW_UP:
                return cursorSequence("1", 'A', modifier);
            case ARROW_DOWN:
                return cursorSequence("1", 'B', modifier);
            case ARROW_RIGHT:
                return cursorSequence("1", 'C', modifier);
            case ARROW_LEFT:
                return cursorSequence("1", 'D', modifier);
            case HOME:
                return cursorSequence("1", 'H', modifier);
            case END:
                return cursorSequence("1", 'F', modifier);
            case PAGE_UP:
                return cursorSequence("5", '~', modifier);
            case PAGE_DOWN:
                return cursorSequence("6", '~', modifier);
            default:
                // ESC and TAB have no modified form in this row; the modifier is
                // simply not applied rather than sending something invented.
                return key.getBaseSequence();
        }
    }

    /**
     * Applies the sticky modifiers to typed input arriving from the terminal
     * page. CTRL folds the first printable character into its C0 control
     * character and ALT prefixes the input with ESC, which is exactly what the
     * modifier keys do on a physical keyboard.
     */
    public static String forTypedInput(String typed, boolean ctrl, boolean alt) {
        if (typed == null || typed.isEmpty() || (!ctrl && !alt)) {
            return typed;
        }
        StringBuilder encoded = new StringBuilder(typed.length() + 1);
        if (alt) {
            encoded.append('\u001b');
        }
        encoded.append(ctrl ? controlCharacter(typed.charAt(0)) : typed.charAt(0));
        if (typed.length() > 1) {
            // Only the first character is modified: a paste must not be mangled
            // into a stream of control characters.
            encoded.append(typed, 1, typed.length());
        }
        return encoded.toString();
    }

    private static int modifierCode(boolean ctrl, boolean alt) {
        return 1 + (alt ? 2 : 0) + (ctrl ? 4 : 0);
    }

    private static String cursorSequence(String code, char terminator, int modifier) {
        return "\u001b[" + code + ";" + modifier + terminator;
    }

    private static char controlCharacter(char value) {
        // C0 control characters are the low five bits of printable ASCII.
        return value >= 0x20 && value < 0x7f ? (char) (value & 0x1f) : value;
    }
}
