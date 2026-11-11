"""
Lightweight JSON Storage for Telegram Business Bot.
Stores user settings, default wallets, and business connection states.
"""
import json
import os
from pathlib import Path
from typing import Dict, Any, Optional

DB_FILE = Path(__file__).resolve().parent / "data.json"


class Storage:
    def __init__(self):
        self._data: Dict[str, Any] = {
            "global_default_wallet": "UQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqEBI",
            "user_wallets": {},
            "connections": {}
        }
        self.load()

    def load(self):
        if DB_FILE.exists():
            try:
                with open(DB_FILE, "r", encoding="utf-8") as f:
                    self._data = json.load(f)
            except Exception:
                pass
        else:
            self.save()

    def save(self):
        with open(DB_FILE, "w", encoding="utf-8") as f:
            json.dump(self._data, f, indent=4, ensure_ascii=False)

    def get_wallet(self, user_id: Optional[int] = None) -> str:
        if user_id:
            uid_str = str(user_id)
            if uid_str in self._data.get("user_wallets", {}):
                return self._data["user_wallets"][uid_str]
        return self._data.get("global_default_wallet", "")

    def set_wallet(self, wallet: str, user_id: Optional[int] = None):
        wallet_clean = wallet.strip()
        if user_id:
            uid_str = str(user_id)
            if "user_wallets" not in self._data:
                self._data["user_wallets"] = {}
            self._data["user_wallets"][uid_str] = wallet_clean
        # Also update global if not set
        self._data["global_default_wallet"] = wallet_clean
        self.save()

    def register_connection(self, connection_id: str, user_id: int, can_reply: bool, is_enabled: bool):
        if "connections" not in self._data:
            self._data["connections"] = {}
        self._data["connections"][str(user_id)] = {
            "connection_id": connection_id,
            "can_reply": can_reply,
            "is_enabled": is_enabled
        }
        self.save()

    def get_connection(self, user_id: int) -> Optional[Dict[str, Any]]:
        return self._data.get("connections", {}).get(str(user_id))


storage = Storage()
