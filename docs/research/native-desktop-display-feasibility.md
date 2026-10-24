# Kelayakan UI desktop native di NusaDesk

Riset diperbarui 23 Oktober 2026, berdasarkan source NusaDesk 0.14.4, pemeriksaan source tiga kandidat pada revisi tetap, issue/PR upstream, status build, dan inventaris perangkat melalui ADB. Snapshot audit awal berscope riset dan rekomendasi. Setelah itu user mengizinkan implementasi best-effort; status terbaru tercatat di bagian berikut.

**Target minimum adalah Android 10/API 29, ARM64.** Rekomendasi awal tetap X11 native dengan software rendering untuk aplikasi 2D terpilih. Kandidat yang menaikkan minimum Android tidak memenuhi target tanpa pekerjaan porting terpisah. Detail batas GPU dicatat di [limitasi GPU](../limitations.md#guest-gpunpu-acceleration-is-device-class-specific).

Asumsi kebutuhan: aplikasi GUI Linux berjalan lokal dalam guest ARM64 NusaDesk, lalu tampil dan menerima input di layar Android. “Kiosk” berarti satu aplikasi aktif memenuhi area kerja; kebutuhan mengunci perangkat Android dengan device-owner/lock-task tidak diasumsikan. Jika yang dimaksud adalah menampilkan desktop dari komputer lain melalui jaringan, penilaian transport dan latensinya perlu dipisahkan.

Peluang berhasil tinggi untuk aplikasi 2D yang dipilih. Rekomendasinya adalah X server native Android dengan pola satu aplikasi fullscreen, memakai guest Linux yang sudah ada. Membuat desktop lengkap atau kompatibilitas semua aplikasi menaikkan cakupan secara signifikan.

## Hasil implementasi lanjutan, 23 Oktober 2026

Spike X11 telah dibangun dan dipasang sebagai QA 0.14.4 bertanda tangan lokal ke Samsung S10e Android 12/API 31 ARM64. Mousepad dan Openbox berjalan di guest PRoot yang sudah ada, dengan output SurfaceView/JNI di area aplikasi NusaDesk. Ketikan, IME Samsung, menu, Save As, penyimpanan file dan stop/restart sesi telah diperiksa pada perangkat. Build tetap opsional dengan `-PnativeDesktop=true`; default tidak menyertakan library display atau tile.

Pernyataan “belum dibangun/diuji” dan “riset saja” pada snapshot audit di bawah **superseded untuk spike ini**, bukan untuk kandidat Wayland. Termux:X11 beserta submodule kemudian diambil dan dibangun. Tidak ada hasil build/run Piperella atau wlroots-android-bridge. Follow-up S7 Edge FloydROM Android 10/API 29 kemudian membuktikan frame native, input/IME, save, rotasi, serta stop/restart pada perangkat fisik; presentasi EGL Android tidak membuktikan akselerasi GPU guest.

Lihat [ADR-0068](../decisions/0068-experimental-native-x11-app-surface.md), [catatan limitasi](../limitations.md), dan [provenance/build dependency](../../third_party/termux-x11/README.md). Rekomendasi konkret: pertahankan X11 sebagai jalur pertama, lanjutkan pengujian Android 10 stock, perangkat modern/16 KB, dan input/lifecycle sebelum mengaktifkannya secara default. Integrasi harian dan semua aplikasi belum selesai.

## Mekanisme yang cocok

```text
GUI Linux di guest PRoot
        ↕ X11 melalui socket lokal
X server native milik host Android
        ↕ buffer gambar / JNI
SurfaceView di area aplikasi NusaDesk
        ↕ sentuhan, mouse, keyboard Android
```

Pola ini mengambil gagasan pemisahan aplikasi, layar, dan input seperti VNC, dengan transport display lokal X11. Tidak memerlukan browser, HTTP frontend, atau protokol RFB/VNC. Kontrol fullscreen dan fokus mengambil pola kiosk.

[Termux:X11](https://github.com/termux/termux-x11) menyediakan implementasi nyata X server berbasis Android NDK. Upstream menjelaskan penggunaan bersama PRoot dengan direktori sementara yang dibagi. Source [LorieView](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie/src/main/java/com/termux/x11/LorieView.java) menggunakan SurfaceView, sementara renderer native memakai EGL/GLES. Ini membuktikan mekanismenya tersedia; integrasi dengan NusaDesk masih pekerjaan baru. View tersebut juga terkait MainActivity upstream, sehingga tidak dapat dianggap komponen siap tempel.

## Kecocokan dengan proyek

Source saat ini sudah memiliki guest Ubuntu ARM64, supervisor Android, dan container tampilan yang mempertahankan surface ketika pengguna kembali ke launcher. ProotLauncher.buildSpec menerima tambahan bind dan environment; itu seam yang relevan untuk socket display dan DISPLAY. AppSurfaceHostView menyediakan FrameLayout untuk surface aplikasi. Belum ditemukan jalur display X11/Wayland native pada source yang diperiksa.

Guest /tmp dibersihkan pada batas sesi sesuai ADR-0027. Socket display harus dibuat setelah pembersihan dan dihentikan sebelum teardown; memakai direktori socket tanpa mengikuti urutan ini bisa menghasilkan koneksi putus atau socket usang. Server bionic/NDK sebaiknya dimiliki host, sedangkan aplikasi glibc tetap di guest. Socket menyambungkan keduanya tanpa memuat library Android langsung ke proses glibc.

Untuk kiosk X11, gunakan window manager ringan yang mengatur fullscreen, fokus, dialog, dan popup. Menjalankan satu window tanpa pengelolaan dialog belum cukup untuk aplikasi desktop nyata. Untuk Wayland, [Weston kiosk-shell](https://wayland.pages.freedesktop.org/weston/toc/kiosk-shell.html) menyediakan pola fullscreen, tetapi shell itu sendiri tidak menyediakan backend tampilan Android.

## Estimasi awal, disimpan sebagai judgement historis

Angka berikut berasal dari penilaian awal sebelum audit source lanjutan di bawah. Angka ini tidak diperbarui menjadi probabilitas dukungan Android 10+; gunakan matriks bukti dan acceptance spike untuk keputusan berikutnya. Ini judgement engineering, bukan hasil statistik atau tingkat kelulusan pengujian. Asumsinya aplikasi ARM64 2D terpilih, perangkat memiliki RAM memadai, dan keputusan lisensi/dependensi dapat diselesaikan. Tidak ada estimasi untuk aplikasi x86 melalui emulasi CPU.

| Sasaran | Estimasi | Alasan |
|---|---:|---|
| Prototipe satu aplikasi 2D tampil dan menerima input melalui X11 native | 85–95% | Mekanisme upstream tersedia; guest dan host NusaDesk sudah berjalan |
| Integrasi satu APK yang layak dipakai harian untuk aplikasi terpilih | 65–80% | Adaptasi native, IME, dialog, resize, dan pemulihan surface belum dibuktikan |
| Desktop ringan lengkap, misalnya XFCE | 65–80% | Upstream mendukung pola ini; penggunaan RAM, fokus, dan ergonomi HP menambah risiko |
| Wayland native sebagai jalur pertama pada perangkat Exynos/Mali | 40–60% | Backend serta jalur software rendering perlu validasi lebih besar |

Perbedaan angka menunjukkan cakupan yang berbeda. Angka prototipe tinggi tidak berarti semua aplikasi akan cepat atau stabil lintas perangkat. Untuk game/3D berat dan kompatibilitas semua GPU, belum ada dasar yang cukup untuk persentase yang dapat dipertanggungjawabkan.

[Anland: Termux](https://github.com/lfdevs/anland-termux) menjadi alternatif Wayland yang relevan: mendukung PRoot dan menjelaskan jembatan native Android. Dokumentasinya membatasi akselerasi pada Snapdragon yang didukung; perangkat lain bergantung pada software rendering dan sebagian belum tentu dapat masuk desktop. [Waymux](https://github.com/kevincarlson/waymux) mendekati ide capture-buffer-plus-input, tetapi README saat diperiksa menyatakan validasi perangkat masih berlangsung dan injeksi keyboard belum selesai. Karena itu, keduanya belum lebih meyakinkan sebagai pilihan awal NusaDesk.

## Hambatan yang menentukan hasil

GPU presentasi Android berbeda dari GPU rendering aplikasi guest. Batas per SoC, DMA-BUF, serta klaim zero-copy dipusatkan di [docs/limitations.md](../limitations.md#guest-gpunpu-acceleration-is-device-class-specific); baseline display di sini tidak mensyaratkan akselerasi aplikasi Linux.

Input memerlukan pemetaan koordinat setelah scaling/rotasi, klik kanan dan drag, modifier, keyboard fisik, IME composition, serta clipboard. Lifecycle perlu membedakan surface Android yang hilang dari proses Linux yang mati agar kembali dari Home tidak mengulang aplikasi atau menyisakan layar hitam. Upstream Termux:X11 sendiri mendokumentasikan workaround black-screen dan format warna pada perangkat tertentu.

Ada keputusan lisensi sebelum mengambil source: repository NusaDesk berlisensi MIT, sedangkan [Termux:X11](https://github.com/termux/termux-x11/blob/master/LICENSE) menyatakan GPL-3.0. Menanam source upstream perlu penilaian kewajiban distribusi per komponen; jangan menganggap hasil gabungannya tetap MIT-only. Companion app terpisah dapat mengurangi keterikatan implementasi, tetapi integrasi UID/socket dan distribusinya tetap perlu dirancang.

## Validasi yang paling bernilai

Mulai dengan satu aplikasi GUI kecil, lalu satu aplikasi GTK/Qt yang benar-benar dibutuhkan. Buktikan gambar, mengetik memakai IME, klik/drag/scroll, popup/dialog, rotasi, buka/tutup keyboard, Home/kembali, serta stop/restart tanpa proses yatim. Ukur waktu tampil, input-to-frame, memori, dan CPU pada resolusi yang dicatat. Keberhasilan prototipe harus berasal dari alur guest → display server → surface, bukan gambar uji yang dibuat langsung oleh Android.

Emulator boleh dipakai untuk membuktikan jembatan native bila ABI server dan guest sesuai. Android x86_64 dengan ARM translation tidak membuktikan jalur PRoot ARM64, driver GPU HP, atau lifecycle OEM. Sediakan build native x86_64 dan guest yang sesuai bila ingin uji end-to-end di emulator x86_64; itu bukan dukungan ABI baru yang otomatis masuk produk.

Catatan pemeriksaan awal bahwa ADB tidak memiliki perangkat aktif **sudah superseded** oleh pemeriksaan lanjutan di bawah. Emulator tetap belum dibuktikan tersedia. Tidak ada emulator, system image, APK kandidat, atau paket GUI yang dipasang dalam riset ini.

Keputusan yang disarankan: lanjutkan spike X11 satu aplikasi dengan software rendering sebagai baseline. Setelah alur gambar-input dan recovery lolos, baru putuskan investasi integrasi satu APK dan akselerasi GPU berdasarkan aplikasi target.

## Audit lanjutan: revisi dan batas bukti

Semua kandidat dibaca dari shallow clone di luar repository NusaDesk, tanpa mengambil submodule atau menjalankan installer/build upstream. Tanggal akses 23 Oktober 2026. SHA berikut membekukan source yang dinilai; issue, release `nightly`, dan status CI dapat berubah setelah pemeriksaan.

| Kandidat | Revisi source | Temuan utama terhadap target Android 10+ |
| --- | --- | --- |
| Termux:X11 | [`7e5f5c5c9226e2cdab392b2efba91bc4bd86463e`](https://github.com/termux/termux-x11/tree/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e) | Modul library nyata, Java + JNI/NDK; minSdk source 24. Kandidat pertama, dengan adaptasi integrasi. |
| Piperella | [`df06380a7c3ec890baf8f8385de8237ce84541ed`](https://github.com/Piperella/Piperella-Wayland-Compositor/tree/df06380a7c3ec890baf8f8385de8237ce84541ed) | Library nyata, tetapi minSdk 36, Kotlin, ARM64; tidak dapat dipakai langsung pada API 29–35. |
| wlroots-android-bridge | [`52178bb20b1672a63a765c9a2666cb04163cdfee`](https://github.com/Xtr126/wlroots-android-bridge/tree/52178bb20b1672a63a765c9a2666cb04163cdfee) | App + bridge Termux/labwc; minSdk 34 dan konfigurasi native x86_64. Perlu porting besar untuk API 29 ARM64. |

“Upstream runtime” berarti laporan penulis/pengguna atau artefak upstream. “Diperiksa lokal” berarti source atau inventaris perangkat yang benar-benar dibaca sesi ini. Belum ada baris “native display NusaDesk lulus”. Build CI yang berhasil tidak membuktikan input, popup, atau lifecycle HP.

## Termux:X11: komponen yang bisa diadaptasi

[`lorie/build.gradle`](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie/build.gradle) memang menggunakan `com.android.library`, minSdk 24, compile/target 34, dan AIDL. Artinya kesimpulan awal “bukan library siap tempel” perlu diperjelas: **ada modul library**, tetapi kontrak UI, manifest, serta lifecycle-nya belum sesuai NusaDesk. Modul ini juga mendeteksi application module pemakainya untuk menghasilkan `BuildConfig.APPLICATION_ID`, sehingga sudah ada seam untuk applicationId host.

Bagian yang berguna:

| Komponen | Peran dan pekerjaan adaptasi |
| --- | --- |
| [`libXlorie.so`](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie/src/main/cpp/recipes/xserver.cmake) | Menggabungkan Xorg, framebuffer/pixman, input, clipboard, renderer dan JNI. Bangun sebagai library Android bionic dari source terpin; jangan mencoba memuatnya ke proses glibc guest. |
| [`LorieView.java`](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie/src/main/java/com/termux/x11/LorieView.java) | `SurfaceView`, JNI connection FD, transform input, IME dan clipboard. Masih memanggil `MainActivity.findActivity`, prefs, extra keys, handler dan input handler upstream. Perlu mengganti keterikatan tersebut dengan host NusaDesk; menambahkan View saja belum cukup. |
| [`CmdEntryPoint.java`](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie/src/main/java/com/termux/x11/CmdEntryPoint.java) + AIDL | Entry server upstream berjalan melalui `app_process`; `getXConnection()` mengirim FD yang dipasang oleh Activity ke `LorieView.connect()`. Adaptasi harus mempertahankan pemisahan lifetime server dan surface. Jangan mengasumsikan entry ini API JNI in-process yang siap dipanggil dari service. |
| [`cmdentrypoint.cpp`](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie/src/main/cpp/lorie/cmdentrypoint.cpp) | `TMPDIR` menentukan pathname `.X11-unix/X…`; `XKB_CONFIG_ROOT` harus ada dan dapat dibaca host. Auto-discovery mencakup path Termux/container sehingga NusaDesk sebaiknya memasok path host secara eksplisit. |

Ada detail jaringan yang tidak boleh terlewat. `lorieListenForKnocks()` memakai `INADDR_ANY`; [`lorie.h`](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie/src/main/cpp/lorie/lorie.h) menetapkan port 7892 dan basis port bertag 7900. Ini listener reconnect/control, berbeda dari transport X11. Menambahkan `-nolisten tcp` pada X server tidak otomatis mengubahnya. Spike harus menghilangkan kebutuhan listener ini lewat koneksi host-owned atau membatasinya ke loopback, sesuai aturan repo yang sudah ada; konflik port tidak boleh diam-diam menghubungkan ke sesi lain.

[`manifest library`](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie/src/main/AndroidManifest.xml) membawa Application, Activity, preferences, dan service keyboard sendiri. Audit manifest merger diperlukan agar NusaDesk tetap memakai composition root dan supervisor yang ada. Baseline input di area aplikasi tidak memerlukan penambahan global accessibility capture. Semua jalur input tetap perlu diuji, termasuk release modifier ketika surface kehilangan fokus.

Build upstream memerlukan recursive submodules Xorg/dependensinya, Python, Bison, CMake dan NDK. [`version.gradle`](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie/version.gradle) mematok NDK `29.0.14206865`; wrapper mematok Gradle 9.8.2. Upstream menjalankan `./gradlew assembleDebug` setelah mengambil submodule dalam [workflow Build](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/.github/workflows/debug_build.yml). Mesin riset hanya ditemukan memiliki NDK 28.2, jadi build upstream memerlukan toolchain tambahan atau studi kompatibilitas; tidak dicoba dalam scope ini. Java source upstream tidak berarti dependency graph bebas Kotlin: modul `lorie` masih membawa Kotlin stdlib dan lifecycle KTX. Seleksi dependency harus menjadi bagian adaptasi, tanpa memperkenalkan source Kotlin produksi NusaDesk.

Bukti build/runtime yang tersedia:

- [CI untuk SHA yang dibaca](https://github.com/termux/termux-x11/actions/runs/39908101658) berstatus `success`. [Release nightly](https://github.com/termux/termux-x11/releases/tag/nightly) menyediakan APK universal sekitar 14.7 MB dan companion package; tag itu bergerak, sehingga eksperimen berikutnya perlu mencatat SHA/digest artefak yang benar-benar dipakai.
- [README penggunaan PRoot](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/README.md) mensyaratkan shared tmp atau `TMPDIR` yang menunjuk ke padanan `/tmp` guest. Ini bukti kontrak upstream, belum bukti socket NusaDesk berjalan.
- [Issue #1144](https://github.com/termux/termux-x11/issues/1144) awalnya melaporkan flicker/freeze pada fork tertanam. Update terakhir pelapor menyatakan stack yang sama berjalan pada aplikasi resmi, termasuk rotasi dan kembali ke Activity. Ini mendukung kelayakan mekanisme, sekaligus menunjukkan fork dapat merusak recovery. Jangan mengutip laporan awal itu sebagai bug upstream yang masih terbuka.
- [Issue #1145](https://github.com/termux/termux-x11/issues/1145) tentang keyboard dan [#1149](https://github.com/termux/termux-x11/issues/1149) tentang Ctrl yang tertinggal masih terbuka saat dibaca. Laporan pengguna tersebut menjadi kasus uji, bukan kesimpulan bahwa seluruh input gagal atau telah selesai diperbaiki.

Varian [`sharedUid`](https://github.com/termux/termux-x11/blob/7e5f5c5c9226e2cdab392b2efba91bc4bd86463e/lorie-app/build.gradle) memakai targetSdk 28 dan shared identity Termux. Itu bukan jalur integrasi NusaDesk targetSdk 37. Companion app terpisah boleh menjadi pembanding teknis kemudian, tetapi tidak memenuhi target GUI di area aplikasi NusaDesk dan tidak menyelesaikan akses direktori antar-UID.

## Piperella: bentuk library cocok, kontraknya belum cocok

[`android/build.gradle.kts`](https://github.com/Piperella/Piperella-Wayland-Compositor/blob/df06380a7c3ec890baf8f8385de8237ce84541ed/android/build.gradle.kts) menetapkan minSdk/compileSdk 36, ABI `arm64-v8a`, NDK r29 dan JVM 17. Native build secara eksplisit memakai platform 35; skrip dependency default ke API 36. Menurunkan angka minSdk saja tidak membuktikan library dan prebuilts dapat dimuat pada API 29. Perlu membangun ulang dependensi dan mengaudit simbol/API terlebih dahulu.

Source compositor benar-benar ada: C++ Wayland server, `wl_shm`, xdg-shell/popups, seat, text input, EGL renderer, JNI, beserta Kotlin `CompositorEngine`. Ini lebih konkret daripada klaim README semata. Namun integrasi “drop-in” memiliki batas berikut:

- [`SurfaceProvider.attach()`](https://github.com/Piperella/Piperella-Wayland-Compositor/blob/df06380a7c3ec890baf8f8385de8237ce84541ed/android/src/main/java/com/piperella/compositor/SurfaceProvider.kt) menambahkan SurfaceView ke `activity.window.decorView`, dengan overlay di atas. NusaDesk membutuhkan view di `AppSurfaceHostView`, ukuran/fokus mengikuti area aplikasi serta task bar. Perlu mengganti ownership/layout tersebut.
- [`CompositorEngine`](https://github.com/Piperella/Piperella-Wayland-Compositor/blob/df06380a7c3ec890baf8f8385de8237ce84541ed/android/src/main/java/com/piperella/compositor/CompositorEngine.kt) mempertahankan native handle process-global saat surface hilang. Tetapi API `stop()` benar-benar menghentikan compositor. Mengikuti contoh README yang memanggil stop pada `onDestroy()` dapat memutus klien; lifecycle harus disesuaikan dengan supervisor NusaDesk.
- Path aktual engine adalah `<dataDir>/tmp`, sedangkan contoh README memakai `<dataDir>/files/tmp`. `isSocketReady` hanya memeriksa keberadaan file `piperella-wayland-0`. [`labwc.cpp`](https://github.com/Piperella/Piperella-Wayland-Compositor/blob/df06380a7c3ec890baf8f8385de8237ce84541ed/android/src/main/cpp/labwc.cpp) membuat `wayland-0` wajib dan alias `piperella-wayland-0` opsional. Maka contoh path, alias readiness, dan health check tidak boleh disalin tanpa pembetulan.
- [`xdg_shell.cpp`](https://github.com/Piperella/Piperella-Wayland-Compositor/blob/df06380a7c3ec890baf8f8385de8237ce84541ed/android/src/main/cpp/xdg_shell.cpp) memiliki implementasi popup/fullscreen, tetapi `toplevel_set_parent()` masih no-op untuk hubungan stacking. Dialog transient/modal dalam satu aplikasi belum terbukti benar hanya dari daftar protocol yang tersedia.
- [`manifest`](https://github.com/Piperella/Piperella-Wayland-Compositor/blob/df06380a7c3ec890baf8f8385de8237ce84541ed/android/src/main/AndroidManifest.xml) membawa foreground mediaPlayback service, service input, accessibility dan notification listener, serta Vulkan 1.1 required. Kebutuhan tambahan tersebut tidak otomatis menjadi requirement kiosk 2D NusaDesk; mengambil seluruh manifest akan memperbesar scope.

Cara build yang didokumentasikan adalah menyertakan module `android/`, mengaktifkan AIDL, menyediakan Wayland 1.24.0/libffi 3.4.6/libxkbcommon 1.7.0, lalu membangun app contoh. Ada prebuilts yang committed, tetapi [`skrip dependency`](https://github.com/Piperella/Piperella-Wayland-Compositor/blob/df06380a7c3ec890baf8f8385de8237ce84541ed/scripts/build-libwayland-android.sh) tetap perlu diaudit/rebuild sebelum distribusi NusaDesk. Skrip menggunakan tag versi yang bisa dioverride; itu belum setara provenance source+digest dan reproduksibilitas yang diterapkan pada bridge PRoot repo ini.

Bukti runtime perlu dibaca hati-hati. [`examples/test-app`](https://github.com/Piperella/Piperella-Wayland-Compositor/blob/df06380a7c3ec890baf8f8385de8237ce84541ed/examples/test-app/README.md) menyediakan klien NDK 3D dengan jalur AHardwareBuffer khusus; demo tersebut tidak membuktikan aplikasi Ubuntu/glibc biasa melalui PRoot. [PR #36](https://github.com/Piperella/Piperella-Wayland-Compositor/pull/36) justru memilih glibc-on-bionic tanpa PRoot, dan checklist memasang APK hingga Notepad++ tampil masih kosong. [PR #31](https://github.com/Piperella/Piperella-Wayland-Compositor/pull/31) meminta validasi perangkat setelah perbaikan DMA-BUF. Status merge bukan hasil pengujian.

Saat diperiksa, API GitHub mengembalikan tidak ada release, issues dinonaktifkan (hasil endpoint issues yang ada adalah PR), serta satu workflow run lama berstatus [failure](https://github.com/Piperella/Piperella-Wayland-Compositor/actions/runs/28291268542). Ini tidak membuktikan HEAD tidak dapat dibangun, tetapi tidak menyediakan build sukses HEAD atau APK rilis tervalidasi yang dapat dijadikan bukti. Putusan: referensi compositor dan API embedding; bukan dependency pertama untuk Android 10+.

## wlroots-android-bridge: reference backend, bukan jalur langsung ARM64 API 29

[`app/build.gradle.kts`](https://github.com/Xtr126/wlroots-android-bridge/blob/52178bb20b1672a63a765c9a2666cb04163cdfee/app/build.gradle.kts) memakai minSdk 34, target/compile 36, Java 21, Kotlin dan Compose. [`tinywl/build.gradle.kts`](https://github.com/Xtr126/wlroots-android-bridge/blob/52178bb20b1672a63a765c9a2666cb04163cdfee/tinywl/build.gradle.kts) membatasi native CMake ke `x86_64`, menggunakan NDK 28.0 dan direktori output AIDL internal AGP. Tidak ada baseline ARM64/API 29 siap dibangun dari konfigurasi tersebut.

Backend compositor kini berada pada repository pendamping labwc-android. Source pendamping yang dibaca adalah [`7396c3a6136bcf3afd811af8ffc54d0c3ffdd290`](https://github.com/Xtr126/labwc-android/tree/7396c3a6136bcf3afd811af8ffc54d0c3ffdd290/src/android). [`meson.build`](https://github.com/Xtr126/labwc-android/blob/7396c3a6136bcf3afd811af8ffc54d0c3ffdd290/src/android/meson.build) memasukkan allocator, buffer presenter dan glue Binder. [`start.sh`](https://github.com/Xtr126/labwc-android/blob/7396c3a6136bcf3afd811af8ffc54d0c3ffdd290/src/android/start.sh) memakai path XKB Termux, mencari APK `com.xtr.tinywl`, lalu menjalankan `app_process`. Versi pendamping ini bukan submodule yang dipin oleh bridge; kompatibilitas kedua revisi belum diuji.

[Instruksi build upstream](https://github.com/Xtr126/wlroots-android-bridge/blob/52178bb20b1672a63a765c9a2666cb04163cdfee/README.md) membangun labwc dengan Meson di Termux dan memerlukan APK terpasang untuk linking shared library. App dibangun lewat Gradle, lalu membutuhkan paket wlroots/Mesa dari fork Termux. Mengimpor direktori `tinywl` ke NusaDesk tidak membawa seluruh server dan dependency tersebut. Komponen yang relevan untuk dipelajari adalah ownership buffer, presentasi SurfaceControl/SurfaceTransaction, dan pengiriman input; detail jalur GPU dipusatkan di limitasi.

Bukti upstream meliputi screenshot multiwindow dan catatan vkcube 60 FPS pada Intel; tidak direplay sesi ini. Checklist README menandai keyboard/mouse dan fullscreen selesai, sedangkan touchscreen, stylus serta mouse capture belum. [Issue #6](https://github.com/Xtr126/wlroots-android-bridge/issues/6) mengenai context menu Firefox tidak tampil dan [#1](https://github.com/Xtr126/wlroots-android-bridge/issues/1) mengenai modifier masih terbuka. [Issue #8](https://github.com/Xtr126/wlroots-android-bridge/issues/8) memuat kegagalan memuat liblabwc; status tersebut tidak membuktikan penyebabnya berlaku untuk semua perangkat.

Ada bukti alternatif yang lebih dekat ke kebutuhan kita pada [diskusi issue #5](https://github.com/Xtr126/wlroots-android-bridge/issues/5): pengembang Haven melaporkan labwc terintegrasi JNI, foot dari PRoot Alpine melalui socket bind, IME, pixman dan ANativeWindow software blit. Mereka menyebut patch `shm_open`/memfd, XKB, seat capability, frame callbacks, dan sekitar 20 dependensi. Ini **laporan pihak ketiga**, bukan demonstrasi branch bridge utama atau pengujian NusaDesk. Jalur Wayland software tetap mungkin; audit fork/build/runtime tersebut merupakan riset lanjutan bila X11 tersandung, bukan alasan menyatakan bridge saat ini kompatibel.

## Kontrak integrasi paling kecil di NusaDesk

Pemeriksaan source lokal mengonfirmasi `ProotLauncher.buildSpec(appId, guestArgv, extraBinds, extraEnv)` menerima bind app-private dan environment tambahan. `AppSurfaceHostView.getSurfaceContainer()` mengembalikan FrameLayout, sementara surface dipertahankan dan disembunyikan ketika kembali ke launcher. Ini seam yang dapat digunakan; visibility `View` tidak menjamin buffer SurfaceView tetap hidup, sehingga renderer harus menerima detach/attach surface tanpa otomatis mengulang aplikasi Linux.

Pilihan awal yang disarankan menggunakan **direktori `/tmp` rootfs yang sudah ada**, bukan mengganti seluruh `/tmp` dengan bind baru. X server bionic milik host diberi `TMPDIR=<activeRootfs>/tmp` dan path host `XKB_CONFIG_ROOT` ke data terpin. Aplikasi glibc melihat socket yang sama sebagai `/tmp/.X11-unix/X1` dengan `DISPLAY=:1`. Nomor `:1` hanya contoh display eksperimen, bukan perubahan endpoint SSH. PRoot extra bind masih berguna jika kemudian dipilih direktori display khusus, tetapi tidak wajib untuk mengakses rootfs `/tmp` yang sama. Wajib uji pathname Unix socket melintasi PRoot, panjang `sockaddr_un`, dan transfer FD sebelum menilai alur berhasil.

Urutan yang harus dipertahankan dari [ADR-0027](../decisions/0027-session-temporary-guest-tmp.md):

1. Reclaim proses sesi lama, lalu `GuestEphemeralStateCleaner.clearGuestTmp()`; jangan membuka socket lebih awal.
2. Mulai server display host, buat socket dan buktikan health dengan klien X11 yang menyelesaikan handshake/query. Keberadaan file socket atau PID saja tidak cukup.
3. Mulai window manager ringan dan satu aplikasi Linux terpilih di **guest yang sudah ada**. Jalankan dari kanal guest terkontrol; environment display harus diberikan pada klien dan WM, bukan diasumsikan diwariskan melalui login SSH yang menyaring environment.
4. Tempelkan surface native di area aplikasi; perpindahan launcher/Home dan kehilangan surface tidak langsung menghentikan server. Kembali menempelkan output dan mengembalikan fokus/input dengan geometri terbaru.
5. Pada stop sesi, hentikan klien dan WM, tutup server/FD renderer, pastikan proses selesai, baru bersihkan `/tmp`. Pada force-stop, reclaim berikutnya harus memperhitungkan server host juga sebelum cleanup.

Untuk kiosk, kandidat WM eksperimen adalah Openbox dengan konfigurasi satu aplikasi dimaksimalkan/fullscreen, tanpa panel desktop. Ini pilihan eksperimen yang perlu divalidasi, bukan dependency yang ditambahkan. Dialog transient, popup override-redirect, focus grab, dan penempatan di layar tetap perlu bekerja. Jika WM terpilih tidak melakukannya, spike gagal meskipun jendela utama terlihat. Tidak ada kebutuhan device-owner, lock-task, desktop lengkap, Wine, atau emulasi x86 dalam spike ini.

## Lisensi dan unit distribusi

Root NusaDesk adalah MIT. Ketiga repository kandidat menyatakan GPL-3.0; [`THIRD_PARTY_NOTICES` Piperella](https://github.com/Piperella/Piperella-Wayland-Compositor/blob/df06380a7c3ec890baf8f8385de8237ce84541ed/THIRD_PARTY_NOTICES.md) merinci turunan input bridge GPL, AOSP AIDL Apache-2.0, serta Wayland/libffi/libxkbcommon dengan lisensi permisif. Lisensi project tidak menggantikan pemeriksaan header dan dependensi transitive masing-masing file.

Mengambil mekanisme SurfaceView/JNI/socket sebagai gagasan berbeda dari menyalin `LorieView`, input bridge, atau menautkan native compositor. MIT tidak membuat penautan GPL menjadi MIT-only. Sebelum distribusi hasil integrasi, perlu keputusan unit combined work, GPL yang berlaku, source yang bersesuaian beserta patch/build scripts, dan notices tiap komponen. Piperella sendiri menyatakan aplikasi pemakai library harus dirilis di bawah GPL-3.0. Keputusan distribusi belum dibuat dalam riset ini; tidak ada clearance lisensi yang diklaim.

Companion process/app terpisah juga tidak otomatis menyelesaikan kewajiban lisensi. Secara teknis, menaruh server pada APK UID lain justru menambah masalah socket app-private, akses rootfs, embedding surface dan lifecycle. Karena target akhirnya area aplikasi NusaDesk dalam satu APK, rekomendasi teknis adalah adaptasi native host dengan keputusan lisensi eksplisit, bukan menjadikan companion UI sebagai hasil akhir.

## Inventaris perangkat yang benar-benar diperiksa

| Pemeriksaan ADB | Hasil 23 Oktober 2026 | Batas kesimpulan |
| --- | --- | --- |
| Perangkat online | Samsung SM-G970F/S10e fisik | Tidak lagi terblokir oleh tidak adanya perangkat; emulator tidak digunakan. |
| Android / ABI / page size | Android 12, API 31; arm64-v8a; 4096 byte | API 29 dan 16 KB belum diuji untuk display native. |
| APK NusaDesk terpasang | Debug 0.14.3, versionCode 14003, targetSdk 37 | Source repo VERSION 0.14.4; jangan menyamakan keduanya. Tidak ada update APK dilakukan. |
| Proses terlihat | App NusaDesk, libproot.so, sshd fixed port | Inventaris proses; bukan health test guest dan bukan bukti display. |
| Kapasitas snapshot | RAM total sekitar 5.3 GiB, available sekitar 2.1 GiB; ruang `/data` sekitar 98 GiB; layar 1080×2280 | Snapshot sesaat; belum baseline konsumsi compositor atau jaminan memori tersedia saat spike. |
| Kandidat terpasang | Filter package untuk Termux/Piperella/Wayland tidak menemukan paket kandidat | Tidak ada uji runtime ketiga kandidat pada sesi ini. |
| File guest terpilih | Rootfs dan `/tmp` ada; xterm, xdpyinfo, Openbox, Weston, `/usr/share/X11/xkb` tidak ditemukan pada path standar yang diperiksa | Inspeksi filesystem `run-as`; bukan inventaris semua aplikasi dan bukan bukti eksekusi dalam domain app. |

Probe hanya membaca properties, package/process state, SurfaceFlinger, ukuran layar, kapasitas dan path rootfs. Tidak ada force-stop, restart Linux, pemasangan APK/paket, perubahan grant, atau mutasi rootfs. Tidak ada screenshot GUI kandidat, ukuran FPS/latency, atau hasil input yang dapat dilaporkan. Pemeriksaan `run-as` tidak dipakai sebagai pengganti bukti `untrusted_app`.

## Spike berikutnya dan kriteria keputusan

Tabel ini menyimpan snapshot usulan sebelum implementasi, sehingga status “belum” di tabel adalah historis. Status aktual S0–S4 dan gate yang belum selesai dicatat pada [ADR-0068](../decisions/0068-experimental-native-x11-app-surface.md) dan [limitasi](../limitations.md). Sasaran minimum Android 10+ tetap berlaku walaupun perangkat yang tersedia saat ini API 31.

| Tahap | Pekerjaan terbatas | Lulus jika | Status |
| --- | --- | --- | --- |
| S0: build dan provenance | Pin Termux:X11 + submodule, daftar lisensi, bangun server ARM64 serta host eksperimen minSdk 29 tanpa mengubah targetSdk NusaDesk; nilai toolchain/dependency terlebih dahulu | ELF/dependensi dapat ditelusuri; tidak bergantung pada sharedUid/legacy target Termux; API/simbol >29 tidak dipanggil tanpa guard/fallback | Belum dilakukan |
| S1: gambar dari guest | SurfaceView di host eksperimen, server X11 dan XKB; klien X11 diagnostik kecil dari guest PRoot yang sudah ada | Query X11 berhasil dan frame klien guest terlihat dalam screenshot yang diinspeksi; bukan Android test pattern | Belum dilakukan |
| S2: aplikasi dan input kiosk | Payload eksperimen terpin untuk satu aplikasi GTK kecil, misalnya Mousepad, beserta WM, font dan dependensi yang dihitung dahulu | Satu aplikasi fullscreen; menu/context menu, dialog modal/file chooser, mengetik/IME, Enter/Backspace, Ctrl/Alt/Shift, touch click/drag/scroll, mouse dan keyboard fisik jika tersedia | Belum dilakukan |
| S3: area aplikasi NusaDesk | Adaptasi view/manifest dan ownership ke supervisor serta FrameLayout; tidak membuat sesi Linux kedua | GUI ada di area aplikasi NusaDesk; task bar tetap dapat dipakai; tanpa WebView/VNC; protocol probe dan kegagalan eksplisit terhubung ke status | Belum dilakukan |
| S4: recovery dan matriks | Home/kembali, launcher, IME buka/tutup, rotasi, surface recreation; stop/restart sesi, server crash, force-stop | Tidak ada modifier tersangkut, layar hitam permanen, socket usang, atau proses yatim; cleanup mengikuti ADR-0027; bukti API 29 fisik dan API 31 dicatat terpisah | Belum dilakukan |

S1 tidak menggantikan S2/S3. APK companion resmi hanya dapat menjadi kontrol pembanding upstream apabila diperlukan; lulus di aplikasi Termux:X11 bukan lulus di area aplikasi NusaDesk. Klien GTK eksperimen juga tidak membuka API instalasi aplikasi umum di produk.

Mulai pada resolusi guest tetap, misalnya 1280×720 yang diskalakan ke area aplikasi, lalu catat rasio dan pemetaan koordinat. Ukur waktu sampai frame pertama, input-to-frame dengan metode yang dinyatakan, CPU/RSS serta pemakaian berkelanjutan pada konfigurasi tersebut. Angka FPS dari README upstream tidak dipakai sebagai hasil lokal. Mouse/keyboard fisik yang belum tersedia tetap berstatus belum diuji.

API 31/S10e cukup untuk langkah runtime pertama, tetapi klaim Android 10+ tetap memerlukan perangkat API 29 atau lingkungan API 29 yang sesuai; emulator x86_64 hanya membuktikan jalur dengan ABI yang benar-benar dibangun. API modern/16 KB dan lifecycle OEM lain menjadi matriks terpisah. Bila X11 terbukti gagal karena batas konkret yang tidak bisa diatasi secara kecil, langkah cadangan adalah audit Wayland software labwc/pixman yang disebut dalam issue #5, bukan langsung mengadopsi backend GPU minigbm.

Rekomendasi konkret: pilih Termux:X11 sebagai basis spike native API 29 ARM64, dengan renderer guest software dan WM satu aplikasi. Pertahankan Piperella sebagai referensi API compositor, serta wlroots/labwc sebagai referensi backend dan alternatif software. Keputusan produk, lisensi distribusi dan klaim dukungan Android 10+ menunggu bukti spike; riset ini belum mengubah arsitektur produksi.

## Verifikasi perubahan dokumentasi

Pemeriksaan whitespace mencakup file tracked dan laporan yang masih untracked; keduanya lulus. Pemeriksaan tautan lokal, keseimbangan code fence dan keberadaan 25 path source upstream terpin pada checkout yang dibaca juga lulus. Pemeriksaan path source tersebut bukan pengecekan status HTTP seluruh tautan eksternal. Tidak ada source produksi atau dependency proyek yang diubah, sehingga Gradle test/lint/build tidak dijalankan; tidak ada hasil build atau pengujian GUI lokal yang diklaim.
