# Camera and microphone (live stream and file capture)

Live media is the guest camera/microphone session. Android captures
with Camera2 and AudioRecord, encodes H.264 video and AAC-LC audio
with hardware encoders, and serves one loopback-only RTSP-over-TCP
stream. The stream session itself is live-only: it writes no
capture file and persists nothing, so save from the stream if
that is what you need. Still photos and microphone recordings
are a separate file-capture surface - see the capture section
below.

There are three track modes, one session at a time:

| mode | tracks | command |
| --- | --- | --- |
| `both` | camera + microphone | `nusadesk-android media start` |
| `camera` | camera only | `nusadesk-android media start --camera` |
| `microphone` | microphone only | `nusadesk-android media start --microphone` |

A camera-only session never asks for the microphone grant, and a
microphone-only session never asks for the camera grant. Asking for
another mode while a session runs returns `media-mode-conflict`;
stop the running session first.

## Lifetime

The stream lives only while both of these hold:

- the guest bridge session for this app is live, and
- the user-visible foreground media service for the active mode runs.

`media stop` stops the service; closing the bridge session also
tears the stream down. Nothing is stored for resuming later.

## Commands

- `nusadesk-android media start` - start camera + microphone; on
  success the JSON response reports `state` = `running` and the
  `rtsp_url`
- `nusadesk-android media start --camera` - start camera only
- `nusadesk-android media start --microphone` - start microphone only
- `nusadesk-android media status` - always ok; reports the explicit
  `state` (`stopped`, `starting`, `running`, `failed`) and the
  active `mode`
- `nusadesk-android media stop` - idempotent; always reports
  `state` = `stopped`

## States

| state | meaning |
| --- | --- |
| `stopped` | no live session |
| `starting` | start accepted, pipeline coming up |
| `running` | RTSP listening on loopback, the mode's encoders producing |
| `failed` | session failed with a typed `error` |

## Running response

In the running state the response carries flat fields: `state` =
`running`, `mode` (`both`, `camera`, `microphone`), `rtsp_url` =
`rtsp://127.0.0.1:<port>/`, and `client_limit` = 2.

Video fields appear only for a video mode: `video_codec` = `h264`,
`video_width` / `video_height` (target at most 1280 x 720), and
`video_fps` = 30.

Audio fields appear only for an audio mode: `audio_codec` = `aac`.

A microphone-only session therefore reports no video size, and a
camera-only session reports no audio codec — the status never
implies a track that does not exist.

The stream binds only to `127.0.0.1` and serves RTSP over TCP with
a bounded client cap (`client_limit`). It is never exposed on the
LAN. The SDP advertises exactly the tracks of the active mode, and
`SETUP` for a track the mode does not carry answers `404`.

## Consuming the stream

Use the `rtsp_url` from a successful start or a running
`media status`:

```sh
ffplay -rtsp_transport tcp 'rtsp://127.0.0.1:<port>/'
ffmpeg -rtsp_transport tcp -i 'rtsp://127.0.0.1:<port>/' -c copy out.mp4
# microphone-only session: keep just the audio track
ffmpeg -rtsp_transport tcp -i 'rtsp://127.0.0.1:<port>/' -vn -c:a copy out.m4a
# camera-only session: keep just the video track
ffmpeg -rtsp_transport tcp -i 'rtsp://127.0.0.1:<port>/' -an -c:v copy out.h264
```

OpenCV (C++ or Python) can open the same URL with the TCP transport.
Saving is manual; NusaDesk does not persist the stream.

## Permissions and foreground

The bridge never opens a permission prompt. Each mode needs only its
own grants: CAMERA for a camera mode, RECORD_AUDIO for an audio mode,
both for the combined mode. A missing or denied grant is a typed
`media-*` error, never a prompt. Capture is user-visible foreground
work, and the notification names only the active mode's tracks.

## Typed errors

| error | meaning |
| --- | --- |
| `media-permission-required` | a grant the mode needs is missing, no denial on record |
| `media-permission-denied` | a grant the mode needs was denied |
| `media-foreground-required` | app not eligible to start the camera/microphone foreground service |
| `media-unavailable` | platform cannot serve the stream |
| `media-busy` | a start is already in flight or the camera is in use |
| `media-mode-conflict` | another mode is already running; stop it first |
| `media-encoder-unavailable` | a hardware encoder or codec metadata is not ready |
| `media-start-failed` | start did not complete within its bound |

## File capture (a separate surface)

Still photos and microphone recordings are real guest files,
served by their own bridge methods - not by this live stream:
`camera.info` (no grant), `camera.photo` (CAMERA), and the
stateful `microphone.record.start|info|stop` (RECORD_AUDIO). A
recording outlives the command that started it and is bounded to
0..900 seconds (`0` selects the ceiling); encoders are `aac`,
`amr_wb`, `amr_nb`, or `opus`. Capture runs through the
foreground host, so the app must be able to surface a window for
a photo or a recording start.

The guest passes a path inside the rootfs (the generated
clients stage under `/tmp`), the host fills it, and the guest
moves the result to the destination itself - a workspace bind
is guest-side only. The installed clients are the optional
Termux toolkit's `termux-camera-info`,
`termux-camera-photo [-c ID] FILE`, and
`termux-microphone-record [-f FILE] [-l SEC] [-e ENC] [-b KBPS]
[-r HZ] [-c N] [-i] [-q] [-d]`; see termux-compat.md when
installed. Errors are typed names like the media ones - mostly
`camera-*` / `microphone-*`, with generic `invalid-argument` and
`foreground-*` possible.

## Retired names

The old `camera.snapshot` and `mic.record` names stay retired:
the bridge does not expose them. The capture methods above are
the file surface - do not look for the retired names.

> Docs generated by the NusaDesk Android app (version %APP_VERSION%). Manual edits may be overwritten on the next session start.


