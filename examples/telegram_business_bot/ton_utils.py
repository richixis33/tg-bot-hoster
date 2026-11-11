"""
TON Address utilities: CRC16, Base64url, Raw <-> Friendly conversions and validation.
"""
import base64
import re
from typing import Optional, Tuple


def crc16(data: bytes) -> int:
    """Calculate CRC16-CCITT for TON addresses."""
    crc = 0
    for byte in data:
        crc ^= (byte << 8)
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def is_valid_hex_address(addr: str) -> bool:
    """Check if address is raw format (e.g. 0:83dfd5...)"""
    return bool(re.match(r"^-?[0-9]+:[0-9a-fA-F]{64}$", addr))


def is_valid_domain(addr: str) -> bool:
    """Check if input looks like a TON DNS domain (e.g. alice.ton, my-wallet.t.me)."""
    return addr.lower().endswith((".ton", ".t.me"))


def raw_to_friendly(raw: str, bounceable: bool = False, testnet: bool = False) -> str:
    """
    Convert raw TON address (-1:hex or 0:hex) to friendly base64url format.
    bounceable=True produces EQ... (or Ef... for masterchain)
    bounceable=False produces UQ... (or Uf... for masterchain)
    """
    try:
        parts = raw.split(":")
        wc = int(parts[0])
        acc_hex = parts[1]
        acc_bytes = bytes.fromhex(acc_hex)

        tag = 0x11 if bounceable else 0x51
        if testnet:
            tag |= 0x80

        wc_byte = wc & 0xFF
        data = bytes([tag, wc_byte]) + acc_bytes
        crc = crc16(data)
        full = data + crc.to_bytes(2, "big")
        return base64.urlsafe_b64encode(full).decode().rstrip("=")
    except Exception:
        return raw


def friendly_to_raw(addr: str) -> Optional[str]:
    """
    Convert friendly base64url TON address (EQ... or UQ...) to raw 0:hex format.
    Validates CRC16 checksum.
    """
    try:
        addr = addr.strip()
        rem = len(addr) % 4
        if rem:
            addr += "=" * (4 - rem)
        data = base64.urlsafe_b64decode(addr)
        if len(data) != 36:
            return None

        payload = data[:34]
        checksum = int.from_bytes(data[34:], "big")
        if crc16(payload) != checksum:
            return None

        wc = int.from_bytes(payload[1:2], "big", signed=True)
        acc_hex = payload[2:34].hex()
        return f"{wc}:{acc_hex}"
    except Exception:
        return None


def normalize_address(input_str: str) -> Tuple[Optional[str], Optional[str], Optional[str]]:
    """
    Normalizes any address input (EQ, UQ, 0:hex, or URL) into:
    (raw_address, friendly_non_bounceable, friendly_bounceable)
    Returns (None, None, None) if parsing fails.
    """
    cleaned = input_str.strip()
    cleaned = re.sub(r"^https?://(tonviewer\.com|tonscan\.org|tonapi\.io/account)/", "", cleaned)
    cleaned = cleaned.rstrip("/?#& ")

    if is_valid_domain(cleaned):
        return cleaned, cleaned, cleaned

    if is_valid_hex_address(cleaned):
        raw = cleaned
        uq = raw_to_friendly(raw, bounceable=False)
        eq = raw_to_friendly(raw, bounceable=True)
        return raw, uq, eq

    raw = friendly_to_raw(cleaned)
    if raw:
        uq = raw_to_friendly(raw, bounceable=False)
        eq = raw_to_friendly(raw, bounceable=True)
        return raw, uq, eq

    return None, None, None


def shorten_address(addr: str, head: int = 4, tail: int = 4) -> str:
    """Shorten address for clean display: UQC1...8B2N"""
    if len(addr) <= head + tail + 3:
        return addr
    return f"{addr[:head]}...{addr[-tail:]}"
