"""
Private Chat Handlers (Direct messaging with the Bot).
Aesthetic and minimalist interface with crafted Telegram emojis:
- Connect the bot to Telegram Business
- Set default wallet
- Check balance directly
- Crypto charts & price tracking (with real icons)
- Multi-currency converter (AZN, RUB, USD, and 35+ currencies)
- Live gas network fees & market top
- Diagnostics and status
"""
from aiogram import Router, Bot, F
from aiogram.filters import CommandStart, Command
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import StatesGroup, State
from aiogram.types import Message, CallbackQuery, InlineKeyboardMarkup, InlineKeyboardButton, BufferedInputFile

from storage import storage
from ton_utils import normalize_address
from ton_service import ton_service
from crypto_service import crypto_service
from card_generator import render_crypto_chart_card, render_converter_card, render_gas_card
import formatter
from formatter import DIVIDER, EMOJI_DIAMOND

router = Router(name="private_router")


class WalletState(StatesGroup):
    waiting_for_address = State()


def get_main_keyboard(user_id: int) -> InlineKeyboardMarkup:
    wallet = storage.get_wallet(user_id)
    wallet_preview = f"{wallet[:6]}...{wallet[-4:]}" if wallet else "не задан"

    return InlineKeyboardMarkup(
        inline_keyboard=[
            [
                InlineKeyboardButton(text="💎 Баланс", callback_data="btn_check_balance"),
                InlineKeyboardButton(text="⚙️ Кошелек", callback_data="btn_set_wallet"),
            ],
            [
                InlineKeyboardButton(text=f"👛 {wallet_preview}", callback_data="btn_show_wallet"),
                InlineKeyboardButton(text="💼 Business", callback_data="btn_business_status"),
            ],
            [
                InlineKeyboardButton(text="⚡ Gas Fee", callback_data="btn_gas_fees"),
                InlineKeyboardButton(text="📈 Топ крипты", callback_data="btn_top_crypto"),
            ],
            [
                InlineKeyboardButton(text="📖 Команды", callback_data="btn_help_commands"),
            ],
        ]
    )


@router.message(CommandStart())
async def cmd_start(message: Message, state: FSMContext):
    await state.clear()
    uid = message.from_user.id
    current_wallet = storage.get_wallet(uid)
    conn = storage.get_connection(uid)
    conn_badge = "подключен" if (conn and conn.get("is_enabled")) else "не подключен"
    wallet_text = current_wallet or "не установлен"

    text = (
        "<blockquote>"
        f"{EMOJI_DIAMOND} <b>Панель управления</b>\n"
        f"{DIVIDER}\n"
        f"💼 <b>Статус:</b> <code>{conn_badge}</code>\n"
        f"👛 <b>Кошелек:</b> <code>{wallet_text}</code>\n"
        f"{DIVIDER}\n"
        "<b>Команды в чатах:</b>\n"
        "• <code>!balance</code> — баланс кошелька\n"
        "• <code>!gram</code>, <code>!btc</code>, <code>!eth</code> — график и курс с иконкой\n"
        "• <code>!12 gram azn</code> или <code>!50 манат в руб</code> — конвертер валют\n"
        "• <code>!gas</code> — комиссии сетей TON, BTC, ETH\n"
        "• <code>!top</code> — обзор топ активов\n"
        "• <code>!tx</code>, <code>!ping</code>, <code>!help</code>\n\n"
        "<b>Подключение:</b>\n"
        "Настройки Telegram → Telegram Business → Чат-боты → добавить бота с правом ответа."
        "</blockquote>"
    )
    await message.answer(text, reply_markup=get_main_keyboard(uid), parse_mode="HTML")


@router.message(Command("help", "хелп", "помощь"))
async def cmd_help(message: Message):
    uid = message.from_user.id
    wallet = storage.get_wallet(uid)
    text = formatter.format_help_menu(wallet, ["!", ".", "/"])
    await message.answer(text, reply_markup=get_main_keyboard(uid), parse_mode="HTML")


@router.message(Command("balance", "баланс"))
async def cmd_balance(message: Message):
    uid = message.from_user.id
    parts = (message.text or "").split(maxsplit=1)
    target = parts[1].strip() if len(parts) > 1 else storage.get_wallet(uid)

    if not target:
        err = formatter.format_error(
            "Кошелек не установлен",
            "Установите кошелек: <code>/setwallet &lt;адрес&gt;</code> или кнопкой ниже."
        )
        await message.answer(err, reply_markup=get_main_keyboard(uid), parse_mode="HTML")
        return

    loading = await message.answer(formatter.format_loading(), parse_mode="HTML")
    data = await ton_service.get_wallet_full_data(target)
    if not data.get("success"):
        await loading.edit_text(
            formatter.format_error("Ошибка", data.get("error", "Сбой получения данных")),
            parse_mode="HTML"
        )
        return

    card = formatter.format_balance_card(data)
    await loading.edit_text(card, reply_markup=get_main_keyboard(uid), parse_mode="HTML", disable_web_page_preview=True)


@router.message(Command("gas", "газ", "fee"))
async def cmd_gas(message: Message):
    gas_data = await crypto_service.get_gas_data()
    card_bytes = render_gas_card(gas_data)
    caption = formatter.format_gas_caption(gas_data)
    photo_file = BufferedInputFile(card_bytes, filename="gas.jpg")
    await message.answer_photo(photo_file, caption=caption, parse_mode="HTML")


@router.message(Command("top", "топ", "рынок"))
async def cmd_top(message: Message):
    top_list = await crypto_service.get_top_crypto()
    caption = formatter.format_top_caption(top_list)
    await message.answer(caption, parse_mode="HTML")


@router.message(Command("setwallet", "кошелек"))
async def cmd_setwallet(message: Message, state: FSMContext):
    await state.clear()
    uid = message.from_user.id
    parts = (message.text or "").split(maxsplit=1)
    if len(parts) < 2 or not parts[1].strip():
        await message.answer(
            f"<blockquote>{EMOJI_DIAMOND} <b>Укажите адрес кошелька</b><br/>Пример: <code>/setwallet UQCD...</code> или <code>/кошелек alice.ton</code></blockquote>",
            parse_mode="HTML"
        )
        return

    target = parts[1].strip()
    raw_addr, uq_addr, eq_addr = normalize_address(target)
    if not raw_addr:
        await message.answer(
            formatter.format_error("Неверный адрес", f"Строка <code>{target}</code> не является корректным адресом TON."),
            parse_mode="HTML"
        )
        return

    loading = await message.answer("<blockquote><i>Проверка адреса...</i></blockquote>", parse_mode="HTML")
    acc = await ton_service.get_account(raw_addr)
    if not acc:
        await loading.edit_text(
            formatter.format_error("Кошелек не найден", f"Сеть TON не вернула информации по адресу: <code>{target}</code>"),
            parse_mode="HTML"
        )
        return

    saved_addr = uq_addr or target
    storage.set_wallet(saved_addr, user_id=uid)
    await loading.edit_text(
        formatter.format_wallet_saved(target, saved_addr),
        reply_markup=get_main_keyboard(uid),
        parse_mode="HTML"
    )


# --- CALLBACK QUERIES ---

@router.callback_query(F.data == "btn_check_balance")
async def cb_check_balance(callback: CallbackQuery):
    uid = callback.from_user.id
    wallet = storage.get_wallet(uid)
    if not wallet:
        await callback.answer("Кошелек не установлен.", show_alert=True)
        return

    await callback.answer("Загрузка...")
    data = await ton_service.get_wallet_full_data(wallet)
    if not data.get("success"):
        await callback.message.answer(
            formatter.format_error("Ошибка", data.get("error", "Сбой загрузки данных")),
            parse_mode="HTML"
        )
        return
    card = formatter.format_balance_card(data)
    await callback.message.answer(card, reply_markup=get_main_keyboard(uid), parse_mode="HTML", disable_web_page_preview=True)


@router.callback_query(F.data == "btn_set_wallet")
async def cb_set_wallet(callback: CallbackQuery, state: FSMContext):
    await state.set_state(WalletState.waiting_for_address)
    text = (
        "<blockquote>"
        f"{EMOJI_DIAMOND} <b>Привязка кошелька</b>\n"
        f"{DIVIDER}\n"
        "Отправьте сообщением TON-адрес (<code>UQ...</code> / <code>EQ...</code>) "
        "или домен (<code>*.ton</code>):"
        "</blockquote>"
    )
    await callback.message.answer(text, parse_mode="HTML")
    await callback.answer()


@router.callback_query(F.data == "btn_show_wallet")
async def cb_show_wallet(callback: CallbackQuery):
    uid = callback.from_user.id
    wallet = storage.get_wallet(uid)
    if not wallet:
        await callback.answer("Кошелек не задан.", show_alert=True)
        return
    text = (
        "<blockquote>"
        "👛 <b>Текущий кошелек</b>\n"
        f"{DIVIDER}\n"
        f"<code>{wallet}</code>\n\n"
        f'<a href="https://tonviewer.com/{wallet}">Tonviewer ↗</a>'
        "</blockquote>"
    )
    await callback.message.answer(text, reply_markup=get_main_keyboard(uid), parse_mode="HTML")
    await callback.answer()


@router.callback_query(F.data == "btn_business_status")
async def cb_business_status(callback: CallbackQuery):
    uid = callback.from_user.id
    conn = storage.get_connection(uid)
    is_active = conn and conn.get("is_enabled")
    can_reply = conn.get("can_reply", False) if conn else False

    status_text = "активен" if is_active else "не подключен"
    reply_text = "включено" if can_reply else "выключено"

    text = (
        "<blockquote>"
        "💼 <b>Telegram Business</b>\n"
        f"{DIVIDER}\n"
        f"Соединение: <code>{status_text}</code>\n"
        f"Право ответа: <code>{reply_text}</code>\n"
        f"{DIVIDER}\n"
        "Настройки: Telegram → Telegram Business → Чат-боты."
        "</blockquote>"
    )
    await callback.message.answer(text, reply_markup=get_main_keyboard(uid), parse_mode="HTML")
    await callback.answer()


@router.callback_query(F.data == "btn_gas_fees")
async def cb_gas_fees(callback: CallbackQuery):
    await callback.answer("Загрузка комиссий...")
    gas_data = await crypto_service.get_gas_data()
    card_bytes = render_gas_card(gas_data)
    caption = formatter.format_gas_caption(gas_data)
    photo_file = BufferedInputFile(card_bytes, filename="gas.jpg")
    await callback.message.answer_photo(photo_file, caption=caption, parse_mode="HTML")


@router.callback_query(F.data == "btn_top_crypto")
async def cb_top_crypto(callback: CallbackQuery):
    await callback.answer("Загрузка...")
    top_list = await crypto_service.get_top_crypto()
    caption = formatter.format_top_caption(top_list)
    await callback.message.answer(caption, parse_mode="HTML")


@router.callback_query(F.data == "btn_help_commands")
async def cb_help_commands(callback: CallbackQuery):
    uid = callback.from_user.id
    wallet = storage.get_wallet(uid)
    text = formatter.format_help_menu(wallet, ["!", ".", "/"])
    await callback.message.answer(text, reply_markup=get_main_keyboard(uid), parse_mode="HTML")
    await callback.answer()


@router.message(WalletState.waiting_for_address)
async def state_receive_address(message: Message, state: FSMContext):
    target = (message.text or "").strip()
    raw_addr, uq_addr, eq_addr = normalize_address(target)
    if not raw_addr:
        await message.answer(
            formatter.format_error(
                "Неверный формат",
                f"Строка <code>{target}</code> не является корректным TON-адресом или доменом.<br/>Попробуйте снова:"
            ),
            parse_mode="HTML"
        )
        return

    loading = await message.answer("<blockquote><i>Проверка кошелька...</i></blockquote>", parse_mode="HTML")
    acc = await ton_service.get_account(raw_addr)
    if not acc:
        await loading.edit_text(
            formatter.format_error("Кошелек не найден", f"Сеть TON не вернула информации по адресу: <code>{target}</code>"),
            parse_mode="HTML"
        )
        return

    await state.clear()
    saved_addr = uq_addr or target
    storage.set_wallet(saved_addr, user_id=message.from_user.id)
    await loading.edit_text(
        formatter.format_wallet_saved(target, saved_addr),
        reply_markup=get_main_keyboard(message.from_user.id),
        parse_mode="HTML"
    )


@router.message(F.text.startswith(("!", ".", "/")))
async def private_prefix_handler(message: Message, state: FSMContext):
    """Handle !help, !balance, !gram, !btc, !12 gram to azn directly in bot private chat."""
    raw = (message.text or "").strip()
    without = raw[1:].strip()

    # 1. Converter check: e.g. !12 gram to azn or !10 btc usd
    conv_parsed = crypto_service.parse_converter_query(without)
    if conv_parsed:
        amt, from_c, to_c = conv_parsed
        conv_data = await crypto_service.convert_currency(amt, from_c, to_c)
        if conv_data:
            card_bytes = render_converter_card(
                from_amount=conv_data["from_amount"],
                from_symbol=conv_data["from_symbol"],
                to_amount=conv_data["to_amount"],
                to_symbol=conv_data["to_symbol"],
                exchange_rate=conv_data["rate"],
                other_rates=conv_data["other_rates"]
            )
            caption = formatter.format_converter_caption(conv_data)
            photo_file = BufferedInputFile(card_bytes, filename="converter.jpg")
            await message.answer_photo(photo_file, caption=caption, parse_mode="HTML")
            return

    parts = without.split(maxsplit=1)
    cmd = parts[0].lower() if parts else ""
    args = parts[1].strip() if len(parts) > 1 else ""
    uid = message.from_user.id

    # 2. Gas fees: !gas
    if cmd in ["gas", "газ", "комиссия", "fee", "fees"]:
        gas_data = await crypto_service.get_gas_data()
        card_bytes = render_gas_card(gas_data)
        caption = formatter.format_gas_caption(gas_data)
        photo_file = BufferedInputFile(card_bytes, filename="gas.jpg")
        await message.answer_photo(photo_file, caption=caption, parse_mode="HTML")
        return

    # 3. Top crypto: !top
    if cmd in ["top", "топ", "рынок", "market"]:
        top_list = await crypto_service.get_top_crypto()
        caption = formatter.format_top_caption(top_list)
        await message.answer(caption, parse_mode="HTML")
        return

    # 4. Crypto chart check: e.g. !gram, !btc, !eth, !sol
    if crypto_service.is_crypto_query(cmd) and cmd not in ["help", "хелп", "balance", "баланс", "setwallet", "кошелек", "tx", "транзакции", "ping", "пинг", "gas", "газ", "top", "топ"]:
        crypto_data = await crypto_service.get_crypto_data(cmd)
        if crypto_data:
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
            photo_file = BufferedInputFile(card_bytes, filename="chart.jpg")
            await message.answer_photo(photo_file, caption=caption, parse_mode="HTML")
            return

    if cmd in ["help", "хелп", "h", "помощь"]:
        wallet = storage.get_wallet(uid)
        await message.answer(
            formatter.format_help_menu(wallet, ["!", ".", "/"]),
            reply_markup=get_main_keyboard(uid),
            parse_mode="HTML"
        )
        return

    if cmd in ["balance", "баланс", "bal", "б"]:
        target = args if args else storage.get_wallet(uid)
        if not target:
            await message.answer(
                formatter.format_error("Кошелек не установлен", "Установите кошелек командой: <code>!setwallet &lt;адрес&gt;</code>"),
                reply_markup=get_main_keyboard(uid),
                parse_mode="HTML"
            )
            return

        loading = await message.answer(formatter.format_loading(), parse_mode="HTML")
        data = await ton_service.get_wallet_full_data(target)
        if not data.get("success"):
            await loading.edit_text(formatter.format_error("Ошибка", data.get("error", "Сбой")), parse_mode="HTML")
            return
        card = formatter.format_balance_card(data)
        await loading.edit_text(card, reply_markup=get_main_keyboard(uid), parse_mode="HTML", disable_web_page_preview=True)
        return

    if cmd in ["setwallet", "кошелек", "кош"]:
        if not args:
            await message.answer(
                "<blockquote>Укажите адрес: <code>!setwallet &lt;адрес&gt;</code></blockquote>",
                parse_mode="HTML"
            )
            return
        raw_addr, uq_addr, eq_addr = normalize_address(args)
        if not raw_addr:
            await message.answer(formatter.format_error("Неверный адрес", f"Адрес <code>{args}</code> некорректен."), parse_mode="HTML")
            return
        loading = await message.answer("<blockquote><i>Проверка адреса...</i></blockquote>", parse_mode="HTML")
        acc = await ton_service.get_account(raw_addr)
        if not acc:
            await loading.edit_text(formatter.format_error("Не найден", f"Сеть TON не вернула адрес: <code>{args}</code>"), parse_mode="HTML")
            return
        saved_addr = uq_addr or args
        storage.set_wallet(saved_addr, user_id=uid)
        await loading.edit_text(formatter.format_wallet_saved(args, saved_addr), reply_markup=get_main_keyboard(uid), parse_mode="HTML")
        return


@router.message(F.text)
async def private_general_text_handler(message: Message, state: FSMContext):
    """Handles converter or crypto queries without prefix in bot private chat."""
    cur_state = await state.get_state()
    if cur_state:
        return

    text = (message.text or "").strip()
    conv_parsed = crypto_service.parse_converter_query(text)
    if conv_parsed:
        amt, from_c, to_c = conv_parsed
        conv_data = await crypto_service.convert_currency(amt, from_c, to_c)
        if conv_data:
            card_bytes = render_converter_card(
                from_amount=conv_data["from_amount"],
                from_symbol=conv_data["from_symbol"],
                to_amount=conv_data["to_amount"],
                to_symbol=conv_data["to_symbol"],
                exchange_rate=conv_data["rate"],
                other_rates=conv_data["other_rates"]
            )
            caption = formatter.format_converter_caption(conv_data)
            photo_file = BufferedInputFile(card_bytes, filename="converter.jpg")
            await message.answer_photo(photo_file, caption=caption, parse_mode="HTML")
            return

    if crypto_service.is_crypto_query(text) and len(text) <= 8:
        crypto_data = await crypto_service.get_crypto_data(text)
        if crypto_data:
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
            photo_file = BufferedInputFile(card_bytes, filename="chart.jpg")
            await message.answer_photo(photo_file, caption=caption, parse_mode="HTML")
            return
