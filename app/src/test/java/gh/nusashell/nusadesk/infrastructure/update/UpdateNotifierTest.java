package gh.nusashell.nusadesk.infrastructure.update;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowPendingIntent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The permission gate and channel contract of the update notification
 * (ADR-0038): the {@code "updates"} channel exists at DEFAULT importance, a
 * notification is posted only while {@code POST_NOTIFICATIONS} is granted
 * (always below API 33), and the tap carries an {@code ACTION_VIEW} intent
 * for the release page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class UpdateNotifierTest {

    private static final String RELEASE_URL =
            "https://github.com/jahrulnr/NusaDesk/releases/tag/v0.4.0";

    @Test
    public void constructionCreatesTheUpdatesChannelAtDefaultImportance() {
        new UpdateNotifier(context());

        NotificationChannel channel =
                notificationManager().getNotificationChannel(UpdateNotifier.CHANNEL_ID);
        assertNotNull("the updates channel must exist", channel);
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.getImportance());
        assertEquals("App updates", channel.getName().toString());
    }

    @Test
    public void postsTheNotificationWhenTheGrantExists() {
        grant(Manifest.permission.POST_NOTIFICATIONS);

        new UpdateNotifier(context()).notifyIfAvailable(
                context(), "Update available", "NusaDesk v0.4.0 is out", RELEASE_URL);

        assertEquals(1, shadow().getAllNotifications().size());
        Notification posted = shadow().getAllNotifications().get(0);
        assertTrue("the notification must cancel itself on tap",
                (posted.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        Intent tap = Shadow.<ShadowPendingIntent>extract(posted.contentIntent).getSavedIntent();
        assertEquals(Intent.ACTION_VIEW, tap.getAction());
        assertEquals(RELEASE_URL, tap.getDataString());
    }

    @Test
    public void skipsTheNotificationWhenTheGrantIsMissing() {
        deny(Manifest.permission.POST_NOTIFICATIONS);

        new UpdateNotifier(context()).notifyIfAvailable(
                context(), "Update available", "NusaDesk v0.4.0 is out", RELEASE_URL);

        assertTrue("no notification may be posted without the grant",
                shadow().getAllNotifications().isEmpty());
    }

    @Test
    public void skipsTheNotificationWhenTheReleaseUrlIsMissing() {
        grant(Manifest.permission.POST_NOTIFICATIONS);

        new UpdateNotifier(context()).notifyIfAvailable(
                context(), "Update available", "NusaDesk v0.4.0 is out", null);

        assertTrue(shadow().getAllNotifications().isEmpty());
    }

    @Test
    @Config(sdk = 29)
    public void belowApi33PostsWithoutAnyGrant() {
        new UpdateNotifier(context()).notifyIfAvailable(
                context(), "Update available", "NusaDesk v0.4.0 is out", RELEASE_URL);

        assertEquals("POST_NOTIFICATIONS does not exist before API 33",
                1, shadow().getAllNotifications().size());
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static NotificationManager notificationManager() {
        return context().getSystemService(NotificationManager.class);
    }

    private static ShadowNotificationManager shadow() {
        return Shadow.<ShadowNotificationManager>extract(notificationManager());
    }

    private static void grant(String permission) {
        Shadow.<ShadowContextWrapper>extract(context()).grantPermissions(permission);
    }

    private static void deny(String permission) {
        Shadow.<ShadowContextWrapper>extract(context()).denyPermissions(permission);
    }
}
