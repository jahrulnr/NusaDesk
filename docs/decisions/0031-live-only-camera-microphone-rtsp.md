# ADR-0031: Live-only camera and microphone over loopback RTSP

## Status
Accepted — implementation complete; positive physical encoder/consumer
verification passed on Samsung S10e API 31. Wider OEM/API coverage remains
open. The three media methods stay parameterless; ADR-0032 later added one
bounded `params` object for the declaring calendar writes, which does not
change anything decided here.

## Date
2026-09-17

## Context

The Android capability bridge needs to expose camera and microphone data to the
Linux guest, but the guest cannot honestly access Android `/dev/video*` or
`/dev/snd` devices through PRoot. The earlier artifact design (`camera.snapshot`
and `mic.record`) was bounded, but it made NusaDesk own capture files, required
artifact binds, and did not match the desired workflow: a consumer should save
only when it explicitly needs a file.

The replacement must be live-only and must preserve the project boundaries:

- JSONL remains a control/status channel, not a media-byte transport;
- Android owns Camera2, AudioRecord, MediaCodec, permission checks, and service
  lifecycle;
- the guest needs a normal Linux-consumable URL and may use ffplay, ffmpeg, or
  OpenCV;
- there is no LAN exposure, wildcard bind, automatic permission prompt, or
  unbounded client/queue behavior;
- the Java-only Android project supports API 29 and higher; the feature must
  not add an unreviewed binary or a dependency that weakens loopback binding.

## Decision

Implement one parameterless, version-1 control surface:

- `media.start` starts a unified camera + microphone session and returns
  `state=running`, a dynamically bound `rtsp://127.0.0.1:<port>/` URL, H.264/AAC
  codec metadata, the selected video dimensions/fps, and `client_limit=2`.
- `media.camera.start` and `media.microphone.start` start the same session with
  a single track (camera-only, microphone-only). The track mode comes from the
  fixed allowlisted method, so the envelope still carries no request
  parameters.
- `media.status` always returns a success response with one explicit state
  (`stopped`, `starting`, `running`, `failed`) plus the session `mode`, and only
  the fields of the tracks that mode carries: `video_*` for a video mode,
  `audio_codec` for an audio mode.
- `media.stop` is idempotent and returns `state=stopped`.

One mode policy derives from a single `LiveMediaMode` value:

1. **Permissions.** Only the grants the mode uses are required and only those
   can mark it denied: CAMERA for a video mode, RECORD_AUDIO for an audio mode.
   A camera-only session never demands (or reports a denial for) the
   microphone, and vice versa.
2. **Foreground service.** The service claims only the types it uses
   (`CAMERA`, `MICROPHONE`, or both) and the notification names only the active
   tracks, so a user who never granted the microphone never sees a camera-only
   stream claiming it.
3. **RTSP.** The SDP advertises exactly the mode's tracks; `SETUP` for a track
   the mode does not carry answers `404`; `PLAY` requires only the advertised
   tracks; RTCP runs only for those tracks. The client's requested interleaved
   channel pair is honoured per session, because a single-track session numbers
   its first track 0-1 regardless of whether that track is video or audio.
4. **One session at a time.** Starting a session while another mode is already
   running answers the typed `media-mode-conflict` instead of silently
   replacing the live session; the guest stops it first.

The Android implementation is:

1. `AndroidLiveMediaController` checks CAMERA and RECORD_AUDIO without asking
   for permission, starts the unexported `LiveMediaService`, and waits for a
   bounded typed result.
2. `LiveMediaService` promotes immediately to a user-visible foreground
   service with `camera|microphone` types and a notification Stop action. The
   service owns the pipeline and tears it down on explicit stop, bridge close,
   failure, or destruction.
3. `Camera2EncoderSource` selects the back camera at the largest safe size no
   larger than 1280x720, feeds a surface-mode H.264 MediaCodec at 30 fps, and
   publishes Annex-B access units.
4. `AudioEncoderSource` reads mono 44.1 kHz PCM from AudioRecord and feeds an
   AAC-LC MediaCodec at 64 kbps. Encoder output is raw AAC, not ADTS.
5. `LoopbackRtspServer` binds an explicit IPv4 `127.0.0.1` address on a dynamic
   port. It supports RTSP over TCP interleaving only, publishes H.264/AAC RTP,
   emits SPS/PPS and AAC SDP metadata, replays a keyframe for new consumers,
   sends bounded RTCP sender reports, and limits clients to two.
6. Each playing client has a bounded queue. A slow consumer is disconnected on
   overflow; capture pumps never wait on a client socket. Closing the server
   closes all clients and no later capture publish is accepted.

The guest receives no capture path and NusaDesk writes no JPEG, PNG, M4A, MP4,
or other media artifact. If a user needs a file, the guest consumer saves it
explicitly, for example:

```sh
ffplay -rtsp_transport tcp 'rtsp://127.0.0.1:<port>/'
ffmpeg -rtsp_transport tcp -i 'rtsp://127.0.0.1:<port>/' -c copy out.mp4
```

The generated `/root/docs/media.md` and the fixed-allowlist
`/usr/local/bin/nusadesk-android media start|status|stop` command are the guest
contract. The CLI reads only `/run/nusadesk/android-bridge.env`, sends the
existing authenticated JSONL envelope, carries no media bytes, and never
accepts a generic method or shell command.

## Alternatives considered

### RootEncoder / RTSP-Server dependency

RootEncoder and RTSP-Server are credible Java-compatible Android libraries with
H.264/AAC and Camera2 support, and their Apache-2.0 licensing is compatible
with this project. They were not used directly in this slice because the
available JitPack artifacts add a supply-chain/reproducibility surface and the
RTSP server implementation binds a wildcard listener (`ServerSocket(port)` or
Ktor's `0.0.0.0`). Using it unmodified would violate NusaDesk's loopback-only
rule. A pinned fork with an explicit bind could be reconsidered later, but it
must preserve the same security and verification contract.

### MediaRecorder / file-backed capture

Rejected. It makes the host own a file format and artifact lifecycle, cannot
provide the desired unified live URL as directly, and encourages accidental
persistence. Consumers can choose their own muxer and storage policy.

### WebRTC or a raw guest device bind

Rejected for this slice. WebRTC would add signaling, session, and dependency
surface beyond the Linux consumer need. PRoot/SELinux does not provide honest
raw Android camera or microphone device passthrough, so binding `/dev/video*`
or `/dev/snd` would be a misleading contract.

### UDP RTP or LAN RTSP

Rejected. UDP would require more ports and teardown paths; LAN/wildcard RTSP
would expose camera and microphone data to other device actors. TCP interleaving
keeps the stream on one explicit loopback listener and lets the server enforce
the client cap without opening a UDP range.

## Consequences

### Positive

- Linux tools consume one familiar RTSP URL while Android keeps platform-specific
  capture and encoding ownership.
- No capture bytes or paths cross the control RPC, and no NusaDesk-owned media
  file can grow in app or guest storage.
- Explicit IPv4 loopback binding, per-session authenticated control, TCP-only
  interleaving, two-client cap, bounded queues, and slow-client disconnects
  preserve the project's security and resource limits.
- Permission and foreground eligibility remain typed and user-visible; the
  bridge never opens a permission UI from a socket worker.
- The implementation remains Java-only and introduces no new dependency.

### Negative and limitations

- Hardware codec and Camera2 behavior vary by OEM. A device can return typed
  `media-unavailable` or `media-encoder-unavailable` even when the Android
  permission grants are present.
- API 29+ physical verification is still required for positive H.264/AAC
  output, consumer compatibility, service teardown, and OEM camera contention.
- The service must be started while Android considers the app eligible for a
  camera/microphone foreground service. A background or locked-screen request
  can return `media-foreground-required`; this is intentional, not an
  automatic permission flow.
- The stream is intentionally not resumable and has no NusaDesk recording
  feature. A guest process that wants a file must save it itself.
- The initial RTSP implementation supports the narrow contract needed by
  ffplay/ffmpeg/OpenCV: RTSP over TCP interleaving, H.264, AAC-LC, and one
  fixed path. It is not a general-purpose RTSP server.

## Verification

- `H264RtpPacketizerTest`, `AacRtpPacketizerTest`, and `LoopbackRtspServerTest`
  cover Annex-B parsing, RTP fragmentation, marker bits, SDP, RTSP control,
  loopback rejection, TCP interleaving, keyframe replay, client caps, and
  bounded queue overflow.
- `LiveMediaServiceTest` and the manifest test cover foreground types,
  notification Stop, typed start failure, destruction cleanup, and stop while
  the pipeline start is in flight.
- `AndroidLiveMediaControllerTest`, `LiveMediaStatusTest`, and the capability
  handler/bridge tests cover typed permission/foreground/busy/failure states,
  the requested mode reaching the service, mode conflict, per-mode status
  fields, idempotent stop, authenticated dispatch, and absence of artifact
  binds.
- `LoopbackRtspServerTest` also covers the single-track sessions: an audio-only
  session describes and plays with `SETUP trackID=1` on the client's requested
  0-1 channels (and `trackID=0` answering 404), and a video-only session plays
  without any audio track. A 2026-09-17 physical pass confirmed the three modes
  on Samsung S10e SM-G970F (Android 12/API 31): camera-only ran with
  RECORD_AUDIO revoked (video only, 181 decoded frames in 6 s, notification
  naming the camera), microphone-only ran with CAMERA revoked (audio only,
  AAC 44.1 kHz mono, a 5 s recording with 216 audio frames and no decode
  errors), the combined mode still served both tracks, and a start in another
  mode answered `media-mode-conflict`.
- Generated guest docs and `GuestAwarenessCliTest` cover the fixed CLI
  allowlist, env validation, authenticated JSONL round trips, and no generic
  method/shell path.
- A positive physical pass succeeded on Samsung S10e SM-G970F
  `R39M209Q3TM` (Android 12/API 31, arm64, 4 KB pages, 2026-09-17): with
  test-only CAMERA and RECORD_AUDIO grants and the Activity visible, the actual
  guest CLI returned `running` with H.264/AAC 1280x720 metadata and a loopback
  RTSP URL. Through an `adb forward` used only for the host-side consumer,
  `ffprobe` enumerated both streams and `ffmpeg -t 3 -f null -` exited cleanly.
  The guest CLI then stopped the session; the service was gone and both grants
  were revoked. No capture file was written. This is device evidence, not a
  product-wide OEM/API claim.
