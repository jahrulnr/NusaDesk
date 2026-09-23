# Bluetooth HID, audio profile, and guest routing evidence

## Test setup

- Date: 2026-09-24.
- Host: Samsung Galaxy S10e (SM-G970F), Android 12 / API 31, NusaDesk
  `0.8.0` / `versionCode 14`. The installed APK was a local, same-key QA build
  with `DEBUGGABLE` enabled for the test; the shipped version number was not
  changed.
- Peer: Samsung Galaxy S7 Edge (SM-G935F), Android 10 / API 29, unchanged
  `0.8.0` / `versionCode 14`.
- Audio peer: Sony WH-CH520, already paired on the S10e. Its Settings display
  name is user-customized as “My headphone”; the Bluetooth API identifies it
  as `WH-CH520` at `E8:9E:13:A3:53:4D`.
- Existing S10e↔S7 bond was preserved. The test did not unpair devices, change
  volume, place a call, or record microphone audio.

## Results

| Surface | Probe and observation | Result / boundary |
| --- | --- | --- |
| HID registration | With NusaDesk in the foreground: `nusadesk-bt hid start --name NusaDeskQA`, then `hid status`. The module reported `registered=true`, `connected=false`. | PASS on S10e API 31. Registration was explicitly stopped after the probe. |
| HID connection | `nusadesk-bt hid connect 30:CB:F8:57:DC:8A` to the already bonded S7 Edge. After the module was corrected to wait for a terminal `CONNECTED` callback rather than `CONNECTING`, it reported `connected=true`, host `Galaxy S7 Edge milik Jahrul`. | PASS on the second build. Both devices remained bonded. |
| HID keyboard text | On the S7 Settings search field, `nusadesk-bt hid type 'NusaDesk QA'` returned `typed=11`; the exact string appeared in the focused search field. An appended `x` followed by `hid key BACKSPACE` restored the previous search string; `ENTER` submitted the harmless query, which returned no result. | PASS for text, Backspace, and Enter. No credentials or destructive keys were sent. |
| HID mouse | `nusadesk-bt hid mouse move 10 10 --wheel 1` returned `moved=true`. | Command/report path PASS; the S7 screenshot showed no visible pointer, and no click was attempted. Visual pointer/mouse-click behavior remains unverified. |
| HID teardown | `nusadesk-bt hid stop` returned `stopped=true`; final `hid status` reported `registered=false`, `connected=false`. | PASS. The registration and peer connection were released. |
| Public audio profile status | With WH-CH520 connected, `nusadesk-bt audio status` listed A2DP and HEADSET/HFP as available, each with the device connected (`state=2`). | PASS on S10e API 31. No profile connect/disconnect API was invoked. |
| HFP voice recognition | `nusadesk-bt audio voice start` without an address selected the single connected HFP device and returned `started=true`; `voice stop` returned `stopped=true` for the same address. | PASS on WH-CH520. No call was made and no microphone sample was captured. |
| Bluetooth outputs | `nusadesk-android media outputs` while the headset was connected listed `device_id=418` (`bluetooth_sco`) and `device_id=421` (`bluetooth_a2dp`), both `is_sink=true`, in addition to built-in outputs. | PASS. These IDs were used only from the fresh snapshot and not persisted. |
| Per-player route | `nusadesk-android media route 421` before playback returned `applied=false` (pending). A 60-second, low-amplitude test tone was started through `termux-media-player`; applying route 421 while the player was active returned `applied=true`. Android `dumpsys audio` reported an active `MediaPlayer` playback configuration with `deviceId:421`, `state:started`, `usage=USAGE_MEDIA`. | PASS at the Android routing/state level for this app-owned player and WH-CH520. Physical audibility was not independently measured. No system-wide route claim is made. |
| Route teardown | `termux-media-player stop` and `nusadesk-android media route clear`; subsequent info reported no track. The headset was disconnected in Android Settings, not unpaired. | PASS. Final bridge status showed no connected A2DP/HEADSET device and no pending route. |
| Stale output ID | After the app restart disconnected the headset, the former ID 421 was rejected as `mediaplayer-route-device-unknown`. | PASS. Confirms output IDs are ephemeral and revalidated. |
| Built-in speaker route | A route to the current built-in speaker ID was accepted as pending; on this S10e ROM, test playback drained before a track could be observed (`No track currently!` on the subsequent query). | Not fully verified. The route implementation is unit-covered; this run did not prove built-in-speaker audibility or sustained output. |

## Cleanup

- HID app registration was stopped, and final status was `registered=false`,
  `connected=false`.
- Media playback was stopped, `mediaplayer.route.clear` was run, and no track
  remained.
- WH-CH520 was disconnected through Android Settings after the test; it was
  not unpaired. The S10e reported no connected audio profile afterward.
- The temporary `authorized_keys` entry, temporary public key, ADB forward, WAV
  file, screenshots, and local SSH key were removed. No temporary ADB forward
  remains.

## Coverage limit

The full local JVM suite completed with 1,866 tests, 0 failures, and 0 errors;
`lintDebug` and `assembleDebug` passed. This does not establish behavior across
all OEMs, other HID hosts, LE Audio headsets, or profiles that the public SDK
does not expose. Mouse pointer visibility/click and built-in-speaker audibility/
sustained routing remain unverified.
