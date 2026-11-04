# Kaveh QR Studio (Android APK)

A lightweight, offline Android application for **Sini Cable Kaveh**.

## What it does
- **Fixed built-in QR** pointing to `https://sinicablekaveh-create.github.io/telegram-electric/kaveh/` (also contains Kaveh's branded QR logo).
- Choose **only a photo** from Gallery / Files. No need to choose a QR image.
- QR appears automatically. Drag it with one finger, pinch to resize, or adjust the size slider.
- Save a high-resolution PNG to **Pictures/KavehQRStudio** (Android 10+).
- Optional **2x HD** size enhancement with mild unsharp mask (max 6000 px on longer edge); this is *not AI super-resolution*, and cannot recover missing original detail.
- Offline; no storage permissions, internet permission, ads, analytics, or external libraries.

## Build
Use **Android Studio** to open this folder or run with Java 17, Android SDK 35 and Gradle 8.10.2:

```sh
gradle --no-daemon :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

A GitHub Actions workflow (`.github/workflows/kaveh-qr-studio.yml` at repository root) can build and upload the APK.

## Privacy
Images remain on the phone. The app only needs the user-selected image URI and writes the output to MediaStore (on Android 10+). A built-in QR avoids fetching remote assets. No network permission is requested.

## Version 1.1 verification
- Embedded QR opens the GitHub Pages URL directly, with no is.gd middle hop.
- QR image was decoded with both ZBar and OpenCV offline; also after compositing at 256 px onto a factory image.
- QR pixels are drawn without bilinear interpolation; the 5-module quiet zone remains.
- The app has a "Test QR link" button that opens the direct URL in the device browser.
- Photo editing still works offline; the website requires internet access.
- Since builds are debug-signed with per-run keys, uninstall the previous 1.0 APK before installing 1.1.