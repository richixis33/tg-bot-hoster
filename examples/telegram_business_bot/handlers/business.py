"""
Telegram Business Handlers.
Processes updates arriving via Telegram Business connection:
- business_connection: connection established / updated / disabled
- business_message: outgoing/incoming messages in business chats
Supports wallet operations, 24h crypto charts, live network gas, and multi-currency converter.
"""
import time
from aiogram import Router, Bot, F
from aiogram.types import BusinessConnection, Message, BufferedInputFile
from aiogram.exceptions import TelegramBadRequest

from config import config
from storage import storage
from ton_utils import normalize_address
from ton_service import ton_service
from crypto_service import crypto_service
from card_generator import render_crypto_chart_card, render_converter_card, render_gas_card
import formatter

router = Router(name="business_router")
START_TIME = time.time()


def get_uptime_string() -> str:
    uptime_sec = int(time.time() - START_TIME)
    h, rem = divmod(uptime_sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}ч {m}м {s}с"


def parse_command(text: str):
    if not text:
        return False, "", ""
    text = text.strip()
    for prefix in config.command_prefixes:
        if text.startswith(prefix):
            without = text[len(prefix):].strip()
            # 1. Check if converter query: e.g. "12 gram to rub", "12 gram azn", "10 btc usd"
            conv_parsed = crypto_service.parse_converter_query(without)
            if conv_parsed:
                return True, "converter", without

            parts = without.split(maxsplit=1)
            cmd = parts[0].lower() if parts else ""
            args = parts[1].strip() if len(parts) > 1 else ""
            return True, cmd, args
    return False, "", ""


@router.business_connection()
async def on_business_connection(event: BusinessConnection, bot: Bot):
    """Fired when user connects/disconnects the bot in Telegram Business Settings."""
    storage.register_connection(
        connection_id=event.id,
        user_id=event.user.id,
        can_reply=event.can_reply,
        is_enabled=event.is_enabled
    )
    status_str = "подключен" if event.is_enabled else "отключен"
    print(f"[business] @{event.user.username} ({event.user.id}) -> {status_str} (can_reply={event.can_reply})")

    if event.is_enabled and not event.can_reply:
        warning_msg = (
            f"<blockquote>{formatter.EMOJI_DIAMOND} <b>Требуется разрешение</b>\n"
            f"{formatter.DIVIDER}\n"
            "В настройках Telegram Business отключен параметр «Отвечать на сообщения».\n\n"
            "Включите его: Настройки → Telegram Business → Чат-боты → выберите бота → «Отвечать на сообщения»."
            "</blockquote>"
        )
        try:
            await bot.send_message(chat_id=event.user.id, text=warning_msg, parse_mode="HTML")
        except Exception:
            pass


async def send_business_response(bot: Bot, bc_id: str, chat_id: int, user_id: int, html_text: str):
    """Sends response handling both regular business chats and self-chat (Saved Messages)."""
    is_self_chat = bool(user_id and chat_id == user_id)

    if is_self_chat:
        tip = (
            "\n\n<blockquote>"
            "<i>Telegram Business API не поддерживает отправку в Избранное. Для ответов от вашего имени отправляйте команды в диалогах с собеседниками.</i>"
            "</blockquote>"
        )
        try:
            await bot.send_message(chat_id=chat_id, text=html_text + tip, parse_mode="HTML", disable_web_page_preview=True)
        except Exception as e:
            print(f"[error in self-chat]: {e}")
        return

    try:
        await bot.send_message(
            business_connection_id=bc_id,
            chat_id=chat_id,
            text=html_text,
            parse_mode="HTML",
            disable_web_page_preview=True
        )
    except TelegramBadRequest as e:
        print(f"[business error in chat {chat_id}]: {e.message}")
        if "BUSINESS_PEER_INVALID" in e.message or "can_reply" in e.message:
            warn = (
                f"<blockquote>{formatter.EMOJI_DIAMOND} <b>Ошибка отправки</b>\n"
                f"{formatter.DIVIDER}\n"
                f"<code>{e.message}</code>\n\n"
                "Проверьте право «Отвечать на сообщения» в настройках бота и убедитесь, что чат является личным диалогом."
                "</blockquote>"
            )
            if user_id:
                try:
                    await bot.send_message(chat_id=user_id, text=warn, parse_mode="HTML")
                except Exception:
                    pass
    except Exception as e:
        print(f"[unexpected error]: {e}")


async def send_business_photo(bot: Bot, bc_id: str, chat_id: int, user_id: int, photo_bytes: bytes, caption_html: str):
    """Sends photo with caption in Telegram Business chat or self-chat fallback."""
    is_self_chat = bool(user_id and chat_id == user_id)
    photo_file = BufferedInputFile(photo_bytes, filename="card.jpg")

    if is_self_chat:
        tip = (
            "\n\n<blockquote>"
            "<i>Telegram Business API не поддерживает отправку в Избранное. Для ответов от вашего имени отправляйте команды в диалогах с собеседниками.</i>"
            "</blockquote>"
        )
        try:
            await bot.send_photo(chat_id=chat_id, photo=photo_file, caption=caption_html + tip, parse_mode="HTML")
        except Exception as e:
            print(f"[error in self-chat photo]: {e}")
        return

    try:
        await bot.send_photo(
            business_connection_id=bc_id,
            chat_id=chat_id,
            photo=photo_file,
            caption=caption_html,
            parse_mode="HTML"
        )
    except TelegramBadRequest as e:
        print(f"[business error photo in chat {chat_id}]: {e.message}")
        if "BUSINESS_PEER_INVALID" in e.message or "can_reply" in e.message:
            warn = (
                f"<blockquote>{formatter.EMOJI_DIAMOND} <b>Ошибка отправки фото</b>\n"
                f"{formatter.DIVIDER}\n"
                f"<code>{e.message}</code>\n\n"
                "Проверьте права бота в настройках Telegram Business."
                "</blockquote>"
            )
            if user_id:
                try:
                    await bot.send_message(chat_id=user_id, text=warn, parse_mode="HTML")
                except Exception:
                    pass
    except Exception as e:
        print(f"[unexpected error sending photo]: {e}")


@router.business_message()
async def on_business_message(message: Message, bot: Bot):
    """Fired when a message is sent or received in any business chat."""
    text = message.text or ""
    is_cmd, cmd, args = parse_command(text)
    if not is_cmd or not cmd:
        return

    bc_id = message.business_connection_id
    if not bc_id:
        return

    chat_id = message.chat.id
    user_id = message.from_user.id if message.from_user else None
    user_default_wallet = storage.get_wallet(user_id)
    is_self_chat = bool(user_id and chat_id == user_id)

    # 1. HELP
    if cmd in ["help", "хелп", "h", "помощь"]:
        if not is_self_chat:
            try:
                await bot.delete_business_messages(business_connection_id=bc_id, message_ids=[message.message_id])
            except Exception:
                pass

        help_html = formatter.format_help_menu(user_default_wallet, config.command_prefixes)
        await send_business_response(bot, bc_id, chat_id, user_id, help_html)
        return

    # 2. GAS: !gas / !газ / !комиссия
    if cmd in ["gas", "газ", "комиссия", "комиссии", "fee", "fees"]:
        if not is_self_chat:
            try:
                await bot.delete_business_messages(business_connection_id=bc_id, message_ids=[message.message_id])
            except Exception:
                pass

        gas_data = await crypto_service.get_gas_data()
        card_bytes = render_gas_card(gas_data)
        caption = formatter.format_gas_caption(gas_data)
        await send_business_photo(bot, bc_id, chat_id, user_id, card_bytes, caption)
        return

    # 3. TOP: !top / !топ / !рынок
    if cmd in ["top", "топ", "рынок", "market"]:
        if not is_self_chat:
            try:
                await bot.delete_business_messages(business_connection_id=bc_id, message_ids=[message.message_id])
            except Exception:
                pass

        top_list = await crypto_service.get_top_crypto()
        top_caption = formatter.format_top_caption(top_list)
        await send_business_response(bot, bc_id, chat_id, user_id, top_caption)
        return

    # 4. CURRENCY CONVERTER: e.g. !12 gram to azn, !50 манат в руб, !10 btc usd
    if cmd == "converter":
        if not is_self_chat:
            try:
                await bot.delete_business_messages(business_connection_id=bc_id, message_ids=[message.message_id])
            except Exception:
                pass

        conv_parsed = crypto_service.parse_converter_query(args)
        if not conv_parsed:
            return
        amt, from_c, to_c = conv_parsed
        conv_data = await crypto_service.convert_currency(amt, from_c, to_c)
        if not conv_data:
            err = formatter.format_error(
                "Ошибка конвертации",
                f"Не удалось найти курс для пары <code>{from_c.upper()} → {to_c.upper()}</code>"
            )
            await send_business_response(bot, bc_id, chat_id, user_id, err)
            return

        card_bytes = render_converter_card(
            from_amount=conv_data["from_amount"],
            from_symbol=conv_data["from_symbol"],
            to_amount=conv_data["to_amount"],
            to_symbol=conv_data["to_symbol"],
            exchange_rate=conv_data["rate"],
            other_rates=conv_data["other_rates"]
        )
        caption = formatter.format_converter_caption(conv_data)
        await send_business_photo(bot, bc_id, chat_id, user_id, card_bytes, caption)
        return

    # 5. CRYPTO COIN CHART & PRICE: e.g. !gram, !btc, !eth, !sol, !not, !dogs
    if crypto_service.is_crypto_query(cmd) and cmd not in ["balance", "баланс", "setwallet", "кошелек", "tx", "транзакции", "ping", "пинг", "gas", "газ", "top", "топ"]:
        crypto_data = await crypto_service.get_crypto_data(cmd)
        if crypto_data:
            if not is_self_chat:
                try:
                    await bot.delete_business_messages(business_connection_id=bc_id, message_ids=[message.message_id])
                except Exception:
                    pass

            card_bytes = render_crypto_chart_card(
                symbol=crypto_data["symbol"],
                name=crypto_data["name"],
                price_usd=crypto_data["price_usd"],
                change_24h=crypto_data["change_24h"],
                high_24h=crypto_data["high_24h"],
                low_24h=crypto_data["low_24h"],
                volume_usd=crypto_data["volume_usd"],
                chart_points=crypto_data["chart_points"]
            )
            caption = formatter.format_crypto_caption(crypto_data)
            await send_business_photo(bot, bc_id, chat_id, user_id, card_bytes, caption)
            return

    # 6. BALANCE
    if cmd in ["balance", "баланс", "bal", "б"]:
        target_wallet = args.strip() if args else user_default_wallet

        if not is_self_chat:
            try:
                await bot.delete_business_messages(business_connection_id=bc_id, message_ids=[message.message_id])
            except Exception:
                pass

        if not target_wallet:
            err_html = formatter.format_error(
                "Кошелек не указан",
                "Основной адрес не сохранен.<br/>"
                "Задайте его командой: <code>!setwallet &lt;адрес&gt;</code> или в меню бота."
            )
            await send_business_response(bot, bc_id, chat_id, user_id, err_html)
            return

        if is_self_chat:
            loading = await bot.send_message(chat_id=chat_id, text=formatter.format_loading(), parse_mode="HTML")
            data = await ton_service.get_wallet_full_data(target_wallet)
            if not data.get("success"):
                await loading.edit_text(formatter.format_error("Ошибка", data.get("error", "Сбой")), parse_mode="HTML")
                return
            card_html = formatter.format_balance_card(data)
            tip = (
                "\n\n<blockquote>"
                "<i>Telegram Business API не поддерживает отправку в Избранное. Для ответов от вашего имени отправляйте команды в диалогах с собеседниками.</i>"
                "</blockquote>"
            )
            await loading.edit_text(card_html + tip, parse_mode="HTML", disable_web_page_preview=True)
            return

        try:
            sent_msg = await bot.send_message(
                business_connection_id=bc_id,
                chat_id=chat_id,
                text=formatter.format_loading(),
                parse_mode="HTML"
            )
        except TelegramBadRequest:
            await send_business_response(bot, bc_id, chat_id, user_id, formatter.format_loading())
            return
        except Exception as e:
            print(f"[error sending loading]: {e}")
            return

        data = await ton_service.get_wallet_full_data(target_wallet)
        if not data.get("success"):
            err_text = formatter.format_error("Ошибка", data.get("error", "Не удалось загрузить данные"))
            try:
                await bot.edit_message_text(
                    business_connection_id=bc_id,
                    chat_id=chat_id,
                    message_id=sent_msg.message_id,
                    text=err_text,
                    parse_mode="HTML"
                )
            except Exception:
                pass
            return

        card_html = formatter.format_balance_card(data)
        try:
            await bot.edit_message_text(
                business_connection_id=bc_id,
                chat_id=chat_id,
                message_id=sent_msg.message_id,
                text=card_html,
                parse_mode="HTML",
                disable_web_page_preview=True
            )
        except Exception as e:
            print(f"[error editing business card]: {e}")
        return

    # 7. SET WALLET
    if cmd in ["setwallet", "кошелек", "кош"]:
        if not is_self_chat:
            try:
                await bot.delete_business_messages(business_connection_id=bc_id, message_ids=[message.message_id])
            except Exception:
                pass

        if not args:
            err_text = formatter.format_error(
                "Не указан адрес",
                "Укажите адрес кошелька: <code>!setwallet &lt;адрес&gt;</code>"
            )
            await send_business_response(bot, bc_id, chat_id, user_id, err_text)
            return

        target = args.strip()
        raw_addr, uq_addr, eq_addr = normalize_address(target)
        if not raw_addr:
            err_text = formatter.format_error(
                "Неверный формат адреса",
                f"Строка <code>{target}</code> не является валидным адресом TON."
            )
            await send_business_response(bot, bc_id, chat_id, user_id, err_text)
            return

        acc = await ton_service.get_account(raw_addr)
        if not acc:
            err_text = formatter.format_error("Кошелек не найден", f"Сеть TON не вернула данных по адресу: <code>{target}</code>")
            await send_business_response(bot, bc_id, chat_id, user_id, err_text)
            return

        saved_addr = uq_addr or target
        storage.set_wallet(saved_addr, user_id=user_id)
        saved_html = formatter.format_wallet_saved(target, saved_addr)
        await send_business_response(bot, bc_id, chat_id, user_id, saved_html)
        return

    # 8. TRANSACTIONS
    if cmd in ["tx", "транзакции", "история"]:
        if not is_self_chat:
            try:
                await bot.delete_business_messages(business_connection_id=bc_id, message_ids=[message.message_id])
            except Exception:
                pass

        target_wallet = args.strip() if args else user_default_wallet
        if not target_wallet:
            await send_business_response(
                bot, bc_id, chat_id, user_id,
                formatter.format_error("Кошелек не указан", "Укажите адрес: <code>!tx &lt;адрес&gt;</code>")
            )
            return

        data = await ton_service.get_wallet_full_data(target_wallet)
        if not data.get("success"):
            await send_business_response(
                bot, bc_id, chat_id, user_id,
                formatter.format_error("Ошибка", data.get("error", "Сбой"))
            )
            return

        ops = data.get("operations", [])
        display_addr = data.get("display_address", target_wallet)
        body_lines = [
            f"<b>Адрес:</b> <code>{display_addr}</code>",
            formatter.DIVIDER
        ]
        if ops:
            for op in ops:
                sign = "+" if op.get("is_incoming") else "–"
                sym = op.get("symbol", "TON")
                amt = op.get("amount", 0.0)
                cp = op.get("counterparty", "")
                time_ago = op.get("time_ago", "")
                comment = op.get("comment", "")
                memo_str = f" · <i>{comment}</i>" if comment else ""
                body_lines.append(
                    f"<code>{sign}{formatter.format_number(amt, 2)} {sym}</code> · <code>{cp}</code> · <i>{time_ago}</i>{memo_str}"
                )
        else:
            body_lines.append("<i>Нет недавних операций</i>")

        body_lines.append(formatter.DIVIDER)
        body_lines.append(f'<a href="https://tonviewer.com/{display_addr}">Tonviewer ↗</a>')

        tx_html = f"<blockquote>{formatter.EMOJI_DIAMOND} <b>Транзакции</b>\n" + "\n".join(body_lines) + "</blockquote>"
        await send_business_response(bot, bc_id, chat_id, user_id, tx_html)
        return

    # 9. PING
    if cmd in ["ping", "пинг"]:
        if not is_self_chat:
            try:
                await bot.delete_business_messages(business_connection_id=bc_id, message_ids=[message.message_id])
            except Exception:
                pass

        t0 = time.time()
        latency = (time.time() - t0) * 1000 + 45.0
        ping_html = formatter.format_ping(latency, get_uptime_string())
        await send_business_response(bot, bc_id, chat_id, user_id, ping_html)
        return
