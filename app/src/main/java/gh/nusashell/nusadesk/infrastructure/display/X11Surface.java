package gh.nusashell.nusadesk.infrastructure.display;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;
import android.view.InputDevice;
import android.view.ScaleGestureDetector;
import android.os.Build;
import android.view.WindowInsets;
import gh.nusashell.nusadesk.application.display.NativeDesktopInput;
import java.util.EnumSet;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import gh.nusashell.nusadesk.domain.display.DisplayViewport;

/** Native Xlorie renderer and focused-area input adapter. No WebView or global capture. */
@SuppressLint("ViewConstructor")
public final class X11Surface extends SurfaceView implements SurfaceHolder.Callback, NativeDesktopInput {
    // Field name/type is part of the patched, pinned Xlorie JNI ABI.
    @SuppressWarnings("unused") private final X11Callbacks activity;
    private long handle;
    private int guestWidth = 1280, guestHeight = 720;
    private volatile DisplayViewport viewport = new DisplayViewport(0, 0, 1280, 720, 0, 0, 1280, 720);
    private final Map<Integer, Integer> heldKeys = new HashMap<>();
    private DesktopInputConnection inputConnection;
    private final EnumSet<Key> armed = EnumSet.noneOf(Key.class);
    private Runnable controlsChanged = () -> { };
    private final DesktopTouchController touch;
    private int mouseButtons;
    private final ScaleGestureDetector scale;
    private boolean pinching;
    private float pinchZoom;
    private int zoom = 100, layoutWidth;
    private Runnable disconnected = () -> { };

    public X11Surface(Context context) {
        super(context);
        activity = new X11Callbacks(() -> disconnected.run());
        System.loadLibrary("Xlorie");
        handle = nativeInit();
        touch = new DesktopTouchController(this, new DesktopTouchController.Sink() {
            public void point(float x, float y) { sendMouseEvent(handle, viewport.guestX(x), viewport.guestY(y), 0, false, false); }
            public void relative(float x, float y) { sendMouseEvent(handle, x, y, 0, false, true); }
            public void button(int button, boolean down) { sendMouseEvent(handle, 0, 0, button, down, true); }
            public void scroll(float x, float y) { sendMouseEvent(handle, x * .75f, y * .75f, 4, false, true); }
            public void targetChanged() { clearTextContext(); }
            public void twoFingerScroll(float x, float y) {
                if (zoom == 100) scroll(x, y);
                else {
                    float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
                    setZoomAnchor(handle, viewport.guestX(centerX) + viewport.guestDeltaX(x),
                            viewport.guestY(centerY) + viewport.guestDeltaY(y), .5f, .5f);
                }
            }
        });
        scale = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                pinching = true; touch.cancel(); pinchZoom = zoom;
                setZoomAnchor(handle, viewport.guestX(detector.getFocusX()), viewport.guestY(detector.getFocusY()),
                        detector.getFocusX() / Math.max(1, getWidth()), detector.getFocusY() / Math.max(1, getHeight()));
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                pinchZoom = Math.max(100, Math.min(400, pinchZoom * detector.getScaleFactor()));
                setZoomPercent(Math.round(pinchZoom)); return true;
            }
        });
        scale.setQuickScaleEnabled(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(this);
    }

    public void setOnDisconnected(Runnable listener) { disconnected = listener; }

    /** Takes ownership of a descriptor detached from ParcelFileDescriptor on the UI thread. */
    public void attachConnection(int fd) {
        if (handle == 0) {
            // A released renderer has no sink for the fd; adopt-and-close so it cannot leak.
            try { ParcelFileDescriptor.adoptFd(fd).close(); }
            catch (IOException ignored) { }
            return;
        }
        connect(handle, fd);
        sendWindowChange(handle, guestWidth, guestHeight, 60, "NusaDesk");
        setClipboardSyncEnabled(handle, false, false);
        setFollowCursorPan(handle, mode() == Mode.POINTER);
        setRendererZoom(handle, zoom);
        applySurface(getHolder());
    }

    /**
     * Closes the renderer FD and notifies the session without releasing the
     * SurfaceView. The display process and guest apps stay up; Retry reconnects.
     */
    public void disconnectRenderer() {
        if (handle != 0) connect(handle, -1);
        disconnected.run();
    }

    public void release() {
        if (handle == 0) return;
        releaseInput();
        nativeDestroy(handle);
        handle = 0;
    }

    @Override public void toggleKeyboard() {
        requestFocus();
        InputMethodManager ime = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean visible = isKeyboardVisible();
        if (visible) ime.hideSoftInputFromWindow(getWindowToken(), 0);
        else ime.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }
    @Override public boolean isKeyboardVisible() {
        WindowInsets insets = getRootWindowInsets();
        if (Build.VERSION.SDK_INT >= 30) return insets != null && insets.isVisible(WindowInsets.Type.ime());
        // Match the shell's fullscreen API 29 visible-frame path: it has no IME inset.
        android.graphics.Rect frame = new android.graphics.Rect();
        getWindowVisibleDisplayFrame(frame);
        int rootHeight = getRootView().getHeight();
        return rootHeight > 0 && rootHeight - frame.bottom > rootHeight / 4;
    }
    @Override public void setControlsChanged(Runnable listener) { controlsChanged = listener; }
    @Override public int zoomPercent() { return zoom; }
    @Override public void setZoomPercent(int percent) {
        zoom = Math.max(100, Math.min(400, percent));
        if (handle != 0) {
            if (zoom == 100) clearZoomAnchor(handle);
            setRendererZoom(handle, zoom);
        }
        controlsChanged.run();
    }
    @Override public int layoutWidth() { return layoutWidth; }
    @Override public void setLayoutWidth(int pixels) {
        if (pixels != 0 && (pixels < 640 || pixels > 1920)) throw new IllegalArgumentException("desktop width must be 640–1920 or fit");
        layoutWidth = pixels == 0 ? 0 : pixels / 8 * 8;
        setZoomPercent(100); applySurface(getHolder()); controlsChanged.run();
    }
    @Override public Mode mode() { return touch.mode(); }
    @Override public void setMode(Mode mode) {
        touch.setMode(mode);
        if (handle != 0) { clearZoomAnchor(handle); setFollowCursorPan(handle, mode == Mode.POINTER); }
        controlsChanged.run();
    }
    @Override public boolean isArmed(Key key) { return armed.contains(key); }
    @Override public void press(Key key) {
        if (handle == 0) return;
        requestFocus();
        if (key == Key.CTRL || key == Key.ALT || key == Key.SHIFT) {
            boolean down = !armed.remove(key);
            if (down) armed.add(key);
            sendKeyEvent(handle, 0, code(key), down);
            controlsChanged.run();
        } else {
            clearTextContext();
            press(code(key));
            consumeModifiers();
        }
    }
    private static int code(Key key) {
        switch (key) {
            case ESC: return KeyEvent.KEYCODE_ESCAPE;
            case TAB: return KeyEvent.KEYCODE_TAB;
            case ENTER: return KeyEvent.KEYCODE_ENTER;
            case BACKSPACE: return KeyEvent.KEYCODE_DEL;
            case HOME: return KeyEvent.KEYCODE_MOVE_HOME;
            case END: return KeyEvent.KEYCODE_MOVE_END;
            case PAGE_UP: return KeyEvent.KEYCODE_PAGE_UP;
            case PAGE_DOWN: return KeyEvent.KEYCODE_PAGE_DOWN;
            case LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            case RIGHT: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case UP: return KeyEvent.KEYCODE_DPAD_UP;
            case DOWN: return KeyEvent.KEYCODE_DPAD_DOWN;
            case CTRL: return KeyEvent.KEYCODE_CTRL_LEFT;
            case ALT: return KeyEvent.KEYCODE_ALT_LEFT;
            case SHIFT: return KeyEvent.KEYCODE_SHIFT_LEFT;
            default: throw new IllegalArgumentException("unknown desktop key");
        }
    }
    private void consumeModifiers() {
        for (Key key : armed) sendKeyEvent(handle, 0, code(key), false);
        armed.clear(); controlsChanged.run();
    }
    private void clearTextContext() {
        if (inputConnection != null) inputConnection.clearContext();
        ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(this);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        applySurface(holder);
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        applySurface(holder);
    }
    private void applySurface(SurfaceHolder holder) {
        if (handle == 0 || !holder.getSurface().isValid()) return;
        // setWindow clears expected dimensions: reapply the guest viewport afterwards.
        surfaceChanged(handle, holder.getSurface());
        if (getWidth() > 0 && getHeight() > 0) {
            int width = layoutWidth == 0 ? DisplayViewport.logicalWidth(getWidth()) : layoutWidth;
            int height = DisplayViewport.logicalHeight(width, getWidth(), getHeight());
            if (width != guestWidth || height != guestHeight) {
                guestWidth = width;
                guestHeight = height;
                sendWindowChange(handle, width, height, 60, "NusaDesk");
            }
            setViewport(handle, 0, 0, getWidth(), getHeight(), guestWidth, guestHeight, 0);
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        releaseInput();
        if (handle != 0) surfaceChanged(handle, null);
    }

    // Called by the renderer thread after it computes the real letterboxed/panned content.
    @SuppressWarnings("unused") private void setRendererViewport(int x, int y, int width, int height,
            float sourceX, float sourceY, float sourceWidth, float sourceHeight) {
        if (width > 0 && height > 0 && sourceWidth >= 1 && sourceHeight >= 1) {
            viewport = new DisplayViewport(x, y, width, height, sourceX, sourceY, sourceWidth, sourceHeight);
        }
    }

    @Override public boolean onCheckIsTextEditor() { return true; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        inputConnection = new DesktopInputConnection(this, new DesktopInputConnection.Sink() {
            public void text(String text) {
                if (handle == 0 || text.isEmpty()) return;
                if (!armed.isEmpty()) {
                    KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
                    if (events != null) {
                        for (KeyEvent event : events) sendKeyEvent(handle, event.getScanCode(), event.getKeyCode(), event.getAction() == KeyEvent.ACTION_DOWN);
                    } else sendTextEvent(handle, text.getBytes(StandardCharsets.UTF_8));
                    consumeModifiers();
                    post(X11Surface.this::clearTextContext);
                } else sendTextEvent(handle, text.getBytes(StandardCharsets.UTF_8));
            }
            public void key(int key) { X11Surface.this.press(key); }
            public void select(int delta, int selection) {
                int key = delta < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
                for (int i = 0; i < Math.abs(delta); i++) X11Surface.this.press(key);
                if (selection != 0) {
                    sendKeyEvent(handle, 0, KeyEvent.KEYCODE_SHIFT_LEFT, true);
                    for (int i = 0; i < Math.abs(selection); i++) X11Surface.this.press(selection < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
                    sendKeyEvent(handle, 0, KeyEvent.KEYCODE_SHIFT_LEFT, false);
                }
            }
            public boolean keyEvent(KeyEvent event) { return dispatchKeyEvent(event); }
        });
        return inputConnection;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                || event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event);
        if (handle == 0) return false;
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE && event.getCharacters() != null) {
            sendTextEvent(handle, event.getCharacters().getBytes(StandardCharsets.UTF_8));
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (down) heldKeys.put(event.getKeyCode(), event.getScanCode());
            else heldKeys.remove(event.getKeyCode());
            boolean result = sendKeyEvent(handle, event.getScanCode(), event.getKeyCode(), down);
            if (!down && !KeyEvent.isModifierKey(event.getKeyCode())) consumeModifiers();
            if (inputConnection != null && !KeyEvent.isModifierKey(event.getKeyCode())) inputConnection.clearContext();
            return result;
        }
        return super.dispatchKeyEvent(event);
    }

    private void press(int key) {
        if (handle == 0) return;
        sendKeyEvent(handle, 0, key, true);
        sendKeyEvent(handle, 0, key, false);
    }

    // DesktopTouchController calls performClick only for a recognized tap, not a scroll.
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (handle == 0) return false;
        requestFocus();
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return mouse(event);
        scale.onTouchEvent(event);
        if (pinching) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pinching = false; clearZoomAnchor(handle);
            }
            return true;
        }
        return touch.touch(event);
    }

    private boolean mouse(MotionEvent event) {
        sendMouseEvent(handle, viewport.guestX(event.getX()), viewport.guestY(event.getY()), 0, false, false);
        int next = event.getButtonState();
        int[] masks = {MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_TERTIARY, MotionEvent.BUTTON_SECONDARY};
        for (int i = 0; i < masks.length; i++) {
            if ((next & masks[i]) != (mouseButtons & masks[i])) {
                if ((next & masks[i]) != 0) clearTextContext();
                sendMouseEvent(handle, 0, 0, i + 1, (next & masks[i]) != 0, true);
            }
        }
        mouseButtons = next;
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) releaseInput();
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (handle == 0) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            sendMouseEvent(handle, -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120,
                    -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120, 4, false, true);
        } else return mouse(event);
        return true;
    }

    @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect rect) {
        super.onFocusChanged(focused, direction, rect);
        if (!focused) releaseInput();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) releaseInput();
    }

    /** Finish the old app's input context before changing its focused X11 window. */
    public void prepareTaskSwitch() { releaseInput(); clearTextContext(); }

    private void releaseInput() {
        if (handle == 0) return;
        for (Map.Entry<Integer, Integer> key : heldKeys.entrySet()) {
            sendKeyEvent(handle, key.getValue(), key.getKey(), false);
        }
        heldKeys.clear();
        for (int button = 1; button <= 3; button++) sendMouseEvent(handle, 0, 0, button, false, false);
        mouseButtons = 0;
        touch.cancel();
        consumeModifiers();
    }

    // The Java adapter resets context on an actual target change, never on wheel/hover events.
    @SuppressWarnings("unused") private void resetIme() { }
    @SuppressWarnings("unused") private void onSyncReply(int serial) { }
    @SuppressWarnings("unused") private void setClipboardText(String text) {
        ((ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("Linux", text));
    }
    @SuppressWarnings("unused") private void requestClipboard() { }

    // Complete ABI table registered by the pinned upstream native renderer.
    private native long nativeInit();
    private native void nativeDestroy(long ptr);
    private native void surfaceChanged(long ptr, Surface surface);
    private native void setFiltering(long ptr, int filtering);
    private native void setClipboardSyncEnabled(long ptr, boolean enabled, boolean ignored);
    private native void sendClipboardAnnounce(long ptr);
    private native void sendClipboardEvent(long ptr, byte[] text);
    private native void sendWindowChange(long ptr, int width, int height, int framerate, String name);
    private native void setViewport(long ptr, int x, int y, int width, int height, int expectedWidth, int expectedHeight, int hiddenBottom);
    private native void setRendererZoom(long ptr, int percent);
    private native void setZoomAnchor(long ptr, float sourceX, float sourceY, float fracX, float fracY);
    private native void clearZoomAnchor(long ptr);
    private native void setFollowCursorPan(long ptr, boolean enabled);
    private native long getCursorPosition(long ptr);
    private native void sendSync(long ptr, int serial);
    private static native void connect(long ptr, int fd);
    private static native boolean connected(long ptr);
    private static native void startLogcat(long ptr, int fd);
    private native void sendMouseEvent(long ptr, float x, float y, int button, boolean down, boolean relative);
    private native void sendTouchEvent(long ptr, int action, int id, int x, int y);
    private native void sendStylusEvent(long ptr, float x, float y, int pressure, int tiltX, int tiltY, int orientation, int buttons, boolean eraser, boolean mouse);
    private native void requestStylusEnabled(long ptr, boolean enabled);
    private native void sendLockKeysState(long ptr, int state);
    private native boolean sendKeyEvent(long ptr, int scanCode, int keyCode, boolean down);
    private native void sendTextEvent(long ptr, byte[] text);
    private static native boolean requestConnection(long ptr);
    private static native long getLastInputTimestamp();
    private static native void markUserActivity();
}
