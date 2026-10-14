from __future__ import annotations

import argparse
import gzip
import io
import json
import struct
import zlib
from pathlib import Path
from typing import TypeAlias, cast


Tag: TypeAlias = int | float | str | bytes | list["Tag"] | dict[str, "Tag"]


class NbtReader:
    """Connector đọc NBT từ region đã đóng; từ chối định dạng ngoài phạm vi."""

    def __init__(self, data: bytes) -> None:
        self.stream: io.BytesIO = io.BytesIO(data)

    def take(self, size: int) -> bytes:
        value: bytes = self.stream.read(size)
        if len(value) != size:
            raise ValueError(f"NBT thiếu byte offset={self.stream.tell()} size={size}")
        return value

    def number(self, fmt: str) -> int | float:
        return cast(int | float, struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0])

    def string(self) -> str:
        return self.take(int(self.number(">H"))).decode("utf-8")

    def payload(self, kind: int) -> Tag:
        if kind in (1, 2, 3, 4, 5, 6):
            return self.number({1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}[kind])
        if kind == 7:
            return self.take(int(self.number(">i")))
        if kind == 8:
            return self.string()
        if kind == 9:
            child: int = int(self.number(">B"))
            count: int = int(self.number(">i"))
            return [self.payload(child) for _ in range(count)]
        if kind == 10:
            result: dict[str, Tag] = {}
            while True:
                child = int(self.number(">B"))
                if child == 0:
                    return result
                name: str = self.string()
                result[name] = self.payload(child)
        if kind in (11, 12):
            count = int(self.number(">i"))
            return [self.number(">i" if kind == 11 else ">q") for _ in range(count)]
        raise ValueError(f"NBT tag chưa hỗ trợ kind={kind}")

    def root(self) -> dict[str, Tag]:
        if self.number(">B") != 10:
            raise ValueError("NBT root phải compound")
        self.string()
        return cast(dict[str, Tag], self.payload(10))


def chunk(region: Path, x: int, z: int) -> dict[str, Tag]:
    data: bytes = region.read_bytes()
    offset: int = 4 * ((x & 31) + (z & 31) * 32)
    sector: int = int.from_bytes(data[offset:offset + 3], "big")
    if sector == 0:
        raise ValueError(f"Chunk chưa ghi region={region} x={x} z={z}")
    start: int = sector * 4096
    size: int = int.from_bytes(data[start:start + 4], "big")
    kind: int = data[start + 4]
    encoded: bytes = data[start + 5:start + 4 + size]
    if kind == 1:
        decoded: bytes = gzip.decompress(encoded)
    elif kind == 2:
        decoded = zlib.decompress(encoded)
    elif kind == 3:
        decoded = encoded
    else:
        raise ValueError(f"Region compression chưa hỗ trợ kind={kind} path={region}")
    return NbtReader(decoded).root()


def block(root: dict[str, Tag], x: int, y: int, z: int) -> str:
    sections: list[dict[str, Tag]] = cast(list[dict[str, Tag]], root["sections"])
    section: dict[str, Tag] = next(item for item in sections if item["Y"] == y // 16)
    states: dict[str, Tag] = cast(dict[str, Tag], section["block_states"])
    palette: list[Tag] = cast(list[Tag], states["palette"])
    index: int = 0
    if len(palette) > 1:
        bits: int = max(4, (len(palette) - 1).bit_length())
        per_long: int = 64 // bits
        position: int = (y & 15) * 256 + (z & 15) * 16 + (x & 15)
        longs: list[int] = cast(list[int], states["data"])
        index = (longs[position // per_long] >> ((position % per_long) * bits)) & ((1 << bits) - 1)
    value: Tag = palette[index]
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        # NBT 26.3 bọc phần tử chuỗi trong compound khóa rỗng khi list có kiểu hỗn hợp.
        if set(value) == {""}:
            return cast(str, value[""])
        if "id" in value:
            return cast(str, value["id"])
    raise ValueError(f"Palette 26.3 không hợp lệ index={index} value={value}")


def main() -> None:
    parser: argparse.ArgumentParser = argparse.ArgumentParser()
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args: argparse.Namespace = parser.parse_args()
    region: Path = args.runtime / "world/dimensions/minecraft/cv_sky/region/r.0.0.mca"
    root: dict[str, Tag] = chunk(region, 4, 0)
    positions: list[tuple[int, int, int, str]] = (
        [(x, 100, z, "minecraft:cobblestone") for x in range(67, 70) for z in range(3, 6)]
        + [(x, 100, z, "minecraft:cobblestone") for x in range(71, 74) for z in range(7, 10)]
        + [(x, 100, 11, "minecraft:stone_bricks") for x in range(74, 76)]
        + [(76, 100, 11, "minecraft:air")]
        + [(64, 95, 0, "minecraft:dirt")]
    )
    result: dict[str, str] = {}
    for x, y, z, expected in positions:
        actual: str = block(root, x, y, z)
        if actual != expected:
            raise ValueError(f"World sai runtime={args.runtime} xyz={x},{y},{z} expected={expected} actual={actual}")
        result[f"{x},{y},{z}"] = actual
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"WORLD_PASS blocks={len(result)} region={region}")


if __name__ == "__main__":
    main()
