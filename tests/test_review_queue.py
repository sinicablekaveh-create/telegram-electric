"""Offline queue safety checks; no Telegram connection or messages."""
import tempfile
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
from review_queue import ReviewQueue


class ReviewQueueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.queue = ReviewQueue(self.temp.name, 123456)

    def test_add_and_persist_in_private_account_file(self):
        entry = self.queue.add("-10055", "Test group", "Hello")
        again = ReviewQueue(self.temp.name, 123456)
        self.assertEqual(again.get(entry["id"])["state"], "pending")
        self.assertEqual(again.read()[0]["text"], "Hello")
        self.assertEqual(ReviewQueue(self.temp.name, 9999).read(), [])

    def test_duplicate_pending_and_uncertain_are_blocked(self):
        entry = self.queue.add("-10055", "Test", "Hello")
        with self.assertRaises(ValueError):
            self.queue.add("-10055", "Test", "Hello")
        self.queue.transition(entry["id"], "pending", "uncertain")
        with self.assertRaises(ValueError):
            self.queue.add("-10055", "Test", "Hello")

    def test_unknown_must_be_resolved_before_retry(self):
        entry = self.queue.add("-10055", "Test", "Hello")
        self.queue.transition(entry["id"], "pending", "uncertain")
        with self.assertRaises(ValueError):
            self.queue.transition(entry["id"], "pending", "sent")
        self.assertEqual(self.queue.get(entry["id"])["state"], "uncertain")
        self.queue.transition(entry["id"], "uncertain", "cancelled")
        self.assertEqual(self.queue.get(entry["id"])["state"], "cancelled")

    def test_cancel_pending_and_clear_finished_only(self):
        first = self.queue.add("-10055", "Test", "first")
        second = self.queue.add("-10055", "Test", "second")
        third = self.queue.add("-10055", "Test", "third")
        self.queue.transition(first["id"], "pending", "cancelled")
        self.queue.transition(second["id"], "pending", "uncertain")
        self.assertEqual(self.queue.clear_finished(), 1)
        self.assertEqual({e["id"] for e in self.queue.read()}, {second["id"], third["id"]})

    def test_corrupt_file_is_not_overwritten(self):
        self.queue.path.write_text("{broken", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.queue.add("-10055", "Test", "Hello")
        self.assertEqual(self.queue.path.read_text(encoding="utf-8"), "{broken")

    def test_invalid_content_or_account(self):
        with self.assertRaises(ValueError):
            ReviewQueue(self.temp.name, "../unsafe")
        with self.assertRaises(ValueError):
            self.queue.add("-10055", "Test", " ")
        with self.assertRaises(ValueError):
            self.queue.add("-10055", "Test", "x" * 4097)


if __name__ == "__main__":
    unittest.main()
