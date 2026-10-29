package gh.nusashell.nusadesk.application.webapp;

import gh.nusashell.nusadesk.domain.webapp.WebAppDownloadStatus;

/** Downloads for one registered origin; callbacks may arrive on a worker thread. */
public interface WebAppDownloadPort extends AutoCloseable {
    interface Listener {
        void onStatus(WebAppDownloadStatus status);
        void onExternal(String url);
        void onBlocked(String url);
    }

    /** Enqueues an owned GET using the WebView session captured at the tap. */
    void download(String url, String userAgent, String cookie, String suggestedName, Listener listener);

    /** Acknowledged writes bound the renderer/native transfer to one chunk in flight. */
    interface Completion { void complete(String error); }
    interface BinaryTransfer {
        void append(byte[] chunk, Completion completion);
        void finish(Completion completion);
        void abort(String reason);
    }

    /** Creates a temporary guest file asynchronously; null completion error means ready. */
    BinaryTransfer beginBinary(String suggestedName, long length, Listener listener, Completion ready);

    /** Cancels queued/active work and removes its incomplete file. Idempotent. */
    @Override void close();
}
