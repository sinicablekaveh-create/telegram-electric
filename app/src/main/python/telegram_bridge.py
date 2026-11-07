"""Telegram account connection for the Android UI (manual actions only)."""
import asyncio
import json
from pathlib import Path
from review_queue import ReviewQueue
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

async def joined_group(group_id):
    """Resolve only groups the signed-in account has actually joined."""
    async for dialog in _client.iter_dialogs(limit=500):
        if dialog.is_group and str(dialog.id) == str(group_id):
            return dialog
    return None


async def dialogs(term=""):
    await require_auth()
    term = term.strip().casefold()
    rows = []
    async for dialog in _client.iter_dialogs(limit=500):
        if not dialog.is_group:
            continue
        name = dialog.name or "(untitled)"
        username = getattr(dialog.entity, "username", None) or ""
        if term and term not in name.casefold() and term not in username.casefold():
            continue
        rows.append({
            "id": str(dialog.id), "title": name, "kind": "Joined group",
            "username": username, "selectable": True
        })
    return answer("ok", "Joined groups (up to 500 dialogs checked)", rows)


async def contacts(term=""):
    await require_auth()
    term = term.strip().casefold()
    result = await _client(GetContactsRequest(hash=0))
    rows = []
    for user in result.users:
        name = " ".join(part for part in (user.first_name, user.last_name) if part)
        username = user.username or ""
        if term and term not in name.casefold() and term not in username.casefold():
            continue
        rows.append({
            "id": str(user.id), "title": name or username or "Unnamed",
            "kind": "Telegram contact", "username": username,
            "selectable": False
        })
        if len(rows) >= 300:
            break
    return answer("ok", "Telegram contacts (up to 300 shown; no phone numbers)", rows)


async def public_search(term):
    await require_auth()
    term = term.strip()
    if len(term) < 2 or len(term) > 100:
        return answer("error", "Enter 2 to 100 characters.")
    result = await _client(SearchRequest(q=term, limit=50))
    rows = []
    seen = set()
    for chat in result.chats:
        username = getattr(chat, "username", None) or ""
        title = getattr(chat, "title", None) or "(untitled)"
        kind = "Public group" if (
            getattr(chat, "megagroup", False)
            or getattr(chat, "gigagroup", False)
            or chat.__class__.__name__ == "Chat"
        ) else "Public channel"
        key = ("chat", str(chat.id))
        if key not in seen:
            rows.append({"id": str(chat.id), "title": title, "kind": kind,
                         "username": username, "selectable": False})
            seen.add(key)
    for user in result.users:
        key = ("user", str(user.id))
        if key in seen:
            continue
        name = " ".join(part for part in (user.first_name, user.last_name) if part)
        rows.append({"id": str(user.id), "title": name or user.username or "User",
                     "kind": "Public user", "username": user.username or "",
                     "selectable": False})
        seen.add(key)
    return answer("ok", "Live Telegram public search (not joined automatically)", rows)


async def account_queue():
    await require_auth()
    account = await _client.get_me()
    if account is None or _session_directory is None:
        raise ValueError("Connect Telegram before opening the review queue.")
    return ReviewQueue(_session_directory, account.id)


async def queue_add(group_id, message):
    await require_auth()
    dialog = await joined_group(group_id)
    if dialog is None:
        return answer("error", "Select a joined group before queuing.")
    store = await account_queue()
    store.add(str(dialog.id), dialog.name or "(untitled)", message)
    return answer("queued", "Added one group message to the manual review queue.")


async def queue_list():
    store = await account_queue()
    rows = store.read()
    return answer("ok", "Review queue: %d entries" % len(rows), rows)


async def queue_send(item_id):
    store = await account_queue()
    item = store.get(item_id)
    if item["state"] != "pending":
        return answer("error", "This entry is not pending. Review its current state.")
    dialog = await joined_group(item["group_id"])
    if dialog is None:
        return answer("error", "The account is no longer in this group; no send was attempted.")
    # A crash or timeout after this write must NEVER cause an automatic resend.
    store.transition(item_id, "pending", "uncertain")
    try:
        await _client.send_message(dialog.entity, item["text"])
    except Exception:
        # Leave the entry uncertain; the user must check Telegram for delivery.
        return answer("uncertain", "Delivery unknown. Check the group before resolving; do not retry blindly.")
    store.transition(item_id, "uncertain", "sent")
    return answer("sent", "Telegram confirmed delivery of the reviewed message.")


async def queue_cancel(item_id):
    store = await account_queue()
    store.transition(item_id, "pending", "cancelled")
    return answer("ok", "Pending message cancelled. Nothing was sent.")


async def queue_resolve(item_id, action):
    if action not in ("sent", "cancelled"):
        return answer("error", "Invalid resolution.")
    store = await account_queue()
    store.transition(item_id, "uncertain", action)
    return answer("ok", "Uncertain entry resolved manually; no message was sent.")


async def queue_clear():
    store = await account_queue()
    count = store.clear_finished()
    return answer("ok", "Removed %d completed or cancelled entries." % count)


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
        "queue_add": queue_add, "queue_list": queue_list,
        "queue_send": queue_send, "queue_cancel": queue_cancel,
        "queue_resolve": queue_resolve, "queue_clear": queue_clear,
        "proxy": configure_proxy
    }
    handler = handlers.get(operation)
    if handler is None:
        return json.dumps(answer("error", "Unknown operation."))
    try:
        result = event_loop().run_until_complete(handler(*args))
    except ValueError as exc:
        result = answer("error", str(exc))
    except FloodWaitError as exc:
        result = answer("error", "Telegram rate limit. Wait %s seconds." % exc.seconds)
    except Exception as exc:
        # Never include API Hash, login code, phone or password in UI or logs.
        result = answer("error", "Operation failed: " + type(exc).__name__)
    return json.dumps(result, ensure_ascii=False)
