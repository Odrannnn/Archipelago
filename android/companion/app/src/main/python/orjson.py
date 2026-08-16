"""Small Android-compatible subset of orjson used by the bundled Emerald world."""

from __future__ import annotations

import json


def loads(value):
    if isinstance(value, (bytes, bytearray, memoryview)):
        value = bytes(value).decode("utf-8")
    return json.loads(value)


def dumps(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
