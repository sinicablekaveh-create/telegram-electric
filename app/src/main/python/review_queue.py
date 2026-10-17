"""Private, account-scoped, manually confirmed Telegram message review queue.

No scheduler or automatic sender. Persist an uncertain state *before* network sends,
so a crash cannot silently repeat a possibly delivered message.
"""
import json
import os
from pathlib import Path
from uuid import uuid4

VALID_STATES = {"pending", "uncertain", "sent", "cancelled"}
MAX_ITEMS = 300
MAX_TEXT = 4096


class ReviewQueue:
    def __init__(self, directory, user_id):
        if not str(user_id).isdigit():
            raise ValueError("Invalid Telegram account identifier.")
        self.path = Path(directory) / ("review_queue_" + str(user_id) + ".json")
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def read(self):
        if not self.path.exists():
            return []
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (ValueError, OSError) as exc:
            raise ValueError("Queue storage is unreadable; nothing was overwritten.") from exc
        if not isinstance(data, list) or len(data) > MAX_ITEMS:
            raise ValueError("Queue storage format is invalid.")
        for entry in data:
            if (not isinstance(entry, dict)
                    or entry.get("state") not in VALID_STATES
                    or not isinstance(entry.get("id"), str)
                    or not isinstance(entry.get("group_id"), str)
                    or not isinstance(entry.get("text"), str)):
                raise ValueError("Queue storage entry is invalid.")
        return data

    def _write(self, entries):
        temporary = self.path.with_suffix(".tmp")
        try:
            with temporary.open("w", encoding="utf-8") as handle:
                json.dump(entries, handle, ensure_ascii=False, indent=2)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, self.path)
        finally:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass

    def add(self, group_id, group_title, text):
        text = text.strip()
        if not group_id or not text or len(text) > MAX_TEXT:
            raise ValueError("Select a joined group and a message up to 4096 characters.")
        entries = self.read()
        if len(entries) >= MAX_ITEMS:
            raise ValueError("Queue is full (300 entries). Clear cancelled or sent items.")
        if any(item["group_id"] == str(group_id)
               and item["text"] == text
               and item["state"] in ("pending", "uncertain")
               for item in entries):
            raise ValueError("This message is already pending or uncertain for the group.")
        item = {
            "id": uuid4().hex, "group_id": str(group_id),
            "group": str(group_title)[:160], "text": text, "state": "pending"
        }
        entries.append(item)
        self._write(entries)
        return item

    def get(self, item_id):
        for item in self.read():
            if item["id"] == item_id:
                return item
        raise ValueError("Queue entry was not found.")

    def transition(self, item_id, from_state, to_state):
        if to_state not in VALID_STATES:
            raise ValueError("Invalid queue state.")
        entries = self.read()
        for entry in entries:
            if entry["id"] == item_id:
                if entry["state"] != from_state:
                    raise ValueError("Queue entry has changed; refresh before proceeding.")
                entry["state"] = to_state
                self._write(entries)
                return entry
        raise ValueError("Queue entry was not found.")

    def clear_finished(self):
        entries = self.read()
        remaining = [x for x in entries if x["state"] not in ("sent", "cancelled")]
        self._write(remaining)
        return len(entries) - len(remaining)
