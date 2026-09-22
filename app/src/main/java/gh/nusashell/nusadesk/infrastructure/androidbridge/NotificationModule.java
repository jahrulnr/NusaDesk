package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Notification capability domain: posting, channel management, listing, and
 * removal behind four bridge methods.
 *
 * <p>{@code notification.post} builds an {@link android.app.Notification}
 * through a notification channel (channels are mandatory on this app's
 * minimum SDK). Posting requires {@code POST_NOTIFICATIONS} on API 33+ —
 * reported through {@link AndroidPermissionChecker} so a never-asked grant
 * ({@code notification-permission-required}) stays distinct from a refused
 * one ({@code notification-permission-denied}) — and the app-level
 * notifications toggle on every level
 * ({@link NotificationManager#areNotificationsEnabled()}).</p>
 *
 * <p>Priority, sound, and vibration are channel properties on Android 8+,
 * so a {@code --priority} value selects a per-importance channel that is
 * created on demand ({@code nusadesk-notification[-min|low|high|max]});
 * {@code sound}/{@code vibrate} shape that channel at creation. An
 * explicitly named {@code channel} must already exist — created through
 * {@code notification.channel}, matching upstream — or the post answers
 * {@code notification-unavailable} instead of silently dropping.</p>
 *
 * <p>{@code notification.list} needs the user-enabled notification access
 * grant; without it the method answers
 * {@code notification-permission-required:enable notification access in
 * Settings}. Rows come from the in-memory
 * {@link CapabilityNotificationListenerService} snapshot, bounded to 50
 * rows and a JSON budget, with {@code truncated} reporting either cap.</p>
 *
 * <p>{@code notification.remove} cancels one of this app's notifications by
 * the tag {@code notification.post} used (the guest {@code --id}), matching
 * upstream's {@code cancel(tag, 0)}. {@code removed} is verified against
 * the listener snapshot when notification access is enabled; when it is
 * not, the cancel is still issued and {@code removed} reports that the
 * request was accepted, which is all the platform exposes.</p>
 *
 * <p>The notification {@code id} cannot be a response field — it collides
 * with the protocol envelope — so {@code notification.post} reports it as
 * {@code notification_id}.</p>
 */
public final class NotificationModule implements CapabilityModule {
    private static final String TAG = "NotificationModule";

    private static final String METHOD_POST = "notification.post";
    private static final String METHOD_CHANNEL = "notification.channel";
    private static final String METHOD_LIST = "notification.list";
    private static final String METHOD_REMOVE = "notification.remove";

    private static final String DEFAULT_CHANNEL_ID = "nusadesk-notification";
    private static final String DEFAULT_CHANNEL_NAME = "NusaDesk notifications";

    private static final Set<String> PRIORITIES =
            Set.of("min", "low", "default", "high", "max");

    private static final int ID_MAX_CHARS = 128;
    private static final int TITLE_MAX_CHARS = 512;
    private static final int CONTENT_MAX_CHARS = 8192;
    private static final int CHANNEL_ID_MAX_CHARS = 64;
    private static final int CHANNEL_NAME_MAX_CHARS = 256;
    private static final int GROUP_MAX_CHARS = 128;
    private static final int PATH_MAX_CHARS = 4096;
    private static final int STAGED_TEXT_MAX_CHARS = 32_768;
    private static final long STAGED_FILE_MAX_BYTES = 256 * 1024L;
    private static final long IMAGE_MAX_BYTES = 8L * 1024 * 1024;
    private static final int IMAGE_MAX_DIMENSION = 2048;

    private static final int LIST_MAX_ROWS = 50;
    /** Keeps the response comfortably under the guest's 16 KiB read cap. */
    private static final int LIST_MAX_JSON_CHARS = 12_000;

    private static final String PERMISSION_HINT_REQUIRED =
            "notification-permission-required:grant via permission.request";
    private static final String PERMISSION_HINT_DENIED =
            "notification-permission-denied:enable notifications in app settings";
    private static final String LISTENER_HINT =
            "notification-permission-required:enable notification access in Settings";

    private final Context context;
    private final AndroidPermissionChecker checker;
    private final GuestFilePathResolver resolver;

    public NotificationModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.checker = new AndroidPermissionChecker(this.context);
        this.resolver = new GuestFilePathResolver(this.context);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_POST, METHOD_CHANNEL, METHOD_LIST, METHOD_REMOVE);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_POST, METHOD_CHANNEL, METHOD_REMOVE);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_POST:
                return post(request);
            case METHOD_CHANNEL:
                return channel(request);
            case METHOD_LIST:
                return list(request);
            case METHOD_REMOVE:
                return remove(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    /**
     * {@code notification.post} — params {@code id}, {@code title},
     * {@code content} (or staged {@code content_path}), {@code channel},
     * {@code priority}, {@code ongoing}, {@code sound}, {@code vibrate},
     * {@code group}, {@code image_path}. Reports {@code posted} and
     * {@code notification_id} (the tag the notification was posted under —
     * what {@code notification.remove} later takes).
     */
    private AndroidCapabilityProtocol.Response post(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("id", "title", "content", "content_path",
                "channel", "priority", "ongoing", "sound", "vibrate", "group",
                "image_path"));
        String id = params.optionalString("id", ID_MAX_CHARS, "");
        String title = params.optionalString("title", TITLE_MAX_CHARS, "");
        String channel = params.optionalString("channel", CHANNEL_ID_MAX_CHARS, "");
        String priority = params.optionalString("priority", 16, "default");
        if (!PRIORITIES.contains(priority)) {
            throw new CapabilityParams.Invalid("unsupported parameter value: priority");
        }
        boolean ongoing = params.optionalBoolean("ongoing", false);
        boolean sound = params.optionalBoolean("sound", false);
        boolean vibrate = params.optionalBoolean("vibrate", false);
        String group = params.optionalString("group", GROUP_MAX_CHARS, "");
        String imagePath = params.optionalString("image_path", PATH_MAX_CHARS, "");
        boolean[] truncated = new boolean[1];
        String content = contentParam(params, truncated);

        NotificationManager manager = notificationManager();
        if (manager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "notification-unavailable");
        }
        AndroidCapabilityProtocol.Response gate = notificationsGate(request, manager);
        if (gate != null) {
            return gate;
        }

        String channelId;
        if (channel.isEmpty()) {
            channelId = channelIdFor(priority);
            try {
                manager.createNotificationChannel(
                        defaultChannel(channelId, priority, sound, vibrate));
            } catch (RuntimeException e) {
                Log.w(TAG, "channel creation failed", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "notification-unavailable");
            }
        } else {
            channelId = channel;
            try {
                if (manager.getNotificationChannel(channelId) == null) {
                    return AndroidCapabilityProtocol.Response.error(request.getId(),
                            "notification-unavailable:unknown channel " + channelId);
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "channel lookup failed", e);
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "notification-unavailable");
            }
        }

        String tag = id.isEmpty() ? UUID.randomUUID().toString() : id;
        Notification.Builder builder = new Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setOngoing(ongoing)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false);
        if (!title.isEmpty()) {
            builder.setContentTitle(title);
        }
        if (!group.isEmpty()) {
            builder.setGroup(group);
        }
        Bitmap image = decodeStagedImage(imagePath);
        if (image != null) {
            builder.setLargeIcon(image)
                    .setStyle(new Notification.BigPictureStyle().bigPicture(image));
        }
        if (!content.isEmpty()) {
            // Upstream precedence: a multi-line body's BigTextStyle replaces
            // the picture style; a single-line body leaves a picture in place.
            if (content.indexOf('\n') >= 0) {
                builder.setStyle(new Notification.BigTextStyle().bigText(content));
            } else {
                builder.setContentText(content);
            }
        }
        try {
            manager.notify(tag, 0, builder.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "notify failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "notification-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("posted", true);
        fields.put("notification_id", tag);
        if (truncated[0]) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code notification.channel} — params {@code id}, {@code name},
     * {@code delete} (bool, default false). Creates or deletes a channel;
     * reports {@code channel} and {@code deleted}. Creating without a name
     * is {@code invalid-argument}; a platform refusal is
     * {@code notification-unavailable}.
     */
    private AndroidCapabilityProtocol.Response channel(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("id", "name", "delete"));
        String channelId = params.requireString("id", CHANNEL_ID_MAX_CHARS);
        boolean delete = params.optionalBoolean("delete", false);

        NotificationManager manager = notificationManager();
        if (manager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "notification-unavailable");
        }
        String name = delete ? "" : params.requireString("name", CHANNEL_NAME_MAX_CHARS);
        try {
            if (delete) {
                manager.deleteNotificationChannel(channelId);
            } else {
                manager.createNotificationChannel(new NotificationChannel(
                        channelId, name, NotificationManager.IMPORTANCE_DEFAULT));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "channel operation failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "notification-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("channel", channelId);
        fields.put("deleted", delete);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code notification.list} — no params. Reports
     * {@code notifications_json}: one pre-encoded array of
     * {@code {"id","tag","package","title","content","posted_ms"}} rows from
     * the listener snapshot, plus {@code truncated}. A missing notification
     * access grant is {@code notification-permission-required} with the
     * Settings hint.
     */
    private AndroidCapabilityProtocol.Response list(
            AndroidCapabilityProtocol.Request request) {
        CapabilityPermission state =
                checker.check(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE);
        if (state != CapabilityPermission.GRANTED) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), LISTENER_HINT);
        }
        List<CapabilityNotificationListenerService.Row> rows =
                CapabilityNotificationListenerService.snapshot();
        StringBuilder json = new StringBuilder(LIST_MAX_JSON_CHARS / 2);
        json.append('[');
        int emitted = 0;
        boolean truncated = false;
        for (CapabilityNotificationListenerService.Row row : rows) {
            if (emitted >= LIST_MAX_ROWS) {
                truncated = true;
                break;
            }
            String entry = rowJson(row);
            if (json.length() + entry.length() + 2 > LIST_MAX_JSON_CHARS) {
                truncated = true;
                break;
            }
            if (emitted > 0) {
                json.append(',');
            }
            json.append(entry);
            emitted++;
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("notifications_json", json.toString());
        fields.put("truncated", truncated);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code notification.remove} — params {@code id} (the tag
     * {@code notification.post} reported). Cancels through
     * {@link NotificationManager#cancel(String, int)}; reports
     * {@code removed}: verified against the listener snapshot when
     * notification access is enabled, otherwise the accepted cancel.
     */
    private AndroidCapabilityProtocol.Response remove(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("id"));
        String id = params.requireString("id", ID_MAX_CHARS);

        NotificationManager manager = notificationManager();
        if (manager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "notification-unavailable");
        }
        try {
            manager.cancel(id, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "cancel failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "notification-unavailable");
        }
        boolean listenerEnabled = checker.check(
                Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE)
                == CapabilityPermission.GRANTED;
        boolean removed = listenerEnabled
                ? CapabilityNotificationListenerService.findOwn(
                        context.getPackageName(), id)
                : true;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("removed", removed);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * The notification gate: the {@code POST_NOTIFICATIONS} runtime grant on
     * API 33+ (typed required/denied through the checker), then the
     * app-level notifications toggle on every level. {@code null} when the
     * post may proceed.
     */
    private AndroidCapabilityProtocol.Response notificationsGate(
            AndroidCapabilityProtocol.Request request, NotificationManager manager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            CapabilityPermission state =
                    checker.check(Manifest.permission.POST_NOTIFICATIONS);
            if (state == CapabilityPermission.REQUIRED) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), PERMISSION_HINT_REQUIRED);
            }
            if (state == CapabilityPermission.DENIED) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), PERMISSION_HINT_DENIED);
            }
        }
        try {
            if (!manager.areNotificationsEnabled()) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), PERMISSION_HINT_DENIED);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "areNotificationsEnabled failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "notification-unavailable");
        }
        return null;
    }

    /**
     * The default channel id for a priority: one channel per importance so
     * the {@code priority} value survives the platform's "the channel owns
     * alerting" rule instead of silently doing nothing.
     */
    private static String channelIdFor(String priority) {
        return "default".equals(priority)
                ? DEFAULT_CHANNEL_ID
                : DEFAULT_CHANNEL_ID + "-" + priority;
    }

    /** The default channel for a priority; a no-op create when it exists. */
    private static NotificationChannel defaultChannel(String channelId, String priority,
                                                      boolean sound, boolean vibrate) {
        NotificationChannel channel = new NotificationChannel(channelId,
                DEFAULT_CHANNEL_NAME, importanceFor(priority));
        channel.setSound(sound ? Settings.System.DEFAULT_NOTIFICATION_URI : null,
                sound ? new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION).build() : null);
        channel.enableVibration(vibrate);
        return channel;
    }

    /** Upstream importance mapping: min/low/default map down, high/max to HIGH. */
    private static int importanceFor(String priority) {
        switch (priority) {
            case "min":
                return NotificationManager.IMPORTANCE_MIN;
            case "low":
                return NotificationManager.IMPORTANCE_LOW;
            case "high":
            case "max":
                return NotificationManager.IMPORTANCE_HIGH;
            default:
                return NotificationManager.IMPORTANCE_DEFAULT;
        }
    }

    /** The {@code content}/{@code content_path} pair read as one value. */
    private String contentParam(CapabilityParams params, boolean[] truncated) {
        if (params.has("content") && params.has("content_path")) {
            throw new CapabilityParams.Invalid(
                    "content and content_path are mutually exclusive");
        }
        if (!params.has("content_path")) {
            return params.optionalString("content", CONTENT_MAX_CHARS, "");
        }
        String guestPath = params.optionalString("content_path", PATH_MAX_CHARS, "");
        Path file;
        try {
            file = resolver.resolveForRead(guestPath);
        } catch (GuestFilePathResolver.Invalid e) {
            throw new CapabilityParams.Invalid("content_path is not readable");
        }
        try {
            if (Files.size(file) > STAGED_FILE_MAX_BYTES) {
                throw new CapabilityParams.Invalid("content_path file too large");
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (text.length() > STAGED_TEXT_MAX_CHARS) {
                truncated[0] = true;
                return text.substring(0, STAGED_TEXT_MAX_CHARS);
            }
            return text;
        } catch (IOException e) {
            throw new CapabilityParams.Invalid("content_path is not readable");
        }
    }

    /**
     * Decode a staged image for the notification's big picture. A missing or
     * unreadable path is {@link CapabilityParams.Invalid}; a file that is
     * too large or does not decode is skipped (logged, like upstream's
     * missing-file path) rather than failing the whole post.
     */
    private Bitmap decodeStagedImage(String guestPath) {
        if (guestPath.isEmpty()) {
            return null;
        }
        Path file;
        try {
            file = resolver.resolveForRead(guestPath);
        } catch (GuestFilePathResolver.Invalid e) {
            throw new CapabilityParams.Invalid("image_path is not readable");
        }
        try {
            long size = Files.size(file);
            if (size <= 0 || size > IMAGE_MAX_BYTES) {
                Log.w(TAG, "image skipped: " + size + " bytes");
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.toString(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "image did not decode");
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / sample > IMAGE_MAX_DIMENSION
                    || bounds.outHeight / sample > IMAGE_MAX_DIMENSION) {
                sample *= 2;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.toString(), decode);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "image decode failed", e);
            return null;
        }
    }

    private NotificationManager notificationManager() {
        try {
            return context.getSystemService(NotificationManager.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String rowJson(CapabilityNotificationListenerService.Row row) {
        return new StringBuilder(160)
                .append("{\"id\":").append(row.id)
                .append(",\"tag\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(row.tag))
                .append(",\"package\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(row.packageName))
                .append(",\"title\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(row.title))
                .append(",\"content\":")
                .append(AndroidCapabilityProtocol.encodeStringValue(row.content))
                .append(",\"posted_ms\":").append(row.postedMs)
                .append('}')
                .toString();
    }
}
