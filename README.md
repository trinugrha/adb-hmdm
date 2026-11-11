# HMDM Installer (Android)

Port dari HMDM ADB Installer v3 (Windows) ke aplikasi Android. HP teknisi menjadi "komputer ADB":
pair + connect lewat Wireless debugging ke HP target, memasang APK, lalu `dpm set-device-owner`.
Tidak butuh adb.exe / PC.

## Cara membangun APK
**Opsi A – Android Studio:** File › Open folder ini › tunggu sync › Build › Build APK(s).
Hasil: `app/build/outputs/apk/debug/app-debug.apk`.

**Opsi B – GitHub Actions (tanpa install apa pun):** upload folder ini ke repo GitHub (private juga bisa),
buka tab Actions › "Build APK" › Run workflow, lalu unduh artifact `HMDM-Installer-apk`.

## Cara pakai
1. Pasang APK ini di HP teknisi (izinkan "install dari sumber tidak dikenal").
2. HP target: Opsi Developer › Wireless debugging (satu Wi-Fi dengan HP teknisi) › "Pair device with pairing code".
3. Isi IP, Port pairing, kode › **Pair**. Lalu isi IP + Port connect (layar utama Wireless debugging) › **Connect**.
4. **1 · Instalasi Aplikasi**: ketuk area "Pilih File APK Di Sini" (bisa banyak file). Daftar aplikasi yang akan dipasang (nama + ukuran) tampil di bawahnya; ketuk ikon tempat sampah untuk membuang satu APK dari daftar. Lalu **Pasang Semua APK**.
5. **2 · Atur Hak Akses**: pastikan semua akun Google/pengguna di HP target sudah dihapus, cek status Device Owner, lalu **Jalankan set-device-owner**.
6. Atau pakai **Proses Otomatis** (hanya Install APK + Set Owner; Pair & Connect harus sudah dilakukan lebih dulu). Kartu **Uninstall Kaspersky** berisi 3 langkah: hapus data, matikan Aplikasi Admin Kaspersky di HP (manual), lalu uninstall.

Syarat device owner: HP target baru / habis factory reset dan belum ada akun (Google dll).
Satu HP tidak bisa memasang dirinya sendiri sebagai device owner dengan cara ini kecuali IP diisi 127.0.0.1 dan
syarat di atas terpenuhi.

## Opsi USB debugging (kabel)
Alternatif tanpa Wi-Fi / Pair:
1. HP target: Opsi Developer › aktifkan **USB debugging** (mode USB: Transfer file).
2. Colok HP target ke HP teknisi memakai kabel OTG (HP teknisi = USB host).
3. Di kartu **USB Debugging (Kabel)** ketuk **Connect USB**; izinkan akses USB di HP teknisi, lalu di HP target
   setujui dialog "Izinkan USB debugging?" (centang "Selalu izinkan").
4. Lanjut seperti biasa: Pasang APK, set-device-owner, atau Proses Otomatis.
Cara kerja: paket ADB dikirim lewat soket loopback lalu diteruskan ke endpoint bulk USB (`UsbAdbBridge.kt`).

## Login PIN
- PIN 4 digit. PIN awal: **4321** (dibuat otomatis saat pertama kali dibuka). Untuk mengganti PIN, ubah `DEFAULT_PIN` di `PinStore.kt` lalu build ulang.
- Setelah itu setiap buka aplikasi harus memasukkan PIN (otomatis diperiksa setelah digit ke-4; PIN benar langsung masuk).
- Tombol **Logout** (header) langsung mengunci aplikasi; koneksi ADB / proses yang berjalan tidak terputus.
- **Timer sesi** di status bar header menghitung mundur 30 menit sejak login (TIDAK direset oleh sentuhan layar; ditahan penuh
  saat operasi install/pair berjalan). Habis → terkunci otomatis. Batas waktu: `LOCK_TIMEOUT_MS` di `MainActivity.kt`.
- Salah 5x berturut-turut → terkunci sementara (30 dtk, lalu 1, 2, 4 mnt ... maks 15 mnt).
- PIN disimpan sebagai hash PBKDF2-SHA256 + salt (`PinStore.kt`), bukan teks asli.

## Catatan teknis
- ADB memakai library [libadb-android](https://github.com/MuntashirAkon/libadb-android) (JitPack) + Conscrypt (TLS 1.3).
- Kunci/sertifikat ADB dibuat sekali (`AdbManager.kt`) dan disimpan; pairing terikat pada kunci ini.
- Install = `cmd package install -r -S <ukuran>` lewat stream `exec:` (setara `adb install -r`).
- Package id `co.sat.hmdminstaller`, minSdk 28 (Android 9+), build debug.

## PENTING: belum pernah dikompilasi
Proyek ini ditulis tanpa akses Android SDK/Maven, jadi belum dikompilasi atau diuji di perangkat.
Kemungkinan perlu penyesuaian kecil, terutama di `AdbManager.kt` / `AdbController.kt`
(nama method libadb-android) dan versi `libadb-android` di `app/build.gradle.kts` (cek tag terbaru di JitPack).
Kirim pesan error build-nya jika ada, nanti diperbaiki.
