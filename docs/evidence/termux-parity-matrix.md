# Termux:API parity matrix (evidence ledger)

Target: every upstream `termux-*` client command that is possible on the
project's own hardware, served by the NusaDesk bridge and **device-verified**.
Upstream contract source: the MIT-licensed client package
(`termux-api-package`, 57 scripts). The GPL-3.0 app is read only for its
method -> permission mapping, never copied.

This ledger is tracked in the repository because ADR-0049 makes recorded device
evidence the acceptance gate: a row reads `DONE` only after its output was
observed on a device, and the public docs (CHANGELOG, README, SECURITY,
limitations, test plan) cite it. Update it here as device passes land, and keep
the cited docs in step.

Status legend: `DONE` device-verified with recorded output, `WIP` implemented
but not yet verified on device, `TODO` not implemented, `ABSENT` deliberately
not offered (hardware/platform hard limit, documented in the guest doc).

| # | command | upstream flags | bridge method(s) | Android permission / access | status |
|---|---|---|---|---|---|
| 1 | termux-battery-status | - | battery.status | none | DONE (2026-09-19) |
| 2 | termux-contact-list | - | contacts.list | READ_CONTACTS | DONE (2026-09-19) |
| 3 | termux-location | -p provider, -r request | location.get, location.stream.* | ACCESS_FINE_LOCATION | DONE (2026-09-22, S10e after the fix: 30 s window + freshest-last-known provider selection returned a fresh network fix `{"latitude":-6.1926605,...,"provider":"network"}`; `-r last` answered instantly with a `last known fix (0 ms old)` stderr note; no fix at all still answers typed `location-timeout`) |
| 4 | termux-sensor | -s, -n, -d, -l, -a, -c | sensor.list/read/stream.* | none | DONE (2026-09-22, S10e: `-l` 42 sensors; light/proximity/magnetic_field samples) |
| 5 | termux-sms-list | -l, -o, -t, -n, -f | sms.inbox | READ_SMS, READ_CONTACTS | DONE (2026-09-22 S10e empty inbox; 2026-09-23 S7 Edge non-empty inbox: real Telkomsel OTP row with address/body/read/received) |
| 6 | termux-telephony-deviceinfo | - | telephony.info | READ_PHONE_STATE | DONE (2026-09-22 S10e sim absent; 2026-09-23 S7 Edge with SIM: `{"phone_type":"gsm","sim_state":"ready","network_type":"lte"}`) |
| 7 | termux-telephony-cellinfo | - | telephony.cellinfo | ACCESS_FINE_LOCATION | DONE (2026-09-19) |
| 8 | termux-call-log | -l, -o | calllog.list | READ_CALL_LOG | DONE (2026-09-22, S10e: `[]` — the device call log is empty; bridge read verified) |
| 9 | termux-sms-send | -n numbers, -s slot, text/stdin | sms.send | SEND_SMS, READ_PHONE_STATE (READ_SMS only when granted: sent-box confirmation) | DONE (2026-09-23, S7 Edge with SIM: two real SMS to 082218594993 landed in the platform sent box; the second ran on the fixed path and exited 0. Fixed a false negative: when the platform never delivers the per-part result broadcast, the module now confirms against `content://sms/sent` before calling a dispatched message unsent. That confirmation reads the sent box, so it runs only when `READ_SMS` is granted — the send path itself never demands read access, and without the grant a dispatched part stays honestly `unconfirmed`) |
| 10 | termux-telephony-call | number | phone.call | CALL_PHONE | DONE (2026-09-23, S7 Edge with SIM: real call placed, `mCallState=2` + InCallActivity while ringing, hung up cleanly; the foreground host runs it because a background ACTION_CALL is dropped) |
| 11 | termux-audio-info | - | audio.info | none | DONE (2026-09-22, S10e: 48000 Hz, buffer 192, latency 4/40 ms, 2 ch) |
| 12 | termux-volume | stream volume | volume.get / volume.set | none | DONE (2026-09-22, S10e: 6 stream + set music=9; invalid stream typed) |
| 13 | termux-brightness | value | brightness.set | WRITE_SETTINGS (special) | DONE (2026-09-22, S10e: permission-required first, then `permission.request mode=settings` opened the system screen, toggle on, `settings get system screen_brightness` = 120) |
| 14 | termux-torch | on/off | torch.set | none | DONE (2026-09-22, S10e: enabled true/false, camera_id 0) |
| 15 | termux-vibrate | -d, -f | vibrate | VIBRATE | DONE (2026-09-22, S10e: silent -> vibrated=false, force -> vibrated=true) |
| 16 | termux-media-scan | -r, paths | media.scan | none | PARTIAL (2026-09-22 S10e: runs, "Finished scanning 0 file(s)" for a path inside the app-private rootfs — the media provider cannot read it; indexing works for workspace-bound paths) |
| 17 | termux-wallpaper | -f file, -u url, -l | wallpaper.set | SET_WALLPAPER | DONE (2026-09-22, S10e: a 512x512 PNG and a stock 3 MB webp both applied — screenshots show the change) |
| 18 | termux-download | -d, -t, -p, url | download.request | none (DownloadManager) | DONE (2026-09-22, S10e: `/sdcard/Download/LICENSE` 1068 B and `download-test.txt` 1068 B landed) |
| 19 | termux-clipboard-get | - | clipboard.get | none | DONE (2026-09-22, S10e: round trip with `termux-clipboard-set` while the app was foreground) |
| 20 | termux-clipboard-set | text/stdin | clipboard.set | none | DONE (2026-09-22) |
| 21 | termux-toast | -s, text | toast | none | DONE (2026-09-22, S10e: screenshot shows the toast with the NusaDesk icon) |
| 22 | termux-notification | many flags | notification.post | POST_NOTIFICATIONS | DONE (2026-09-22, S10e: `dumpsys notification` shows tag 77/43 with title/content/channel) |
| 23 | termux-notification-channel | -d, id name | notification.channel | POST_NOTIFICATIONS | DONE (2026-09-22, S10e: "Created channel with id \"testchan\"") |
| 24 | termux-notification-list | - | notification.list | notification-listener access | DONE (2026-09-22, S10e: 6 rows incl. other apps after `cmd notification allow_listener`; before the grant it answers typed `notification-permission-required`) |
| 25 | termux-notification-remove | id | notification.remove | POST_NOTIFICATIONS | DONE (2026-09-22) |
| 26 | termux-tts-engines | - | tts.engines | none | DONE (2026-09-22, S10e: Samsung SMT + Google TTS listed) |
| 27 | termux-tts-speak | -e -l -n -v -p -r -s, text/stdin | tts.speak | none | DONE (2026-09-22, S10e: spoke with `-l en -n US` on both engines; the Samsung engine lacks id-ID so the default-language call answers typed `tts-language-unsupported:ind_IDN`) |
| 28 | termux-speech-to-text | - | speech.recognize | RECORD_AUDIO (foreground) | WIP (2026-09-23: neither project device has a usable recognizer backend — S10e answers `speech-unavailable:network error`, S7 Edge has no `voice_recognition_service` and answers bare `speech-unavailable`; the command fails typed instead of pretending) |
| 29 | termux-dialog | -t, -i, -p, -w, -l, -n, -m | dialog.show | none (foreground activity) | DONE (2026-09-22, S10e: real dialog; OK -> `{"code":-1,"text":"typed-text-ok"}`, Cancel -> `{"code":-2,"text":""}`; an ignored dialog returns typed `foreground-timeout` after the 120 s bound, and the response does arrive) |
| 30 | termux-camera-info | - | camera.info | none | DONE (2026-09-22, S10e: 4 cameras with facing + JPEG size lists) |
| 31 | termux-camera-photo | -c id, output-file | camera.photo | CAMERA (foreground host) | DONE (2026-09-22, S10e: front camera wrote a real 16.3 MB JPEG (scene visible); the rear capture was black because the phone lay face-down) |
| 32 | termux-microphone-record | -d -f -l -e -b -r -c -i -q | microphone.record.* | RECORD_AUDIO (FGS microphone) | DONE (2026-09-22, S10e: `-f /tmp/rec.m4a -l 8` then `-q` produced a 13 553-byte M4A, ffprobe duration 8.192 s) |
| 33 | termux-media-player | info/play/pause/stop | mediaplayer.* | FOREGROUND_SERVICE_MEDIA_PLAYBACK | DONE (2026-09-22, S10e: position advanced 0 -> 3.73 s of a 6 s WAV; fixed a real defect: the FD data source made the track stop within 35 ms) |
| 34 | termux-saf-managedir | - | saf.manage | SAF tree picker (foreground) | DONE (2026-09-22, S10e: picked Documents, persisted grant returned) |
| 35 | termux-saf-dirs | - | saf.trees | persisted SAF grant | DONE (2026-09-22, S10e) |
| 36 | termux-saf-ls | folder-uri | saf.list | persisted SAF grant | DONE (2026-09-22, S10e: real Documents rows) |
| 37 | termux-saf-stat | uri | saf.stat | persisted SAF grant | DONE (2026-09-22, S10e: name/type/uri/last_modified/length) |
| 38 | termux-saf-create | -t, folder-uri name | saf.create | persisted SAF grant | DONE (2026-09-22, S10e: created a real file, visible from the host at /sdcard/Documents) |
| 39 | termux-saf-mkdir | parent-uri name | saf.create (directory) | persisted SAF grant | DONE (2026-09-22) |
| 40 | termux-saf-read | uri | saf.read | persisted SAF grant | DONE (2026-09-22, S10e: read back the exact written bytes) |
| 41 | termux-saf-write | uri (stdin) | saf.write | persisted SAF grant | DONE (2026-09-22) |
| 42 | termux-saf-rm | uri | saf.remove | persisted SAF grant | DONE (2026-09-22) |
| 43 | termux-storage-get | output-file | storage.get | SAF picker (foreground) | DONE (2026-09-22, S10e: picked /sdcard/Documents/host-test.txt; the guest got the exact content) |
| 44 | termux-share | -a -c -d -t, file/stdin | share.send | none (foreground chooser) | DONE (2026-09-22, S10e: chooser shown; Chrome displayed the shared text from the content URI) — two defects fixed on device: missing chooser read grant, and no MIME guess |
| 45 | termux-keystore | list/delete/generate/sign/verify | keystore.* | none (AndroidKeyStore) | DONE (2026-09-22, S10e: RSA-2048 hardware-backed key listed; ECDSA sign produced a 71-byte DER signature; verify true for the same data and false for tampered data) |
| 46 | termux-job-scheduler | -p -s --job-id ... | jobscheduler.* | none (needs live session) | DONE (2026-09-22, S10e: schedule + list + `cmd jobscheduler run -f` executed the guest script once (`/tmp/job-ran.txt`)) |
| 47 | termux-nfc | -r -w -e, -t | nfc.read / nfc.write | NFC (foreground reader mode) | PARTIAL (2026-09-23, S7 Edge: a real tag was detected in reader mode and answered the upstream shape `{"error":"Wrong Technology","description":"termux API support only NDEF Tag"}` — the available tag is not NDEF, so an NDEF read/write still needs an NDEF tag) |
| 48 | termux-infrared-frequencies | - | infrared.frequencies | TRANSMIT_IR | DONE as typed-absent (2026-09-22, S10e: `infrared-unavailable:this device has no IR emitter` rc 1) |
| 49 | termux-infrared-transmit | -f, pattern | infrared.transmit | TRANSMIT_IR | DONE as typed-absent (2026-09-22, S10e, same typed absence) |
| 50 | termux-usb | -l -r -e -E, device | usb.list / usb.open | USB host + platform consent dialog | DONE for the no-device path (2026-09-22, S10e: `-l` -> `[]`); the fd path was verified 2026-09-20 with a device attached (USB-006..008) |
| 51 | termux-wifi-connectioninfo | - | wifi.connectioninfo | ACCESS_FINE_LOCATION, ACCESS_WIFI_STATE | DONE (2026-09-22, S10e: real SSID/BSSID/IP/RSSI/link speed) |
| 52 | termux-wifi-scaninfo | - | wifi.scaninfo | ACCESS_FINE_LOCATION | DONE (2026-09-22, S10e: 3 APs with band/capabilities) |
| 53 | termux-wifi-enable | true/false | wifi.set | platform forbids third-party toggle (API 29+) | DONE as typed-absent (2026-09-22, S10e: `wifi-toggle-unsupported` rc 1, never claims success) |
| 54 | termux-api-start | - | - | none | DONE (2026-09-22, S10e: no-op rc 0 + stderr note that the bridge lives with the session) |
| 55 | termux-api-stop | - | - | none | DONE (2026-09-22, S10e: same) |
| 56 | termux-sms-inbox | (deprecated alias) | sms.inbox | READ_SMS | DONE (2026-09-22, S10e: deprecation note on stderr + the same rows as termux-sms-list) |
| 57 | termux-camera-photo (front) | -c 1 | camera.photo | CAMERA | DONE (2026-09-22, S10e: front camera scene rendered) |
| 58 | termux-fingerprint | -t -d -s -c | fingerprint.authenticate | USE_BIOMETRIC (foreground) | DONE (2026-09-23, S10e: BiometricPrompt shown, the enrolled finger authenticated -> `{"auth_result":true,"errors":[],"failed_attempts":0}`; an untouched prompt answers `ERROR_TIMEOUT`, and a device without enrolment answers `ERROR_NO_ENROLLED_FINGERPRINTS`) |

## Permission inventory (what the bridge can use)

Runtime grants are asked per capability on first use (`permission.request`);
special access opens its own Settings screen and is reported honestly until
granted. The manifest is pinned by `BridgePermissionsManifestTest`.

- Declared before the parity surface: CAMERA, RECORD_AUDIO, location
  (fine+coarse+background), READ_PHONE_STATE, READ_CALL_LOG, READ_SMS,
  READ_CONTACTS, READ/WRITE_CALENDAR, POST_NOTIFICATIONS,
  MANAGE_EXTERNAL_STORAGE, SYSTEM_ALERT_WINDOW, PACKAGE_USAGE_STATS,
  QUERY_ALL_PACKAGES, USB host, BLUETOOTH_CONNECT/SCAN, ACTIVITY_RECOGNITION,
  HIGH_SAMPLING_RATE_SENSORS.
- Added for the parity surface: SEND_SMS, CALL_PHONE, VIBRATE, SET_WALLPAPER,
  TRANSMIT_IR, NFC, WRITE_SETTINGS (special), USE_BIOMETRIC,
  ACCESS_WIFI_STATE / CHANGE_WIFI_STATE / NEARBY_WIFI_DEVICES,
  MODIFY_AUDIO_SETTINGS, FOREGROUND_SERVICE_MEDIA_PLAYBACK,
  DOWNLOAD_WITHOUT_NOTIFICATION, and the notification-listener service behind
  `termux-notification-list` (special).
- Deliberately not declared: READ_MEDIA_* (nothing queries MediaStore
  image/video/audio; storage reads stay on MANAGE_EXTERNAL_STORAGE + SAF), and
  no accessibility service and no SMS receive trigger.

## Evidence

Per-command device evidence is recorded as the work lands: command, device,
observed output, date. Only rows with recorded device output may read `DONE`.

### 2026-09-22, S10e (SM-G970F, Android 12/API 31), QA build 0.6.4

Bridge foundation:

- `bridge.info` advertises the module methods:
  `...,vibrate,torch.set,volume.get,volume.set,audio.info,brightness.set,bridge.permissions,permission.request`.
- `bridge.permissions` returns per-permission state; special access uses its
  documented API (`WRITE_SETTINGS` -> granted after the Settings flow).
- `permission.request {"permissions":"android.permission.CAMERA","mode":"runtime"}`
  launched the platform dialog ("Izinkan NusaDesk mengambil gambar dan merekam
  video?"), a tap on "Saat aplikasi digunakan" returned
  `{"granted":"android.permission.CAMERA","denied":""}` and `dumpsys package`
  shows `android.permission.CAMERA: granted=true`.
- `permission.request {"permissions":"android.permission.WRITE_SETTINGS","mode":"settings"}`
  opened `com.android.settings/.Settings$WriteSettingsActivity`, returned
  `{"granted":"","denied":"...WRITE_SETTINGS","opened":"...WRITE_SETTINGS"}`;
  after enabling the toggle, `brightness.set {"value":120}` answered
  `{"ok":true,"value":120}` and `settings get system screen_brightness` = 120.

Device state (module `DeviceStateModule`):

- `vibrate {"duration_ms":300}` -> `{"vibrated":false,"duration_ms":300}` (ringer
  mode 0 = silent, no `force`); `vibrate {"duration_ms":400,"force":true}` ->
  `{"vibrated":true,"duration_ms":400}`.
- `torch.set {"enabled":true}` -> `{"enabled":true,"camera_id":"0"}`; then
  `{"enabled":false}`.
- `volume.get` -> six streams with volume/max/min/muted; `volume.set
  {"stream":"music","volume":9}` -> `{"stream":"music","volume":9,"max_volume":15}`;
  `{"stream":"nope"}` -> `volume-invalid-stream`.
- `audio.info` -> `{"sample_rate":48000,"frames_per_buffer":192,
  "output_latency_ms":4.0,"input_latency_ms":40.0,"channels":2,...}`.
- `volume.get {"bogus":1}` -> `unsupported-parameter` (framework rejects params on
  a method that declares none).

Guest compat refactor:

- `/usr/local/lib/nusadesk/termux_compat.py` installed; `/usr/local/bin/termux-*`
  are thin importers; `termux-battery-status`, `termux-telephony-deviceinfo`,
  `termux-sensor -s gyroscope -n 1`, `termux-location -p wifi` (rc 2 usage) all
  behave as before.

### 2026-09-23, S7 Edge (SM-G935F, Android 10/API 29) and S10e

- `termux-sms-send` (S7 Edge with SIM): two real SMS to 082218594993 landed in
  the platform sent box; the second ran on the fixed sent-box confirmation path
  and exited 0.
- `termux-telephony-call` (S7 Edge with SIM): a real call was placed,
  `mCallState=2` with InCallActivity while ringing, then hung up cleanly. The
  foreground host runs it because a background `ACTION_CALL` is dropped.
- `termux-sms-list` / `termux-telephony-deviceinfo` (S7 Edge with SIM): a
  non-empty inbox row and `{"phone_type":"gsm","sim_state":"ready",
  "network_type":"lte"}`.
- `termux-fingerprint` (S10e): BiometricPrompt shown, the enrolled finger
  authenticated -> `{"auth_result":true,"errors":[],"failed_attempts":0}`; an
  untouched prompt answers `ERROR_TIMEOUT`, and a device without enrolment
  answers `ERROR_NO_ENROLLED_FINGERPRINTS`.
- `termux-nfc` (S7 Edge): a real tag was detected in reader mode and answered
  the upstream "Wrong Technology" shape (the available tag is not NDEF), so an
  NDEF read/write still needs an NDEF tag.
- `termux-speech-to-text`: still WIP; neither project device has a usable
  recognizer backend (the S10e answers `speech-unavailable:network error`, the
  S7 Edge has no `voice_recognition_service`), and the command fails typed
  instead of pretending.
