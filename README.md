# Telegram Direct (Android)
Native Java Android interface with a Python / Telethon MTProto bridge, targeting Android 8+ (arm64).

Version 0.2.0-proxy uses the separate package `dev.telegram.direct.proxy` so the prior debug app and its local data are kept intact when installing this test APK.

Features:
- Telegram login using your own API ID, API Hash, phone verification code and optional 2-step password.
- Proxy settings: Off/Direct, SOCKS5 (optional username/password), MTProto (secret), and importing `tg://proxy`, `tg://socks`, or corresponding `https://t.me/...` links.
- Tap **PROXY SETTINGS**. Use **TEST CONNECTION** to verify SOCKS5 or MTProto against Telegram without requesting a login code, changing the active session or sending a message. Enter API ID and API Hash on the main screen first.
- Tap **APPLY** to use the settings. If already connected, the Telegram client reconnects using the proxy; otherwise settings are used on the next **CONNECT**. A failed proxy connection does not overwrite the saved settings.
- Proxy testing requires an actual working proxy server. An offline or invalid proxy may return an error; no direct-connection fallback is attempted.
- Proxy settings including credentials are encrypted locally using Android Keystore; no credentials are sent to GitHub. This config applies to this Telegram app only, not system-wide traffic.
- List conversations, joined groups, contacts and search public Telegram.
- Send a test message to your own Saved Messages.
- Send a single message to one manually selected joined group only after confirming the destination and contents.
- No automatic joins, scraping of phone numbers, mass messaging, or unattended sending.
- API credentials are entered on the device and are not committed to GitHub; the session is stored in private app data (Android backup disabled).

Offline proxy validation: `python -m unittest discover -s tests -v`.

Build with GitHub Actions (recommended): Actions > Build Android debug APK > Run workflow.
On Windows: JDK 17, Android SDK 35, Python 3.13, Gradle 8.9 and access to Google Maven are required.
Run `gradle :app:assembleDebug`. Download the debug APK from the Actions artifact or find it under `app/build/outputs/apk/debug`.

Warning: APK compilation and actual account login must be tested separately; passing source syntax checks does not prove the Telegram service is working on-device.
