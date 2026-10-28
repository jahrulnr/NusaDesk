# Android capability bridge

NusaDesk exposes a small, authenticated capability bridge to the
guest for the active PRoot session. It is loopback-only, never bound
to the LAN, and dispatches only fixed method names; it is not a shell
and no guest-supplied method, class, or URI is ever dispatched. The
envelope is param-free except for the methods that declare a bounded
`params` object (see below).

## Session configuration

The address, port, protocol version, and session token are owned by
the app and pinned in `/run/nusadesk/android-bridge.env`:

- `NUSADESK_ANDROID_BRIDGE_ADDRESS=127.0.0.1`
- `NUSADESK_ANDROID_BRIDGE_PORT=<ephemeral port>`
- `NUSADESK_ANDROID_BRIDGE_TOKEN=<session token>`
- `NUSADESK_ANDROID_BRIDGE_PROTOCOL=1`

The token authenticates every request because loopback is only a
reachability boundary, not an identity boundary. The token is never
printed by `nusadesk-android`.

## Protocol

One connection carries exactly one newline-delimited JSON request and
receives one JSON response on the same connection. The envelope is a
flat object:

- `v` - fixed protocol version (`1`)
- `id` - bounded, unique per invocation
- `token` - the session token
- `method` - one fixed allowlist method
- `params` - optional bounded object, only for the methods that
  declare it

## Bounded params

Each method declares whether it takes parameters. Where it does,
`params` is one flat object: at most 16 keys, each key at most 32
characters, values are strings, integers, or booleans (strings at
most 8192 characters; typed keys reject the wrong JSON type), and
the whole request frame at most 64 KiB. The calendar writes are
the narrowest case:

- `calendar.insert` - `title` (required), `begin_ms` (required),
  `end_ms` (required), `all_day`, `location`, `calendar_id`
- `calendar.update` - `event_id` (required) plus at least one of
  `title`, `location`, `all_day`, or the `begin_ms` + `end_ms` pair
- `calendar.delete` - `event_id` (required)
- `mediaplayer.route` - `device_id` (required current output id)
- `camera.photo` - `path` (required guest path), `camera_id`
- `microphone.record.start` - `path` (required), `limit_seconds`,
  `encoder`, `bitrate_kbps`, `sample_rate`, `channels`
- `location.get` - `provider` (`gps`, `network`, or `passive`),
  `request` (`once` or `last`); `location.background.start` -
  `provider`, `interval_ms`, `distance_m`
- `sensor.read` - `sensor` or `all`, `count`, `delay_ms`;
  `sensor.stream.poll` - `timeout_ms`
- `permission.request` - `permissions` (required comma list),
  `mode` (`runtime` or `settings`)
- `bt.hid.start` - optional bounded `name`; `connect` requires a
  paired `address`; `type`, `key`, and `mouse.*` accept only the
  fixed, bounded report inputs described in the Bluetooth CLI help
- `bt.audio.voice.start|stop` - optional connected-headset `address`
- every other method that declares params (the termux parity
  surface and `nusadesk-*` extras such as `wifi.suggest.add`,
  `usage.query`, `overlay.show`, `usb.open`) accepts only its
  own bounded keys; the generated CLIs express them as typed
  flags

Nested objects, arrays, oversized keys or values, and control
characters are rejected at decode. A method that does not declare
parameters answers `unsupported-parameter` when one is sent, so a
request never carries input that is silently ignored, and a key the
declaring method does not know fails closed as the method's
invalid-argument-family error (`invalid-argument`, or
`calendar-invalid-argument` for the calendar writes).

Responses are one flat JSON line: success carries `ok` = `true` plus
method fields; failure carries `ok` = `false` plus a typed `error`.
Frames are bounded (64 KiB), and the bridge applies a socket timeout
and a bounded concurrency cap per session.

## Methods

- `bridge.info` - transport and capability list
- `battery.status` - Android battery snapshot
- `sensor.accelerometer`, `sensor.gyroscope` - one-shot reads;
  `sensor.list`, `sensor.read`, `sensor.stream.start|poll|stop` -
  the full sensor catalog
- `location.get`, `location.stream.start|poll|stop` -
  foreground one-shot and pull-stream fixes
- `location.background.start|poll|stop` - the opt-in background
  service (`nusadesk-loc`, "Allow all the time" grant)
- `contacts.list`, `calllog.list`, `sms.inbox`, `telephony.info`,
  `telephony.cellinfo` - read-only, bounded row reads
- `sms.send`, `phone.call` - real SMS and calls, each with its own
  per-call grant (see the messaging doc)
- `camera.info`, `camera.photo`,
  `microphone.record.start|info|stop` - capture files (media.md)
- `bridge.permissions`, `permission.request` - permission state and
  the user-visible consent/Settings flow
- the Termux:API parity methods behind the generated `termux-*`
  commands (device state, text and notifications, speech, dialog,
  storage/SAF/share/download, comms, keystore, job scheduler,
  sensors, wifi, infrared, media playback, NFC, USB, fingerprint)
  - see `docs/termux-compat.md`
- `wifi.hotspot.*`, `wifi.suggest.*`, `wifi.lock.*`, `packages.*`,
  `usage.*`, `overlay.*` - behind the `nusadesk-wifi`,
  `nusadesk-pkg`, `nusadesk-usage`, and `nusadesk-overlay` clients
- `usb.list`, `usb.open` - USB pass-through behind the optional
  `nusadesk-usb` client
- `media.start`, `media.camera.start`, `media.microphone.start`,
  `media.status`, `media.stop` - live media session
- `mediaplayer.outputs`, `mediaplayer.route`,
  `mediaplayer.route.clear` - route NusaDesk's MediaPlayer only;
  output device ids are temporary and never persisted
- `bt.*` - adapter state, pairing, discovery, BLE scan and
  advertise, GATT client and server, and RFCOMM serial -
  behind `nusadesk-bt`
- `bt.hid.start|status|connect|disconnect|type|key|mouse.*|stop` -
  present this phone as a bounded keyboard/mouse HID device
- `bt.audio.status` and `bt.audio.voice.start|stop` - public
  profile state and HFP voice-recognition control
- `calendar.list` - bounded read of the next seven days
- `calendar.insert`, `calendar.update`, `calendar.delete` - bounded
  writes into the user's own calendars

## Clients

`nusadesk-android` wraps the envelope with a fixed allowlist:

- `nusadesk-android bridge info` - discovery
- `nusadesk-android media start [--camera|--microphone]`,
  `media status`, `media stop`, `media outputs`,
  `media route DEVICE_ID`, `media route clear` - live media
  control and this app's MediaPlayer routing (ids ephemeral)
- `nusadesk-android calendar list`, `calendar add ...`,
  `calendar update EVENT_ID ...`, `calendar delete EVENT_ID` -
  bounded calendar access
- the other generated clients - `nusadesk-bt`, `nusadesk-wifi`,
  `nusadesk-pkg`, `nusadesk-usage`, `nusadesk-overlay`,
  `nusadesk-loc`, `nusadesk-serve`, `nusadesk-net`, plus the
  optional `nusadesk-usb`/`adb` and `termux-*` toolkits - map
  fixed verbs onto the methods above

There is no `nusadesk-android call <method>`. No generated CLI
forwards a user-supplied method name or a raw JSON `params`
object: each builds the bounded parameters from typed flags or
validated numeric ids, so an unknown key cannot be expressed.

> Docs generated by the NusaDesk Android app (version %APP_VERSION%). Manual edits may be overwritten on the next session start.


