"""Dolphin Memory Engine-compatible API backed by Android's Dolphin GDB transport.

The public surface mirrors ``dolphin-memory-engine`` so upstream Archipelago
clients can import it unchanged. The foreground Android bridge service installs
the single persistent Kotlin backend with :func:`configure_backend`.
"""

from __future__ import annotations

from enum import IntEnum
import struct
import threading
from typing import Any, Iterable


class DolphinStatus(IntEnum):
    hooked = 0
    notRunning = 1
    noEmu = 2
    unHooked = 3


_backend: Any | None = None
_lock = threading.RLock()


def configure_backend(backend: Any | None) -> None:
    """Install the service-owned transport; not part of the desktop API."""
    global _backend
    with _lock:
        _backend = backend


def _require_backend() -> Any:
    if _backend is None:
        raise RuntimeError("Dolphin GDB backend is unavailable")
    return _backend


def hook() -> None:
    with _lock:
        try:
            _require_backend().hook()
        except Exception:
            # Desktop DME reports failure through is_hooked rather than hook.
            return None


def un_hook() -> None:
    with _lock:
        backend = _backend
        if backend is not None:
            backend.unHook()


def is_hooked() -> bool:
    with _lock:
        backend = _backend
        if backend is None:
            return False
        try:
            return bool(backend.isHooked())
        except Exception:
            return False


def assert_hooked() -> None:
    if not is_hooked():
        raise RuntimeError("not hooked")


def get_status() -> DolphinStatus:
    with _lock:
        backend = _backend
        if backend is None:
            return DolphinStatus.notRunning
        try:
            if backend.isHooked():
                return DolphinStatus.hooked
            if backend.isSocketConnected():
                return DolphinStatus.unHooked
        except Exception:
            pass
        return DolphinStatus.notRunning


def read_bytes(console_address: int, size: int) -> bytes:
    assert_hooked()
    if size < 0:
        raise ValueError("size must not be negative")
    try:
        return bytes(_require_backend().readBytes(int(console_address), int(size)))
    except Exception as error:
        raise RuntimeError(f"Could not read memory at {console_address}: {error}") from error


def write_bytes(console_address: int, memory: bytes | bytearray | memoryview) -> None:
    assert_hooked()
    data = bytes(memory)
    try:
        _require_backend().writeBytes(int(console_address), data)
    except Exception as error:
        raise RuntimeError(f"Could not write memory at {console_address}: {error}") from error


def read_byte(console_address: int) -> int:
    return read_bytes(console_address, 1)[0]


def read_word(console_address: int) -> int:
    return int.from_bytes(read_bytes(console_address, 4), byteorder="big", signed=False)


def read_float(console_address: int) -> float:
    return struct.unpack(">f", read_bytes(console_address, 4))[0]


def read_double(console_address: int) -> float:
    return struct.unpack(">d", read_bytes(console_address, 8))[0]


def write_byte(console_address: int, value: int) -> None:
    if not 0 <= int(value) <= 0xFF:
        raise OverflowError("unsigned byte integer is outside of the allowed range")
    write_bytes(console_address, bytes([int(value)]))


def write_word(console_address: int, value: int) -> None:
    write_bytes(console_address, int(value).to_bytes(4, byteorder="big", signed=False))


def write_float(console_address: int, value: float) -> None:
    write_bytes(console_address, struct.pack(">f", float(value)))


def write_double(console_address: int, value: float) -> None:
    write_bytes(console_address, struct.pack(">d", float(value)))


def _valid_console_address(address: int) -> bool:
    # MEM1 plus Wii MEM2. The GDB transport performs the authoritative check.
    return 0x80000000 <= address < 0x81800000 or 0x90000000 <= address < 0x94000000


def follow_pointers(console_address: int, pointer_offsets: Iterable[int]) -> int:
    assert_hooked()
    resolved = int(console_address)
    for offset in pointer_offsets:
        resolved = read_word(resolved)
        if not _valid_console_address(resolved):
            raise RuntimeError(f"Address {resolved} is not valid")
        resolved += int(offset)
    return resolved


class MemWatch:
    """Small compatibility implementation of DME's public MemWatch helper."""

    def __init__(self, label: str, console_address: int, is_pointer: bool):
        self.label = str(label)
        self.console_address = int(console_address)
        self.is_pointer = bool(is_pointer)
        self.offsets: list[int] = []
        self._value = 0

    def add_offset(self, offset: int) -> None:
        self.offsets.append(int(offset))

    def _address(self) -> int:
        if not self.is_pointer or not self.offsets:
            return self.console_address
        return follow_pointers(self.console_address, self.offsets)

    def get_value(self) -> int:
        return self._value

    def read_memory_from_ram(self) -> bool:
        try:
            self._value = read_word(self._address())
            return True
        except RuntimeError:
            return False

    def write_memory_from_string(self, value: str) -> bool:
        try:
            parsed = int(value, 10)
            if not -(1 << 31) <= parsed < (1 << 31):
                return False
            write_word(self._address(), parsed & 0xFFFFFFFF)
            return True
        except (RuntimeError, TypeError, ValueError, OverflowError):
            return False


__all__ = [
    "MemWatch",
    "assert_hooked",
    "follow_pointers",
    "get_status",
    "hook",
    "is_hooked",
    "read_byte",
    "read_bytes",
    "read_double",
    "read_float",
    "read_word",
    "un_hook",
    "write_byte",
    "write_bytes",
    "write_double",
    "write_float",
    "write_word",
]
