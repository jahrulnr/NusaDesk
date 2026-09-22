package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Notification listener backing {@code notification.list}.
 *
 * <p>The platform binds this service only while the user has granted
 * notification access in Settings; the manifest declares the required
 * {@code BIND_NOTIFICATION_LISTENER_SERVICE} signature permission, so only
 * the system can bind it. The service keeps no state of its own beyond an
 * in-memory snapshot: posted notifications are reduced to a small immutable
 * row (id, tag, package, title, content, post time) at callback time and
 * held in a bounded map keyed by the notification's platform key. Nothing
 * is written to disk and no notification object is retained, so the service
 * is inert and safe when it is not bound — the snapshot is simply empty.</p>
 *
 * <p>The snapshot is rebuilt from {@link #getActiveNotifications()} when
 * the listener (re)connects, maintained incrementally through
 * {@link #onNotificationPosted}/{@link #onNotificationRemoved}, and cleared
 * on disconnect or destroy, so a stale row is never reported after the
 * platform revokes access.</p>
 */
public final class CapabilityNotificationListenerService
        extends NotificationListenerService {
    private static final String TAG = "CapabilityNLS";

    /** Hard bound on tracked notifications; the eldest is evicted beyond it. */
    private static final int MAX_TRACKED = 100;
    /** Per-row text bounds so one abusive notification cannot flood the map. */
    private static final int MAX_TITLE_CHARS = 256;
    private static final int MAX_CONTENT_CHARS = 512;

    private static final Object LOCK = new Object();
    private static final Map<String, Row> ROWS = new LinkedHashMap<>();
    private static boolean connected;

    public CapabilityNotificationListenerService() {
    }

    @Override
    public void onListenerConnected() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (RuntimeException e) {
            Log.w(TAG, "getActiveNotifications failed", e);
            active = new StatusBarNotification[0];
        }
        synchronized (LOCK) {
            ROWS.clear();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    putLocked(sbn);
                }
            }
            connected = true;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        synchronized (LOCK) {
            putLocked(sbn);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        synchronized (LOCK) {
            ROWS.remove(rowKey(sbn));
        }
    }

    @Override
    public void onListenerDisconnected() {
        synchronized (LOCK) {
            ROWS.clear();
            connected = false;
        }
    }

    @Override
    public void onDestroy() {
        synchronized (LOCK) {
            ROWS.clear();
            connected = false;
        }
        super.onDestroy();
    }

    /** A copy of the current snapshot, in insertion (oldest-first) order. */
    static List<Row> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(ROWS.values());
        }
    }

    /** Whether the platform currently has the listener bound. */
    static boolean connected() {
        synchronized (LOCK) {
            return connected;
        }
    }

    /**
     * Whether the snapshot holds a notification posted by this app whose
     * tag or numeric id matches {@code id} — the verification
     * {@code notification.remove} can offer when the listener is bound.
     */
    static boolean findOwn(String packageName, String id) {
        if (packageName == null || id == null) {
            return false;
        }
        synchronized (LOCK) {
            for (Row row : ROWS.values()) {
                if (!packageName.equals(row.packageName)) {
                    continue;
                }
                if (id.equals(row.tag) || id.equals(String.valueOf(row.id))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * One tracked notification, reduced to the fields the bridge reports.
     * The platform tag is kept because it is what
     * {@code termux-notification --id} sets and what
     * {@code termux-notification-remove} later names.
     */
    static final class Row {
        final long id;
        final String tag;
        final String packageName;
        final String title;
        final String content;
        final long postedMs;

        Row(long id, String tag, String packageName, String title,
            String content, long postedMs) {
            this.id = id;
            this.tag = tag;
            this.packageName = packageName;
            this.title = title;
            this.content = content;
            this.postedMs = postedMs;
        }
    }

    private static void putLocked(StatusBarNotification sbn) {
        ROWS.put(rowKey(sbn), capture(sbn));
        while (ROWS.size() > MAX_TRACKED) {
            Iterator<String> eldest = ROWS.keySet().iterator();
            if (!eldest.hasNext()) {
                break;
            }
            eldest.next();
            eldest.remove();
        }
    }

    private static String rowKey(StatusBarNotification sbn) {
        String key = sbn.getKey();
        if (key != null) {
            return key;
        }
        return sbn.getPackageName() + ":" + sbn.getId() + ":" + sbn.getTag();
    }

    private static Row capture(StatusBarNotification sbn) {
        String title = "";
        String content = "";
        Notification notification = sbn.getNotification();
        if (notification != null) {
            Bundle extras = notification.extras;
            if (extras != null) {
                title = bound(extraText(extras, Notification.EXTRA_TITLE),
                        MAX_TITLE_CHARS);
                CharSequence text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (text == null) {
                    text = extras.getCharSequence(Notification.EXTRA_TEXT);
                }
                content = bound(text == null ? "" : text.toString(),
                        MAX_CONTENT_CHARS);
            }
        }
        String tag = sbn.getTag();
        return new Row(sbn.getId(), tag == null ? "" : tag,
                sbn.getPackageName(), title, content, sbn.getPostTime());
    }

    private static String extraText(Bundle extras, String key) {
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    private static String bound(String value, int maxChars) {
        return value.length() > maxChars ? value.substring(0, maxChars) : value;
    }
}
