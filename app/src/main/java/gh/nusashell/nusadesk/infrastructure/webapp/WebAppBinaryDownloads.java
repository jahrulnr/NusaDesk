package gh.nusashell.nusadesk.infrastructure.webapp;

import android.os.Handler;
import android.os.Looper;
import android.webkit.URLUtil;
import android.webkit.WebView;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;
import gh.nusashell.nusadesk.application.webapp.WebAppDownloadPort;
import gh.nusashell.nusadesk.domain.webapp.WebAppDownloadStatus;
import gh.nusashell.nusadesk.domain.webapp.WebAppDownloadStatus.State;
import gh.nusashell.nusadesk.infrastructure.runtimehost.LoopbackNavigationPolicy;

/** One WebView's bounded export channel, injected only into its registered origin. */
public final class WebAppBinaryDownloads implements AutoCloseable {
    private final WebView web;
    private final LoopbackNavigationPolicy policy;
    private final WebAppDownloadPort downloads;
    private final Consumer<WebAppDownloadStatus> status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, WebAppDownloadPort.BinaryTransfer> transfers = new HashMap<>();
    private final Map<String, Runnable> timeouts = new HashMap<>();
    private boolean supported;
    private boolean closed;

    public WebAppBinaryDownloads(WebView web, String origin, WebAppDownloadPort downloads,
                                 Consumer<WebAppDownloadStatus> status) {
        this.web = web; this.downloads = downloads; this.status = status;
        policy = new LoopbackNavigationPolicy(origin);
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    String script = script();
                    var origins = Collections.singleton(policy.getOwnedOrigin());
                    WebViewCompat.addWebMessageListener(web, "NusaDeskDownload", origins,
                            (view, message, source, mainFrame, reply) -> {
                                if (message.getType() == WebMessageCompat.TYPE_STRING)
                                    receive(message.getData(), source.toString(), reply::postMessage);
                            });
                    WebViewCompat.addDocumentStartJavaScript(web, script, origins);
                    supported = true;
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            // Old providers keep HTTP downloads; binary URLs get an explicit actionable error.
            supported = false;
        }
    }

    private String script() throws IOException {
        try (InputStream input = web.getContext().getAssets().open("webapp/binary-downloads.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Handles only download navigation; ordinary blob/data page loading stays blocked. */
    public boolean navigate(String url, String suggestedName) {
        if (url == null || !(url.startsWith("blob:") || url.startsWith("data:"))) return false;
        if (policy.classify(web.getUrl()) != LoopbackNavigationPolicy.Decision.OWNED
                || web.getUrl().startsWith("about:")) return false;
        if (!supported) {
            fail(suggestedName, "Update Android System WebView to enable browser exports");
        } else {
            web.evaluateJavascript("window.__nusaDeskExport && window.__nusaDeskExport("
                    + JSONObject.quote(url) + "," + JSONObject.quote(suggestedName == null ? "" : suggestedName) + ")", null);
        }
        return true;
    }

    void receive(String message, String source, Consumer<String> reply) {
        if (closed || policy.classify(source) != LoopbackNavigationPolicy.Decision.OWNED
                || source.startsWith("about:")) return;
        try {
            if (message == null || message.length() > 50_000) throw new JSONException("Invalid export packet");
            JSONObject packet = new JSONObject(message);
            String id = packet.getString("id");
            int seq = packet.getInt("seq");
            String op = packet.getString("op");
            if (id.isEmpty() || id.length() > 96) throw new JSONException("Invalid export id");
            WebAppDownloadPort.Completion ack = error -> main.post(() -> {
                if (closed) return;
                if (error != null || op.equals("finish") || op.equals("abort")) remove(id);
                else touch(id);
                JSONObject answer = new JSONObject();
                try {
                    answer.put("id", id).put("seq", seq);
                    if (error != null) answer.put("error", error);
                    reply.accept(answer.toString());
                } catch (JSONException | RuntimeException gone) { cancel(id, "Export page closed"); }
            });
            if (op.equals("start")) {
                if (transfers.containsKey(id)) throw new JSONException("Export already started");
                long size = packet.getLong("size");
                if (size < 0) throw new JSONException("Invalid export size");
                String name = packet.optString("name");
                if (name.isEmpty()) name = URLUtil.guessFileName(policy.getOwnedOrigin() + "/download", null,
                        packet.optString("mime").split(";", 2)[0].trim());
                WebAppDownloadPort.BinaryTransfer transfer = downloads.beginBinary(name, size, listener(), ack);
                transfers.put(id, transfer);
            } else if (op.equals("abort")) {
                if (transfers.containsKey(id)) cancel(id, packet.optString("error", "Export failed"));
                else fail(packet.optString("name", "download"), packet.optString("error", "Export failed"));
                ack.complete(null);
            } else {
                WebAppDownloadPort.BinaryTransfer transfer = transfers.get(id);
                if (transfer == null) { ack.complete("Export no longer exists"); return; }
                if (op.equals("chunk")) {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(packet.getString("data"));
                        if (bytes.length > 32768) throw new IllegalArgumentException("Export chunk too large");
                        transfer.append(bytes, ack);
                    } catch (IllegalArgumentException invalid) { cancel(id, "Invalid export chunk"); ack.complete("Invalid export chunk"); }
                } else if (op.equals("finish")) transfer.finish(ack);
                else { cancel(id, "Unknown export packet"); ack.complete("Unknown export packet"); }
            }
        } catch (JSONException invalid) { fail("download", invalid.getMessage()); }
    }

    private WebAppDownloadPort.Listener listener() {
        return new WebAppDownloadPort.Listener() {
            public void onStatus(WebAppDownloadStatus value) { main.post(() -> { if (!closed) status.accept(value); }); }
            public void onExternal(String url) { }
            public void onBlocked(String url) { }
        };
    }

    private void fail(String name, String detail) { status.accept(new WebAppDownloadStatus(State.FAILED, name, 0, -1, detail)); }
    private void touch(String id) {
        Runnable old = timeouts.remove(id);
        if (old != null) main.removeCallbacks(old);
        if (!transfers.containsKey(id)) return;
        Runnable timeout = () -> cancel(id, "Export stopped responding");
        timeouts.put(id, timeout); main.postDelayed(timeout, 60_000);
    }
    private void remove(String id) {
        transfers.remove(id);
        Runnable timeout = timeouts.remove(id);
        if (timeout != null) main.removeCallbacks(timeout);
    }
    private void cancel(String id, String reason) {
        WebAppDownloadPort.BinaryTransfer transfer = transfers.get(id);
        remove(id);
        if (transfer != null) transfer.abort(reason);
    }

    /** Cancels unfinished renderer-owned bytes when their document is replaced. */
    public void navigationStarted() {
        for (String id : new java.util.ArrayList<>(transfers.keySet())) cancel(id, "Export page closed");
    }
    @Override public void close() { navigationStarted(); closed = true; }
}
