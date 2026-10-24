package gh.nusashell.nusadesk.infrastructure.display;

import android.os.ParcelFileDescriptor;

/** Minimal JNI server entry for the pinned Xlorie ABI; owned by the display process. */
final class X11Server {
    native boolean start(String[] args);
    native ParcelFileDescriptor getXConnection();
    native ParcelFileDescriptor getLogcatOutput();
    native void reportFatalError(String message);
    private static native boolean connected();

    // Upstream reconnect uses these callbacks. NusaDesk exchanges its FD through private Binder.
    @SuppressWarnings("unused") private void sendBroadcast() { }
    @SuppressWarnings("unused") private void sendBroadcastDelayed() { }
    @SuppressWarnings("unused") private void setDocumentTag(String tag) { }
}
