# Riset upstream Termux:API — surface kapabilitas resmi

Tanggal riset: 2026-09-23. Sumber: GitHub API/raw ke `termux/termux-api-package`
(tag `v0.60.0`, commit `9e7f1531`) dan `termux/termux-api` (`master`, commit
terakhir `fc26ce1`, 2026-09-17; rilis terakhir `v0.53.0`, 2025-09-01).

Daftar lengkap 57 script client v0.60.0 ada di
`https://api.github.com/repos/termux/termux-api-package/git/trees/v0.60.0?recursive=1`
(semua di `scripts/termux-*.in`). Daftar lengkap 45 nilai `api_method` yang
diterima app ada di `TermuxApiReceiver.java:96-275` (master). Transport client:
`termux-api.c:31` LocalSocket `com.termux.api://listen`, fallback `am broadcast`
ke `com.termux.api/.TermuxApiReceiver` dengan `--es api_method "<Name>"`
(`termux-api.c:364,392`).

Manifest app master (`app/src/main/AndroidManifest.xml`) mendeklarasikan 33
`uses-permission` (baris 8–40). Yang **tidak** dideklarasikan: `BLUETOOTH*`,
`QUERY_ALL_PACKAGES`, `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE*`,
`DOWNLOAD_WITHOUT_NOTIFICATION`, `POST_NOTIFICATIONS`, `NEARBY_WIFI`. Tidak ada
satu pun `foregroundServiceType` di manifest — upstream tidak punya typed FGS;
`KeepAliveService` dan `LocationAPI$LocationService` keduanya `Service` biasa
tanpa `startForeground`.

## Tabel per domain

| Domain | Client (v0.60.0) | App (master / v0.53.0) | Bukti | Catatan |
|---|---|---|---|---|
| Bluetooth (scan/pair/connect/GATT/advertise/RFCOMM) | Tidak — tidak ada script `termux-bluetooth*` | Tidak — tidak ada `BluetoothAPI`, tidak ada case di receiver, tidak ada permission `BLUETOOTH*` di manifest | tree v0.60.0; `TermuxApiReceiver.java:96-275`; manifest master baris 8–40 | Satu-satunya jejak: field output `BLUETOOTH_A2DP_IS_ON` di `termux-audio-info`, dari `AudioManager.isBluetoothA2dpOn()` (deprecated) — `AudioAPI.java:54,83`. Read-only, tanpa permission BT |
| Usage stats | Tidak | Deklarasi saja — `PACKAGE_USAGE_STATS` di manifest (baris 40), nol pemakaian `UsageStatsManager` di seluruh repo | commit `989a19b7f8` (2024-12-22, "Add DUMP and PACKAGE_USAGE_STATS permissions") hanya menyentuh manifest; `grep UsageStatsManager` di `app/src/` kosong | Kemungkinan dideklarasikan untuk alur `ReportActivity` di `termux-shared` (dipanggil dari `TermuxAPISettingsActivity.java:100`). Bukan kapabilitas client |
| Overlay / draw over apps | Tidak | Deklarasi + UI internal saja — `SYSTEM_ALERT_WINDOW` (manifest baris 33) dipakai `TermuxAPIMainActivity` untuk tombol "grant display over other apps" | `TermuxAPIMainActivity.java:30-33,60-62,120-125`; commit `ba2836ba60` (2022-03-30); helper `termux-shared PermissionUtils.requestDisplayOverOtherAppsPermission` → `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` | Tidak ada action/contract API — ini affordance manual di layar settings app sendiri, bukan kapabilitas guest |
| Enumerasi & launch package lain | Tidak | Tidak — tidak ada action list/launch; `REQUEST_INSTALL_PACKAGES`/`REQUEST_DELETE_PACKAGES` dideklarasikan (baris 28,30) tapi tidak ada pemakaian di repo | receiver case list; `grep PackageManager` hanya menemukan `DialogAPI.java:861` (probe `queryIntentActivities` untuk speech recognizer) dan toggle launcher-icon internal (`TermuxAPIMainActivity.java:144-171`) | `termux-share`/`ShareAPI` bisa `ACTION_VIEW` URL/file, tapi itu share-sheet, bukan enumerate/launch by package |
| Wifi tambahan (hotspot/LOHS, network suggestion, WifiP2P, WifiRTT, WifiLock) | Tidak — client hanya `termux-wifi-connectioninfo`, `termux-wifi-enable`, `termux-wifi-scaninfo` | Tidak — `WifiAPI.java` hanya `getConnectionInfo`/`getScanResults`/`setWifiEnabled`; nol `Hotspot`, `WifiP2p`, `WifiNetworkSuggestion`, `WifiRttManager`, `WifiLock` | `WifiAPI.java` master (138 baris): connectioninfo L24-52, scaninfo L59-123, enable L125-136 (`setWifiEnabled`, L133) | `WifiScanInfo` digate `ACCESS_FINE_LOCATION` runtime permission (`TermuxApiReceiver.java:269`); `setWifiEnabled` deprecated no-op untuk non-system app sejak API 29 |
| Background location | Client sama (`termux-location` flag `-p`/`-r` tidak berubah) | **Ada di master, BELUM RILIS** (rilis terakhir v0.53.0) | lihat bagian di bawah | Kontrak baru dijelaskan terpisah |
| ACTIVITY_RECOGNITION (step counter/detector) | Tidak ada flag khusus; `termux-sensor -s <nama>` generik | **Tidak ditangani** — `SensorAPI` tidak menyebut `ACTIVITY_RECOGNITION`/permission sama sekali; permission tidak dideklarasikan di manifest | `SensorAPI.java` master (470 baris): registerListener generik L283/L306 dengan `SENSOR_DELAY_UI`; receiver `case "Sensor"` L205-206 tanpa `checkAndRequestPermissions`; manifest tanpa `ACTIVITY_RECOGNITION` | Konsekuensi: di Android 10+ `TYPE_STEP_COUNTER`/`TYPE_STEP_DETECTOR` tidak pernah deliver event — upstream gagal diam-diam tanpa pesan izin. `BODY_SENSORS` juga dideklarasikan (baris 13) tapi tidak dicek di mana pun |
| DownloadManager notifikasi tersembunyi | `termux-download` hanya flag `-d`/`-t`/`-p` | **Tidak pernah** — `setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)` di semua revisi | `DownloadAPI.java` master L35, v0.53.0 L35, `c698354b76` (2020) L29, `1a85aeffb5` (2016) L31; manifest tidak mendeklarasikan `DOWNLOAD_WITHOUT_NOTIFICATION` | `VISIBILITY_HIDDEN` memang akan butuh `DOWNLOAD_WITHOUT_NOTIFICATION` (signature-level); upstream tidak pernah memakainya |

## Detail: kontrak background-location baru (master, belum rilis)

Dua commit langsung ke master pada 2026-09-16 (tanpa PR terkait):

- `255bc405f5` — "Fixed(LocationAPI)!: Add `LocationService` to handle
  `termux-location` commands ...". Menambah inner class
  `LocationAPI$LocationService` (`LocationAPI.java:50`) + deklarasi
  `<service android:name=".apis.LocationAPI$LocationService" android:exported="false"/>`
  (manifest baris 184, patch +3). Mengganti eksekusi inline di receiver +
  `Thread.sleep(30_000)` (v0.53.0, `LocationAPI.java:95,127`) menjadi
  `CountDownLatch`. `-r updates` sekarang mengembalikan **array JSON di root**
  (tiap fix = objek di dalam array), sebelumnya objek tunggal.
- `ee29d4314c` — "Fixed(LocationAPI): Ask user to set `Allow all the time` ...".
  Di `LocationService.onStartCommand` (`LocationAPI.java:82-89`): pada
  API >= 30 (R), jika `ACCESS_BACKGROUND_LOCATION` belum granted, **semua**
  request (`once`/`last`/`updates`) langsung keluar dengan
  `{"API_ERROR": "Background location permission not granted. Grant it manually
  from Android Settings -> Apps -> Termux:API -> Permissions -> Location ->
  Allow all the time"}`. Tidak ada runtime prompt — commit message menyebut
  halaman izin yang dibuka tidak menawarkan opsi "Allow all the time", jadi
  user diarahkan manual ke Settings.

Kontrak runtime yang tersisa sama: `-p gps|network|passive` (default gps),
`-r once|last|updates` (default once) → `api_method=Location`
(`termux-location.in:39-40`). `once` = `requestSingleUpdate`, `updates` =
`requestLocationUpdates(provider, 5000ms, 1.0m)` menunggu maks 30 detik
(`LocationAPI.java:157-170`), `last` = `getLastKnownLocation`. Output fields:
latitude, longitude, altitude, accuracy, vertical_accuracy (API 26+), bearing,
speed, elapsedMs, provider (`LocationAPI.java:237-250`). Error shape:
`{"API_ERROR": "..."}`. Receiver tetap meng-gate `Location` dengan runtime
permission `ACCESS_FINE_LOCATION` (`TermuxApiReceiver.java:161`).

Catatan penting: `ACCESS_BACKGROUND_LOCATION` **sudah dideklarasikan sejak
v0.53.0** (manifest rilis baris 8) tetapi inert — baru di master ia ditegakkan
(runtime check + `LocationService`). `LocationService` adalah `Service` biasa
(`START_NOT_STICKY`, no `startForeground`, no `foregroundServiceType`) —
upstream memilih "app memegang grant background" daripada FGS bertipe location.

## Tidak bisa diverifikasi

- Alasan pasti `PACKAGE_USAGE_STATS`/`DUMP` dideklarasikan: commit `989a19b7f8`
  hanya mengubah manifest; apakah `com.termux.shared` `ReportActivity` benar-benar
  membaca usage stats tidak diverifikasi (repo `termux-app/termux-shared`, di luar
  scope; `ReportActivity.java` tidak mengandung `UsageStatsManager` di grep cepat).
- Apakah `REQUEST_INSTALL_PACKAGES`/`REQUEST_DELETE_PACKAGES` dipakai termux-shared
  (mis. flow uninstall di settings) — nol referensi di repo `termux-api` sendiri.
- Perilaku runtime aktual `LocationService` master di device (mis. apakah
  `startService` dari background benar-benar diizinkan pada targetSdk 35 tanpa
  FGS) — belum ada rilis, tidak ada device run; hanya bukti source.
