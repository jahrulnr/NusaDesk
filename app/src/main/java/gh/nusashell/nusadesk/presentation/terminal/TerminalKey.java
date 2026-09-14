package gh.nusashell.nusadesk.presentation.terminal;

import gh.nusashell.nusadesk.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The fixed accessory keys the mobile terminal offers above the soft keyboard.
 *
 * <p>Each key carries the escape sequence a hardware keyboard would send for
 * it, so the row is a keyboard extension rather than a second input protocol.
 * The sticky CTRL/ALT modifiers are not keys of this enum: they change how the
 * next key or typed character is encoded, which is
 * {@link TerminalKeySequences}'s job.</p>
 */
public enum TerminalKey {

    ESC(R.string.terminal_key_esc, "\u001b"),
    TAB(R.string.terminal_key_tab, "\t"),
    ARROW_LEFT(R.string.terminal_key_left, "\u001b[D"),
    ARROW_UP(R.string.terminal_key_up, "\u001b[A"),
    ARROW_DOWN(R.string.terminal_key_down, "\u001b[B"),
    ARROW_RIGHT(R.string.terminal_key_right, "\u001b[C"),
    HOME(R.string.terminal_key_home, "\u001b[H"),
    END(R.string.terminal_key_end, "\u001b[F"),
    PAGE_UP(R.string.terminal_key_pgup, "\u001b[5~"),
    PAGE_DOWN(R.string.terminal_key_pgdn, "\u001b[6~");

    private final int labelRes;
    private final String baseSequence;

    TerminalKey(int labelRes, String baseSequence) {
        this.labelRes = labelRes;
        this.baseSequence = baseSequence;
    }

    /** Short key cap label, e.g. {@code ESC}. */
    public int getLabelRes() {
        return labelRes;
    }

    /** Sequence sent when neither sticky modifier is armed. */
    public String getBaseSequence() {
        return baseSequence;
    }

    /** Key order in the row: most-used first, so wrapping keeps them reachable. */
    public static List<TerminalKey> row() {
        return Collections.unmodifiableList(Arrays.asList(values()));
    }
}
