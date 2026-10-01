"""Minimal Shining Mask BLE client. See PROTOCOL.md for the wire format.

    pip install bleak cryptography pillow
    python shiningmask.py scan
    python shiningmask.py --addr AA:BB:CC:DD:EE:FF light 50
    python shiningmask.py --addr AA:BB:CC:DD:EE:FF image 3
    python shiningmask.py --addr AA:BB:CC:DD:EE:FF text "HELLO" --color ff0000
"""

import argparse
import asyncio
import os
import time

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

KEY = bytes.fromhex("32672f7974ad43451d9c6c894a0e8764")

SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb"
CMD_CHAR = "d44bc439-abfd-45a2-b575-925416129600"
NOTIFY_CHAR = "d44bc439-abfd-45a2-b575-925416129601"
DATA_CHAR = "d44bc439-abfd-45a2-b575-92541612960a"
RHYTHM_CHAR = "d44bc439-abfd-45a2-b575-92541612960b"

MANUFACTURER_PREFIX = b"TR\x00J"

DIY_WIDTH, DIY_HEIGHT = 46, 58


def encrypt(block: bytes) -> bytes:
    enc = Cipher(algorithms.AES(KEY), modes.ECB()).encryptor()
    return enc.update(block) + enc.finalize()


def decrypt(block: bytes) -> bytes:
    dec = Cipher(algorithms.AES(KEY), modes.ECB()).decryptor()
    return dec.update(block) + dec.finalize()


def frame(opcode: bytes, *args: int, random_pad: bool = True) -> bytes:
    """Build a 16-byte plaintext command: [len][opcode][args][padding]."""
    body = opcode + bytes(a & 0xFF for a in args)
    if len(body) > 15:
        raise ValueError("command too long")
    pad = os.urandom(15 - len(body)) if random_pad else bytes(15 - len(body))
    return bytes([len(body)]) + body + pad


def chunk_payload(payload: bytes, chunk: int = 18) -> list[bytes]:
    """Split bulk data into [len+1][index][data...] packets for DATA_CHAR."""
    packets = []
    for idx, off in enumerate(range(0, len(payload), chunk)):
        part = payload[off:off + chunk]
        packets.append(bytes([len(part) + 1, idx & 0xFF]) + part + bytes(chunk - len(part)))
    return packets


def render_text(text: str, font_path: str | None = None) -> bytes:
    """Render text into the mask's column bitmap: 2 bytes per column, 16 rows."""
    from PIL import Image, ImageDraw, ImageFont

    font = ImageFont.truetype(font_path, 16) if font_path else ImageFont.load_default()
    width = max(1, int(ImageDraw.Draw(Image.new("1", (1, 1))).textlength(text, font=font)))
    img = Image.new("1", (width, 16), 0)
    ImageDraw.Draw(img).text((0, 0), text, fill=1, font=font)
    out = bytearray()
    for x in range(width):
        hi = lo = 0
        for y in range(16):
            if img.getpixel((x, y)):
                if y < 8:
                    hi |= 0x80 >> y
                else:
                    lo |= 0x80 >> (y - 8)
        out += bytes([hi, lo])
    return bytes(out)


def image_payload(path: str) -> bytes:
    """Resize an image to 46x58 and emit column-major RGB."""
    from PIL import Image

    img = Image.open(path).convert("RGB").resize((DIY_WIDTH, DIY_HEIGHT))
    return bytes(c for x in range(DIY_WIDTH) for y in range(DIY_HEIGHT) for c in img.getpixel((x, y)))


class Mask:
    def __init__(self, client):
        self.client = client
        self.replies: asyncio.Queue[bytes] = asyncio.Queue()

    async def start(self):
        await self.client.start_notify(NOTIFY_CHAR, self._on_notify)

    def _on_notify(self, _sender, data: bytearray):
        self.replies.put_nowait(decrypt(bytes(data[:16])))

    async def expect(self, tag: bytes, timeout: float = 5.0) -> bytes:
        while True:
            reply = await asyncio.wait_for(self.replies.get(), timeout)
            if reply[1:1 + len(tag)] == tag:
                return reply
            if reply[1:6] == b"ERROR":
                raise RuntimeError("mask returned ERROR")

    async def command(self, plaintext: bytes):
        await self.client.write_gatt_char(CMD_CHAR, encrypt(plaintext), response=False)

    async def light(self, value: int):
        await self.command(frame(b"LIGHT", max(1, min(100, value))))

    async def speed(self, value: int):
        await self.command(frame(b"SPEED", max(1, min(100, value))))

    async def image(self, n: int):
        await self.command(frame(b"IMAG", n))

    async def anim(self, n: int):
        await self.command(frame(b"ANIM", n))

    async def mode(self, m: int):
        await self.command(frame(b"MODE", m))

    async def text_color(self, r: int, g: int, b: int, enabled: bool = True):
        await self.command(frame(b"FC", int(enabled), r, g, b))

    async def bg_color(self, r: int, g: int, b: int, enabled: bool = True):
        await self.command(frame(b"BC", int(enabled), r, g, b))

    async def _upload(self, announce: bytes, payload: bytes, commit: bytes):
        await self.command(announce)
        await self.expect(b"DATSOK")
        for packet in chunk_payload(payload):
            await self.client.write_gatt_char(DATA_CHAR, packet, response=False)
            await self.expect(b"REOK")
        await self.command(commit)
        await self.expect(b"DATCPOK")

    async def upload_text(self, bitmap: bytes, rgb: tuple[int, int, int], mode: int = 1, speed: int = 50):
        colors = bytes(rgb) * (len(bitmap) // 2)
        payload = bitmap + colors
        announce = frame(b"DATS", len(payload) >> 8, len(payload), len(bitmap) >> 8, len(bitmap), 0, random_pad=False)
        await self._upload(announce, payload, frame(b"DATCP", random_pad=False))
        await self.mode(mode)
        await asyncio.sleep(0.15)
        await self.speed(speed)

    async def upload_diy(self, payload: bytes, slot: int):
        ts = int(time.time()).to_bytes(4, "big")
        announce = frame(b"DATS", len(payload) >> 8, len(payload), 0, slot, 1, random_pad=False)
        await self._upload(announce, payload, frame(b"DATCP", *ts, random_pad=False))


async def scan(timeout: float):
    from bleak import BleakScanner

    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    for dev, adv in found.values():
        for company, data in adv.manufacturer_data.items():
            raw = company.to_bytes(2, "little") + data
            if raw.startswith(MANUFACTURER_PREFIX):
                print(f"{dev.address}  {dev.name}  rssi={adv.rssi}")


async def run(args):
    from bleak import BleakClient

    async with BleakClient(args.addr) as client:
        mask = Mask(client)
        await mask.start()
        if args.cmd == "light":
            await mask.light(args.value)
        elif args.cmd == "speed":
            await mask.speed(args.value)
        elif args.cmd == "image":
            await mask.image(args.value)
        elif args.cmd == "anim":
            await mask.anim(args.value)
        elif args.cmd == "text":
            rgb = tuple(bytes.fromhex(args.color))
            await mask.upload_text(render_text(args.text, args.font), rgb, args.mode, args.speed)
        elif args.cmd == "diy":
            await mask.upload_diy(image_payload(args.path), args.slot)
            await mask.command(frame(b"PLAY", 1, args.slot, random_pad=False))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--addr", help="mask BLE address")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("scan").add_argument("--timeout", type=float, default=5.0)
    for name in ("light", "speed", "image", "anim"):
        sub.add_parser(name).add_argument("value", type=int)
    t = sub.add_parser("text")
    t.add_argument("text")
    t.add_argument("--color", default="ffffff")
    t.add_argument("--font", help="path to a .ttf rendered at 16px")
    t.add_argument("--mode", type=int, default=1)
    t.add_argument("--speed", type=int, default=50)
    d = sub.add_parser("diy")
    d.add_argument("path")
    d.add_argument("--slot", type=int, default=1)
    args = p.parse_args()

    if args.cmd == "scan":
        asyncio.run(scan(args.timeout))
    else:
        if not args.addr:
            p.error("--addr is required")
        asyncio.run(run(args))


if __name__ == "__main__":
    main()
