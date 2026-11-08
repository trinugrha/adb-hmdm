# HMDM Wireless ADB Installer — Aplikasi Android

Konversi dari **Web ADB HMDM v2.5** (WebUSB) menjadi **aplikasi Android native** yang memakai
**ADB Wireless** (tanpa kabel USB, tanpa PC), dan **bisa di-build langsung di GitHub** via GitHub Actions.

PIN login sama seperti versi web: `4567`.

## Fitur (sama seperti versi Web)

1. **Login PIN 4 digit** (hash SHA-256, di dalam aplikasi)
2. **Koneksi ADB Wireless** ke HP target:
   - Mode klasik: `IP:5555` (setelah `adb tcpip 5555` / aktifkan ADB over network)
   - Mode Wireless Debugging Android 11+: `IP:port` + centang **TLS** (aktifkan *Debugging nirkabel* di HP target)
3. **Install banyak APK sekaligus** (picker dokumen, streaming `cmd package install -S`, fallback `push + pm install` untuk Android lama)
4. **Set Device Owner** `com.hmdm.launcher/.AdminReceiver` + cek status (`dpm list-owners` / `dumpsys device_policy`)
5. **Otomatis: Install APK + Set Owner** satu klik
6. **Uninstall Kaspersky** (`com.kaspersky.kes`): `pm clear` → panduan matikan Device Admin → `pm uninstall`
7. **Aktifkan ADB Wireless dari jarak jauh**: kirim `tcpip:5555` + tampilkan `adb connect IP:5555`
8. **Log terminal** di aplikasi

## Cara pakai (Wireless Debugging, tanpa kabel — disarankan)

Di **HP target** (yang mau di-MDM):
1. Aktifkan *Opsi developer*.
2. Aktifkan **Debugging nirkabel** (*Wireless debugging*).
3. Ketuk **Pair device with pairing code** HANYA untuk pairing sistem sekali saja (di *Settings*, bukan di aplikasi ini).
4. Lihat **IP address & port utama** di menu *Wireless debugging* (contoh `192.168.1.10:37747`).

Di **HP teknisi** (yang menginstal aplikasi ini):
1. Buka aplikasi → login PIN `4567`.
2. Isi **IP** = `192.168.1.10`, **Port** = `37747`, aktifkan **TLS (Wireless debugging)**.
3. Ketuk **Hubungkan**. Setujui dialog *"Allow wireless debugging?"* di HP target.
4. Pilih APK → **Pasang Semua APK** → **Set Device Owner** (atau tombol **Otomatis**).
5. Pastikan HP target: tidak ada akun Google, tidak ada user/profil kerja lain (syarat `dpm set-device-owner`).

## Cara pakai (mode klasik port 5555)

1. HP target sudah dalam mode `adb tcpip 5555` (mis. via USB sekali `adb tcpip 5555`, atau ROM yang menyediakan *ADB over network*).
2. Di aplikasi: isi IP + port `5555`, **matikan TLS**, ketuk Hubungkan.

## Cara build di GitHub (tanpa Android Studio)

1. Buat repo GitHub baru, upload **seluruh isi folder ini** (termasuk folder `.github/`).
2. Buka tab **Actions** → workflow **Build Debug APK** akan jalan otomatis.
3. Setelah selesai, unduh artefak **app-debug-apk** → file `app-debug.apk`.
4. Aktifkan * mohon izinkan jika muncul warning Play Protect (APK debug tidak ditandatangani Play).
5. Tiap push / tiap rilis tag `v*` akan memicu build ulang (lihat `.github/workflows/build-apk.yml`).

Build lokal (opsional):

```bash
gradle assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
```

## Struktur proyek

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/alfamart/hmdmadb/MainActivity.kt
app/src/main/java/com/alfamart/hmdmadb/adb/AdbClient.kt
app/src/main/java/com/alfamart/hmdmadb/adb/AdbCrypto.kt
.github/workflows/build-apk.yml
```

## Catatan teknis

- Protokol ADB diimplementasikan murni di Kotlin (`AdbClient.kt`): `CNXN`, `AUTH` (RSA 2048, kunci disimpan di `SharedPreferences`), `OPEN`/`WRTE`/`OKAY`/`CLSE`.
- Mode TLS memakai `SSLSocket` Trust-All (sertifikat self-signed milik Wireless Debugging). Verifikasi identitas dilakukan lewat dialog sistem di HP target + RSA AUTH — sama seperti `adb connect` desktop.
- Tidak perlu root, tidak perlu Shizuku, tidak perlu kabel USB.
- `minSdk 26` (Android 8+), `targetSdk 34`. UI Jetpack Compose Material3.

© 2026 PT. Sumber Alfaria Trijaya, Tbk. — IT RZ01 Department · v3.0-android-wireless
