"""Offline validation tests: never contact Telegram or a proxy."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from proxy_config import parse_proxy, telethon_options, proxy_label


class ProxyConfigTests(unittest.TestCase):
    def test_direct(self):
        self.assertEqual(parse_proxy('{"type":"direct","server":"ignored"}'), {"type": "direct"})
        self.assertEqual(telethon_options({"type": "direct"}, object()), {})

    def test_socks5(self):
        cfg = parse_proxy('{"type":"socks5","server":"proxy.example.org","port":"1080","username":"me","password":"secret"}')
        opt = telethon_options(cfg, object())
        self.assertEqual(opt["proxy"]["proxy_type"], "socks5")
        self.assertEqual(opt["proxy"]["port"], 1080)
        self.assertTrue(opt["proxy"]["rdns"])
        self.assertNotIn("secret", proxy_label(cfg))

    def test_mtproto(self):
        cfg = parse_proxy('{"type":"mtproto","server":"proxy.example.org","port":443,"secret":"dd' + 'a'*32 + '"}')
        conn = object()
        self.assertEqual(telethon_options(cfg, conn)["connection"], conn)
        self.assertEqual(telethon_options(cfg, conn)["proxy"][2], 'dd' + 'a'*32)

    def test_mtproto_fake_tls(self):
        cfg = parse_proxy({"type":"mtproto","server":"p.example.org","port":443,"secret":"ee" + "a"*32 + "646f6d61696e"})
        self.assertEqual(cfg["type"], "mtproto")

    def test_reject_invalid(self):
        invalid = [
            '{} extra', '[]', '{"type":"http"}',
            '{"type":"socks5","server":"","port":123}',
            '{"type":"socks5","server":"foo","port":0}',
            '{"type":"socks5","server":"foo","port":65536}',
            '{"type":"mtproto","server":"foo","port":443,"secret":"invalid"}',
            '{"type":"socks5","server":"bad host","port":1080}',
        ]
        for sample in invalid:
            with self.subTest(sample=sample), self.assertRaises(ValueError):
                parse_proxy(sample)


if __name__ == "__main__":
    unittest.main()
