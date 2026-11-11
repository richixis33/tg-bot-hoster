"""
Configuration for Telegram Business Bot.
"""
import os
from pathlib import Path
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
ENV_PATH = BASE_DIR / ".env"

if ENV_PATH.exists():
    load_dotenv(dotenv_path=ENV_PATH)


class Config:
    @property
    def bot_token(self) -> str:
        return os.getenv("BOT_TOKEN", "").strip()

    @property
    def tonapi_key(self) -> str:
        return os.getenv("TONAPI_KEY", "").strip()

    @property
    def command_prefixes(self) -> list:
        return ["!", ".", "/"]


config = Config()
