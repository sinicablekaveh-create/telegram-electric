# Telegram Direct (Android)
Native Java Android interface with a Python / Telethon MTProto bridge, targeting Android 8+ (arm64).

Features:
- Telegram login using your own API ID, API Hash, phone verification code and optional 2-step password.
- List conversations, joined groups, contacts and search public Telegram.
- Send a test message to your own Saved Messages.
- Send a single message to one manually selected joined group only after confirming the destination and contents.
- No automatic joins, scraping of phone numbers, mass messaging, or unattended sending.
- API credentials are entered on the device and are not committed to GitHub; the session is stored in private app data (Android backup disabled).

Build with GitHub Actions (recommended): Actions > Build Android debug APK > Run workflow.
On Windows: JDK 17, Android SDK 35, Python 3.13, Gradle 8.9 and access to Google Maven are required.
Run `gradle :app:assembleDebug`. Download the debug APK from the Actions artifact or find it under `app/build/outputs/apk/debug`.

Warning: APK compilation and actual account login must be tested separately; passing source syntax checks does not prove the Telegram service is working on-device.
