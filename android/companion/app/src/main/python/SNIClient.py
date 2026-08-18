"""Android transport port of Archipelago's desktop SNI client primitives.

The upstream game handlers import this module directly.  Context/device state
and write-buffer semantics intentionally mirror desktop SNIClient.py; only the
wire transport is replaced by the Kotlin RetroArch Network Commands adapter.
"""

from __future__ import annotations

import enum


class DeathState(enum.IntEnum):
    killing_player = 1
    alive = 2
    dead = 3


class SNESState(enum.IntEnum):
    SNES_DISCONNECTED = 0
    SNES_CONNECTING = 1
    SNES_CONNECTED = 2
    SNES_ATTACHED = 3


def snes_attached(ctx) -> None:
    """Apply the state transition performed after desktop SNI Attach."""
    ctx.snes_state = SNESState.SNES_ATTACHED
    ctx.snes_write_buffer = []
    ctx.rom = None


def snes_disconnected(ctx) -> None:
    """Apply the cleanup from the desktop SNI receive loop's finally block."""
    ctx.snes_state = SNESState.SNES_DISCONNECTED
    ctx.snes_write_buffer = []
    ctx.hud_message_queue = []
    ctx.rom = None


async def snes_read(ctx, address: int, size: int) -> bytes | None:
    if ctx.snes_state != SNESState.SNES_ATTACHED:
        return None
    try:
        return bytes(ctx.backend.read(int(address), int(size)))
    except Exception:
        ctx.emulator_lifecycle.note_io_failure()
        return None


def snes_buffered_write(ctx, address: int, data: bytes) -> None:
    address = int(address)
    data = bytes(data)
    if ctx.snes_write_buffer and (
        ctx.snes_write_buffer[-1][0] + len(ctx.snes_write_buffer[-1][1]) == address
    ):
        previous_address, previous_data = ctx.snes_write_buffer[-1]
        ctx.snes_write_buffer[-1] = (previous_address, previous_data + data)
    else:
        ctx.snes_write_buffer.append((address, data))


async def snes_write(ctx, write_list: list[tuple[int, bytes]]) -> bool:
    if ctx.snes_state != SNESState.SNES_ATTACHED:
        return False
    for address, data in write_list:
        try:
            ctx.backend.write(int(address), bytes(data))
            ctx.emulator_writes += 1
            ctx.emulator_write_bytes += len(data)
            ctx.diagnostic = (
                f"{ctx.game or 'SNI'} flow: emulator write "
                f"writes={ctx.emulator_writes} bytes={ctx.emulator_write_bytes} "
                f"items={len(ctx.items_received)}"
            )
        except Exception:
            ctx.emulator_lifecycle.note_io_failure()
            return False
    return True


async def snes_flush_writes(ctx) -> None:
    if not ctx.snes_write_buffer:
        return
    writes = ctx.snes_write_buffer
    ctx.snes_write_buffer = []
    await snes_write(ctx, writes)
