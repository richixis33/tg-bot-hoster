"""
High-craft crypto card and currency converter image generator using Pillow.
Dark mode aesthetic, Swiss typography, anti-aliased charts, and crisp financial badges.
"""
import io
import os
import math
import urllib.request
from typing import Dict, Any, List, Optional, Tuple
from PIL import Image, ImageDraw, ImageFont

# Fonts
FONT_REGULAR = "C:\\Windows\\Fonts\\segoeui.ttf"
FONT_BOLD = "C:\\Windows\\Fonts\\segoeuib.ttf"
FONT_MONO = "C:\\Windows\\Fonts\\consola.ttf"

# Directory for local icon caching
ICONS_DIR = os.path.join(os.path.dirname(__file__), "icons")
os.makedirs(ICONS_DIR, exist_ok=True)

# In-memory cache for processed circular icon images: (symbol_or_key, size) -> Image
_PROCESSED_ICON_CACHE: Dict[Tuple[str, int], Optional[Image.Image]] = {}

# Coin metadata & fallback glyphs
COIN_THEMES = {
    "BTC": {"name": "Bitcoin", "color": (247, 147, 26), "glyph": "₿"},
    "ETH": {"name": "Ethereum", "color": (98, 126, 234), "glyph": "Ξ"},
    "TON": {"name": "Toncoin", "color": (0, 152, 234), "glyph": "💎"},
    "GRAM": {"name": "Gram", "color": (0, 152, 234), "glyph": "💎"},
    "USDT": {"name": "Tether USD", "color": (38, 161, 123), "glyph": "₮"},
    "SOL": {"name": "Solana", "color": (20, 241, 149), "glyph": "◎"},
    "NOT": {"name": "Notcoin", "color": (220, 220, 220), "glyph": "NOT"},
    "DOGS": {"name": "Dogs", "color": (255, 255, 255), "glyph": "DOGS"},
    "BNB": {"name": "BNB Chain", "color": (243, 186, 47), "glyph": "BNB"},
    "XRP": {"name": "Ripple", "color": (50, 100, 220), "glyph": "XRP"},
    "DOGE": {"name": "Dogecoin", "color": (194, 166, 51), "glyph": "Ð"},
    "LTC": {"name": "Litecoin", "color": (52, 93, 157), "glyph": "Ł"},
    "TRX": {"name": "TRON", "color": (239, 0, 39), "glyph": "TRX"},
    "ADA": {"name": "Cardano", "color": (0, 51, 173), "glyph": "ADA"},
    "AVAX": {"name": "Avalanche", "color": (232, 65, 66), "glyph": "AVAX"},
    "NEAR": {"name": "NEAR Protocol", "color": (0, 0, 0), "glyph": "NEAR"},
    "SUI": {"name": "Sui", "color": (39, 117, 202), "glyph": "SUI"},
}

FIAT_SYMBOLS = {
    "USD": "$", "EUR": "€", "RUB": "₽", "AZN": "₼", "UAH": "₴",
    "KZT": "₸", "BYN": "Br", "UZS": "so'm", "GEL": "₾", "AMD": "֏",
    "KGS": "сом", "TJS": "смн", "GBP": "£", "TRY": "₺", "AED": "AED",
    "SAR": "SAR", "QAR": "QAR", "CNY": "¥", "JPY": "¥", "KRW": "₩",
    "CHF": "CHF", "PLN": "zł", "CZK": "Kč", "HUF": "Ft", "BGN": "лв",
    "RON": "lei", "RSD": "din", "ILS": "₪", "INR": "₹", "THB": "฿",
    "VND": "₫", "IDR": "Rp", "BRL": "R$", "CAD": "C$", "AUD": "A$",
    "SGD": "S$", "HKD": "HK$", "ZAR": "R"
}


def _get_font(path: str, size: int) -> ImageFont.FreeTypeFont:
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()


def format_val_str(val: float, decimals: int = 2) -> str:
    if val >= 1_000:
        return f"{val:,.2f}"
    elif val >= 1:
        return f"{val:.{decimals}f}"
    elif val >= 0.0001:
        return f"{val:.4f}".rstrip("0").rstrip(".")
    elif val > 0:
        return f"{val:.8f}".rstrip("0")
    return "0.00"


def get_crypto_icon(symbol: str, size: int) -> Optional[Image.Image]:
    """
    Retrieves or downloads the cryptocurrency icon, resizing and masking
    it into an anti-aliased circular RGBA image of `(size, size)`.
    Caches on disk in `icons/` and in memory.
    """
    sym = symbol.lower().strip()
    cache_key = (f"crypto_{sym}", size)
    if cache_key in _PROCESSED_ICON_CACHE:
        return _PROCESSED_ICON_CACHE[cache_key]

    lookup_candidates = [sym]
    if sym == "gram":
        lookup_candidates = ["gram", "ton"]

    icon_path = None
    for cand in lookup_candidates:
        local_file = os.path.join(ICONS_DIR, f"{cand}.png")
        if os.path.exists(local_file) and os.path.getsize(local_file) > 200:
            icon_path = local_file
            break

    # If not on disk, download synchronously with brief timeout
    if not icon_path:
        urls = [
            f"https://assets.coincap.io/assets/icons/{sym}@2x.png",
            f"https://cdn.jsdelivr.net/gh/spothq/cryptocurrency-icons@master/128/color/{sym}.png"
        ]
        target_file = os.path.join(ICONS_DIR, f"{sym}.png")
        for u in urls:
            try:
                req = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=2.0) as resp:
                    if resp.status == 200:
                        data = resp.read()
                        if len(data) > 300:
                            with open(target_file, "wb") as f:
                                f.write(data)
                            icon_path = target_file
                            break
            except Exception:
                continue

    if not icon_path or not os.path.exists(icon_path):
        _PROCESSED_ICON_CACHE[cache_key] = None
        return None

    try:
        raw_img = Image.open(icon_path).convert("RGBA")
        raw_img = raw_img.resize((size, size), Image.Resampling.LANCZOS)

        # High-quality circular mask
        mask = Image.new("L", (size, size), 0)
        m_draw = ImageDraw.Draw(mask)
        m_draw.ellipse((0, 0, size - 1, size - 1), fill=255)

        circular = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        circular.paste(raw_img, (0, 0), mask)
        _PROCESSED_ICON_CACHE[cache_key] = circular
        return circular
    except Exception:
        _PROCESSED_ICON_CACHE[cache_key] = None
        return None


def get_fiat_badge(currency: str, size: int) -> Image.Image:
    """
    Renders a clean circular dark badge for fiat currencies with symbol or code.
    """
    cur = currency.upper().strip()
    cache_key = (f"fiat_{cur}", size)
    if cache_key in _PROCESSED_ICON_CACHE and _PROCESSED_ICON_CACHE[cache_key]:
        return _PROCESSED_ICON_CACHE[cache_key]

    symbol_char = FIAT_SYMBOLS.get(cur, cur[:3])
    badge = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(badge)

    # Dark aesthetic circle background
    draw.ellipse((0, 0, size - 1, size - 1), fill=(30, 34, 44, 255), outline=(52, 58, 74, 255), width=max(1, size // 24))

    # Currency symbol
    font_size = int(size * 0.44) if len(symbol_char) <= 2 else int(size * 0.28)
    f_sym = _get_font(FONT_BOLD, font_size)
    draw.text((size // 2, size // 2), symbol_char, font=f_sym, fill=(240, 243, 250), anchor="mm")

    _PROCESSED_ICON_CACHE[cache_key] = badge
    return badge


def render_crypto_chart_card(
    symbol: str,
    name: str,
    price_usd: float,
    change_24h: float,
    high_24h: float,
    low_24h: float,
    volume_usd: float,
    chart_points: List[float]
) -> bytes:
    """
    Renders an 800x420 dark financial card with real crypto icon and smooth 24h trend.
    Uses 2x supersampling (1600x840 -> 800x420) for ultra-sharp typography & curves.
    """
    scale = 2
    w, h = 800 * scale, 420 * scale
    theme = COIN_THEMES.get(symbol.upper(), {"name": name or symbol, "color": (79, 70, 229), "glyph": symbol[:3]})
    accent_color = theme["color"]

    is_positive = change_24h >= 0
    trend_color = (16, 185, 129) if is_positive else (239, 68, 68)

    img = Image.new("RGBA", (w, h), color=(15, 17, 21, 255))
    draw = ImageDraw.Draw(img)

    # 1. Subtle card border
    draw.rounded_rectangle([(16, 16), (w - 16, h - 16)], radius=28 * scale, outline=(36, 40, 50), width=2 * scale)

    # 2. Header: Icon Badge (Real crypto icon or fallback glyph)
    badge_x, badge_y, badge_r = 52 * scale, 52 * scale, 26 * scale
    icon_diam = badge_r * 2
    coin_icon = get_crypto_icon(symbol, icon_diam)

    if coin_icon:
        img.paste(coin_icon, (badge_x - badge_r, badge_y - badge_r), coin_icon)
        draw.ellipse(
            [(badge_x - badge_r, badge_y - badge_r), (badge_x + badge_r, badge_y + badge_r)],
            outline=(50, 56, 70), width=1 * scale
        )
    else:
        # Fallback styled glyph circle
        draw.ellipse(
            [(badge_x - badge_r, badge_y - badge_r), (badge_x + badge_r, badge_y + badge_r)],
            fill=accent_color
        )
        glyph = theme["glyph"]
        glyph_size = 20 * scale if len(glyph) <= 2 else 13 * scale
        f_glyph = _get_font(FONT_BOLD, glyph_size)
        text_color = (0, 0, 0) if (accent_color[0] * 0.299 + accent_color[1] * 0.587 + accent_color[2] * 0.114) > 160 else (255, 255, 255)
        draw.text((badge_x, badge_y), glyph, font=f_glyph, fill=text_color, anchor="mm")

    # Name & Ticker
    f_title = _get_font(FONT_BOLD, 26 * scale)
    f_sub = _get_font(FONT_REGULAR, 15 * scale)
    coin_title = theme["name"] if name in ["", symbol] else name
    draw.text((badge_x + badge_r + 16 * scale, badge_y - 12 * scale), coin_title, font=f_title, fill=(255, 255, 255), anchor="lm")
    draw.text((badge_x + badge_r + 16 * scale, badge_y + 14 * scale), f"{symbol.upper()} / USD · 24h", font=f_sub, fill=(138, 143, 156), anchor="lm")

    # Right side: Price & 24h badge
    f_price = _get_font(FONT_BOLD, 32 * scale)
    f_badge = _get_font(FONT_BOLD, 15 * scale)
    price_str = f"${format_val_str(price_usd, 2)}"
    draw.text((w - 52 * scale, badge_y - 12 * scale), price_str, font=f_price, fill=(255, 255, 255), anchor="rm")

    sign = "+" if is_positive else ""
    change_str = f"{sign}{change_24h:.2f}%"
    badge_text_w = len(change_str) * 11 * scale + 24 * scale
    pill_x2 = w - 52 * scale
    pill_x1 = pill_x2 - badge_text_w
    pill_y1 = badge_y + 4 * scale
    pill_y2 = pill_y1 + 24 * scale
    draw.rounded_rectangle([(pill_x1, pill_y1), (pill_x2, pill_y2)], radius=12 * scale, fill=trend_color)
    draw.text(((pill_x1 + pill_x2) / 2, (pill_y1 + pill_y2) / 2), change_str, font=f_badge, fill=(255, 255, 255), anchor="mm")

    # 3. Chart area
    chart_x1 = 52 * scale
    chart_x2 = w - 52 * scale
    chart_y1 = 125 * scale
    chart_y2 = 330 * scale
    chart_w = chart_x2 - chart_x1
    chart_h = chart_y2 - chart_y1

    if not chart_points or len(chart_points) < 2:
        chart_points = [price_usd * (1 + math.sin(i * 0.4) * 0.015) for i in range(24)]

    min_p = min(chart_points)
    max_p = max(chart_points)
    p_range = max_p - min_p or 1.0

    # Draw horizontal grid guide lines (3 lines)
    f_mono = _get_font(FONT_MONO, 12 * scale)
    grid_steps = [max_p, (max_p + min_p) / 2, min_p]
    for p_val in grid_steps:
        norm_y = chart_y2 - ((p_val - min_p) / p_range) * chart_h
        draw.line([(chart_x1, norm_y), (chart_x2, norm_y)], fill=(28, 32, 40), width=1 * scale)
        label_str = f"${format_val_str(p_val, 2)}"
        draw.text((chart_x2 - 4 * scale, norm_y - 8 * scale), label_str, font=f_mono, fill=(90, 95, 108), anchor="rb")

    # Generate coordinate path
    coords = []
    n = len(chart_points)
    for i, pt in enumerate(chart_points):
        cx = chart_x1 + i * (chart_w / (n - 1))
        cy = chart_y2 - ((pt - min_p) / p_range) * chart_h
        coords.append((cx, cy))

    # Area gradient fill
    gradient_img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    g_draw = ImageDraw.Draw(gradient_img)
    poly = [(chart_x1, chart_y2)] + coords + [(chart_x2, chart_y2)]
    g_draw.polygon(poly, fill=(*trend_color, 25))
    img.alpha_composite(gradient_img)

    # Smooth main chart line
    draw.line(coords, fill=trend_color, width=3 * scale)

    # Glowing end dot on current price
    last_x, last_y = coords[-1]
    dot_r = 6 * scale
    draw.ellipse([(last_x - dot_r, last_y - dot_r), (last_x + dot_r, last_y + dot_r)], fill=trend_color)
    draw.ellipse([(last_x - 3 * scale, last_y - 3 * scale), (last_x + 3 * scale, last_y + 3 * scale)], fill=(255, 255, 255))

    # 4. Bottom Metric Strip
    f_label = _get_font(FONT_REGULAR, 13 * scale)
    f_val = _get_font(FONT_BOLD, 14 * scale)

    bottom_y = 370 * scale
    col_w = chart_w / 4

    metrics = [
        ("24h High", f"${format_val_str(high_24h or max_p, 2)}"),
        ("24h Low", f"${format_val_str(low_24h or min_p, 2)}"),
        ("24h Volume", f"${format_val_str(volume_usd, 0)}" if volume_usd else "—"),
        ("Статус", "Live Market · 24h")
    ]

    for i, (m_lbl, m_val) in enumerate(metrics):
        cx = chart_x1 + i * col_w
        draw.text((cx, bottom_y - 8 * scale), m_lbl, font=f_label, fill=(120, 125, 138), anchor="lm")
        draw.text((cx, bottom_y + 12 * scale), m_val, font=f_val, fill=(230, 233, 240), anchor="lm")

    # Resample down 2x with Lanczos
    final_img = img.resize((800, 420), Image.Resampling.LANCZOS).convert("RGB")
    buf = io.BytesIO()
    final_img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


def render_converter_card(
    from_amount: float,
    from_symbol: str,
    to_amount: float,
    to_symbol: str,
    exchange_rate: float,
    other_rates: Dict[str, float]
) -> bytes:
    """
    Renders an 800x420 currency exchange card with real icons / fiat badges,
    geometric arrow, and secondary rate grid.
    """
    scale = 2
    w, h = 800 * scale, 420 * scale
    img = Image.new("RGBA", (w, h), color=(15, 17, 21, 255))
    draw = ImageDraw.Draw(img)

    # 1. Card border
    draw.rounded_rectangle([(16, 16), (w - 16, h - 16)], radius=28 * scale, outline=(36, 40, 50), width=2 * scale)

    # 2. Header
    f_title = _get_font(FONT_BOLD, 22 * scale)
    f_sub = _get_font(FONT_REGULAR, 14 * scale)
    draw.text((50 * scale, 45 * scale), "КОНВЕРТЕР ВАЛЮТ", font=f_title, fill=(255, 255, 255), anchor="lm")
    draw.text((50 * scale, 75 * scale), "Актуальный биржевой курс · Live Exchange", font=f_sub, fill=(138, 143, 156), anchor="lm")

    # 3. Two exchange boxes: Left (FROM) and Right (TO)
    box_w = 320 * scale
    box_h = 100 * scale
    box_y = 115 * scale
    box_l_x = 50 * scale
    box_r_x = w - 50 * scale - box_w

    # Left box
    draw.rounded_rectangle([(box_l_x, box_y), (box_l_x + box_w, box_y + box_h)], radius=16 * scale, fill=(24, 27, 34), outline=(42, 46, 58), width=1 * scale)
    # Right box
    draw.rounded_rectangle([(box_r_x, box_y), (box_r_x + box_w, box_y + box_h)], radius=16 * scale, fill=(24, 27, 34), outline=(42, 46, 58), width=1 * scale)

    # Render left icon / badge (crypto icon if available, else fiat badge)
    icon_sz = 52 * scale
    icon_l_y = box_y + (box_h - icon_sz) // 2
    icon_l = get_crypto_icon(from_symbol, icon_sz) or get_fiat_badge(from_symbol, icon_sz)
    img.paste(icon_l, (box_l_x + 16 * scale, icon_l_y), icon_l)

    # Render right icon / badge
    icon_r_y = box_y + (box_h - icon_sz) // 2
    icon_r = get_crypto_icon(to_symbol, icon_sz) or get_fiat_badge(to_symbol, icon_sz)
    img.paste(icon_r, (box_r_x + 16 * scale, icon_r_y), icon_r)

    # Drawn geometric arrow in the middle
    mid_x, mid_y = w / 2, box_y + box_h / 2
    arrow_color = (0, 152, 234)
    draw.line([(mid_x - 16 * scale, mid_y), (mid_x + 12 * scale, mid_y)], fill=arrow_color, width=4 * scale)
    draw.polygon([(mid_x + 18 * scale, mid_y), (mid_x + 6 * scale, mid_y - 9 * scale), (mid_x + 6 * scale, mid_y + 9 * scale)], fill=arrow_color)

    # Left box text
    f_amt = _get_font(FONT_BOLD, 24 * scale)
    f_cur = _get_font(FONT_BOLD, 14 * scale)
    text_l_x = box_l_x + 16 * scale + icon_sz + 14 * scale
    from_amt_str = format_val_str(from_amount, 2)
    draw.text((text_l_x, box_y + 35 * scale), from_amt_str, font=f_amt, fill=(255, 255, 255), anchor="lm")
    draw.text((text_l_x, box_y + 70 * scale), from_symbol.upper(), font=f_cur, fill=(0, 152, 234), anchor="lm")

    # Right box text
    to_amt_str = format_val_str(to_amount, 2)
    to_fiat_sign = FIAT_SYMBOLS.get(to_symbol.upper(), "")
    right_display = f"{to_fiat_sign}{to_amt_str}" if to_fiat_sign and len(to_fiat_sign) <= 2 else to_amt_str
    text_r_x = box_r_x + 16 * scale + icon_sz + 14 * scale
    draw.text((text_r_x, box_y + 35 * scale), right_display, font=f_amt, fill=(16, 185, 129), anchor="lm")
    draw.text((text_r_x, box_y + 70 * scale), to_symbol.upper(), font=f_cur, fill=(138, 143, 156), anchor="lm")

    # 4. Exchange rate line
    f_rate = _get_font(FONT_BOLD, 16 * scale)
    rate_str = f"1 {from_symbol.upper()} ≈ {format_val_str(exchange_rate, 4)} {to_symbol.upper()}"
    draw.text((w / 2, 245 * scale), rate_str, font=f_rate, fill=(240, 243, 250), anchor="mm")

    # 5. Secondary rates grid at the bottom (4 tiles)
    f_tile_lbl = _get_font(FONT_REGULAR, 12 * scale)
    f_tile_val = _get_font(FONT_BOLD, 14 * scale)

    tile_y = 280 * scale
    tile_h = 80 * scale
    tile_w = (w - 100 * scale - 36 * scale) / 4

    items = list(other_rates.items())[:4]
    for i, (sym, val) in enumerate(items):
        tx = int(50 * scale + i * (tile_w + 12 * scale))
        draw.rounded_rectangle([(tx, tile_y), (tx + int(tile_w), tile_y + tile_h)], radius=12 * scale, fill=(20, 22, 28), outline=(32, 36, 46), width=1 * scale)
        draw.text((tx + 16 * scale, tile_y + 24 * scale), f"в {sym}", font=f_tile_lbl, fill=(120, 125, 138), anchor="lm")
        sign = FIAT_SYMBOLS.get(sym, "")
        val_display = f"{sign}{format_val_str(val, 2)}" if sign and len(sign) <= 2 else f"{format_val_str(val, 2)} {sym}"
        draw.text((tx + 16 * scale, tile_y + 54 * scale), val_display, font=f_tile_val, fill=(255, 255, 255), anchor="lm")

    # Resample 2x
    final_img = img.resize((800, 420), Image.Resampling.LANCZOS).convert("RGB")
    buf = io.BytesIO()
    final_img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


def render_gas_card(gas_data: Dict[str, Any]) -> bytes:
    """
    Renders an 800x420 network fee comparison card for TON, Bitcoin, and Ethereum.
    """
    scale = 2
    w, h = 800 * scale, 420 * scale
    img = Image.new("RGBA", (w, h), color=(15, 17, 21, 255))
    draw = ImageDraw.Draw(img)

    # 1. Card border
    draw.rounded_rectangle([(16, 16), (w - 16, h - 16)], radius=28 * scale, outline=(36, 40, 50), width=2 * scale)

    # 2. Header
    f_title = _get_font(FONT_BOLD, 22 * scale)
    f_sub = _get_font(FONT_REGULAR, 14 * scale)
    draw.text((50 * scale, 45 * scale), "КОМИССИИ СЕТЕЙ (GAS)", font=f_title, fill=(255, 255, 255), anchor="lm")
    draw.text((50 * scale, 75 * scale), "Актуальные комиссии блокчейнов · Live Network Gas", font=f_sub, fill=(138, 143, 156), anchor="lm")

    # 3. Three blockchain columns: TON, BTC, ETH
    col_w = (w - 100 * scale - 24 * scale) / 3
    col_y = 115 * scale
    col_h = 245 * scale

    chains = [
        {
            "name": "TON Network", "sym": "TON",
            "stat1_lbl": "Базовый перевод", "stat1_val": gas_data.get("ton_tx", "~0.005 TON"),
            "stat2_lbl": "USDT / Jetton", "stat2_val": gas_data.get("ton_jetton", "~0.03 TON"),
            "usd_lbl": "Стоимость", "usd_val": gas_data.get("ton_usd", "< $0.02"),
            "speed": "⚡ Мгновенно (< 3 сек)"
        },
        {
            "name": "Bitcoin Mempool", "sym": "BTC",
            "stat1_lbl": "Fastest Priority", "stat1_val": f"{gas_data.get('btc_fast', 3)} sat/vB",
            "stat2_lbl": "Standard (30 min)", "stat2_val": f"{gas_data.get('btc_med', 2)} sat/vB",
            "usd_lbl": "Перевод (~140 vB)", "usd_val": gas_data.get("btc_usd", "~$0.35"),
            "speed": "⏳ Следующий блок"
        },
        {
            "name": "Ethereum Mainnet", "sym": "ETH",
            "stat1_lbl": "Base Gas", "stat1_val": f"{gas_data.get('eth_gwei', 0.6):.1f} Gwei",
            "stat2_lbl": "ETH Transfer", "stat2_val": gas_data.get("eth_tx_usd", "~$0.05"),
            "usd_lbl": "Uniswap Swap", "usd_val": gas_data.get("eth_swap_usd", "~$0.45"),
            "speed": "🟢 Низкая нагрузка"
        }
    ]

    f_col_title = _get_font(FONT_BOLD, 17 * scale)
    f_lbl = _get_font(FONT_REGULAR, 12 * scale)
    f_val = _get_font(FONT_BOLD, 15 * scale)
    f_spd = _get_font(FONT_REGULAR, 12 * scale)

    for i, c in enumerate(chains):
        cx = int(50 * scale + i * (col_w + 12 * scale))
        draw.rounded_rectangle([(cx, col_y), (cx + int(col_w), col_y + col_h)], radius=16 * scale, fill=(24, 27, 34), outline=(42, 46, 58), width=1 * scale)

        # Icon
        icon_sz = 36 * scale
        icon_img = get_crypto_icon(c["sym"], icon_sz)
        if icon_img:
            img.paste(icon_img, (cx + 18 * scale, int(col_y + 18 * scale)), icon_img)

        draw.text((cx + 18 * scale + icon_sz + 12 * scale, col_y + 36 * scale), c["name"], font=f_col_title, fill=(255, 255, 255), anchor="lm")

        # Stats
        draw.text((cx + 18 * scale, col_y + 85 * scale), c["stat1_lbl"], font=f_lbl, fill=(120, 125, 138), anchor="lm")
        draw.text((cx + 18 * scale, col_y + 108 * scale), c["stat1_val"], font=f_val, fill=(240, 243, 250), anchor="lm")

        draw.text((cx + 18 * scale, col_y + 142 * scale), c["stat2_lbl"], font=f_lbl, fill=(120, 125, 138), anchor="lm")
        draw.text((cx + 18 * scale, col_y + 165 * scale), c["stat2_val"], font=f_val, fill=(16, 185, 129), anchor="lm")

        draw.text((cx + 18 * scale, col_y + 198 * scale), c["usd_lbl"], font=f_lbl, fill=(120, 125, 138), anchor="lm")
        draw.text((cx + 18 * scale, col_y + 221 * scale), c["usd_val"], font=f_val, fill=(0, 152, 234), anchor="lm")

    final_img = img.resize((800, 420), Image.Resampling.LANCZOS).convert("RGB")
    buf = io.BytesIO()
    final_img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()
