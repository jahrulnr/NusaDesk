# Capability closure matrix (evidence ledger)

Tracks the 2026-09-23 recheck finding: 51 manifest permissions declared, 13
with no capability behind them. Every row now has a disposition — a shipped
capability, or a pruned declaration with the reason recorded. This ledger is
tracked because the public docs cite it; a row reads `DONE` only after its
output was observed on a device (ADR-0049's rule, applied here).

Status legend: `UNIT` unit-verified with the named test class, `DEVICE`
additionally verified on the S10e (SM-G970F, Android 12/API 31) with the
observed output recorded below, `PRUNED` declaration removed.

As of 2026-09-24 every row below is both unit-verified and device-verified.
The peer-dependent probes were closed on 2026-09-24 with the S10e and the S7
Edge as each other's peer (LE advertise/scan, GATT client against the other
phone's GATT server, RFCOMM echo, the discoverable consent dialog, pairing, and
the overlay's drawn pixels on an unlocked screen) — see the device-evidence
section.

## Permission disposition

| # | Permission | Capability | Decision | Status |
|---|---|---|---|---|
| 1 | BLUETOOTH_CONNECT | `bt.status`, `bt.devices`, `bt.pair`, `bt.gatt.*`, `bt.rfcomm.*`, `bt.gatt.server.*` | native (ADR-0050) | UNIT (`BluetoothModuleTest` 31, `BluetoothGattModuleTest` 30, `BluetoothRfcommModuleTest` 26) |
| 2 | BLUETOOTH_SCAN | `bt.discover.*`, `bt.le.scan.*` | native (ADR-0050) | UNIT (`BluetoothModuleTest`, `BluetoothLeModuleTest` 25) |
| 3 | BLUETOOTH_ADVERTISE (new) | `bt.le.advertise.*`, `bt.discoverable.request` | native (ADR-0050) | UNIT (`BluetoothLeModuleTest`, `BluetoothModuleTest`) |
| 4 | BLUETOOTH + BLUETOOTH_ADMIN (≤30) | the same paths on legacy API levels | native (ADR-0050) | UNIT |
| 5 | CHANGE_WIFI_STATE | `wifi.hotspot.*`, `wifi.suggest.*`, `wifi.lock.*` (toggle stays typed-absent) | native (ADR-0051) | UNIT (`WifiExtrasModuleTest` 26) |
| 6 | PACKAGE_USAGE_STATS | `usage.query`, `usage.events`, `usage.standby` | native (ADR-0052) | UNIT (`UsageModuleTest` 16) |
| 7 | QUERY_ALL_PACKAGES | `packages.list`, `packages.info`, `packages.launch` | native (ADR-0052) | UNIT (`PackagesModuleTest` 21) |
| 8 | SYSTEM_ALERT_WINDOW | `overlay.show/update/status/hide` (plus the existing background-start exemption) | native (ADR-0052) | UNIT (`OverlayModuleTest` 17) |
| 9 | ACCESS_BACKGROUND_LOCATION | `location.background.start/poll/stop` | native, mirroring the upstream contract that is not released yet (ADR-0052) | UNIT (`LocationBackgroundModuleTest` 14) |
| 10 | ACTIVITY_RECOGNITION | step-counter/detector gate with a typed permission hint | native fix (ADR-0052 scope) | UNIT (`SensorCatalogPermissionGateTest` 8) |
| 11 | FOREGROUND_SERVICE_LOCATION | `LocationBackgroundService` (foregroundServiceType="location") | used | UNIT (`LocationBackgroundModuleTest`) |
| 12 | FOREGROUND_SERVICE_CONNECTED_DEVICE | — | PRUNED: no service declares the type; the session foreground service keeps wireless capabilities alive | manifest + allow-list |
| 13 | FOREGROUND_SERVICE_DATA_SYNC | — | PRUNED: AGENTS.md forbids dataSync as an indefinite server process | manifest + allow-list |
| 14 | DOWNLOAD_WITHOUT_NOTIFICATION | — | PRUNED: only required for `VISIBILITY_HIDDEN`, which `download.request` never uses | manifest + allow-list |

Guest commands shipped with the same work (not permission-bound):
`nusadesk-bt` (`GuestBtCliWriterTest` 3), `nusadesk-serve` and
`nusadesk-net` (`GuestServeCliWriterTest` 6, including a real fork + HTTP
round trip).

## Device evidence

Recorded 2026-09-23/24 on the S10e (SM-G970F, Android 12/API 31, QA build
`-PqaDebuggable=true` of the working tree, release-signed, versionCode 13),
driven through the live guest over SSH (`adb forward` + a temporary probe key,
removed afterwards). Observed output, abbreviated:

| Probe | Observed |
|---|---|
| `nusadesk-bt status` | `{"state":"on","name":"S10e milik Jahrul","scan_mode":"connectable","bonded_count":1}` |
| `nusadesk-bt devices` | one bonded `WH-CH520` (dual, bonded) with its UUID list |
| `nusadesk-bt discover` | `[]` after the 12 s window (no classic device in range) — honest empty, rc 0 |
| `nusadesk-bt le-scan --seconds 8` | started/poll/stopped cleanly, `[]` (no advertiser in range) |
| `nusadesk-bt advertise start --name NusaDeskQA` | typed `bt-permission-denied` before the grant; `{"started":true,...}` after `pm grant BLUETOOTH_ADVERTISE`; `stop` → `was_running:true` |
| `nusadesk-bt unpair <addr>` | `bt-unpair-unsupported:platform hides removeBond; use the system Bluetooth settings` |
| `bt.gatt.server.start` / `stop` | `{"started":true,"service_uuid":"7a1f0001-3c4b-4d6e-9f2a-cb8a4d6e5f01"}` / `{"stopped":true}` |
| `bt.rfcomm.listen` (5 s) | `bt-rfcomm-listen-timeout` — the bounded wait is real, no peer was attached |
| `sensor.list` | `step_detector` and `step_counter` rows carry `requires: activity_recognition` |
| `termux-sensor -s step_counter -n 1` | without the grant: `sensor-permission-denied:grant android.permission.ACTIVITY_RECOGNITION in app settings`, rc 1; after the grant: `{"step_counter":{"values":[0.0]}}`, rc 0 |
| `wifi.hotspot.start` | on a dozing device the typed `wifi-hotspot-failed:permission refused` (the platform requires the app in the foreground); awake and focused: `{"ssid":"AndroidShare_9086","passphrase":"…","security_type":"wpa3_sae_transition","running":true}`; `stop` → `was_running:true` |
| `wifi.suggest.add` / `list` / `remove` / `list` | add + list showed the entry; **the first `remove` failed** (`wifi-suggest-failed:nothing to remove`) because the platform matches the whole suggestion object — fixed to submit the stored object, then `removed:true` and an empty list |
| `wifi.lock.acquire` / `release` | `{"held":true,"tag":"qa-probe"}` / `{"held":false,"was_held":true}` |
| `usage.query` | typed `usage-permission-required` before the grant; after `appops set … GET_USAGE_STATS allow`: real rows (NusaDesk, launcher, …), `truncated:true` at the limit |
| `usage.standby gh.nusashell.nusadesk` | `{"bucket":"unknown","inactive":false}` (own package needs no grant) |
| `packages.list --limit 3` / `packages.info` | real labels/versions; NusaDesk info with `target_sdk:37`, `launchable:true` |
| `packages.launch com.android.settings` | `{"launched":true,"component":"com.android.settings/.Settings"}` |
| `overlay.show` / `status` / `hide` | `{"shown":true,…}` / status echoed text/coords/size / `{"shown":false,"was_shown":true}`. Visual capture pending: the phone was locked (the plate does not render over the secure keyguard), so the drawn pixels still need one unlocked-screen pass. |
| `location.background.start` / `poll` / `stop` | typed `location-background-permission-required` before the grant; after `pm grant ACCESS_BACKGROUND_LOCATION`: `started:true`, one real network fix (`lat -6.192587, lon 106.7638895, accuracy 16.5 m`), the FGS notification (`android.title=NusaDesk background location`, channel `nusadesk-location-background`, `mFgServiceShown=true`), `stop` → `was_running:true` |
| `nusadesk-serve start --port 18080 --dir /root` | LAN warning + `lan: http://192.168.18.202:18080/`; `GET /` from the host → 200; `PUT /qa-upload.txt` from the host → 201 and the exact bytes in the guest; `status` + `stop` clean |
| `nusadesk-wifi` / `nusadesk-pkg` / `nusadesk-usage` / `nusadesk-overlay` / `nusadesk-loc` | each returned the bridge JSON with rc 0 (hotspot status, package list, standby, overlay status, background-location poll) |
| `bt.discoverable.request` (S10e, consent dialog) | after the tap: `{"duration_seconds":300,"scan_mode":"connectable_discoverable"}` |
| `bt.discover` (S7 → S10e) | `NusaDeskS10e A8:2B:B9:C0:EA:CD classic none` |
| LE advertise (S10e) → LE scan (S7) | the S7's scan returned the S10e's advertisement: address `55:51:05:C8:49:4E` with `service_uuids: ["7a1f0001-…"]` |
| GATT client (S7) ↔ GATT server (S10e) | `connect` → `{"connected":true}`; `services` listed our `7a1f0001-…` service with characteristic `7a1f0002-…`; `write` → `{"written":true,"length":12}`; `notify.start` → subscribed; the S10e's `server.notify` → `{"delivered":1,"subscribers":1}`; the S7's `notify.poll` returned `646172692d53313065` = "dari-S10e" |
| `bt.pair` (S10e → S7) | both consent dialogs confirmed → the S10e's bonded list shows the S7 as `bonded` (the CLI itself reported `bt-pair-timeout` because the bond broadcast landed late — fixed to re-read `getBondState()`; see the follow-up note) |
| RFCOMM echo (S7 → S10e listener) | `connect` → `{"connected":true,"address":"A8:2B:B9:C0:EA:CD"}` and `accept` → `{"connected":true,"address":"30:CB:F8:57:DC:8A"}`; the S7 wrote `ping-dari-S7` and the S10e read `70696e672d646172692d5337`; the S10e wrote `pong-dari-S10e` and the S7 read `706f6e672d646172692d53313065`; both `close` → `{"closed":true}` |
| `overlay.show` (unlocked S10e) | the screenshot shows the green "NusaDesk QA overlay" plate drawn over the launcher; `hide` → `{"shown":false,"was_shown":true}` |

Two device findings from this pass were fixed and re-checked on the S10e with
the rebuilt APK: `bt.pair` now re-reads `getBondState()` (the already-bonded S7
answered `{"state":"bonded","address":"30:CB:F8:57:DC:8A"}` in 0.5 s instead
of a false timeout), and the BLE advertise payload now omits the device name
when a 128-bit service UUID is present (the same call that used to answer
`bt-advertise-failed:error 1` now returns
`{"started":true,"service_uuid":"7a1f0001-…","name":"NusaDeskQA"}`), with the
platform's advertise error codes mapped to specific typed errors.

Still open for device evidence: Wi-Fi Direct and RTT (deferred, not shipped).

