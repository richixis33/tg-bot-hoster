"""
Main Entrypoint for Telegram Business Crypto Bot.
Built with aiogram 3.
Supports official Telegram Business Chatbots and direct interaction.
"""
import asyncio
import os
import sys
from aiogram import Bot, Dispatcher
from aiogram.fsm.storage.memory import MemoryStorage
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode

from config import config, BASE_DIR
from ton_service import ton_service
from crypto_service import crypto_service
from handlers.business import router as business_router
from handlers.private import router as private_router


async def setup_token_if_missing():
    token = config.bot_token
    if not token:
        print("\n" + "=" * 60)
        print("  🔑 TELEGRAM BOT_TOKEN NOT CONFIGURED!")
        print("  1. Create a bot in Telegram via @BotFather (https://t.me/BotFather)")
        print("  2. In @BotFather: /mybots -> [Your Bot] -> Bot Settings -> Telegram Business -> Turn On")
        print("=" * 60 + "\n")
        try:
            input_token = input("  Enter your BOT_TOKEN from @BotFather: ").strip()
            if not input_token:
                print("Token cannot be empty.")
                sys.exit(1)

            env_path = BASE_DIR / ".env"
            with open(env_path, "w", encoding="utf-8") as f:
                f.write(f"BOT_TOKEN={input_token}\n")
            os.environ["BOT_TOKEN"] = input_token
            print("\n  ✅ Saved BOT_TOKEN to .env file!\n")
        except (KeyboardInterrupt, EOFError):
            print("\nCancelled.")
            sys.exit(1)


async def main():
    await setup_token_if_missing()
    token = config.bot_token
    if not token:
        print("[ERROR] BOT_TOKEN must be provided in .env!")
        sys.exit(1)

    bot = Bot(
        token=token,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML)
    )
    dp = Dispatcher(storage=MemoryStorage())

    # Include routers
    dp.include_router(private_router)
    dp.include_router(business_router)

    me = await bot.get_me()
    print(f"[ready] @{me.username} ({me.first_name}) | mode: business & private")
    print(f"[setup] telegram settings -> telegram business -> chatbots -> @{me.username}")

    # Critical: explicitly request business updates from Telegram
    allowed_updates = [
        "message",
        "edited_message",
        "callback_query",
        "business_connection",
        "business_message",
        "edited_business_message",
        "deleted_business_messages"
    ]

    try:
        await bot.delete_webhook(drop_pending_updates=True)
        await dp.start_polling(bot, allowed_updates=allowed_updates)
    finally:
        await crypto_service.close()
        await ton_service.close()
        await bot.session.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except (KeyboardInterrupt, SystemExit):
        print("\n[INFO] Bot stopped.")
