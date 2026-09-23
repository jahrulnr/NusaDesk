# ADR-0053: Bluetooth HID device, audio profile status, and guest playback routing

- Status: accepted
- Date: 2026-09-24

## Context

ADR-0050 shipped Bluetooth adapter, BLE, GATT, and RFCOMM operations. The
remaining useful public Android surfaces are asymmetric: Android exposes the
phone as a Bluetooth HID **device** through `BluetoothHidDevice`, and exposes
connected audio profile proxies plus HFP voice-recognition controls, but does
not expose ordinary-app profile connect/disconnect methods. Android also lets
one app-owned `MediaPlayer` select an available `AudioDeviceInfo`; it does not
let that player route other apps or force a system-wide route.

The implementation must keep those distinctions visible to guest automation.
It must not pretend the phone can become a public A2DP sink, Bluetooth PAN
router, HID host, or global audio switch, and it must not use hidden/reflection
APIs or expose arbitrary HID report bytes.

## Decision

1. **Fixed HID keyboard/mouse peripheral (`BluetoothHidModule`).** Register one
   fixed composite descriptor through public `BluetoothHidDevice`, with
   separate keyboard and mouse report IDs. Registration is user-initiated and
   requires NusaDesk to be foreground; its public API may unregister the app
   when it backgrounds. Connect only to an already bonded host, use callback
   state as the source of truth, and release registration/profile resources on
   `stop` or bridge close. HID reports are generated only from a 128-character
   printable-ASCII text request, a fixed named-key allowlist, relative mouse
   deltas in `-127..127`, and left/right/middle clicks. No caller-supplied
   descriptor, report ID, or raw report data is accepted.
2. **Public audio profile observation and HFP voice path
   (`BluetoothAudioModule`).** Acquire bounded public A2DP, HEADSET, and (API
   33+) LE Audio profile proxies for status, cap rows, and close every proxy,
   including late callbacks after timeout. `bt.audio.voice.start|stop` uses only
   `BluetoothHeadset.startVoiceRecognition/stopVoiceRecognition` on an already
   connected headset. When no address is supplied, start requires exactly one
   connected headset; ambiguity is typed. This is not A2DP media, a call, or
   app microphone capture. No profile connection/disconnection is attempted.
3. **Per-player guest audio routing (`mediaplayer.*`).** `outputs` enumerates
   currently available output devices; `route(device_id)` selects a current
   sink for NusaDesk's own `MediaPlayer`; `route.clear` drops that preference.
   IDs are ephemeral, process/session-local, validated against a fresh Android
   output list, and cleared when the capability session closes. The device's
   `routed_output_id`, when Android reports one, is observational; acceptance
   of `setPreferredDevice` is not a claim that audio is currently flowing.
4. **Bounded native guest CLIs.** Add only fixed `nusadesk-bt hid|audio ...`
   verbs and fixed `nusadesk-android media outputs|route ...` commands. The
   existing no-generic-method/no-raw-JSON boundary remains. Bluetooth
   permission is checked through the existing `BLUETOOTH_CONNECT` path; no
   manifest permission is added.
5. **Hard limits remain explicit.** Do not implement HID host, A2DP profile
   connect/disconnect, A2DP sink, PAN/tethering, global audio routing, or
   unrestricted HID injection. Those paths are absent from the public SDK or
   require system/privileged access beyond the app's contract.

## Consequences

- The guest can present the phone as a conservative keyboard/mouse HID device,
  inspect public audio profile status, request/stop an existing HFP
  voice-recognition path, and route guest playback to a current audio output.
- HID registration requires the app in the foreground and only one Android HID
  Device app can be registered at a time. A missing registration or connection
  is reported as missing state, never as a successful input operation.
- HFP voice recognition may open the headset's voice channel, but it does not
  capture or return microphone samples to the guest. The OS and the headset
  still decide whether a profile/device is available.
- Output selection applies only to the app's player. It neither connects a
  Bluetooth profile nor changes audio for other apps; output IDs must be
  refreshed after device changes.
- Unit coverage is owned by `BluetoothHidModuleTest` (31),
  `BluetoothAudioModuleTest` (15), and `MediaPlaybackRoutingTest` (21), plus
  generated guest CLI contract tests. The final Gradle suite completed 1,866
  tests with no failures; `lintDebug` and `assembleDebug` passed. Physical-device
  observations and their limits are recorded in
  `docs/evidence/bluetooth-hid-audio-matrix.md`.

## Device evidence

The acceptance matrix is maintained in
[`docs/evidence/bluetooth-hid-audio-matrix.md`](../evidence/bluetooth-hid-audio-matrix.md).
It distinguishes unit coverage from observed device behavior and records the
actual Android/API/model, connected peer, result, and cleanup for each probe.
