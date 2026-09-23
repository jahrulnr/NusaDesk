# Platform capability matrix — 13 unused manifest permissions

Riset platform-only untuk capability bridge NusaDesk (`minSdk 29`, `targetSdk 37`,
device uji Samsung S10e Android 12/API 31). Sumber: developer.android.com + AOSP.

Klasifikasi tiap baris:

- **HARD** — tidak mungkin untuk app pihak ketiga biasa, apa pun izin yang dideklarasikan
  (API dihapus/dinonaktifkan untuk target SDK kita, atau izinnya signature/privileged).
- **CONSENT** — mungkin, tapi butuh izin runtime, special access (Settings), dialog sistem,
  foreground state, atau hardware yang mendukung.
- **FREE** — API publik tanpa izin khusus di luar pemakaian normal.

Fakta manifest saat ini (diverifikasi dari `app/src/main/AndroidManifest.xml`):

- Sudah dideklarasikan: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` dengan
  `usesPermissionFlags="neverForLocation"`, legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`
  (`maxSdkVersion="30"`), `NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`,
  `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `PACKAGE_USAGE_STATS`, `QUERY_ALL_PACKAGES`,
  `ACCESS_BACKGROUND_LOCATION`, `ACTIVITY_RECOGNITION`, `SYSTEM_ALERT_WINDOW`,
  `WRITE_SETTINGS`, `WAKE_LOCK`, `FOREGROUND_SERVICE*` (LOCATION/CONNECTED_DEVICE/
  DATA_SYNC/CAMERA/MICROPHONE/MEDIA_PLAYBACK/SPECIAL_USE), `DOWNLOAD_WITHOUT_NOTIFICATION`.
- **Belum dideklarasikan: `BLUETOOTH_ADVERTISE`** — diperlukan untuk BLE advertise dan
  classic discoverability pada target API 31+.

## A. Bluetooth

Model izin: untuk app yang menargetkan Android 12 (API 31)+, `BLUETOOTH_SCAN`,
`BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` adalah izin runtime grup nearby-devices.
Untuk target ≤ API 30 (atau runtime lama `BLUETOOTH`/`BLUETOOTH_ADMIN`) izin install-time
dipakai. https://developer.android.com/develop/connectivity/bluetooth/bt-permissions

| Kapabilitas | Kelas | API publik | Izin / gate | Consent/foreground | Batas keras |
|---|---|---|---|---|---|
| Baca state adapter (`isEnabled`, `getState`) | FREE..CONSENT | `BluetoothAdapter.isEnabled()` | `BLUETOOTH` (≤30) / `BLUETOOTH_CONNECT` (target 31+) | Tidak | — https://developer.android.com/reference/android/bluetooth/BluetoothAdapter |
| Baca nama adapter | CONSENT | `BluetoothAdapter.getName()` | `BLUETOOTH` (≤30) / `BLUETOOTH_CONNECT` (31+) | Tidak | — https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getName() |
| Baca alamat MAC adapter | **HARD** | `BluetoothAdapter.getAddress()` | `BLUETOOTH_CONNECT` **dan** `LOCAL_MAC_ADDRESS` | — | `LOCAL_MAC_ADDRESS` = `signature\|privileged` (AOSP `core/res/AndroidManifest.xml`) → app biasa selalu menerima placeholder `02:00:00:00:00:00`. https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getAddress() |
| Enable/disable adapter programatik | **HARD** (target 33+) | `BluetoothAdapter.enable()/disable()` | `BLUETOOTH_ADMIN` (≤30) / `BLUETOOTH_CONNECT` (31–32, masih bekerja); deprecated API 33 | — | "Starting with Build.VERSION_CODES.TIRAMISU, applications are not allowed to enable/disable Bluetooth… this API will always fail and return false" untuk target ≥33. Pengecualian: DO/PO/system apps. Alternatif consent: intent `ACTION_REQUEST_ENABLE` (dialog sistem). https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#enable() |
| Bonded/paired devices | CONSENT | `BluetoothAdapter.getBondedDevices()` | `BLUETOOTH_CONNECT` (target 31+) | Tidak | — https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getBondedDevices() |
| Classic discovery (`startDiscovery`) | CONSENT | `BluetoothAdapter.startDiscovery()`, broadcast `ACTION_FOUND`/`ACTION_DISCOVERY_*` | target 31+: `BLUETOOTH_SCAN` + `ACCESS_FINE_LOCATION`, **atau** `BLUETOOTH_SCAN`+`neverForLocation`; ≤30: `BLUETOOTH`+`BLUETOOTH_ADMIN`+`ACCESS_FINE_LOCATION` | Tidak | Durasi: inquiry scan ±12 detik + page scan per device; heavyweight — cancel sebelum connect. https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#startDiscovery() |
| Pairing (`createBond`) | CONSENT | `BluetoothDevice.createBond()` / `createBond(int)` | `BLUETOOTH_CONNECT` (target 31+) | Async; sistem menampilkan dialog pairing bila perlu | Tidak bisa silent — user dapat menolak di dialog sistem. https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createBond() |
| Unpairing programatik | **HARD** | — (tidak ada API publik; `removeBond()` `@hide` di AOSP) | — | — | Tidak ada cara publik menghapus bond; hanya user lewat Settings. https://developer.android.com/reference/android/bluetooth/BluetoothDevice |
| BLE scan dengan `neverForLocation` | CONSENT | `BluetoothLeScanner.startScan()` | `BLUETOOTH_SCAN` + manifest flag `neverForLocation` (sudah kita deklarasikan) | Tidak | Flag dapat membatasi jenis hasil (mis. beacon yang menyiratkan lokasi). https://developer.android.com/develop/connectivity/bluetooth/bt-permissions |
| BLE scan tanpa `neverForLocation` | CONSENT | `BluetoothLeScanner.startScan()` | `BLUETOOTH_SCAN` + `ACCESS_FINE_LOCATION` (runtime) | Tidak | — https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices |
| GATT client (connect/read/write/notify) | CONSENT | `BluetoothDevice.connectGatt()`, `BluetoothGatt.readCharacteristic/writeCharacteristic/setCharacteristicNotification` | `BLUETOOTH_CONNECT` (target 31+) | Tidak | — https://developer.android.com/develop/connectivity/bluetooth/ble/connect-gatt-server |
| GATT server (peripheral GATT) | CONSENT | `BluetoothManager.openGattServer()` | `BLUETOOTH_CONNECT` (target 31+) | Tidak | Requires `BLUETOOTH_CONNECT`. https://developer.android.com/reference/android/bluetooth/BluetoothManager#openGattServer |
| RFCOMM client socket | CONSENT | `BluetoothDevice.createRfcommSocketToServiceRecord(UUID)` / `createInsecureRfcommSocket*` | `BLUETOOTH_CONNECT` (target 31+) | Tidak | — https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices |
| RFCOMM server socket | CONSENT | `BluetoothAdapter.listenUsingRfcommWithServiceRecord(name, uuid)` / insecure variant | `BLUETOOTH_CONNECT` (target 31+) | Tidak | — https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#listenUsingRfcommWithServiceRecord |
| BLE advertise (peripheral) | CONSENT | `BluetoothLeAdvertiser.startAdvertising*()` | **`BLUETOOTH_ADVERTISE` — BELUM kita deklarasikan** (target 31+); ≤30: `BLUETOOTH_ADMIN` | Tidak | `BLUETOOTH_ADVERTISE` "always enforced"; `getBluetoothLeAdvertiser()` bisa `null` jika hardware tidak mendukung atau BT off. https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser#startAdvertising |
| Classic discoverability | CONSENT | intent `BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE` | `BLUETOOTH` (target ≤30) / **`BLUETOOTH_ADVERTISE`** (target 31+) | Dialog sistem wajib | Default 120 dtk, maks 300 dtk per request (`EXTRA_DISCOVERABLE_DURATION`); resultCode = durasi atau `RESULT_CANCELED`. https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#ACTION_REQUEST_DISCOVERABLE |
| OBEX file transfer (OPP/PBAP/MAP) | **HARD** | — tidak ada kelas publik (`BluetoothOpp`/`BluetoothMap`/`BluetoothPbap` tidak ada di `android.bluetooth` SDK) | — | — | Jalur publik satu-satunya: `ACTION_SEND` ke app Bluetooth (user-mediated). https://developer.android.com/reference/android/bluetooth/BluetoothProfile (daftar profil publik) |
| PAN / Bluetooth tethering | **HARD** | — `BluetoothPan` dan `TetheringManager.TETHERING_BLUETOOTH` keduanya `@SystemApi`/`@hide` | `TETHER_PRIVILEGED` | — | Tidak ada API publik start/stop BT tethering. https://developer.android.com/reference/android/net/TetheringManager |
| HID device role (ponsel sbg keyboard/mouse) | CONSENT | `BluetoothHidDevice` via `getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)` + `registerApp` | `BLUETOOTH_CONNECT` (target 31+) | Registrasi app; perangkat peer memilih koneksi | Profil `HID_DEVICE` (id 19) publik sejak API 28; butuh dukungan stack di device. https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice |
| HID host role (pakai HID device eksternal) | **HARD** | — `BluetoothHidHost` tidak ada di SDK publik | — | — | https://developer.android.com/reference/android/bluetooth/BluetoothProfile |
| Battery/device info bonded device | **HARD** | — `BluetoothDevice.getBatteryLevel()`/`ACTION_BATTERY_LEVEL_CHANGED`/`EXTRA_BATTERY_LEVEL` `@hide`/`@SystemApi` | — | — | Tidak muncul di referensi publik `BluetoothDevice`. https://developer.android.com/reference/android/bluetooth/BluetoothDevice |
| Profil audio (A2DP/HEADSET/LE_AUDIO/HEARING_AID/HAP/CSIP) | CONSENT | `BluetoothAdapter.getProfileProxy()` + objek profil | `BLUETOOTH_CONNECT` (target 31+) | Tidak | Hanya proxy profil yang ada di daftar publik (`A2DP`, `HEADSET`, `GATT`, `GATT_SERVER`, `LE_AUDIO`, `HEARING_AID`, `HAP_CLIENT`, `CSIP_SET_COORDINATOR`, `HID_DEVICE`, `SAP`); ketersediaan tergantung device. https://developer.android.com/reference/android/bluetooth/BluetoothProfile |

Catatan gap: untuk menutup BLE advertise + classic discoverable di target 37 perlu
menambah `BLUETOOTH_ADVERTISE` (runtime, grup nearby-devices).

## B. Wi-Fi

| Kapabilitas | Kelas | API publik | Izin / gate | Consent/foreground | Batas keras |
|---|---|---|---|---|---|
| Toggle Wi-Fi on/off programatik | **HARD** (target 29+) | `WifiManager.setWifiEnabled()` | `CHANGE_WIFI_STATE` | — | "Starting with Build.VERSION_CODES.Q, applications are not allowed to enable/disable Wi-Fi… always fail and return false" untuk target ≥29. Pengecualian: DO/PO/system; API 33+: DO/PO dapat mengunci via `DISALLOW_CHANGE_WIFI_STATE`. Alternatif: panel/intent Settings (`Settings.ACTION_WIFI_SETTINGS`, `Settings.Panel.ACTION_WIFI` [free, user tap]). https://developer.android.com/reference/android/net/wifi/WifiManager#setWifiEnabled(boolean) |
| `startScan()` | CONSENT | `WifiManager.startScan()` (deprecated API 28) | target 29+: `ACCESS_FINE_LOCATION` + `CHANGE_WIFI_STATE` + Location Services ON | Hasil via broadcast `SCAN_RESULTS_AVAILABLE_ACTION` | Throttle: foreground 4 scan/2 menit; background 1 scan/30 menit; pemegang `NETWORK_SETTINGS` dikecualikan. https://developer.android.com/develop/connectivity/wifi/wifi-scan |
| `getScanResults()` | CONSENT | `WifiManager.getScanResults()` | `ACCESS_FINE_LOCATION` + `ACCESS_WIFI_STATE` + Location Services ON (target 29+) | Tidak | `NEARBY_WIFI_DEVICES` TIDAK menggantikan location untuk scan AP pada API 33+. https://developer.android.com/develop/connectivity/wifi/wifi-permissions |
| Connection info (SSID/BSSID/RSSI) | CONSENT | `WifiManager.getConnectionInfo()` (deprecated API 31) → `ConnectivityManager.NetworkCallback` + `NetworkCapabilities.getTransportInfo()` → `WifiInfo` | SSID/BSSID = field sensitif-lokasi: perlu izin setara `getScanResults` (`ACCESS_FINE_LOCATION`); tanpa itu `getSSID()`=`UNKNOWN_SSID`, `getBSSID()`=`02:00:00:00:00:00` | Tidak | https://developer.android.com/reference/android/net/wifi/WifiInfo |
| Enumerate saved networks | **HARD** (target 29+) | `WifiManager.getConfiguredNetworks()` | — | — | Deprecated API 29; "always fail and return an empty list" untuk target ≥29; pengecualian DO/PO/system/carrier (carrier: hanya konfigurasinya sendiri). https://developer.android.com/reference/android/net/wifi/WifiManager#getConfiguredNetworks() |
| Tambah saved network dengan consent | CONSENT | intent `Settings.ACTION_WIFI_ADD_NETWORKS` (API 30+) + `WifiNetworkSuggestion` extras | `CHANGE_WIFI_STATE` | Halaman Settings; user approve tiap jaringan (`EXTRA_WIFI_NETWORK_RESULT_LIST`) | Satu-satunya cara menambah *saved network* — platform menolak add langsung. https://developer.android.com/reference/android/provider/Settings#ACTION_WIFI_ADD_NETWORKS |
| `WifiNetworkSuggestion` add/remove | CONSENT | `WifiManager.addNetworkSuggestions()/removeNetworkSuggestions()` | `CHANGE_WIFI_STATE` | User diberi tahu saat suggestion pertama (API 30+: dialog jika foreground, notifikasi jika background; API 29: selalu notifikasi). User menolak → `CHANGE_WIFI_STATE` dicabut (re-grant via Settings > Special app access > Wi-Fi Control) | Suggestion **bukan** saved network dan tidak muncul di daftar saved; platform yang memutuskan connect (auto-join dimungkinkan tapi tak bisa dipaksa); disconnect manual user membuat suggestion diabaikan; hilang saat uninstall/reset. https://developer.android.com/develop/connectivity/wifi/wifi-suggest |
| `WifiNetworkSpecifier` (connect ke 1 AP, request-scoped) | CONSENT | `WifiNetworkSpecifier.Builder` + `ConnectivityManager.requestNetwork()` | `CHANGE_WIFI_STATE` (+ lokasi bila dipakai untuk lokasi) | Dialog pemilihan jaringan tampil ke user; koneksi scoped ke request — dilepas saat callback dilepas | Bukan mekanisme saved network; koneksi local-only bisa jadi secondary connection di device dgn concurrent-STA support (target ≥31/system). https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier |
| Local-only hotspot | CONSENT | `WifiManager.startLocalOnlyHotspot()` (API 26+) / `startLocalOnlyHotspotWithConfiguration()` (API 36+) | target 33+: `CHANGE_WIFI_STATE` + `NEARBY_WIFI_DEVICES`; target ≤32: `CHANGE_WIFI_STATE` + `ACCESS_FINE_LOCATION`; tanpa izin → `SecurityException` | User dapat stop dari Settings (`onStopped()`); hotspot bisa shared antar app | **Tidak ada internet** di jaringan ini; `ERROR_TETHERING_DISALLOWED`/`ERROR_INCOMPATIBLE_MODE` jika konflik dengan tethering aktif. https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspot |
| Wi-Fi Direct / P2P | CONSENT | `WifiP2pManager` (`discoverPeers`, `connect`, `createGroup`, service ops) | target 33+: `NEARBY_WIFI_DEVICES` (runtime); ≤32: `ACCESS_FINE_LOCATION`; plus `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET`, `ACCESS_NETWORK_STATE` | `discoverPeers`/`discoverServices`/`requestPeers` juga butuh Location Mode ON | Dukungan hardware diperlukan; `WIFI_P2P_STATE_CHANGED_ACTION` memberi tahu P2P aktif/tidak. https://developer.android.com/develop/connectivity/wifi/wifip2p |
| Wi-Fi RTT ranging | CONSENT | `WifiRttManager.startRanging()` | target 33+: `NEARBY_WIFI_DEVICES`; ≤32: `ACCESS_FINE_LOCATION`; Location Services ON + Wi-Fi scanning ON | App harus visible atau di foreground service; tidak boleh dari background | Butuh device + AP dengan 802.11mc FTM (atau 802.11az NTB di API 35+); unlimited foreground, throttled background. https://developer.android.com/develop/connectivity/wifi/wifi-rtt |
| `WifiLock` | CONSENT→normal | `WifiManager.createWifiLock(lockType, tag)` → `acquire()/release()` | `WAKE_LOCK` (normal, sudah dideklarasikan) | Tidak | `createWifiLock(String)` deprecated/non-fungsional API 29; lock hanya menahan radio aktif — bukan kontrol Wi-Fi, dan membebani baterai. https://developer.android.com/reference/android/net/wifi/WifiManager.WifiLock |
| Tethering/hotspot internet (`TetheringManager`) | **HARD** ≤ API 35; CONSENT API 36+ | `TetheringManager.startTethering(TetheringRequest,…)` / `stopTethering` | API ≤35: kelas `@SystemApi`/`@hide` → tak tersedia. API 36+: publik, `@RequiresPermission(TETHER_PRIVILEGED, conditional=true)`; jalur error AOSP melempar SecurityException tanpa `TETHER_PRIVILEGED` **atau** `WRITE_SETTINGS` | `WRITE_SETTINGS` = special access (`Settings.ACTION_MANAGE_WRITE_SETTINGS`, sudah kita deklarasikan) | Konstanta type publik hanya `TETHERING_WIFI`; `TETHERING_BLUETOOTH`/`USB` tetap `@SystemApi`. Pada S10e (API 31) tetap **HARD**. AOSP: https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/sdk-release/Tethering/common/TetheringLib/src/android/net/TetheringManager.java ; API: https://developer.android.com/reference/android/net/TetheringManager |
| `allowAutojoin` global / network-selection admin | **HARD** | `WifiManager.allowAutojoin*` | `NETWORK_SETTINGS` atau `MANAGE_WIFI_NETWORK_SELECTION` (privileged) | — | https://developer.android.com/reference/android/net/wifi/WifiManager |
| Buka picker Wi-Fi bawaan | FREE | `Settings.ACTION_WIFI_SETTINGS`, `WifiManager.ACTION_PICK_WIFI_NETWORK`, `ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE` | — | Activity/intent | — https://developer.android.com/reference/android/net/wifi/WifiManager |

## C. Usage stats (`UsageStatsManager`)

- **CONSENT** — `PACKAGE_USAGE_STATS` adalah izin *special access*
  (`signature|privileged|development|appop|retailDemo`), bukan runtime dialog:
  deklarasi di manifest hanya menyatakan niat; user harus mengaktifkan lewat
  `Settings.ACTION_USAGE_ACCESS_SETTINGS`. Hampir semua method membutuhkannya;
  pengecualian: `getAppStandbyBucket()` dan `queryEventsForSelf()` (hanya data app
  sendiri) bekerja tanpa izin.
  https://developer.android.com/reference/android/app/usage/UsageStatsManager
  https://developer.android.com/reference/android/Manifest.permission#PACKAGE_USAGE_STATS
- Yang bisa dibaca: `queryUsageStats` (agregat per interval DAY/WEEK/MONTH/YEAR/BEST),
  `queryEvents`/`queryEvents(UsageEventsQuery)` (event MOVE_TO_FOREGROUND/
  MOVE_TO_BACKGROUND, dsb.), `queryAndAggregateUsageStats`, `queryConfigurations`,
  `queryEventStats`, `isAppInactive`/`getAppStandbyBucket`.
  https://developer.android.com/reference/android/app/usage/UsageStatsManager
- Batas: data agregat/kehadiran-paket saja — tidak ada akses konten layar, notifikasi,
  atau state internal app lain; data retention ditentukan platform; bucket hanya
  indikator prioritas, bukan kontrol.
  https://developer.android.com/reference/android/app/usage/UsageStatsManager

## D. Overlay (`SYSTEM_ALERT_WINDOW`) & background activity start

- Menggambar overlay — **CONSENT**: window `TYPE_APPLICATION_OVERLAY` butuh
  `SYSTEM_ALERT_WINDOW`; target API 23+ user mengaktifkan lewat layar permission
  via intent `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (URI `package:` hanya
  berlaku < API 30); cek via `Settings.canDrawOverlays()`. Protection level:
  `signature|setup|appop|installer|pre23|development`.
  https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW
  https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION
- BAL (background activity launch) — **CONSENT**, sempit: sejak Android 10 (API 29)
  app tidak bisa `startActivity` dari background kecuali pengecualian terdokumentasi;
  **`SYSTEM_ALERT_WINDOW` yang di-grant user adalah salah satu pengecualian resmi**
  (lainnya: window visible, IME aktif, PendingIntent dari sistem, launcher,
  `START_ACTIVITIES_FROM_BACKGROUND` [privileged], bound-service, dsb.).
  https://developer.android.com/guide/components/activities/background-starts
- API 34+ (target): mengirim `PendingIntent` tidak lagi memberi privilege BAL default —
  sender harus opt-in `ActivityOptions.setPendingIntentBackgroundActivityStartMode()`
  (disarankan `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`); API 35+: pembuat
  PendingIntent harus opt-in `setPendingIntentCreatorBackgroundActivityStartMode()`;
  `Context.startIntentSender()` juga butuh opt-in sender.
  https://developer.android.com/guide/components/activities/background-starts
- **HARD**: tidak ada API untuk memulai activity bebas dari background tanpa salah satu
  pengecualian; `FLAG_ACTIVITY_NEW_TASK` hanya flag routing (wajib dari context
  non-Activity) dan tidak melewati BAL.
  https://developer.android.com/guide/components/activities/background-starts

## E. Package visibility & launch app lain

- API 30+ (target 30+): hasil `queryIntentActivities`, `getPackageInfo`,
  `getInstalledApplications` **difilter**; daftar paket terinstall dianggap data
  personal-sensitif oleh Play. Sebagian paket terlihat otomatis; sisanya perlu
  `<queries>` (intent/package/provider) atau `QUERY_ALL_PACKAGES`.
  https://developer.android.com/training/package-visibility
- `QUERY_ALL_PACKAGES` — **FREE secara teknis** (protection level `normal`, install-time,
  tak bisa dicabut user per-app) tapi **policy-sensitive**: penggunaannya subject to
  approval jika publish di Play; `<queries>` selalu lebih disukai bila use-case
  bisa dispesifikkan.
  https://developer.android.com/reference/android/Manifest.permission#QUERY_ALL_PACKAGES
  https://developer.android.com/training/package-visibility
- Launch app lain — **FREE untuk start-nya**: `startActivity()` **tidak** memerlukan
  package visibility — intent explicit/implicit ke activity app lain bekerja tanpa
  `<queries>`/permission; tangkap `ActivityNotFoundException` bila tak ada handler.
  (`<queries>` hanya diperlukan bila app harus *mengetahui lebih dulu* apakah target
  ada, mis. `resolveActivity`/`getLaunchIntentForPackage` → null untuk paket tak
  terlihat.)
  https://developer.android.com/training/package-visibility/use-cases
- **HARD/batas**: launch dari background tetap tunduk pada BAL restrictions di §D —
  `FLAG_ACTIVITY_NEW_TASK` tidak mengecualikan.
  https://developer.android.com/guide/components/activities/background-starts
- Binding ke service app lain **difilter** oleh package visibility — perlu `<queries>`
  atau visibilitas otomatis.
  https://developer.android.com/training/package-visibility

## F. Background location

- Deklarasi — **CONSENT**: `ACCESS_BACKGROUND_LOCATION` (API 29+, protection level
  `dangerous`, *hard-restricted* — installer-on-record harus meng-allowlist) harus
  diminta bersama `ACCESS_COARSE`/`ACCESS_FINE`; sendirian tidak memberi akses lokasi.
  https://developer.android.com/reference/android/Manifest.permission#ACCESS_BACKGROUND_LOCATION
- Alur grant API 29: dialog runtime menyertakan opsi **Allow all the time**. API 30+:
  opsi itu **tidak** ada di dialog — user harus mengaktifkan di halaman Settings;
  app membantu navigasi (label opsi via `PackageManager.getBackgroundPermissionOptionLabel()`).
  https://developer.android.com/develop/sensors-and-location/location/permissions/background
- FGS type `location` ≠ background location: untuk `location` FGS runtime prerequisites
  adalah Location Services ON + `ACCESS_COARSE`/`FINE` granted; izin lokasi itu
  *while-in-use* → service type `location` tidak bisa dibuat dari background **kecuali**
  app sudah punya `ACCESS_BACKGROUND_LOCATION`.
  https://developer.android.com/develop/background-work/services/fgs/service-types
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- "Allow all the time" (background grant) diperlukan untuk akses lokasi saat app tidak
  visible; tanpa itu lokasi hanya saat foreground/FGS dengan while-in-use exemption.
  Presisi background = presisi foreground (approximate grant → approximate di background).
  https://developer.android.com/develop/sensors-and-location/location/permissions
- API 34+: saat membuat FGS dengan type yang butuh while-in-use permission (location/
  camera/mic), sistem memeriksa grant **saat start** — tanpa grant berlaku →
  `SecurityException` seketika (di <34 service dibuat tapi akses ditolak saat dipakai).
  `checkSelfPermission()` tidak menolong: mengembalikan GRANTED walau app di background.
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

## G. Activity recognition

- `Sensor.TYPE_STEP_COUNTER`/`TYPE_STEP_DETECTOR` — **CONSENT**: sejak API 29 sensor ini
  dilindungi `ACTIVITY_RECOGNITION` (runtime, dangerous). GMS Activity Recognition API &
  Google Fit juga tidak mengembalikan hasil tanpa grant.
  https://developer.android.com/about/versions/10/privacy/changes
- Perilaku tanpa grant (AOSP, android12-release `SensorService.cpp`): sensor tetap
  muncul di `getSensorList()`/`getDefaultSensor()` (tidak difilter), tapi enable
  gagal — `canAccessSensor()` false → `BAD_VALUE` → `SensorManager.registerListener()`
  mengembalikan **false** (bukan exception); app target ≤28 dikecualikan.
  https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android12-release/services/sensorservice/SensorService.cpp
- `ActivityRecognitionClient` (activity/transition) — **CONSENT**: Google Play services
  API (`play-services-location` ≥12.0.0), manifest `com.google.android.gms.permission.ACTIVITY_RECOGNITION` +
  runtime `android.permission.ACTIVITY_RECOGNITION` di API 29+; hasil via `PendingIntent`
  callback (`requestActivityTransitionUpdates`/`removeActivityTransitionUpdates`).
  https://developer.android.com/develop/sensors-and-location/location/transitions
- Kehadiran event transition juga tercatat sebagai pengecualian start-FGS-from-background.
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

## H. Foreground service types (API 29–34+)

Aturan umum:

- Target API 31+: app di background tidak bisa start FGS (`ForegroundServiceStartNotAllowedException`)
  kecuali pengecualian (visible activity, BAL exemption, FCM high-priority, aksi UI user
  (bubble/notif/widget), exact alarm, IME, geofence/activity-recognition event,
  BOOT_COMPLETED*, TIMEZONE/TIME/LOCALE changed, NFC transaction, DO/PO, Companion
  Device Manager permission, battery-optimization off, `SYSTEM_ALERT_WINDOW`
  [target 34+: overlay juga harus sedang visible]).
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- API 34+ (target): wajib deklarasi `android:foregroundServiceType` **dan**
  `FOREGROUND_SERVICE_<TYPE>` permission (selain `FOREGROUND_SERVICE`); runtime
  prerequisites diperiksa saat create — kurang → `SecurityException`.
  https://developer.android.com/develop/background-work/services/fgs/service-types
- Notifikasi persisten (tidak bisa di-dismiss user) wajib selama FGS berjalan; user
  bisa stop; FGS bukan tiket hidup-selamanya — OEM masih bisa membunuh proses.
  https://developer.android.com/guide/components/activities/background-starts

Per type:

| Type | Manifest perm | Runtime prerequisite | Bisa start dari background? | Timeout |
|---|---|---|---|---|
| `location` | `FOREGROUND_SERVICE_LOCATION` | Location Services ON + `ACCESS_COARSE`/`FINE` granted | Tidak, kecuali app punya `ACCESS_BACKGROUND_LOCATION` atau salah satu exemption while-in-use (system component, widget, notif, PendingIntent dari app visible, DPC, VoiceInteractionService, `START_ACTIVITIES_FROM_BACKGROUND` privileged) | — |
| `connectedDevice` | `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Salah satu: manifest `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`/`CHANGE_WIFI_MULTICAST_STATE`/`NFC`/`TRANSMIT_IR`, **atau** runtime `BLUETOOTH_CONNECT`/`BLUETOOTH_ADVERTISE`/`BLUETOOTH_SCAN`/`UWB_RANGING`, **atau** `UsbManager.requestPermission()` dipanggil | Umumnya ya (bukan while-in-use type) — tetap tunduk aturan umum API 31+ | — |
| `dataSync` | `FOREGROUND_SERVICE_DATA_SYNC` | Tidak ada | Ya — tapi target API 35+: **tidak boleh** dari `BOOT_COMPLETED` receiver | Target API 35+ (device Android 15): total **6 jam/24 jam** per type; timeout → `Service.onTimeout()` → harus `stopSelf()` dalam hitungan detik atau `RemoteServiceException`; timer reset saat user membawa app ke foreground |
| `shortService` (konteks) | tidak ada type-perm khusus | — | Sama aturan umum | ±3 menit, ANR bila dilanggar |

Sumber per-type: https://developer.android.com/develop/background-work/services/fgs/service-types ;
timeout: https://developer.android.com/develop/background-work/services/fgs/timeout ;
while-in-use: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions ;
BOOT_COMPLETED API 15: https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed

**Implikasi untuk bridge**: `dataSync` **tidak** cocok sebagai server tak terbatas
(konsisten dengan aturan AGENTS.md); `connectedDevice` adalah type yang tepat untuk
sesi bridge aktif karena prerequisite-nya terpenuhi oleh `CHANGE_WIFI_STATE` atau
`BLUETOOTH_CONNECT` yang sudah runtime-granted; `location` hanya hidup dari background
setelah grant `ACCESS_BACKGROUND_LOCATION`.

## I. DownloadManager

- Download biasa — **FREE/CONSENT ringan**: `DownloadManager.enqueue(Request)` tidak
  butuh izin khusus (butuh `INTERNET` untuk URL); notifikasi default hanya saat
  download berjalan.
  https://developer.android.com/reference/android/app/DownloadManager.Request#setNotificationVisibility(int)
- `DOWNLOAD_WITHOUT_NOTIFICATION` — **CONSENT ringan**: wajib **hanya** bila
  `setNotificationVisibility(VISIBILITY_HIDDEN)` atau `setVisibleInDownloadsUi(true)`
  (deprecated `setShowRunningNotification(false)` juga). Protection level AOSP:
  `normal` (didefinisikan oleh package DownloadProvider) → manifest grant saja cukup,
  tanpa consent ekstra.
  https://developer.android.com/reference/android/app/DownloadManager.Request#setNotificationVisibility(int)
  https://android.googlesource.com/platform/packages/providers/DownloadProvider/+/refs/heads/main/AndroidManifest.xml
- Batas: `VISIBILITY_HIDDEN` menyembunyikan progres dari UI — untuk use-case
  "download terlihat user" mode ini tidak perlu, dan izinnya boleh dianggap
  inert oleh desain.

## Klaim yang tidak bisa diverifikasi dari dokumentasi

- Dukungan hardware per-device: apakah S10e mengaktifkan BLE advertising
  (`isMultipleAdvertisementSupported`), Wi-Fi RTT (AP harus 802.11mc/az), Wi-Fi Aware,
  concurrent-STA untuk secondary local-only connection — semua runtime/hardware-gated,
  harus diverifikasi di device.
- Perilaku OBEX/OPP lewat `ACTION_SEND` ke `com.android.bluetooth`: bukan kontrak API
  publik yang stabil (implementasi di app Bluetooth sistem; OEM bisa berbeda).
- Apakah `TetheringManager.startTethering` dengan `WRITE_SETTINGS` di API 36+ benar-benar
  bekerja end-to-end di device retail (provisioning/entitlement check operator bisa
  menolak: `TETHER_ERROR_PROVISIONING_FAILED`/`ENTITLEMENT_UNKNOWN`); belum diverifikasi
  device, dan tidak relevan di API 31.
- Throttling detail tambahan yang diimpose OEM di luar angka dokumentasi
  (scan Wi-Fi, BLE scan-offscreen, pending intent) tidak tercakup di docs.
- Durasi inquiry classic discovery "±12 detik" adalah angka khas dari doc
  `startDiscovery`, bukan kontrak waktu.
- Perilaku `registerListener` tanpa `ACTIVITY_RECOGNITION` diverifikasi dari source
  AOSP android12-release (`BAD_VALUE` → `false`); versi/OEM lain bisa berbeda halus
  walau kontraknya sama.
- Perilaku `shouldShowRequestPermissionRationale`/`getBackgroundPermissionOptionLabel`
  di skin One UI Samsung bisa menambah langkah UI; label lokalisasi datang dari sistem.
- Query usage-stats bisa mengembalikan data lebih sedikit pada profil kerja/secondary
  user; docs tidak menjamin kelengkapan lintas profil.
- Daftar profil Bluetooth yang benar-benar tersedia via `getProfileProxy` di device
  tertentu tidak didokumentasikan per-OEM.
