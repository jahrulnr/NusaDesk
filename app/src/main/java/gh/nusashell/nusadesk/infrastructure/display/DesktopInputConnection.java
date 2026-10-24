package gh.nusashell.nusadesk.infrastructure.display;

import android.text.Editable;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;

/** Mirrors IME pre-edit locally and applies replacements to the focused Linux field. */
final class DesktopInputConnection extends BaseInputConnection {
    interface Sink {
        void text(String text);
        void key(int keyCode);
        void select(int anchorDelta, int selectionDelta);
        boolean keyEvent(KeyEvent event);
    }
    private final Sink sink;

    DesktopInputConnection(View target, Sink sink) {
        super(target, true);
        this.sink = sink;
    }

    /** The pointer/navigation changed the guest field; previous IME context no longer applies. */
    void clearContext() {
        super.finishComposingText();
        getEditable().clear();
        Selection.setSelection(getEditable(), 0);
    }

    private boolean edit(java.util.function.BooleanSupplier action) {
        Editable editable = getEditable();
        String old = editable.toString();
        int start = Math.max(0, Selection.getSelectionStart(editable));
        int end = Math.max(0, Selection.getSelectionEnd(editable));
        boolean result = action.getAsBoolean();
        String next = editable.toString();
        int prefix = 0;
        while (prefix < old.length() && prefix < next.length()
                && old.codePointAt(prefix) == next.codePointAt(prefix)) {
            prefix += Character.charCount(old.codePointAt(prefix));
        }
        int oldEnd = old.length(), newEnd = next.length();
        while (oldEnd > prefix && newEnd > prefix
                && old.codePointBefore(oldEnd) == next.codePointBefore(newEnd)) {
            oldEnd -= Character.charCount(old.codePointBefore(oldEnd));
            newEnd -= Character.charCount(next.codePointBefore(newEnd));
        }
        if (!old.equals(next)) {
            // Collapse a mirrored selection before positioning at the replacement's end.
            if (start != end) sink.key(KeyEvent.KEYCODE_DPAD_RIGHT);
            int caret = Math.max(start, end);
            move(old, caret, oldEnd);
            for (int i = old.codePointCount(prefix, oldEnd); i > 0; i--) sink.key(KeyEvent.KEYCODE_DEL);
            if (newEnd > prefix) sink.text(next.substring(prefix, newEnd));
            int nextStart = Math.max(0, Selection.getSelectionStart(editable));
            int nextEnd = Math.max(0, Selection.getSelectionEnd(editable));
            sink.select(distance(next, newEnd, nextStart), distance(next, nextStart, nextEnd));
        } else {
            int nextStart = Math.max(0, Selection.getSelectionStart(editable));
            int nextEnd = Math.max(0, Selection.getSelectionEnd(editable));
            if (start != nextStart || end != nextEnd) {
                if (start != end) sink.key(KeyEvent.KEYCODE_DPAD_RIGHT);
                sink.select(distance(next, Math.max(start, end), nextStart), distance(next, nextStart, nextEnd));
            }
        }
        return result;
    }

    private static int distance(String text, int from, int to) {
        return to >= from ? text.codePointCount(from, to) : -text.codePointCount(to, from);
    }
    private void move(String text, int from, int to) {
        int delta = distance(text, from, to);
        for (int i = Math.abs(delta); i > 0; i--) sink.key(delta < 0
                ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    @Override public boolean commitText(CharSequence text, int cursor) {
        return edit(() -> super.commitText(text, cursor));
    }
    @Override public boolean setComposingText(CharSequence text, int cursor) {
        return edit(() -> super.setComposingText(text, cursor));
    }
    @Override public boolean finishComposingText() { return super.finishComposingText(); }

    @Override public boolean setSelection(int start, int end) {
        if (start < 0 || end < 0 || start > getEditable().length() || end > getEditable().length()) return false;
        int oldStart = Math.max(0, Selection.getSelectionStart(getEditable()));
        int oldEnd = Math.max(0, Selection.getSelectionEnd(getEditable()));
        if (oldStart != oldEnd) sink.key(KeyEvent.KEYCODE_DPAD_RIGHT);
        String text = getEditable().toString();
        sink.select(distance(text, Math.max(oldStart, oldEnd), start), distance(text, start, end));
        return super.setSelection(start, end);
    }

    @Override public boolean deleteSurroundingText(int before, int after) {
        if (before < 0 || after < 0) return false;
        int start = Math.max(0, Math.min(Selection.getSelectionStart(getEditable()), Selection.getSelectionEnd(getEditable())));
        int end = Math.max(0, Math.max(Selection.getSelectionStart(getEditable()), Selection.getSelectionEnd(getEditable())));
        int unknownBefore = Math.max(0, before - start);
        int unknownAfter = Math.max(0, after - (getEditable().length() - end));
        int from = Math.max(0, start - before), to = Math.min(getEditable().length(), end + after);
        if (from > 0 && from < getEditable().length() && Character.isLowSurrogate(getEditable().charAt(from))) from--;
        if (to > 0 && to < getEditable().length() && Character.isLowSurrogate(getEditable().charAt(to))) to++;
        boolean result = deleteRange(start, end, from, to);
        for (int i = 0; i < unknownBefore; i++) sink.key(KeyEvent.KEYCODE_DEL);
        for (int i = 0; i < unknownAfter; i++) sink.key(KeyEvent.KEYCODE_FORWARD_DEL);
        return result;
    }

    @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
        if (before < 0 || after < 0) return false;
        String text = getEditable().toString();
        int start = Math.max(0, Math.min(Selection.getSelectionStart(getEditable()), Selection.getSelectionEnd(getEditable())));
        int end = Math.max(0, Math.max(Selection.getSelectionStart(getEditable()), Selection.getSelectionEnd(getEditable())));
        int unknownBefore = Math.max(0, before - text.codePointCount(0, start));
        int unknownAfter = Math.max(0, after - text.codePointCount(end, text.length()));
        int from = text.offsetByCodePoints(start, -Math.min(before, text.codePointCount(0, start)));
        int to = text.offsetByCodePoints(end, Math.min(after, text.codePointCount(end, text.length())));
        boolean result = deleteRange(start, end, from, to);
        for (int i = 0; i < unknownBefore; i++) sink.key(KeyEvent.KEYCODE_DEL);
        for (int i = 0; i < unknownAfter; i++) sink.key(KeyEvent.KEYCODE_FORWARD_DEL);
        return result;
    }

    private boolean deleteRange(int start, int end, int from, int to) {
        return edit(() -> {
            getEditable().delete(end, to);
            getEditable().delete(from, start);
            return true;
        });
    }

    @Override public boolean sendKeyEvent(KeyEvent event) {
        boolean result = sink.keyEvent(event);
        clearContext();
        return result;
    }
}
