"""Telegram account connection for the Android UI (manual actions only)."""
import asyncio
import json
from pathlib import Path
from telethon import TelegramClient
from telethon.errors import FloodWaitError, SessionPasswordNeededError
from telethon.tl.functions.contacts import SearchRequest

_client = None
_loop = None
_phone = ""
_phone_hash = ""

def answer(status, message, items=None):
    out = {"status": status, "message": message}
    if items is not None:
        out["items"] = items
    return out

def event_loop():
    global _loop
    if _loop is None or _loop.is_closed():
        _loop = asyncio.new_event_loop()
    asyncio.set_event_loop(_loop)
    return _loop

async def connect(storage, api_id, api_hash, phone):
    global _client, _phone, _phone_hash
    if not api_id.isdigit() or len(api_hash) != 32:
        return answer("error", "Check API ID and API Hash.")
    if _client is not None:
        await _client.disconnect()
    directory = Path(storage)
    directory.mkdir(parents=True, exist_ok=True)
    _client = TelegramClient(str(directory / "telegram_session"),
                             int(api_id), api_hash, receive_updates=False,
                             flood_sleep_threshold=0)
    await _client.connect()
    if await _client.is_user_authorized():
        return answer("authorized", "Telegram is connected.")
    if not phone.strip():
        return answer("error", "Enter phone number with country code.")
    _phone = phone.strip()
    sent = await _client.send_code_request(_phone)
    _phone_hash = sent.phone_code_hash
    return answer("code_sent", "Enter verification code from Telegram.")

async def verify_code(code):
    if _client is None or not _phone_hash:
        return answer("error", "Request a login code first.")
    try:
        await _client.sign_in(phone=_phone, code=code, phone_code_hash=_phone_hash)
    except SessionPasswordNeededError:
        return answer("password_required", "Enter Telegram two-step password.")
    return answer("authorized", "Signed in.")

async def verify_password(password):
    if _client is None:
        return answer("error", "Connect first.")
    await _client.sign_in(password=password)
    return answer("authorized", "Signed in.")

async def require_auth():
    if _client is None or not await _client.is_user_authorized():
        raise ValueError("Sign in to Telegram first.")

async def dialogs():
    await require_auth()
    rows = []
    async for dialog in _client.iter_dialogs(limit=200):
        group = bool(dialog.is_group)
        rows.append({
            "id": str(dialog.id), "title": dialog.name or "(untitled)",
            "kind": "Group" if group else ("Channel" if dialog.is_channel else "Chat"),
            "username": getattr(dialog.entity, "username", None) or "",
            "selectable": group
        })
    return answer("ok", "Joined conversations", rows)

async def contacts():
    await require_auth()
    rows = []
    users = await _client.get_contacts()
    for user in users[:100]:
        name = " ".join(p for p in [user.first_name, user.last_name] if p)
        rows.append({"id": str(user.id), "title": name, "kind": "Contact",
                     "username": user.username or "", "selectable": False})
    return answer("ok", "Telegram contacts", rows)

async def public_search(term):
    await require_auth()
    if len(term.strip()) < 2:
        return answer("error", "Enter at least two characters.")
    result = await _client(SearchRequest(q=term.strip(), limit=30))
    rows = [{"id": str(chat.id), "title": chat.title,
             "kind": "Public group/channel",
             "username": getattr(chat, "username", None) or "",
             "selectable": False} for chat in result.chats]
    rows.extend({"id": str(user.id), "title": user.first_name or "User",
                 "kind": "User", "username": user.username or "",
                 "selectable": False} for user in result.users)
    return answer("ok", "Public search results", rows)

async def saved(message):
    await require_auth()
    if not message.strip():
        return answer("error", "Enter a message.")
    await _client.send_message("me", message)
    return answer("sent", "Sent to Saved Messages.")

async def send_group(group_id, message):
    await require_auth()
    if not message.strip():
        return answer("error", "Enter a message.")
    async for dialog in _client.iter_dialogs(limit=200):
        if str(dialog.id) == str(group_id) and dialog.is_group:
            await _client.send_message(dialog.entity, message)
            return answer("sent", "Sent one message to " + dialog.name)
    return answer("error", "This joined group was not found.")

async def logout():
    global _client, _phone, _phone_hash
    if _client is not None:
        await _client.log_out()
    _client = None
    _phone, _phone_hash = "", ""
    return answer("logged_out", "Logged out of this app.")

def dispatch(operation, *args):
    handlers = {
        "connect": connect, "code": verify_code, "password": verify_password,
        "dialogs": dialogs, "contacts": contacts, "search": public_search,
        "saved": saved, "send_group": send_group, "logout": logout
    }
    handler = handlers.get(operation)
    if handler is None:
        return json.dumps(answer("error", "Unknown operation."))
    try:
        result = event_loop().run_until_complete(handler(*args))
    except FloodWaitError as exc:
        result = answer("error", "Telegram rate limit. Wait %s seconds." % exc.seconds)
    except Exception as exc:
        # Never include API Hash, login code, phone or password in UI or logs.
        result = answer("error", "Operation failed: " + type(exc).__name__)
    return json.dumps(result, ensure_ascii=False)
