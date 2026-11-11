"""
Aesthetic & Minimalist HTML formatters for Telegram Business Bot.
Supports custom Telegram emojis (<tg-emoji>) with clean craft typography,
zero top whitespace in blockquotes, and crypto / converter card formatters.
"""
from typing import Dict, Any, List

DIVIDER = '<tg-emoji emoji-id="5382360493161725288">➖</tg-emoji>' * 5
EMOJI_DIAMOND = '<tg-emoji emoji-id="5471952986970267163">💎</tg-emoji>'
EMOJI_TON = '<tg-emoji emoji-id="5318901904686754959">🙂</tg-emoji>'
EMOJI_USDT = '<tg-emoji emoji-id="5406841020769936275">☺️</tg-emoji>'
EMOJI_MONEY = '<tg-emoji emoji-id="5375296873982604963">💰</tg-emoji>'
EMOJI_NFT = '<tg-emoji emoji-id="6068962426185848828">▪️</tg-emoji>'


def format_number(val: float, decimals: int = 2) -> str:
    """Format number with thousands separators, keeping proper decimals."""
    if val >= 1_000_000:
        return f"{val:,.2f}"
    elif val >= 1_000:
        return f"{val:,.2f}"
    elif val >= 0.01:
        return f"{val:,.{decimals}f}"
    elif val > 0:
        return f"{val:.4f}".rstrip("0").rstrip(".")
    return "0.00"


def format_currency_usd(val: float) -> str:
    """Always format USD as 2 decimal places ($0.06 instead of $0.0594)."""
    if val >= 0.01:
        return f"${val:,.2f}"
    elif val > 0:
        return f"${val:.4f}".rstrip("0").rstrip(".")
    return "$0.00"


def format_balance_card(data: Dict[str, Any]) -> str:
    """
    Renders an elegant financial summary card for Telegram.
    Starts immediately after <blockquote> with zero top whitespace.
    """
    display_addr = data.get("display_address", "")
    ton_bal = data.get("ton_balance", 0.0)
    ton_usd = data.get("ton_value_usd", 0.0)
    usdt_bal = data.get("usdt_balance", 0.0)
    total_usd = data.get("total_usd", 0.0)

    assets: List[Dict[str, Any]] = data.get("assets", [])
    total_assets_count = data.get("total_assets_count", 0)
    operations: List[Dict[str, Any]] = data.get("operations", [])
    nfts: List[Dict[str, Any]] = data.get("nfts", [])

    body_lines = [
        f"<b>Адрес:</b> <code>{display_addr}</code>",
        DIVIDER,
        f"{EMOJI_TON} <b>TON:</b> <code>{format_number(ton_bal, 2)} TON</code> <i>({format_currency_usd(ton_usd)})</i>",
        f"{EMOJI_USDT} <b>USDT:</b> <code>{format_number(usdt_bal, 2)} USDT</code>",
        f"{EMOJI_MONEY} <b>Итого:</b> <code>{format_currency_usd(total_usd)}</code>"
    ]

    # Additional jettons
    if total_assets_count > 0:
        top_tokens = [a['symbol'] for a in assets[:3] if a.get('symbol')]
        tokens_preview = f" · {', '.join(top_tokens)}" if top_tokens else ""
        body_lines.append(f"Токены: <code>{total_assets_count}</code>{tokens_preview}")

    # Recent operations
    if operations:
        body_lines.append(DIVIDER)
        body_lines.append("<b>Недавние операции</b>")
        for op in operations[:3]:
            sign = "+" if op.get("is_incoming") else "–"
            sym = op.get("symbol", "TON")
            amt = op.get("amount", 0.0)
            time_ago = op.get("time_ago", "")
            body_lines.append(f"<code>{sign}{format_number(amt, 2)} {sym}</code> · <i>{time_ago}</i>")

    # NFTs (if any)
    if nfts:
        body_lines.append(DIVIDER)
        body_lines.append(f"{EMOJI_NFT} <b>NFT ({len(nfts)})</b>")
        for nft in nfts[:2]:
            nft_name = nft.get("name", "NFT Item")
            col = nft.get("collection", "")
            col_str = f" · <i>{col}</i>" if col else ""
            body_lines.append(f"• {nft_name}{col_str}")
        if len(nfts) > 2:
            body_lines.append(f"• <i>еще {len(nfts) - 2}</i>")

    body_lines.append(DIVIDER)
    body_lines.append(f'<a href="https://tonviewer.com/{display_addr}">Tonviewer ↗</a>')

    return f"<blockquote>{EMOJI_DIAMOND} <b>Кошелек</b>\n" + "\n".join(body_lines) + "</blockquote>"


def format_crypto_caption(info: Dict[str, Any]) -> str:
    """
    Renders clean quote caption for crypto chart photos.
    """
    sym = info.get("symbol", "")
    name = info.get("name", sym)
    price = info.get("price_usd", 0.0)
    change = info.get("change_24h", 0.0)
    high = info.get("high_24h", 0.0)
    low = info.get("low_24h", 0.0)
    vol = info.get("volume_usd", 0.0)

    sign = "+" if change >= 0 else ""
    change_emoji = "🟢" if change >= 0 else "🔴"

    lines = [
        f"{EMOJI_DIAMOND} <b>{name} ({sym.upper()})</b>",
        DIVIDER,
        f"<b>Цена:</b> <code>${format_number(price, 2)}</code>",
        f"<b>24h Тренд:</b> <code>{sign}{change:.2f}%</code> {change_emoji}",
        f"<b>24h Макс:</b> <code>${format_number(high, 2)}</code>",
        f"<b>24h Мин:</b> <code>${format_number(low, 2)}</code>",
        f"<b>24h Объем:</b> <code>${format_number(vol, 0)}</code>" if vol else None,
        DIVIDER,
        f'<a href="https://coinmarketcap.com">Market Analytics ↗</a>'
    ]
    clean_lines = [l for l in lines if l]
    return f"<blockquote>{clean_lines[0]}\n" + "\n".join(clean_lines[1:]) + "</blockquote>"


def format_converter_caption(data: Dict[str, Any]) -> str:
    """
    Renders clean quote caption for currency converter photos.
    """
    from_amt = data["from_amount"]
    from_sym = data["from_symbol"]
    to_amt = data["to_amount"]
    to_sym = data["to_symbol"]
    rate = data["rate"]
    other_rates = data.get("other_rates", {})

    lines = [
        f"{EMOJI_DIAMOND} <b>Конвертация {format_number(from_amt, 2)} {from_sym}</b>",
        DIVIDER,
        f"<b>Результат:</b> <code>{format_number(to_amt, 2)} {to_sym}</code>",
        f"<b>Курс:</b> <code>1 {from_sym} ≈ {format_number(rate, 4)} {to_sym}</code>",
    ]

    if other_rates:
        lines.append(DIVIDER)
        lines.append("<b>В других валютах:</b>")
        for cur, val in list(other_rates.items())[:4]:
            lines.append(f"• <code>{format_number(val, 2)} {cur}</code>")

    return f"<blockquote>{lines[0]}\n" + "\n".join(lines[1:]) + "</blockquote>"


def format_gas_caption(gas_data: Dict[str, Any]) -> str:
    """
    Renders clean quote caption for live blockchain gas/network fees.
    """
    lines = [
        f"{EMOJI_DIAMOND} <b>Комиссии сетей (Live Gas)</b>",
        DIVIDER,
        f"<b>TON Network:</b>",
        f"• Перевод: <code>{gas_data.get('ton_tx', '~0.005 TON')}</code> ({gas_data.get('ton_usd', '< $0.02')})",
        f"• Jetton / USDT: <code>{gas_data.get('ton_jetton', '~0.03 TON')}</code>",
        DIVIDER,
        f"<b>Bitcoin (Mempool):</b>",
        f"• Скоростной: <code>{gas_data.get('btc_fast', 3)} sat/vB</code>",
        f"• Стандартный: <code>{gas_data.get('btc_med', 2)} sat/vB</code> ({gas_data.get('btc_usd', '~$0.35')})",
        DIVIDER,
        f"<b>Ethereum Mainnet:</b>",
        f"• Базовый Gas: <code>{gas_data.get('eth_gwei', 0.6):.1f} Gwei</code>",
        f"• ETH Transfer: <code>{gas_data.get('eth_tx_usd', '~$0.05')}</code>",
        f"• Swap Uniswap: <code>{gas_data.get('eth_swap_usd', '~$0.45')}</code>",
        DIVIDER,
        f'<a href="https://mempool.space">Mempool Live ↗</a>'
    ]
    return f"<blockquote>{lines[0]}\n" + "\n".join(lines[1:]) + "</blockquote>"


def format_top_caption(top_list: List[Dict[str, Any]]) -> str:
    """
    Renders clean quote caption for top cryptocurrency assets.
    """
    lines = [
        f"{EMOJI_DIAMOND} <b>Топ криптовалют · Live 24h</b>",
        DIVIDER,
    ]
    for coin in top_list:
        sym = coin.get("symbol", "")
        name = coin.get("name", sym)
        price = coin.get("price_usd", 0.0)
        chg = coin.get("change_24h", 0.0)
        sign = "+" if chg >= 0 else ""
        icon = "🟢" if chg >= 0 else "🔴"
        lines.append(f"<b>{name} ({sym}):</b> <code>${format_number(price, 2)}</code> · <code>{sign}{chg:.2f}%</code> {icon}")

    lines.append(DIVIDER)
    lines.append('<a href="https://binance.com">Binance Markets ↗</a>')
    return f"<blockquote>{lines[0]}\n" + "\n".join(lines[1:]) + "</blockquote>"


def format_help_menu(default_wallet: str, prefixes: List[str]) -> str:
    p = prefixes[0] if prefixes else "!"
    wallet_preview = default_wallet if default_wallet else "не установлен"

    lines = [
        f"{EMOJI_DIAMOND} <b>Команды Telegram Business</b>",
        DIVIDER,
        f"<code>{p}balance</code> [адрес]",
        "Баланс кошелька, активы и операции\n",
        f"<code>{p}gram</code>, <code>{p}btc</code>, <code>{p}eth</code>, <code>{p}sol</code>",
        "График, иконка и актуальный курс крипты\n",
        f"<code>{p}12 gram to azn</code> или <code>{p}50 манат в руб</code>",
        "Конвертер криптовалют и валют (AZN, RUB, USD...)\n",
        f"<code>{p}gas</code>",
        "Комиссии сетей TON, Bitcoin и Ethereum\n",
        f"<code>{p}top</code>",
        "Обзор топ-5 активов за 24 часа\n",
        f"<code>{p}setwallet</code> &lt;адрес&gt;",
        "Привязать кошелек по умолчанию\n",
        f"<code>{p}tx</code> [адрес]",
        "История транзакций\n",
        f"<code>{p}ping</code>",
        "Время отклика бота",
        DIVIDER,
        "<b>Текущий кошелек:</b>",
        f"<code>{wallet_preview}</code>"
    ]
    return f"<blockquote>{lines[0]}\n" + "\n".join(lines[1:]) + "</blockquote>"


def format_wallet_saved(address: str, uq_addr: str) -> str:
    lines = [
        f"{EMOJI_DIAMOND} <b>Кошелек сохранен</b>",
        DIVIDER,
        "<b>Адрес:</b>",
        f"<code>{uq_addr}</code>\n",
        f'<a href="https://tonviewer.com/{uq_addr}">Tonviewer ↗</a>'
    ]
    return f"<blockquote>{lines[0]}\n" + "\n".join(lines[1:]) + "</blockquote>"


def format_ping(latency_ms: float, uptime_str: str) -> str:
    lines = [
        "🏓 <b>Статус системы</b>",
        DIVIDER,
        f"Отклик: <code>{latency_ms:.1f} ms</code>",
        f"Аптайм: <code>{uptime_str}</code>",
        "Режим: <code>Business</code>"
    ]
    return f"<blockquote>{lines[0]}\n" + "\n".join(lines[1:]) + "</blockquote>"


def format_error(title: str, details: str) -> str:
    lines = [
        f"⚠️ <b>{title}</b>",
        DIVIDER,
        f"{details}"
    ]
    return f"<blockquote>{lines[0]}\n" + "\n".join(lines[1:]) + "</blockquote>"


def format_loading() -> str:
    return "<blockquote><i>Загрузка данных...</i></blockquote>"
