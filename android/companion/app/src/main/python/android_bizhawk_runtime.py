"""Run supported Archipelago clients over the companion's mGBA bridge."""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any

from android_client_runtime import (
    AndroidClientContext,
    plain as _plain,
    process_packet as process_common_packet,
    reset_connection as reset_common_connection,
)


_SYSTEM_BUS = "System Bus"


class _LadxCheck:
    def __init__(self, check_id: str, address: int, mask: int, alternate_address: int | None):
        self.check_id = check_id
        self.address = address
        self.mask = mask
        self.alternate_address = alternate_address
        parts = check_id.split("-")
        sub_id = 0
        if len(parts) > 1:
            sub_id = (int(parts[1]) + 1) * 1000 if parts[1].isnumeric() else 1000
        self.location_id = 10_000_000 + int(parts[0], 16) + sub_id


class AndroidLadxClient:
    """Android adapter for LADX's custom client memory protocol."""

    game = "Links Awakening DX"
    _slot_name = 0x0134
    _gameplay_type = 0xDB95
    _link_status = 0xDDF7
    _link_health = 0xDB5A
    _give_item = 0xDDF8
    _recv_index = 0xDDFD
    _safety_address = 0xC0FB
    _safety_value = bytes(4)
    _base_id = 10_000_000

    def __init__(self) -> None:
        self.auth = b""
        self.checks = self._build_checks()
        self.remaining_checks = list(self.checks)
        self.start_location = next(check.location_id for check in self.checks if check.check_id == "0x2A3")
        self.last_health = None
        self.last_sync = 0.0

    @staticmethod
    def _build_checks() -> list[_LadxCheck]:
        from worlds.ladx.LADXR.checkMetadata import checkMetadataTable

        mask_overrides = {
            "0x106": 0x20, "0x12B": 0x20, "0x15A": 0x20, "0x166": 0x20,
            "0x185": 0x20, "0x1E4": 0x20, "0x1BC": 0x20, "0x1E0": 0x20,
            "0x1E1": 0x20, "0x1E2": 0x20, "0x223": 0x20, "0x234": 0x20,
            "0x2A3": 0x20, "0x2FD": 0x20, "0x2A7": 0x20, "0x1F5": 0x06,
            "0x301-0": 0x10, "0x301-1": 0x10,
        }
        address_overrides = {
            "0x30A-Owl": 0xDDEA, "0x30F-Owl": 0xDDEF, "0x308-Owl": 0xDDE8,
            "0x302": 0xDDE2, "0x306": 0xDDE6, "0x307": 0xDDE7,
            "0x308": 0xDDE8, "0x30F": 0xDDEF, "0x311": 0xDDF1,
            "0x314": 0xDDF4, "0x1F5": 0xDB7D, "0x301-0": 0xDDE1,
            "0x301-1": 0xDDE1, "0x223": 0xDA2E, "0x169": 0xD97C,
            "0x2A7": 0xDAA1,
        }
        alternate_addresses = {"0x0F2": 0xD8B2}
        checks = []
        for check_id in checkMetadataTable:
            if check_id in {"None", "0x2A1-2"}:
                continue
            room = check_id.split("-", 1)[0]
            address = address_overrides.get(check_id, 0xD800 + int(room, 16))
            mask = 0x20 if "Trade" in check_id or "Owl" in check_id else 0x10
            checks.append(_LadxCheck(
                check_id,
                address,
                mask_overrides.get(check_id, mask),
                alternate_addresses.get(check_id),
            ))
        return checks

    @staticmethod
    def _ready(gameplay: int) -> bool:
        return 0x06 <= gameplay <= 0x1A or gameplay == 0x01

    async def _identity(self, ctx: "AndroidClientContext") -> bytes | None:
        from worlds import _bizhawk as bizhawk

        if await bizhawk.get_system(ctx.bizhawk_ctx) not in {"GB", "GBC"}:
            return None
        auth, cgb_flag = await bizhawk.read(ctx.bizhawk_ctx, [
            (self._slot_name, 12, _SYSTEM_BUS),
            (0x0143, 1, _SYSTEM_BUS),
        ])
        if len(auth) != 12 or not auth.strip(b"\x00\xff") or not (cgb_flag[0] & 0x80):
            return None
        return auth

    async def validate_rom(self, ctx: "AndroidClientContext") -> bool:
        from worlds import _bizhawk as bizhawk

        auth = await self._identity(ctx)
        if auth is None:
            return False
        self.auth = auth
        ctx.game = self.game
        ctx.items_handling = 0b101
        ctx.want_slot_data = True
        return True

    async def validate_identity(self, ctx: "AndroidClientContext", auth: str) -> bool:
        identity = await self._identity(ctx)
        return identity is not None and identity.hex() == auth

    async def set_auth(self, ctx: "AndroidClientContext") -> None:
        if not self.auth:
            identity = await self._identity(ctx)
            if identity is None:
                raise RuntimeError("The active ROM is not a patched Links Awakening DX game")
            self.auth = identity
        ctx.auth = self.auth.hex()

    def on_package(self, ctx: "AndroidClientContext", cmd: str, args: dict) -> None:
        pass

    def bridge_reconnected(self) -> None:
        # The desktop client recreates its tracker after an emulator reconnect.
        self.remaining_checks = list(self.checks)
        self.last_health = None

    async def game_watcher(self, ctx: "AndroidClientContext") -> None:
        from worlds import _bizhawk as bizhawk

        if ctx.slot is None:
            return
        addresses = sorted({
            address
            for check in self.remaining_checks
            for address in (check.address, check.alternate_address)
            if address is not None
        })
        reads = [
            (self._gameplay_type, 1, _SYSTEM_BUS),
            (self._link_health, 1, _SYSTEM_BUS),
            (self._link_status, 1, _SYSTEM_BUS),
            (self._recv_index, 2, _SYSTEM_BUS),
            *((address, 1, _SYSTEM_BUS) for address in addresses),
        ]
        result = await bizhawk.guarded_read(
            ctx.bizhawk_ctx,
            reads,
            [(self._safety_address, self._safety_value, _SYSTEM_BUS)],
        )
        if result is None:
            return
        gameplay = result[0][0]
        if not self._ready(gameplay):
            return
        health = result[1][0]
        status = result[2][0]
        recv_index_bytes = result[3]
        recv_index = int.from_bytes(recv_index_bytes, "big")
        memory = {address: value[0] for address, value in zip(addresses, result[4:])}

        found = []
        still_missing = []
        for check in self.remaining_checks:
            values = [memory[check.address]]
            if check.alternate_address is not None:
                values.append(memory[check.alternate_address])
            if any(value & check.mask for value in values):
                found.append(check.location_id)
            else:
                still_missing.append(check)
        self.remaining_checks = still_missing
        if found:
            await ctx.check_locations(set(found))

        if gameplay == 0x01 and not ctx.finished_game:
            await ctx.send_msgs([{"cmd": "StatusUpdate", "status": 30}])

        self.last_health = health
        start_found = all(check.location_id != self.start_location for check in self.remaining_checks)

        if start_found and recv_index < len(ctx.items_received) and status & 1 == 0:
            item = ctx.items_received[recv_index]
            local_item = int(item.item) - self._base_id
            if not 0 <= local_item <= 0xFF:
                raise ValueError(f"LADX item {item.item} is outside the ROM protocol range")
            from_player = min(max(int(item.player), 0), 101)
            next_index = (recv_index + 1).to_bytes(2, "big")
            delivered = await bizhawk.guarded_write(
                ctx.bizhawk_ctx,
                [
                    (self._give_item, bytes((local_item, from_player)), _SYSTEM_BUS),
                    (self._link_status, bytes((status | 1,)), _SYSTEM_BUS),
                    (self._recv_index, next_index, _SYSTEM_BUS),
                ],
                [
                    (self._safety_address, self._safety_value, _SYSTEM_BUS),
                    (self._gameplay_type, bytes((gameplay,)), _SYSTEM_BUS),
                    (self._link_status, bytes((status,)), _SYSTEM_BUS),
                    (self._recv_index, recv_index_bytes, _SYSTEM_BUS),
                ],
            )
            ctx.diagnostic = (
                f"LADX delivery index={recv_index} item={local_item:#04x} "
                f"items={len(ctx.items_received)} written={delivered}"
            )
        elif recv_index > len(ctx.items_received) and time.time() - self.last_sync >= 5.0:
            self.last_sync = time.time()
            await ctx.send_msgs([{"cmd": "Sync"}])


_CUSTOM_CLIENT_ADAPTERS = (
    (frozenset({"GB", "GBC"}), AndroidLadxClient),
)


def custom_client_games() -> set[str]:
    return {adapter.game for _, adapter in _CUSTOM_CLIENT_ADAPTERS}


class AndroidBizHawkRuntime:
    def __init__(self, work_directory: str, backend: Any):
        self.work_directory = work_directory
        self.backend = backend
        self.loop = asyncio.new_event_loop()
        self.handler = None
        self.ctx = None
        self.last_error = ""
        self._load_worlds()

    def _load_worlds(self) -> None:
        from offline_generator import _load_standard_mgba_clients
        _load_standard_mgba_clients(self.work_directory)

    def _run(self, awaitable):
        result = self.loop.run_until_complete(awaitable)
        self.loop.run_until_complete(asyncio.sleep(0))
        return result

    def probe(self) -> str:
        """Find the supported client which accepts the active mGBA ROM."""
        from worlds._bizhawk.client import AutoBizHawkClientRegister

        self.last_error = ""
        system = str(self.backend.getSystem())
        compatible_systems = {"GBA"} if system == "GBA" else {"GB", "GBC"}

        for systems, handlers in AutoBizHawkClientRegister.game_handlers.items():
            if compatible_systems.isdisjoint(systems):
                continue
            for registered in handlers.values():
                handler = type(registered)()
                ctx = AndroidClientContext(self.backend)
                ctx.client_handler = handler
                try:
                    if not self._run(handler.validate_rom(ctx)):
                        continue
                    self._run(handler.set_auth(ctx))
                except Exception as exc:
                    self.last_error = f"{type(exc).__name__}: {exc}"
                    continue
                self.handler = handler
                self.ctx = ctx
                return json.dumps({
                    "matched": True,
                    "game": ctx.game or getattr(handler, "game", "Imported mGBA game"),
                    "auth": ctx.auth or "",
                    "items_handling": int(ctx.items_handling),
                    "want_slot_data": bool(ctx.want_slot_data),
                    "seed_name": ctx.seed_name or "",
                    "client": f"{type(handler).__module__}.{type(handler).__name__}",
                })

        for systems, adapter in _CUSTOM_CLIENT_ADAPTERS:
            if system not in systems:
                continue
            handler = adapter()
            ctx = AndroidClientContext(self.backend)
            ctx.client_handler = handler
            try:
                if self._run(handler.validate_rom(ctx)):
                    self._run(handler.set_auth(ctx))
                    self.handler = handler
                    self.ctx = ctx
                    return json.dumps({
                        "matched": True,
                        "game": ctx.game,
                        "auth": ctx.auth,
                        "items_handling": int(ctx.items_handling),
                        "want_slot_data": bool(ctx.want_slot_data),
                        "seed_name": "",
                        "client": f"{type(handler).__module__}.{type(handler).__name__}",
                    })
            except Exception as exc:
                self.last_error = f"{type(exc).__name__}: {exc}"
            self.handler = None
            self.ctx = None

        self.handler = None
        self.ctx = None
        return json.dumps({"matched": False, "error": self.last_error})

    def validate_active(self, game: str, auth: str) -> bool:
        """Recheck ROM identity without mutating the active handler's state."""
        if self.handler is None:
            return False
        if isinstance(self.handler, AndroidLadxClient):
            ctx = AndroidClientContext(self.backend)
            try:
                return self._run(self.handler.validate_identity(ctx, auth)) and game == self.handler.game
            except Exception:
                return False
        candidate = type(self.handler)()
        ctx = AndroidClientContext(self.backend)
        ctx.client_handler = candidate
        try:
            if not self._run(candidate.validate_rom(ctx)):
                return False
            self._run(candidate.set_auth(ctx))
            return (ctx.game or getattr(candidate, "game", "")) == game and (ctx.auth or "") == auth
        except Exception:
            return False

    def connection_info(self) -> str:
        if self.ctx is None:
            return json.dumps({"matched": False})
        return json.dumps({
            "matched": True,
            "game": self.ctx.game,
            "auth": self.ctx.auth or "",
            "items_handling": int(self.ctx.items_handling),
            "want_slot_data": bool(self.ctx.want_slot_data),
            "seed_name": self.ctx.seed_name or "",
        })

    def process_packet(self, packet_json: str) -> None:
        if self.ctx is None or self.handler is None:
            return
        process_common_packet(self.ctx, self.handler, packet_json)

    def tick(self, watch_game: bool = True) -> str:
        if self.ctx is None or self.handler is None:
            return json.dumps({"messages": [], "disconnect": False, "error": "No mGBA client is active"})
        error = ""
        self.ctx.emulator_lifecycle.begin_tick(watch_game)
        if watch_game:
            try:
                self._run(self.handler.game_watcher(self.ctx))
            except Exception as exc:
                self.ctx.emulator_lifecycle.note_io_failure()
                error = f"{type(exc).__name__}: {exc}"
                self.last_error = error
        self.ctx.emulator_lifecycle.end_tick()
        messages = self.ctx.outgoing
        self.ctx.outgoing = []
        disconnect = self.ctx.disconnect_requested
        self.ctx.disconnect_requested = False
        return json.dumps({
            "messages": _plain(messages),
            "disconnect": disconnect,
            "error": error,
            "diagnostic": self.ctx.diagnostic,
        })

    def bridge_reconnected(self) -> None:
        if isinstance(self.handler, AndroidLadxClient):
            self.handler.bridge_reconnected()
            if self.ctx is not None:
                # The desktop LADX game loop clears its indexed receive cache
                # before requesting a full Sync after an emulator reconnect.
                self.ctx.items_received.clear()
                self.ctx.received_items_synced = False
        if self.ctx is not None:
            self.ctx.emulator_lifecycle.reattached()

    def reset_connection(self) -> None:
        if self.ctx is None:
            return
        reset_common_connection(self.ctx)

    def close(self) -> None:
        pending = asyncio.all_tasks(self.loop)
        for task in pending:
            task.cancel()
        if pending:
            self.loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
        self.loop.close()
