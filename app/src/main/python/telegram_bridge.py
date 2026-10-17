"""Telegram account connection for the Android UI (manual actions only)."""
import asyncio
import json
from pathlib import Path
from telethon import TelegramClient, connection
from proxy_config import parse_proxy, telethon_options, proxy_label
from telethon.errors import FloodWaitError, SessionPasswordNeededError
from telethon.tl.functions.contacts import SearchRequest, GetContactsRequest

_client = None
_loop = None
_phone = ""
_phone_hash = ""
_session_directory = None
_api_id = None
_api_hash = None
_proxy_settings = {"type": "direct"}

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

def make_client(proxy_settings):
    options = telethon_options(proxy_settings, connection.ConnectionTcpMTProxyRandomizedIntermediate)
    return TelegramClient(str(Path(_session_directory) / "telegram_session"),
                          _api_id, _api_hash, receive_updates=False,
                          flood_sleep_threshold=0, connection_retries=1,
                          retry_delay=0, timeout=12, **options)


async def configure_proxy(settings_json):
    """Apply a new transport to an existing Telegram session; roll back on failure."""
    global _client, _proxy_settings
    settings = parse_proxy(settings_json)
    if _client is None or _session_directory is None:
        _proxy_settings = settings
        return answer("proxy_saved", "Proxy ready. Connect Telegram to use it.")
    if settings == _proxy_settings and _client.is_connected():
        return answer("proxy_applied", "Already connected: " + proxy_label(settings))
    old_settings = _proxy_settings
    await _client.disconnect()
    candidate = make_client(settings)
    try:
        await asyncio.wait_for(candidate.connect(), timeout=20)
        # Exercise Telegram's real MTProto connection, not just the TCP port.
        await asyncio.wait_for(candidate.is_user_authorized(), timeout=20)
    except Exception:
        try:
            await candidate.disconnect()
        except Exception:
            pass
        _client = make_client(old_settings)
        try:
            await asyncio.wait_for(_client.connect(), timeout=20)
        except Exception:
            _client = None
        return answer("error", "Proxy connection failed; previous settings kept. Reconnect if needed.")
    _client = candidate
    _proxy_settings = settings
    return answer("proxy_applied", "Telegram connected: " + proxy_label(settings))


async def connect(storage, api_id, api_hash, phone, proxy_json='{"type":"direct"}'):
    global _client, _phone, _phone_hash, _session_directory, _api_id, _api_hash, _proxy_settings
    if not api_id.isdigit() or len(api_hash) != 32:
        return answer("error", "Check API ID and API Hash.")
    settings = parse_proxy(proxy_json)
    if _client is not None:
        await _client.disconnect()
    directory = Path(storage)
    directory.mkdir(parents=True, exist_ok=True)
    _session_directory = str(directory)
    _api_id, _api_hash = int(api_id), api_hash
    _proxy_settings = settings
    _phone_hash = ""
    _client = make_client(settings)
    await asyncio.wait_for(_client.connect(), timeout=20)
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
    contact_result = await _client(GetContactsRequest(hash=0))
    for user in contact_result.users[:100]:
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
        "saved": saved, "send_group": send_group, "logout": logout,
        "proxy": configure_proxy
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
