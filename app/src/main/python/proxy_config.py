"""Proxy validation and Telethon transport configuration, without network I/O."""
import json
import re

_TYPES = {"direct", "socks5", "mtproto"}
_HEX_SECRET = re.compile(r"(?:[0-9a-fA-F]{32}|[dD][dD][0-9a-fA-F]{32}|[eE][eE][0-9a-fA-F]{32}(?:[0-9a-fA-F]{2})*)\Z")


def parse_proxy(raw):
    """Accept only a supported proxy type and its required settings."""
    try:
        data = json.loads(raw) if isinstance(raw, str) else raw
    except (TypeError, ValueError) as exc:
        raise ValueError("Invalid proxy settings.") from exc
    if not isinstance(data, dict):
        raise ValueError("Invalid proxy settings.")
    proxy_type = data.get("type", "direct")
    if proxy_type not in _TYPES:
        raise ValueError("Select Direct, SOCKS5 or MTProto.")
    if proxy_type == "direct":
        return {"type": "direct"}

    host = str(data.get("server", "")).strip()
    port_raw = data.get("port", "")
    if not host or len(host) > 253 or any(c.isspace() or ord(c) < 32 or c in "/@?#\\\\%" for c in host):
        raise ValueError("Enter a valid proxy server address.")
    if isinstance(port_raw, bool) or not str(port_raw).isdigit():
        raise ValueError("Proxy port must be a number between 1 and 65535.")
    port = int(port_raw)
    if port < 1 or port > 65535:
        raise ValueError("Proxy port must be between 1 and 65535.")
    result = {"type": proxy_type, "server": host, "port": port}
    if proxy_type == "mtproto":
        secret = str(data.get("secret", "")).strip()
        if len(secret) > 512 or not _HEX_SECRET.fullmatch(secret):
            raise ValueError("Enter a valid MTProto secret (hexadecimal).")
        result["secret"] = secret
    else:
        username = str(data.get("username", ""))
        password = str(data.get("password", ""))
        if len(username) > 512 or len(password) > 512:
            raise ValueError("SOCKS5 credentials are too long.")
        result["username"] = username
        result["password"] = password
    return result


def telethon_options(config, mtproxy_connection):
    if config["type"] == "direct":
        return {}
    if config["type"] == "socks5":
        return {"proxy": {
            "proxy_type": "socks5", "addr": config["server"],
            "port": config["port"], "rdns": True,
            "username": config["username"] or None,
            "password": config["password"] or None
        }}
    return {
        "connection": mtproxy_connection,
        "proxy": (config["server"], config["port"], config["secret"])
    }


def proxy_label(config):
    if config["type"] == "direct":
        return "Direct connection"
    return ("SOCKS5" if config["type"] == "socks5" else "MTProto") + " via " + config["server"] + ":" + str(config["port"])
