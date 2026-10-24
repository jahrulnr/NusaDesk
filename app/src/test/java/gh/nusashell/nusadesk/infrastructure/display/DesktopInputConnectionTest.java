package gh.nusashell.nusadesk.infrastructure.display;

import android.view.KeyEvent;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DesktopInputConnectionTest {
    private final StringBuilder guest = new StringBuilder();
    private int caret, anchor;
    private boolean selected;
    private DesktopInputConnection input;
    @Before public void setup() {
        input = new DesktopInputConnection(new View(RuntimeEnvironment.getApplication()), new DesktopInputConnection.Sink() {
            public void text(String text) { guest.insert(caret, text); caret += text.length(); }
            public void key(int key) {
                if (selected && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    caret = Math.max(anchor, caret); selected = false; return;
                }
                if (key == KeyEvent.KEYCODE_DEL && caret > 0) {
                    int from = Character.offsetByCodePoints(guest, caret, -1);
                    guest.delete(from, caret); caret = from;
                } else if (key == KeyEvent.KEYCODE_FORWARD_DEL && caret < guest.length()) {
                    guest.delete(caret, Character.offsetByCodePoints(guest, caret, 1));
                } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) caret = Character.offsetByCodePoints(guest, caret, -1);
                else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) caret = Character.offsetByCodePoints(guest, caret, 1);
            }
            public void select(int anchorDelta, int selectionDelta) {
                caret = Character.offsetByCodePoints(guest, caret, anchorDelta);
                anchor = caret;
                caret = Character.offsetByCodePoints(guest, caret, selectionDelta);
                selected = selectionDelta != 0;
            }
            public boolean keyEvent(KeyEvent event) { return true; }
        });
    }
    @Test public void compositionIsVisibleAndCommitDoesNotDuplicate() {
        input.setComposingText("he", 1); assertEquals("he", guest.toString());
        input.setComposingText("hello", 1); input.commitText("hello", 1);
        assertEquals("hello", guest.toString());
        input.finishComposingText(); assertEquals("hello", guest.toString());
    }
    @Test public void correctionReplacesCompositionAndBackspaceDoesNotDeleteEarlierText() {
        input.commitText("prefix ", 1); input.setComposingText("wrong", 1);
        input.setComposingText("right", 1); input.deleteSurroundingText(1, 0);
        input.commitText("right", 1);
        assertEquals("prefix right", guest.toString());
    }
    @Test public void emojiCompositionDeletesWholeCodePoint() {
        input.setComposingText("a😀", 1); input.deleteSurroundingTextInCodePoints(1, 0);
        input.finishComposingText(); assertEquals("a", guest.toString());
    }
    @Test public void emptyCompositionCancelsPreedit() {
        input.commitText("saved", 1); input.setComposingText("draft", 1);
        input.setComposingText("", 1); assertEquals("saved", guest.toString());
    }
    @Test public void resetContextCanBackspaceExistingGuestText() {
        guest.append("existing"); caret = guest.length();
        input.clearContext(); input.deleteSurroundingText(2, 0);
        assertEquals("existi", guest.toString());
    }
    @Test public void unchangedPreeditStillHonoursRequestedCursorPosition() {
        input.setComposingText("hello", 1); input.setComposingText("hello", 0);
        assertEquals("hello", guest.toString()); assertEquals(0, caret);
    }
    @Test public void selectedRangeCanBeReplacedByIme() {
        input.commitText("abcdef", 1); input.setSelection(2, 4); input.commitText("X", 1);
        assertEquals("abXef", guest.toString()); assertEquals(3, caret);
    }
    @Test public void composingRegionCanCorrectAnAlreadyCommittedWord() {
        input.commitText("prefix teh", 1); input.setComposingRegion(7, 10);
        input.commitText("the", 1); assertEquals("prefix the", guest.toString());
    }
    @Test public void utf16DeleteDoesNotSplitAnEmoji() {
        input.commitText("a😀", 1); input.deleteSurroundingText(1, 0);
        assertEquals("a", guest.toString());
    }
    @Test public void cursorPositionIsHonoured() {
        input.commitText("abcd", 1); input.setSelection(2, 2); input.commitText("X", 1);
        assertEquals("abXcd", guest.toString()); assertEquals(3, caret);
    }
}
