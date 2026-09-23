package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.content.Intent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gh.nusashell.nusadesk.infrastructure.service.MediaPlaybackService;

/**
 * Media-player capability domain: {@code mediaplayer.play},
 * {@code mediaplayer.pause}, {@code mediaplayer.stop}, and
 * {@code mediaplayer.info} — the bridge half of {@code termux-media-player}.
 *
 * <p>Playback lives in {@link MediaPlaybackService}: one process-wide
 * {@link android.media.MediaPlayer} holding at most one track, the same
 * shape as upstream's {@code MediaPlayerService}. The service holds the
 * user-visible {@code mediaPlayback} foreground slot; this module only
 * validates params, resolves the guest staging path (contract §8.1), maps
 * outcomes onto upstream's message texts, and reports typed errors. Platform
 * exceptions are logged inside the service and never reach the wire.</p>
 *
 * <p>The routing methods ({@code mediaplayer.outputs},
 * {@code mediaplayer.route}, {@code mediaplayer.route.clear}) expose the
 * public per-player {@code AudioRouting} surface only: the guest can list the
 * current output sinks as {@code outputs_json} rows of {@code device_id},
 * {@code type}, {@code name}, {@code is_sink} (bounded to
 * {@value #MAX_OUTPUT_ROWS}), pin this app's own playback to one sink, and
 * clear the pin. Device ids are ephemeral and never persisted; a remembered
 * route is revalidated against a fresh device list when a track begins and a
 * stale id surfaces as {@code mediaplayer-route-failed}. This is not global
 * route control and does not connect audio profiles.</p>
 */
public final class MediaPlayerModule implements CapabilityModule {

    private static final String METHOD_PLAY = "mediaplayer.play";
    private static final String METHOD_PAUSE = "mediaplayer.pause";
    private static final String METHOD_STOP = "mediaplayer.stop";
    private static final String METHOD_INFO = "mediaplayer.info";
    private static final String METHOD_OUTPUTS = "mediaplayer.outputs";
    private static final String METHOD_ROUTE = "mediaplayer.route";
    private static final String METHOD_ROUTE_CLEAR = "mediaplayer.route.clear";

    private static final int PATH_MAX_CHARS = 4096;
    private static final int NAME_MAX_CHARS = 512;
    private static final int MAX_OUTPUT_ROWS = 32;

    private final Context context;
    private final GuestFilePathResolver paths;

    public MediaPlayerModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context application = context.getApplicationContext();
        this.context = application;
        this.paths = new GuestFilePathResolver(application);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_PLAY, METHOD_PAUSE, METHOD_STOP, METHOD_INFO,
                METHOD_OUTPUTS, METHOD_ROUTE, METHOD_ROUTE_CLEAR);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_PLAY, METHOD_ROUTE);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(
            AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        try {
            switch (request.getMethod()) {
                case METHOD_PLAY:
                    return play(request);
                case METHOD_PAUSE:
                    return pause(request);
                case METHOD_STOP:
                    return stop(request);
                case METHOD_INFO:
                    return info(request);
                case METHOD_OUTPUTS:
                    return outputs(request);
                case METHOD_ROUTE:
                    return route(request);
                case METHOD_ROUTE_CLEAR:
                    return routeClear(request);
                default:
                    return AndroidCapabilityProtocol.Response.error(
                            request.getId(), "unsupported-method");
            }
        } catch (GuestFilePathResolver.Invalid invalid) {
            // A guest path outside the rootfs is a bad argument, not a
            // capability failure.
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "invalid-argument");
        }
    }

    @Override
    public void close() {
        MediaPlaybackService.releaseAll(context);
    }

    /**
     * {@code mediaplayer.play} — params {@code path} (guest staging path to
     * the media file; absent means "resume the paused track") and
     * {@code name} (display name for the notification and the messages,
     * defaulting to the staged file name). With {@code path} the file is
     * loaded and started; without it the loaded track resumes, matching
     * upstream's {@code play} vs {@code play <file>} split.
     */
    private AndroidCapabilityProtocol.Response play(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("path", "name"));
        String guestPath = params.optionalString("path", PATH_MAX_CHARS, "");
        String display = params.optionalString("name", NAME_MAX_CHARS, "");
        if (guestPath.isEmpty()) {
            return resume(request);
        }
        Path hostPath = paths.resolveForRead(guestPath);
        if (display.isEmpty()) {
            Path fileName = hostPath.getFileName();
            display = fileName == null ? guestPath : fileName.toString();
        }
        String foregroundError = MediaPlaybackService.ensureForeground(context);
        if (foregroundError != null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), foregroundError);
        }
        MediaPlaybackService.PlaybackResult result =
                MediaPlaybackService.play(hostPath, display);
        if (result.getOutcome() == MediaPlaybackService.Outcome.FAILED
                || result.getOutcome() == MediaPlaybackService.Outcome.ROUTE_FAILED) {
            // Nothing loaded: drop the foreground slot the start created.
            try {
                context.stopService(
                        new Intent(context, MediaPlaybackService.class));
            } catch (RuntimeException ignored) {
                // The service settles itself; nothing else to clean up.
            }
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(),
                    result.getOutcome() == MediaPlaybackService.Outcome.ROUTE_FAILED
                            ? "mediaplayer-route-failed" : "mediaplayer-play-failed");
        }
        return AndroidCapabilityProtocol.Response.success(
                request.getId(), fields(result,
                        "Now Playing: " + result.getTrack()));
    }

    private AndroidCapabilityProtocol.Response resume(
            AndroidCapabilityProtocol.Request request) {
        MediaPlaybackService.PlaybackResult result = MediaPlaybackService.resume();
        switch (result.getOutcome()) {
            case NO_TRACK:
                return AndroidCapabilityProtocol.Response.success(
                        request.getId(), fields(result,
                                "No previous track to resume!\n"
                                        + "Please supply a new media file"));
            case ALREADY_PLAYING:
                return AndroidCapabilityProtocol.Response.success(
                        request.getId(), fields(result,
                                "Already playing track!\n"
                                        + positionBlock(result)));
            case RESUMED:
                return AndroidCapabilityProtocol.Response.success(
                        request.getId(), fields(result,
                                "Resumed playback\n" + positionBlock(result)));
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "mediaplayer-play-failed");
        }
    }

    /** {@code mediaplayer.pause} — no params; upstream's pause texts. */
    private AndroidCapabilityProtocol.Response pause(
            AndroidCapabilityProtocol.Request request) {
        MediaPlaybackService.PlaybackResult result = MediaPlaybackService.pause();
        switch (result.getOutcome()) {
            case NO_TRACK:
                return AndroidCapabilityProtocol.Response.success(
                        request.getId(), fields(result, "No track to pause"));
            case ALREADY_PAUSED:
                return AndroidCapabilityProtocol.Response.success(
                        request.getId(), fields(result, "Playback already paused"));
            case PAUSED:
                return AndroidCapabilityProtocol.Response.success(
                        request.getId(), fields(result, "Paused playback"));
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "mediaplayer-unavailable");
        }
    }

    /** {@code mediaplayer.stop} — no params; clears the track and the FGS. */
    private AndroidCapabilityProtocol.Response stop(
            AndroidCapabilityProtocol.Request request) {
        MediaPlaybackService.PlaybackResult result = MediaPlaybackService.stopTrack();
        if (result.getOutcome() == MediaPlaybackService.Outcome.NO_TRACK) {
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), fields(result, "No track to stop"));
        }
        if (result.getOutcome() == MediaPlaybackService.Outcome.STOPPED) {
            try {
                context.stopService(
                        new Intent(context, MediaPlaybackService.class));
            } catch (RuntimeException ignored) {
                // The service settles itself; the track is already cleared.
            }
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), fields(result,
                            "Stopped playback\nTrack cleared"));
        }
        return AndroidCapabilityProtocol.Response.error(
                request.getId(), "mediaplayer-unavailable");
    }

    /** {@code mediaplayer.info} — no params; upstream's status block. */
    private AndroidCapabilityProtocol.Response info(
            AndroidCapabilityProtocol.Request request) {
        MediaPlaybackService.PlaybackResult result = MediaPlaybackService.info();
        if (result.getOutcome() == MediaPlaybackService.Outcome.NO_TRACK) {
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), fields(result, "No track currently!"));
        }
        String message = "Status: " + (result.isPlaying() ? "Playing" : "Paused")
                + "\nTrack: " + result.getTrack()
                + "\nCurrent Position: " + timeString(result.getPositionMs())
                + " / " + timeString(result.getDurationMs());
        return AndroidCapabilityProtocol.Response.success(
                request.getId(), fields(result, message));
    }

    /**
     * {@code mediaplayer.outputs} — no params; the current output sinks as
     * {@code outputs_json} rows {@code {device_id,type,name,is_sink}} sorted
     * by id, bounded to {@value #MAX_OUTPUT_ROWS} with {@code count} and a
     * {@code truncated} flag. The ids are transient and valid only against a
     * fresh list.
     */
    private AndroidCapabilityProtocol.Response outputs(
            AndroidCapabilityProtocol.Request request) {
        List<MediaPlaybackService.AudioOutput> devices =
                new ArrayList<>(MediaPlaybackService.outputs(context));
        devices.sort(Comparator.comparingInt(MediaPlaybackService.AudioOutput::getId));
        int count = Math.min(devices.size(), MAX_OUTPUT_ROWS);
        StringBuilder json = new StringBuilder(count * 96);
        json.append('[');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            MediaPlaybackService.AudioOutput device = devices.get(i);
            json.append("{\"device_id\":").append(device.getId())
                    .append(",\"type\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(device.getType()))
                    .append(",\"name\":")
                    .append(AndroidCapabilityProtocol.encodeStringValue(device.getName()))
                    .append(",\"is_sink\":").append(device.isSink())
                    .append('}');
        }
        json.append(']');
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("outputs_json", json.toString());
        fields.put("count", (long) count);
        if (devices.size() > MAX_OUTPUT_ROWS) {
            fields.put("truncated", true);
        }
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code mediaplayer.route} — params {@code device_id} (required
     * integer). The id must name a current output sink; the choice is applied
     * to the live player or remembered process-locally for the next one.
     */
    private AndroidCapabilityProtocol.Response route(
            AndroidCapabilityProtocol.Request request) {
        String id = request.getId();
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("device_id"));
        if (!params.has("device_id")) {
            throw new CapabilityParams.Invalid("missing integer parameter: device_id");
        }
        long deviceId = params.optionalLong("device_id", 0, Integer.MAX_VALUE, -1);
        MediaPlaybackService.RouteResult result =
                MediaPlaybackService.routeTo(context, (int) deviceId);
        switch (result.getStatus()) {
            case DEVICE_UNKNOWN:
                return AndroidCapabilityProtocol.Response.error(
                        id, "mediaplayer-route-device-unknown");
            case DEVICE_NOT_OUTPUT:
                return AndroidCapabilityProtocol.Response.error(
                        id, "mediaplayer-route-device-not-output");
            case FAILED:
                return AndroidCapabilityProtocol.Response.error(
                        id, "mediaplayer-route-failed");
            default:
                break;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("device_id", (long) result.getDeviceId());
        if (result.getName() != null) {
            fields.put("name", result.getName());
        }
        if (result.getType() != null) {
            fields.put("type", result.getType());
        }
        fields.put("applied", result.isApplied());
        return AndroidCapabilityProtocol.Response.success(id, fields);
    }

    /**
     * {@code mediaplayer.route.clear} — no params; clears the preferred
     * output on the live player and the remembered choice. Idempotent:
     * {@code cleared} reports whether anything was dropped, {@code applied}
     * whether the clear reached a live player.
     */
    private AndroidCapabilityProtocol.Response routeClear(
            AndroidCapabilityProtocol.Request request) {
        MediaPlaybackService.RouteClearResult result =
                MediaPlaybackService.clearRoute();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("cleared", result.isCleared());
        fields.put("applied", result.isApplied());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /** Shared response fields: upstream message text plus typed state. */
    private static Map<String, Object> fields(
            MediaPlaybackService.PlaybackResult result, String message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("message", message);
        fields.put("state", !result.isTrackLoaded() ? "idle"
                : result.isPlaying() ? "playing" : "paused");
        if (result.getTrack() != null) {
            fields.put("track", result.getTrack());
        }
        if (result.getPositionMs() >= 0) {
            fields.put("position_ms", result.getPositionMs());
        }
        if (result.getDurationMs() >= 0) {
            fields.put("duration_ms", result.getDurationMs());
        }
        if (result.getPreferredOutputId() != null) {
            fields.put("preferred_output_id",
                    (long) result.getPreferredOutputId());
        }
        if (result.getRoutedOutputId() != null) {
            fields.put("routed_output_id", (long) result.getRoutedOutputId());
        }
        return fields;
    }

    /** Upstream's "Track: ..\nCurrent Position: .." block for resume. */
    private static String positionBlock(MediaPlaybackService.PlaybackResult result) {
        return "Track: " + result.getTrack()
                + "\nCurrent Position: " + timeString(result.getPositionMs())
                + " / " + timeString(result.getDurationMs());
    }

    /**
     * Upstream's {@code getTimeString}: whole seconds as {@code MM:SS}, with
     * hours prepended only when nonzero. Unknown positions render as 0:00,
     * matching upstream's millisecond-truncated display.
     */
    private static String timeString(long millis) {
        long totalSeconds = Math.max(millis, 0L) / 1000L;
        long hours = totalSeconds / 3600L;
        long mins = (totalSeconds % 3600L) / 60L;
        long secs = totalSeconds % 60L;
        String result = hours > 0 ? String.format(Locale.US, "%02d:", hours) : "";
        return result + String.format(Locale.US, "%02d:%02d", mins, secs);
    }
}
