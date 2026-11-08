# Web ADB HMDM v2.5 — Versi Android (build di GitHub)

Port Android (Kotlin, native) dari `Web ADB HMDM v2.5.html`.
File HTML asli tetap disertakan di [`web-original/`](web-original/) sebagai referensi.

## Perbedaan penting vs versi Web

| Versi Web (HTML) | Versi Android (repo ini) |
|---|---|
| Dijalankan di **PC/browser Chrome** | Dijalankan **langsung di HP target** |
| Kabel USB + WebUSB + `adb push / pm install` | Pemilih file APK + `PackageInstaller` (dialog sistem) |
| `adb shell dpm set-device-owner …` dari PC | Dicoba via shell/root; bila ditolak Android, aplikasi menampilkan **perintah ADB untuk dijalankan sekali dari PC** + tombol salin |
| `pm clear / pm uninstall` via WebUSB | Dicoba via shell; fallback dialog sistem + panduan Admin perangkat |
| `tcpip:5555` via WebUSB | Panduan *Wireless debugging* + tombol coba `setprop` (root) |

> Catatan Android: aplikasi biasa **tidak diizinkan** menjalankan `dpm set-device-owner`
> setelah setup selesai. Itu batasan sistem, bukan bug. Alur yang didukung:
> 1. Install APK ini di HP target → 2. Pasang APK HMDM → 3. Jika tombol langsung
>    ditolak, jalankan **satu perintah** `adb shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver`
>    dari PC (USB debugging aktif), persis syarat yang sama seperti versi Web.

## Fitur (sama seperti Web v2.5)

- 🔒 Login PIN 4 digit — default **`4567`** (hash SHA-256 sama: `db2e7f…2669`), kunci sementara setelah 3x salah, sesi 20 menit
- 📦 Pilih & pasang **banyak APK** sekaligus + progress + log
- 🛡 Cek / Set **Device Owner** `com.hmdm.launcher/.AdminReceiver` + penjelasan error (akun masih ada, multi-user, sudah provisioned, dsb.)
- ⚡ Mode **Otomatis**: Install + Set Owner sekali klik
- 🦠 **Hapus data & Uninstal Kaspersky** `com.kaspersky.kes` + panduan Device Admin
- 📶 Info IP Wi-Fi / ADB wireless
- 📟 Terminal log + 🌙 toggle tema + ⏱ timer sesi

## Cara build di GitHub (tanpa install apa pun)

1. Buat repo baru di GitHub (mis. `hmdm-installer-android`), **tanpa** centang *Add README*.
2. Upload semua file repo ini (cara termudah: di halaman repo → *Add file → Upload files*,
   seret seluruh isi folder — atau via git, lihat bawah).
3. Buka tab **Actions** → klik **I understand my workflows, go ahead and enable them** (jika diminta).
4. Setiap `push` otomatis menjalankan workflow **`Build Android APK`**.
5. Setelah hijau ✅ → tab **Actions → run terbaru → Artifacts → `HMDM-Installer-debug`** →
   unduh ZIP berisi **`.apk`**, pindah ke HP, install.
6. Untuk rilis: buat tag `v2.5-android` (*Releases → Create a new release → Choose a tag*),
   workflow otomatis melampirkan APK release ke halaman Release.

### Via git (alternatif)

```bash
cd HMDM-Installer-Android
git init
git add .
git commit -m "HMDM Installer Android v2.5"
git branch -M main
git remote add origin https://github.com/USERNAME/hmdm-installer-android.git
git push -u origin main
```

## Build lokal (opsional)

Butuh Android Studio (Hedgehog+) atau JDK 17 + Android SDK 34:

```bash
# Android Studio: Open folder ini → Run ▶ / Build → Build APK(s)
# atau CLI:
gradle assembleDebug
# hasil: app/build/outputs/apk/debug/*.apk
```

## Ganti PIN

Default `4567`. Untuk PIN baru (mis. `1234`):

```bash
python3 -c "import hashlib; print(hashlib.sha256(b'1234').hexdigest())"
```

Tempel hasilnya ke `VALID_PASS_HASH` di
`app/src/main/java/com/alfamart/hmdminstaller/AuthManager.kt`.

## Struktur proyek

```
HMDM-Installer-Android/
├── .github/workflows/android.yml   # CI: build APK di GitHub
├── app/
│   ├── build.gradle                 # deps: material, appcompat, coroutines
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/alfamart/hmdminstaller/
│       │   ├── MainActivity.kt      # UI + alur (login, install, owner, kaspersky)
│       │   ├── AuthManager.kt       # PIN sha256 + sesi 20 mnt + lockout
│       │   ├── Shell.kt             # eksekusi shell + mapping error dpm/pm
│       │   └── InstallReceiver.kt   # hasil PackageInstaller
│       └── res/layout/activity_main.xml
└── web-original/                    # file HTML Web v2.5 asli (referensi)
```

## Izin (manifest)

- `REQUEST_INSTALL_PACKAGES` — pasang APK (dialog sistem, aman)
- `REQUEST_DELETE_PACKAGES` — uninstal (dialog sistem)
- `QUERY_ALL_PACKAGES` — cek HMDM/Kaspersky terpasang
- `ACCESS_WIFI_STATE` — tampilkan IP untuk ADB wireless

© 2026 PT. Sumber Alfaria Trijaya, Tbk. — IT RZ01 · v2.5-android
