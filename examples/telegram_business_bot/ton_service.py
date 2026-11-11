"""
Asynchronous TON Blockchain Service using TonAPI.
Fetches balances (TON, USDT, Jettons), USD rates, and recent operations.
"""
import asyncio
import time
import aiohttp
from typing import Dict, Any, Optional, List
from ton_utils import normalize_address, raw_to_friendly, shorten_address
from config import config

TON_API_BASE = "https://tonapi.io/v2"
USDT_MASTER_RAW = "0:b113a994b5024a16719f69139328eb759596c38a29f504be652424b067a845cb"


def format_timeago(timestamp: int) -> str:
    """Returns human-friendly relative time in Russian."""
    now = int(time.time())
    diff = max(0, now - timestamp)
    if diff < 60:
        return f"{diff}с назад"
    elif diff < 3600:
        return f"{diff // 60}м назад"
    elif diff < 86400:
        return f"{diff // 3600}ч назад"
    else:
        return f"{diff // 86400}д назад"


class TonService:
    def __init__(self):
        self._session: Optional[aiohttp.ClientSession] = None

    async def get_session(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            headers = {
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TelegramBusinessBot/1.0",
                "Accept": "application/json",
            }
            if config.tonapi_key:
                headers["Authorization"] = f"Bearer {config.tonapi_key}"
            self._session = aiohttp.ClientSession(
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=10)
            )
        return self._session

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()

    async def fetch_json(self, url: str) -> Optional[Dict[str, Any]]:
        session = await self.get_session()
        try:
            async with session.get(url) as resp:
                if resp.status == 200:
                    return await resp.json()
                return None
        except Exception:
            return None

    async def get_ton_rates(self) -> Dict[str, Any]:
        """Fetch TON price in USD and 24h change."""
        url = f"{TON_API_BASE}/rates?tokens=ton&currencies=usd"
        data = await self.fetch_json(url)
        if data and "rates" in data and "TON" in data["rates"]:
            ton_data = data["rates"]["TON"]
            price_usd = ton_data.get("prices", {}).get("USD", 0.0)
            diff_24h = ton_data.get("diff_24h", {}).get("USD", "0%")
            return {"price_usd": float(price_usd), "diff_24h": str(diff_24h)}
        return {"price_usd": 0.0, "diff_24h": "0%"}

    async def get_account(self, address: str) -> Optional[Dict[str, Any]]:
        """Fetch general account info."""
        url = f"{TON_API_BASE}/accounts/{address}"
        return await self.fetch_json(url)

    async def get_jettons(self, address: str) -> List[Dict[str, Any]]:
        """Fetch all Jetton token balances."""
        url = f"{TON_API_BASE}/accounts/{address}/jettons?currencies=usd"
        data = await self.fetch_json(url)
        if data and "balances" in data:
            return data["balances"]
        return []

    async def get_events(self, address: str, limit: int = 4) -> List[Dict[str, Any]]:
        """Fetch recent transaction events."""
        url = f"{TON_API_BASE}/accounts/{address}/events?limit={limit}"
        data = await self.fetch_json(url)
        if data and "events" in data:
            return data["events"]
        return []

    async def get_nfts(self, address: str, limit: int = 6) -> List[Dict[str, Any]]:
        """Fetch NFT items owned by the account."""
        url = f"{TON_API_BASE}/accounts/{address}/nfts?limit={limit}"
        data = await self.fetch_json(url)
        if data and "nft_items" in data:
            return data["nft_items"]
        return []

    async def get_wallet_full_data(self, input_address: str) -> Dict[str, Any]:
        """
        Gathers full wallet information:
        - normalized addresses
        - TON balance & USD value
        - USDT balance & USD value
        - Other token assets
        - NFT items
        - Recent operations
        - Total portfolio value
        """
        raw_addr, uq_addr, eq_addr = normalize_address(input_address)
        if not raw_addr:
            return {"success": False, "error": f"Некорректный адрес кошелька: <code>{input_address}</code>"}

        # Fetch in parallel
        account_task = self.get_account(raw_addr)
        rates_task = self.get_ton_rates()
        jettons_task = self.get_jettons(raw_addr)
        events_task = self.get_events(raw_addr, limit=4)
        nfts_task = self.get_nfts(raw_addr, limit=6)

        account, rates, jettons, events, nfts = await asyncio.gather(
            account_task, rates_task, jettons_task, events_task, nfts_task
        )

        if not account:
            return {
                "success": False,
                "error": f"Не удалось найти кошелек в сети TON.<br/>Адрес: <code>{input_address}</code>"
            }

        resolved_raw = account.get("address", raw_addr)
        if not uq_addr or uq_addr.endswith(".ton"):
            uq_addr = raw_to_friendly(resolved_raw, bounceable=False)
            eq_addr = raw_to_friendly(resolved_raw, bounceable=True)

        nano_balance = int(account.get("balance", 0))
        ton_balance = nano_balance / 1e9
        ton_price_usd = rates.get("price_usd", 0.0)
        ton_diff_24h = rates.get("diff_24h", "0%")
        ton_value_usd = ton_balance * ton_price_usd

        usdt_balance = 0.0
        usdt_value_usd = 0.0
        other_assets = []

        for item in jettons:
            try:
                jetton_meta = item.get("jetton", {})
                symbol = jetton_meta.get("symbol", "").strip()
                name = jetton_meta.get("name", symbol)
                decimals = int(jetton_meta.get("decimals", 9))
                raw_bal = int(item.get("balance", 0))
                amount = raw_bal / (10 ** decimals)
                
                price_info = item.get("price", {}).get("prices", {})
                unit_price = float(price_info.get("USD", 0.0))
                val_usd = amount * unit_price

                jetton_addr = jetton_meta.get("address", "")
                is_usdt = (
                    symbol.upper() in ["USDT", "USD₮"] or
                    jetton_addr.lower() == USDT_MASTER_RAW.lower()
                )

                if is_usdt:
                    usdt_balance += amount
                    usdt_value_usd += (val_usd if val_usd > 0 else amount)
                else:
                    if amount > 0:
                        other_assets.append({
                            "symbol": symbol or "TOKEN",
                            "name": name,
                            "amount": amount,
                            "value_usd": val_usd,
                            "price_usd": unit_price
                        })
            except Exception:
                continue

        other_assets.sort(key=lambda x: (x["value_usd"], x["amount"]), reverse=True)
        assets_usd_sum = sum(a["value_usd"] for a in other_assets)
        total_usd = ton_value_usd + usdt_value_usd + assets_usd_sum

        # Parse Recent Events
        parsed_operations = []
        for ev in events:
            try:
                timestamp = ev.get("timestamp", int(time.time()))
                time_ago = format_timeago(timestamp)
                actions = ev.get("actions", [])
                
                for act in actions:
                    act_type = act.get("type")
                    if act_type == "TonTransfer":
                        t_data = act.get("TonTransfer", {})
                        sender = t_data.get("sender", {}).get("address", "")
                        recipient = t_data.get("recipient", {}).get("address", "")
                        amt = int(t_data.get("amount", 0)) / 1e9
                        comment = t_data.get("comment", "")

                        is_incoming = (recipient.lower() == resolved_raw.lower())
                        counterparty = sender if is_incoming else recipient
                        counterparty_friendly = raw_to_friendly(counterparty, bounceable=False)

                        parsed_operations.append({
                            "type": "TON",
                            "is_incoming": is_incoming,
                            "amount": amt,
                            "symbol": "TON",
                            "counterparty": shorten_address(counterparty_friendly),
                            "comment": comment,
                            "time_ago": time_ago,
                            "timestamp": timestamp
                        })
                    elif act_type == "JettonTransfer":
                        j_data = act.get("JettonTransfer", {})
                        sender = j_data.get("sender", {}).get("address", "")
                        recipient = j_data.get("recipient", {}).get("address", "")
                        j_meta = j_data.get("jetton", {})
                        sym = j_meta.get("symbol", "JETTON")
                        dec = int(j_meta.get("decimals", 9))
                        amt = int(j_data.get("amount", 0)) / (10 ** dec)
                        comment = j_data.get("comment", "")

                        is_incoming = (recipient.lower() == resolved_raw.lower())
                        counterparty = sender if is_incoming else recipient
                        counterparty_friendly = raw_to_friendly(counterparty, bounceable=False)

                        parsed_operations.append({
                            "type": "JETTON",
                            "is_incoming": is_incoming,
                            "amount": amt,
                            "symbol": sym,
                            "counterparty": shorten_address(counterparty_friendly),
                            "comment": comment,
                            "time_ago": time_ago,
                            "timestamp": timestamp
                        })
            except Exception:
                continue

        # Parse NFTs
        parsed_nfts = []
        for it in (nfts or []):
            try:
                name = (
                    it.get("metadata", {}).get("name")
                    or it.get("dns")
                    or f"NFT #{it.get('index', '')}"
                )
                col = it.get("collection", {}).get("name")
                parsed_nfts.append({
                    "name": name.strip(),
                    "collection": col.strip() if col else "",
                    "address": it.get("address", "")
                })
            except Exception:
                continue

        parsed_operations.sort(key=lambda x: x["timestamp"], reverse=True)

        return {
            "success": True,
            "raw_address": resolved_raw,
            "uq_address": uq_addr,
            "eq_address": eq_addr,
            "display_address": uq_addr,
            "account_name": account.get("name"),
            "status": account.get("status", "active"),
            "ton_balance": ton_balance,
            "ton_price_usd": ton_price_usd,
            "ton_diff_24h": ton_diff_24h,
            "ton_value_usd": ton_value_usd,
            "usdt_balance": usdt_balance,
            "usdt_value_usd": usdt_value_usd,
            "total_usd": total_usd,
            "assets": other_assets,
            "total_assets_count": len(other_assets),
            "operations": parsed_operations[:4],
            "nfts": parsed_nfts,
            "nfts_count": len(parsed_nfts)
        }


ton_service = TonService()
