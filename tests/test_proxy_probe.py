"""Offline transport tests. Never contact Telegram or an external proxy."""
import asyncio
import importlib.util
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

PYTHON_SRC = Path(__file__).resolve().parents[1] / "app/src/main/python"
sys.path.insert(0, str(PYTHON_SRC))


def load_bridge(fail=False):
    seen = []
    class MemorySession:
        pass

    class FakeClient:
        def __init__(self, session, api_id, api_hash, **kwargs):
            self.session = session
            self.kwargs = kwargs
            self.connected = False
            self.disconnected = False
            seen.append(self)

        async def connect(self):
            if fail:
                raise TimeoutError("Fake network refused")
            self.connected = True

        async def disconnect(self):
            self.disconnected = True

        async def is_user_authorized(self):
            raise AssertionError("Probe must not ask for authorization")

        async def send_message(self, *args):
            raise AssertionError("Probe must not send messages")

    client_mod = types.ModuleType("telethon")
    client_mod.TelegramClient = FakeClient
    client_mod.connection = types.SimpleNamespace(
        ConnectionTcpMTProxyRandomizedIntermediate=type("MTProxyTransport", (), {}))
    sessions_mod = types.ModuleType("telethon.sessions")
    sessions_mod.MemorySession = MemorySession
    errors_mod = types.ModuleType("telethon.errors")
    errors_mod.FloodWaitError = type("FloodWaitError", (Exception,), {})
    errors_mod.SessionPasswordNeededError = type("SessionPasswordNeededError", (Exception,), {})
    contacts_mod = types.ModuleType("telethon.tl.functions.contacts")
    contacts_mod.SearchRequest = type("SearchRequest", (), {})
    contacts_mod.GetContactsRequest = type("GetContactsRequest", (), {})
    stubs = {
        "telethon": client_mod,
        "telethon.sessions": sessions_mod,
        "telethon.errors": errors_mod,
        "telethon.tl": types.ModuleType("telethon.tl"),
        "telethon.tl.functions": types.ModuleType("telethon.tl.functions"),
        "telethon.tl.functions.contacts": contacts_mod,
    }
    with patch.dict(sys.modules, stubs):
        spec = importlib.util.spec_from_file_location("bridge_under_test", PYTHON_SRC / "telegram_bridge.py")
        bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bridge)
    return bridge, seen, MemorySession


class ProxyProbeTests(unittest.TestCase):
    def test_socks5_probe_uses_memory_session_and_does_not_login(self):
        bridge, seen, MemorySession = load_bridge()
        settings = json.dumps({"type": "socks5", "server": "127.0.0.1", "port": 1080,
                               "username": "test", "password": "private"})
        result = asyncio.run(bridge.test_proxy_connection("12345", "a" * 32, settings))
        self.assertEqual(result["status"], "proxy_test_ok")
        self.assertEqual(len(seen), 1)
        self.assertIsInstance(seen[0].session, MemorySession)
        self.assertTrue(seen[0].disconnected)
        self.assertEqual(seen[0].kwargs["proxy"]["proxy_type"], "socks5")
        self.assertNotIn("private", json.dumps(result))

    def test_mtproto_probe_uses_mtproto_transport(self):
        bridge, seen, _ = load_bridge()
        secret = "dd" + "a" * 32
        config = json.dumps({"type": "mtproto", "server": "proxy.example.org",
                             "port": 443, "secret": secret})
        result = asyncio.run(bridge.test_proxy_connection("12345", "a" * 32, config))
        self.assertEqual(result["status"], "proxy_test_ok")
        self.assertEqual(seen[0].kwargs["proxy"], ("proxy.example.org", 443, secret))
        self.assertTrue(seen[0].disconnected)

    def test_probe_rejects_missing_api_credentials(self):
        bridge, seen, _ = load_bridge()
        config = json.dumps({"type": "socks5", "server": "proxy.example.org", "port": 1080})
        result = asyncio.run(bridge.test_proxy_connection("", "", config))
        self.assertEqual(result["status"], "error")
        self.assertEqual(seen, [])

    def test_failed_probe_does_not_mutate_active_connection(self):
        bridge, seen, _ = load_bridge(fail=True)
        active = object()
        bridge._client = active
        config = json.dumps({"type": "socks5", "server": "proxy.example.org", "port": 1080})
        response = json.loads(bridge.dispatch("proxy_test", "12345", "a" * 32, config))
        self.assertEqual(response["status"], "error")
        self.assertIs(bridge._client, active)
        self.assertTrue(seen[0].disconnected)


if __name__ == "__main__":
    unittest.main()
