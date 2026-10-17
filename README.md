# Telegram Electric Review (Android)

Android (Java + Chaquopy Python / Telethon 1.45) client. This is an experimental
source branch for version `0.3.0-review`, Android package
`dev.telegram.direct.review` (Android 8+, arm64). The separate app ID protects
the installed `dev.telegram.direct.proxy` and its local login data.

## Features

- Sign in with the account owner's Telegram API ID / API hash, phone code,
  and optional two-step verification. Credentials are entered on the phone.
- Direct, SOCKS5 and MTProto proxy settings, with local encrypted storage.
- Real Telegram API public search: public groups, channels and users.
  Search results do **not** trigger joins or sends.
- Filter *joined* Telegram groups and the logged-in account's Telegram
  contacts by name or username. Contact phone numbers are never displayed
  or exported. Results are based on the account's visibility and API limits;
  they are not a directory of all Telegram accounts.
- Select one joined group and compose a message; either send it after a
  confirmation, or add it to a private on-device **manual review queue**.
- The review queue is scoped to the signed-in Telegram account, persisted in
  private app data, and supports pending / uncertain / sent / cancelled states.
  Each item needs its own explicit confirmation before sending. No batch send,
  background sender, scheduling, scraping, mass invitations or auto-joins.
- An uncertain send is never retried automatically. Check the Telegram group
  manually and resolve the entry to avoid duplicates.

## Development and testing

Offline: `python -m py_compile app/src/main/python/*.py` (or named files)
and `python -m unittest discover -s tests -v`. These tests use no Telegram
account and send no network messages.

Build: GitHub Actions > **Build Android debug APK**, or use JDK 17,
Android SDK 35, Python 3.13, Gradle 8.9 locally and run
`gradle :app:lintDebug :app:assembleDebug`.

The debug APK must be verified separately on the A17 device, using the new
application ID. A compiled APK and offline tests do not prove that a live
Telegram search or send succeeded. Live testing should use the account owner's
permission and a joined test group where the sender is authorized to post.

**Privacy:** Do not commit API hashes, phone numbers, login codes, exported
contacts, session files, queue JSON files or screenshots containing private data.
