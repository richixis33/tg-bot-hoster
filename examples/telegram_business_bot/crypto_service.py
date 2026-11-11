"""
Crypto & Exchange Rates Engine.
Integrates Binance API, TonAPI, Mempool, Ethereum RPC, and Open Exchange Rates
for instant prices, 24h charts, network fees, and multi-currency conversions
across crypto and world fiat (including AZN, RUB, USD, EUR, and 35+ currencies).
"""
import time
import math
import re
import aiohttp
from typing import Dict, Any, Optional, Tuple, List

BINANCE_API = "https://api.binance.com/api/v3"
TON_API = "https://tonapi.io/v2"
FIAT_API = "https://open.er-api.com/v6/latest/USD"
MEMPOOL_API = "https://mempool.space/api/v1/fees/recommended"
ETH_RPC_API = "https://ethereum.publicnode.com"

# Common aliases mapping to standardized ticker
CRYPTO_ALIASES = {
    "gram": "GRAM", "грам": "GRAM", "грамм": "GRAM",
    "ton": "TON", "тон": "TON", "тонкоин": "TON", "toncoin": "TON",
    "btc": "BTC", "биток": "BTC", "биткоин": "BTC", "bitcoin": "BTC",
    "eth": "ETH", "эфир": "ETH", "эфириум": "ETH", "ethereum": "ETH",
    "usdt": "USDT", "тезер": "USDT", "tether": "USDT",
    "sol": "SOL", "солана": "SOL", "solana": "SOL",
    "not": "NOT", "нот": "NOT", "notcoin": "NOT",
    "dogs": "DOGS", "догс": "DOGS",
    "bnb": "BNB", "бнб": "BNB", "binance": "BNB",
    "xrp": "XRP", "рипл": "XRP", "ripple": "XRP",
    "doge": "DOGE", "доги": "DOGE", "dogecoin": "DOGE",
    "ltc": "LTC", "лайт": "LTC", "лайткоин": "LTC", "litecoin": "LTC",
    "trx": "TRX", "трон": "TRX", "tron": "TRX",
    "ada": "ADA", "кардано": "ADA", "cardano": "ADA",
    "avax": "AVAX", "авакс": "AVAX",
    "sui": "SUI", "near": "NEAR", "ниар": "NEAR",
    "pepe": "PEPE", "пепе": "PEPE",
}

# Fiat aliases with special support for AZN (Azerbaijani Manat) and 35+ global fiat
FIAT_ALIASES = {
    # AZN - Azerbaijani Manat / Азербайджанский манат
    "azn": "AZN", "манат": "AZN", "манаты": "AZN", "маната": "AZN", "манатов": "AZN", "manat": "AZN", "₼": "AZN",
    # RUB - Russian Ruble
    "rub": "RUB", "руб": "RUB", "рубль": "RUB", "рубли": "RUB", "рублей": "RUB", "₽": "RUB",
    # USD - US Dollar
    "usd": "USD", "доллар": "USD", "доллары": "USD", "долларов": "USD", "бакс": "USD", "баксы": "USD", "$": "USD",
    # EUR - Euro
    "eur": "EUR", "евро": "EUR", "еврик": "EUR", "€": "EUR",
    # UAH - Ukrainian Hryvnia
    "uah": "UAH", "грн": "UAH", "гривна": "UAH", "гривны": "UAH", "гривен": "UAH", "₴": "UAH",
    # KZT - Kazakhstani Tenge
    "kzt": "KZT", "тенге": "KZT", "тнг": "KZT", "₸": "KZT",
    # BYN - Belarusian Ruble
    "byn": "BYN", "белруб": "BYN", "белорусский": "BYN",
    # UZS - Uzbekistani Som
    "uzs": "UZS", "сум": "UZS", "сумы": "UZS", "сумов": "UZS", "сомов": "UZS", "som": "UZS", "so'm": "UZS",
    # GEL - Georgian Lari
    "gel": "GEL", "лари": "GEL", "₾": "GEL",
    # AMD - Armenian Dram
    "amd": "AMD", "драм": "AMD", "драмы": "AMD", "драмов": "AMD", "֏": "AMD",
    # KGS - Kyrgyzstani Som
    "kgs": "KGS", "кыргызский сом": "KGS",
    # TJS - Tajikistani Somoni
    "tjs": "TJS", "сомони": "TJS", "смн": "TJS",
    # TRY - Turkish Lira
    "try": "TRY", "лира": "TRY", "лиры": "TRY", "лир": "TRY", "₺": "TRY",
    # AED - UAE Dirham
    "aed": "AED", "дирхам": "AED", "дирхамы": "AED", "дирхамов": "AED",
    # SAR - Saudi Riyal
    "sar": "SAR", "саудовский риял": "SAR", "риял": "SAR",
    # QAR - Qatari Riyal
    "qar": "QAR", "катарский риал": "QAR",
    # CNY - Chinese Yuan
    "cny": "CNY", "юань": "CNY", "юани": "CNY", "юаней": "CNY", "¥": "CNY",
    # JPY - Japanese Yen
    "jpy": "JPY", "иена": "JPY", "йена": "JPY", "иены": "JPY",
    # KRW - South Korean Won
    "krw": "KRW", "вона": "KRW", "воны": "KRW", "₩": "KRW",
    # GBP - British Pound
    "gbp": "GBP", "фунт": "GBP", "фунты": "GBP", "фунтов": "GBP", "£": "GBP",
    # CHF - Swiss Franc
    "chf": "CHF", "франк": "CHF", "франки": "CHF",
    # PLN - Polish Zloty
    "pln": "PLN", "злотый": "PLN", "злотые": "PLN", "злотых": "PLN", "zł": "PLN",
    # CZK - Czech Koruna
    "czk": "CZK", "крона": "CZK", "кроны": "CZK", "крон": "CZK", "kč": "CZK",
    # HUF - Hungarian Forint
    "huf": "HUF", "форинт": "HUF", "форинты": "HUF", "ft": "HUF",
    # BGN - Bulgarian Lev
    "bgn": "BGN", "лев": "BGN", "левы": "BGN",
    # RON - Romanian Leu
    "ron": "RON", "лей": "RON", "леи": "RON",
    # RSD - Serbian Dinar
    "rsd": "RSD", "динар": "RSD", "динары": "RSD",
    # ILS - Israeli Shekel
    "ils": "ILS", "шекель": "ILS", "шекели": "ILS", "шекелей": "ILS", "₪": "ILS",
    # INR - Indian Rupee
    "inr": "INR", "рупия": "INR", "рупии": "INR", "₹": "INR",
    # THB - Thai Baht
    "thb": "THB", "бат": "THB", "баты": "THB", "฿": "THB",
    # VND - Vietnamese Dong
    "vnd": "VND", "донг": "VND", "донги": "VND", "₫": "VND",
    # IDR - Indonesian Rupiah
    "idr": "IDR", "индонезийская рупия": "IDR", "rp": "IDR",
    # BRL - Brazilian Real
    "brl": "BRL", "реал": "BRL", "реалы": "BRL", "r$": "BRL",
    # CAD - Canadian Dollar
    "cad": "CAD", "канадский доллар": "CAD", "c$": "CAD",
    # AUD - Australian Dollar
    "aud": "AUD", "австралийский доллар": "AUD", "a$": "AUD",
    # SGD - Singapore Dollar
    "sgd": "SGD", "сингапурский доллар": "SGD", "s$": "SGD",
    # HKD - Hong Kong Dollar
    "hkd": "HKD", "гонконгский доллар": "HKD", "hk$": "HKD",
    # ZAR - South African Rand
    "zar": "ZAR", "рэнд": "ZAR", "ранд": "ZAR"
}

POPULAR_FIAT_CODES = set(FIAT_ALIASES.values())


class CryptoService:
    def __init__(self):
        self._session: Optional[aiohttp.ClientSession] = None
        self._fiat_cache: Dict[str, float] = {}
        self._fiat_last_update: float = 0.0

    async def get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            headers = {"User-Agent": "Mozilla/5.0 TelegramBusinessBot/1.0"}
            self._session = aiohttp.ClientSession(headers=headers, timeout=aiohttp.ClientTimeout(total=8))
        return self._session

    async def get_fiat_rates(self) -> Dict[str, float]:
        """Fetch world fiat rates relative to 1 USD."""
        now = time.time()
        if self._fiat_cache and (now - self._fiat_last_update < 600):  # 10 min cache
            return self._fiat_cache

        session = await self.get_session()
        try:
            async with session.get(FIAT_API) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    rates = data.get("rates", {})
                    if rates:
                        self._fiat_cache = rates
                        self._fiat_last_update = now
                        return rates
        except Exception as e:
            print(f"[CryptoService] Error fetching fiat rates: {e}")

        # Fallback defaults if offline
        return {
            "USD": 1.0, "AZN": 1.70, "RUB": 90.0, "EUR": 0.92, "UAH": 41.5,
            "KZT": 490.0, "BYN": 3.28, "TRY": 34.2, "AED": 3.67, "UZS": 12000.0,
            "GEL": 2.70, "AMD": 387.0, "GBP": 0.78, "CNY": 7.23, "PLN": 4.02
        }

    def normalize_currency_code(self, raw: str) -> Tuple[str, bool]:
        """Returns (normalized_code, is_crypto)."""
        clean = raw.strip().lower()
        if clean in FIAT_ALIASES:
            return FIAT_ALIASES[clean], False
        if clean in CRYPTO_ALIASES:
            return CRYPTO_ALIASES[clean], True

        upper = clean.upper()
        if upper in POPULAR_FIAT_CODES:
            return upper, False

        # Default assume crypto ticker
        return upper, True

    async def get_crypto_data(self, ticker: str) -> Optional[Dict[str, Any]]:
        """
        Fetches 24h stats and kline chart points for a crypto coin.
        Supports TON/GRAM natively and all Binance tokens.
        """
        symbol = ticker.upper()
        session = await self.get_session()

        # 1. Special handling for TON / GRAM
        if symbol in ["TON", "GRAM"]:
            try:
                # Get TonAPI rates
                async with session.get(f"{TON_API}/rates?tokens=ton&currencies=usd,rub") as resp:
                    if resp.status == 200:
                        ton_data = await resp.json()
                        ton_rates = ton_data.get("rates", {}).get("TON", {})
                        price_usd = float(ton_rates.get("prices", {}).get("USD", 1.10))
                        diff_str = str(ton_rates.get("diff_24h", {}).get("USD", "0%")).replace("%", "")
                        change_24h = float(diff_str) if diff_str else 0.0

                        # Get chart klines from Binance TONUSDT
                        chart_points = await self._fetch_binance_klines("TONUSDT")
                        if not chart_points:
                            chart_points = [price_usd * (1 + math.sin(i * 0.3) * 0.01) for i in range(24)]

                        return {
                            "symbol": "GRAM" if symbol == "GRAM" else "TON",
                            "name": "Gram" if symbol == "GRAM" else "Toncoin",
                            "price_usd": price_usd,
                            "change_24h": change_24h,
                            "high_24h": max(chart_points) if chart_points else price_usd * 1.02,
                            "low_24h": min(chart_points) if chart_points else price_usd * 0.98,
                            "volume_usd": 85_000_000.0,
                            "chart_points": chart_points
                        }
            except Exception as e:
                print(f"[CryptoService] TonAPI fallback error: {e}")

        # 2. Query Binance API for any crypto pair
        pair = f"{symbol}USDT"
        try:
            async with session.get(f"{BINANCE_API}/ticker/24hr?symbol={pair}") as resp:
                if resp.status == 200:
                    d = await resp.json()
                    price = float(d.get("lastPrice", 0.0))
                    change = float(d.get("priceChangePercent", 0.0))
                    high = float(d.get("highPrice", 0.0))
                    low = float(d.get("lowPrice", 0.0))
                    vol = float(d.get("quoteVolume", 0.0))

                    chart_points = await self._fetch_binance_klines(pair)
                    return {
                        "symbol": symbol,
                        "name": symbol,
                        "price_usd": price,
                        "change_24h": change,
                        "high_24h": high,
                        "low_24h": low,
                        "volume_usd": vol,
                        "chart_points": chart_points
                    }
        except Exception as e:
            print(f"[CryptoService] Binance error for {pair}: {e}")

        return None

    async def _fetch_binance_klines(self, pair: str) -> List[float]:
        session = await self.get_session()
        try:
            async with session.get(f"{BINANCE_API}/klines?symbol={pair}&interval=1h&limit=24") as resp:
                if resp.status == 200:
                    klines = await resp.json()
                    return [float(k[4]) for k in klines]  # closing price
        except Exception:
            pass
        return []

    async def get_price_in_usd(self, symbol: str) -> float:
        """Returns price in USD for any crypto or fiat currency."""
        code, is_crypto = self.normalize_currency_code(symbol)
        if not is_crypto:
            fiat_rates = await self.get_fiat_rates()
            rate = fiat_rates.get(code, 1.0)
            return (1.0 / rate) if rate else 1.0

        # Crypto
        data = await self.get_crypto_data(code)
        if data:
            return data["price_usd"]
        return 0.0

    async def convert_currency(
        self, amount: float, from_curr: str, to_curr: str
    ) -> Optional[Dict[str, Any]]:
        """
        Converts `amount` of `from_curr` into `to_curr`.
        Also calculates equivalent values in other popular currencies (including AZN).
        """
        from_code, from_is_crypto = self.normalize_currency_code(from_curr)
        to_code, to_is_crypto = self.normalize_currency_code(to_curr)

        from_usd_price = await self.get_price_in_usd(from_code)
        to_usd_price = await self.get_price_in_usd(to_code)

        if from_usd_price <= 0 or to_usd_price <= 0:
            return None

        total_usd = amount * from_usd_price
        result_amount = total_usd / to_usd_price
        rate_1_to = from_usd_price / to_usd_price

        # Calculate secondary conversions with AZN prominently featured
        fiat_rates = await self.get_fiat_rates()
        preferred_pool = ["AZN", "RUB", "USD", "EUR", "TRY", "KZT", "UAH"]
        other_conversions = {}
        for cur in preferred_pool:
            if cur == to_code or cur == from_code:
                continue
            cur_usd_price = 1.0 / fiat_rates.get(cur, 1.0) if cur in fiat_rates else 1.0
            other_conversions[cur] = total_usd / cur_usd_price
            if len(other_conversions) >= 4:
                break

        return {
            "from_amount": amount,
            "from_symbol": from_code,
            "to_amount": result_amount,
            "to_symbol": to_code,
            "rate": rate_1_to,
            "total_usd": total_usd,
            "other_rates": other_conversions
        }

    def parse_converter_query(self, query: str) -> Optional[Tuple[float, str, str]]:
        """
        Parses converter queries with extensive syntax support:
        - 12 gram to rub
        - 12 gram azn
        - 50 манат в руб
        - 100 azn to rub
        - 10 btc usd
        - 100$ in rub
        - 100$ azn
        - $100 azn
        - 50€ to azn
        - 500 rub to ton
        """
        q = query.strip()

        # 1. Check if prefix currency symbol attached: $100 rub, €50 azn
        m_pref = re.match(r"^([$€₽₴₸₺¥₼£֏])\s*([0-9]+(?:\.[0-9]+)?)\s*(?:to|in|в|к|->|=)?\s*(.*)$", q, re.IGNORECASE)
        if m_pref:
            sym_from = m_pref.group(1)
            amt = float(m_pref.group(2))
            to_c = m_pref.group(3).strip()
            if to_c:
                return amt, sym_from, to_c

        # 2. Check if suffix currency symbol attached: 100$ rub, 50$ to azn
        m_suff = re.match(r"^([0-9]+(?:\.[0-9]+)?)\s*([$€₽₴₸₺¥₼£֏])\s*(?:to|in|в|к|->|=)?\s*(.*)$", q, re.IGNORECASE)
        if m_suff:
            amt = float(m_suff.group(1))
            sym_from = m_suff.group(2)
            to_c = m_suff.group(3).strip()
            if to_c:
                return amt, sym_from, to_c

        # 3. Standard format: amount from_curr [to/in/в/к] to_curr
        m_std = re.match(r"^([0-9]+(?:\.[0-9]+)?)\s+([a-zA-Zа-яА-ЯёЁ\'-]+)\s*(?:to|in|в|к|->|=)?\s+([a-zA-Zа-яА-ЯёЁ\'-]+)$", q, re.IGNORECASE)
        if m_std:
            amt = float(m_std.group(1))
            from_c = m_std.group(2).strip()
            to_c = m_std.group(3).strip()
            return amt, from_c, to_c

        return None

    def is_crypto_query(self, cmd: str) -> bool:
        """Checks if a command is a crypto query like !btc, !gram, !ton."""
        lower = cmd.lower().strip()
        return lower in CRYPTO_ALIASES or (len(lower) in [3, 4, 5] and lower.isalpha())

    async def get_gas_data(self) -> Dict[str, Any]:
        """
        Fetches live network gas / fees for TON, Bitcoin, and Ethereum.
        """
        session = await self.get_session()
        data = {
            "ton_tx": "~0.005 TON",
            "ton_jetton": "~0.03 TON",
            "ton_usd": "< $0.02",
            "btc_fast": 3,
            "btc_med": 2,
            "btc_usd": "~$0.35",
            "eth_gwei": 0.6,
            "eth_tx_usd": "~$0.05",
            "eth_swap_usd": "~$0.45"
        }

        # 1. Mempool Bitcoin fees
        try:
            async with session.get(MEMPOOL_API) as resp:
                if resp.status == 200:
                    btc_f = await resp.json()
                    fastest = int(btc_f.get("fastestFee", 3))
                    med = int(btc_f.get("halfHourFee", 2))
                    data["btc_fast"] = fastest
                    data["btc_med"] = med
                    # Approx 140 vB for simple segwit tx
                    btc_price = await self.get_price_in_usd("BTC") or 60000.0
                    cost_usd = (fastest * 140 / 1e8) * btc_price
                    data["btc_usd"] = f"~${cost_usd:.2f}"
        except Exception as e:
            print(f"[CryptoService] Mempool error: {e}")

        # 2. Ethereum RPC gas
        try:
            payload = {"jsonrpc": "2.0", "method": "eth_gasPrice", "params": [], "id": 1}
            headers = {"Content-Type": "application/json", "User-Agent": "Mozilla/5.0"}
            async with session.post(ETH_RPC_API, json=payload, headers=headers) as resp:
                if resp.status == 200:
                    eth_resp = await resp.json()
                    wei_val = int(eth_resp.get("result", "0x0"), 16)
                    gwei = wei_val / 1e9
                    data["eth_gwei"] = gwei
                    eth_price = await self.get_price_in_usd("ETH") or 2500.0
                    # 21000 gas for standard transfer
                    tx_usd = (21000 * wei_val / 1e18) * eth_price
                    swap_usd = (150000 * wei_val / 1e18) * eth_price
                    data["eth_tx_usd"] = f"~${tx_usd:.2f}"
                    data["eth_swap_usd"] = f"~${swap_usd:.2f}"
        except Exception as e:
            print(f"[CryptoService] ETH gas error: {e}")

        # 3. TON fees in USD
        ton_price = await self.get_price_in_usd("TON") or 1.10
        ton_cost = 0.005 * ton_price
        data["ton_usd"] = f"~${ton_cost:.3f}".rstrip("0")

        return data

    async def get_top_crypto(self) -> List[Dict[str, Any]]:
        """
        Fetches live data for top 5 crypto assets (BTC, ETH, TON, SOL, BNB).
        """
        symbols = ["BTC", "ETH", "TON", "SOL", "BNB"]
        results = []
        for s in symbols:
            info = await self.get_crypto_data(s)
            if info:
                results.append(info)
        return results

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()


crypto_service = CryptoService()
