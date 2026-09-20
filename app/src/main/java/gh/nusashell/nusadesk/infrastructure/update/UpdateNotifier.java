package gh.nusashell.nusadesk.infrastructure.update;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

/**
 * Posts the "a newer release exists" system notification (ADR-0038).
 *
 * <p>Uses its own {@code "updates"} channel — separate from the runtime host
 * channel — so the user can silence update notices without touching runtime
 * status. The launcher banner is the primary surface; this notification is
 * the optional second surface and is posted only when the
 * {@code POST_NOTIFICATIONS} grant already exists (unconditionally allowed
 * below API 33). It never requests the runtime permission.</p>
 *
 * <p>There is no in-app update flow: the tap opens the GitHub release page in
 * the system browser via {@code ACTION_VIEW}, and the notification cancels
 * itself.</p>
 */
public final class UpdateNotifier {

    /** Notification channel id for app-update notices. */
    public static final String CHANNEL_ID = "updates";

    private static final String TAG = "UpdateNotifier";
    private static final int NOTIFICATION_ID = 0x4E55; // "NU"

    private final Context appContext;

    public UpdateNotifier(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.appContext = context.getApplicationContext();
        ensureChannel();
    }

    /**
     * Posts the update notification when the notification grant already
     * exists and {@code releasePageUrl} is usable; otherwise does nothing.
     *
     * @param context        any context of this app; used for the permission
     *                       check and the pending intent
     * @param title          notification title (the coordinator owns strings)
     * @param text           notification body text
     * @param releasePageUrl HTTPS release page the tap opens in a browser
     */
    public void notifyIfAvailable(Context context, CharSequence title, CharSequence text,
            String releasePageUrl) {
        if (releasePageUrl == null || releasePageUrl.isEmpty()
                || !notificationsAllowed(context)) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        ensureChannel();
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(releasePageUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        try {
            manager.notify(NOTIFICATION_ID, notification);
        } catch (RuntimeException e) {
            // A grant revoked between the check and the post must not reach the UI.
            Log.w(TAG, "could not post update notification", e);
        }
    }

    private void ensureChannel() {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Notices that a newer NusaDesk release is available.");
        manager.createNotificationChannel(channel);
    }

    private static boolean notificationsAllowed(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Cancels the posted update notice. The banner's "Dismiss" action calls
     * this so the two surfaces stay consistent: a dismissed release silences
     * both the banner and the standing notification, and a newer tag
     * re-surfaces them naturally.
     */
    public void cancelUpdateNotice() {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }
}
