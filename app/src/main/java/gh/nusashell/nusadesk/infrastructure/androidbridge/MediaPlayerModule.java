package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.content.Intent;

import java.nio.file.Path;
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
 */
public final class MediaPlayerModule implements CapabilityModule {

    private static final String METHOD_PLAY = "mediaplayer.play";
    private static final String METHOD_PAUSE = "mediaplayer.pause";
    private static final String METHOD_STOP = "mediaplayer.stop";
    private static final String METHOD_INFO = "mediaplayer.info";

    private static final int PATH_MAX_CHARS = 4096;
    private static final int NAME_MAX_CHARS = 512;

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
        return List.of(METHOD_PLAY, METHOD_PAUSE, METHOD_STOP, METHOD_INFO);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_PLAY);
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
        if (result.getOutcome() == MediaPlaybackService.Outcome.FAILED) {
            // Nothing loaded: drop the foreground slot the start created.
            try {
                context.stopService(
                        new Intent(context, MediaPlaybackService.class));
            } catch (RuntimeException ignored) {
                // The service settles itself; nothing else to clean up.
            }
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "mediaplayer-play-failed");
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
