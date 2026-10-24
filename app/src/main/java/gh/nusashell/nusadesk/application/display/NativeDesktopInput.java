package gh.nusashell.nusadesk.application.display;

/** Focused native-display controls; no Android key codes leak into presentation. */
public interface NativeDesktopInput {
    enum Mode { TOUCH, DRAG, POINTER }
    enum Key { ESC, TAB, ENTER, BACKSPACE, HOME, END, PAGE_UP, PAGE_DOWN, LEFT, UP, DOWN, RIGHT, CTRL, ALT, SHIFT }
    void toggleKeyboard();
    boolean isKeyboardVisible();
    int zoomPercent();
    /** Out-of-range values are clamped to the supported 100–400 percent. */
    void setZoomPercent(int percent);
    int layoutWidth();
    /**
     * Zero fits the current surface; a positive value must be 640–1920 or this
     * throws {@link IllegalArgumentException}. It selects a fixed logical desktop width.
     */
    void setLayoutWidth(int pixels);
    void press(Key key);
    boolean isArmed(Key key);
    void setMode(Mode mode);
    Mode mode();
    /** Called when a one-shot modifier is consumed or input is released. */
    void setControlsChanged(Runnable listener);
}
