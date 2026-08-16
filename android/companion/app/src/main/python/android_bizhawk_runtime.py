"""Run supported Archipelago clients over the companion's mGBA bridge."""

from __future__ import annotations

import asyncio
import json
import time
from types import SimpleNamespace
from typing import Any

from NetUtils import NetworkItem


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
    # Slot 1 SRAM layout: offset 0100 prefix, 0105 main save, 0485 DX1,
    # 048A DX2. wRecvIndex occupies offsets 04A7-04A8 in the save snapshot.
    _saved_recv_index_offset = 0x04A7
    # DD00-DD01 are unused volatile WRAM in the base game and the bundled
    # LADX patch. Unlike the receive counter, these bytes are not restored
    # from battery SRAM. They therefore make resets observable even when
    # RetroArch keeps the core bridge socket alive throughout the reset.
    _reset_marker = 0xDD00
    _reset_marker_value = b"\xA7\x5C"
    _trade_flag_addresses = (0xDB40, 0xDB7F)
    _trade_menu_index = 0xC109
    _safety_address = 0xC0FB
    _safety_value = bytes(4)
    _base_id = 10_000_000
    def __init__(self, recover_from_save: bool = False) -> None:
        self.auth = b""
        self.recover_from_save = recover_from_save
        self.checks = self._build_checks()
        self.checks_by_id = {check.check_id: check for check in self.checks}
        self.remaining_checks = list(self.checks)
        self.start_location = next(check.location_id for check in self.checks if check.check_id == "0x2A3")
        self.last_health = None
        self.last_sync = 0.0
        self._was_ready = False
        self._last_recv_index = None
        self._reset_marker_initialized = False
        self._rolled_back_checks: dict[int, tuple[_LadxCheck, NetworkItem]] = {}
        self._restored_check_locations: set[int] = set()

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

        if await bizhawk.get_system(ctx.bizhawk_ctx) != "GB":
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
        state = await bizhawk.guarded_read(
            ctx.bizhawk_ctx,
            [(self._gameplay_type, 1, _SYSTEM_BUS)],
            [(self._safety_address, self._safety_value, _SYSTEM_BUS)],
        )
        if state is None or not self._ready(state[0][0]):
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
        if cmd == "Connected":
            self.remaining_checks = list(self.checks)
            self.last_health = None
            self._rolled_back_checks.clear()
            self._restored_check_locations.clear()

    def bridge_reconnected(self) -> None:
        # The Kotlin service deliberately preserves this client while the
        # local mGBA socket is unavailable. Re-scan every location when that
        # socket returns: a full RetroArch restart loads older check flags,
        # while an ordinary app switch simply finds the same flags again.
        self.remaining_checks = list(self.checks)
        self._rolled_back_checks.clear()
        self._restored_check_locations.clear()
        self._was_ready = False

    def _arm_reset_recovery(self) -> None:
        self.recover_from_save = True
        self.remaining_checks = list(self.checks)
        self._rolled_back_checks.clear()
        self._restored_check_locations.clear()

    async def game_watcher(self, ctx: "AndroidClientContext") -> None:
        from worlds import _bizhawk as bizhawk

        if ctx.slot is None:
            return
        addresses = sorted({
            address
            for check in self.remaining_checks
            for address in (check.address, check.alternate_address)
            if address is not None
        } | set(self._trade_flag_addresses) | {self._trade_menu_index})
        reads = [
            (self._gameplay_type, 1, _SYSTEM_BUS),
            (self._link_health, 1, _SYSTEM_BUS),
            (self._link_status, 1, _SYSTEM_BUS),
            (self._recv_index, 2, _SYSTEM_BUS),
            (self._reset_marker, len(self._reset_marker_value), _SYSTEM_BUS),
            *((address, 1, _SYSTEM_BUS) for address in addresses),
        ]
        result = await bizhawk.guarded_read(
            ctx.bizhawk_ctx,
            reads,
            [(self._safety_address, self._safety_value, _SYSTEM_BUS)],
        )
        if result is None:
            if self._was_ready:
                self.recover_from_save = True
            self._was_ready = False
            return
        gameplay = result[0][0]
        if not self._ready(gameplay):
            if self._was_ready:
                self.recover_from_save = True
            self._was_ready = False
            return
        self._was_ready = True
        health = result[1][0]
        status = result[2][0]
        recv_index_bytes = result[3]
        recv_index = int.from_bytes(recv_index_bytes, "big")
        reset_marker = result[4]
        reset_detected = (
            self._reset_marker_initialized
            and reset_marker != self._reset_marker_value
        )
        full_rescan_needed = False
        if reset_detected:
            self._arm_reset_recovery()
            full_rescan_needed = True
        if self._last_recv_index is not None and recv_index < self._last_recv_index:
            self._arm_reset_recovery()
            full_rescan_needed = True
        self._last_recv_index = recv_index
        memory = {address: value[0] for address, value in zip(addresses, result[5:])}

        # The bundled ROM menu advances through all fourteen trade-item slots,
        # including empty ones. With two owned trade items this produces long
        # black intervals between their sprites. Keep its display index on an
        # owned item and rotate only among items the player actually has.
        owned_trade_indexes = [
            index
            for index in range(14)
            if memory[self._trade_flag_addresses[index // 8]] & (1 << (index % 8))
        ]
        if owned_trade_indexes:
            desired_trade_index = owned_trade_indexes[
                int(time.monotonic() // 2) % len(owned_trade_indexes)
            ]
            current_trade_index = memory[self._trade_menu_index]
            if current_trade_index != desired_trade_index:
                menu_index_written = await bizhawk.guarded_write(
                    ctx.bizhawk_ctx,
                    [(self._trade_menu_index, bytes((desired_trade_index,)), _SYSTEM_BUS)],
                    [
                        (self._safety_address, self._safety_value, _SYSTEM_BUS),
                        (self._gameplay_type, bytes((gameplay,)), _SYSTEM_BUS),
                        (self._trade_menu_index, bytes((current_trade_index,)), _SYSTEM_BUS),
                    ],
                )
                if menu_index_written:
                    memory[self._trade_menu_index] = desired_trade_index

        if reset_marker != self._reset_marker_value:
            marker_written = await bizhawk.guarded_write(
                ctx.bizhawk_ctx,
                [(self._reset_marker, self._reset_marker_value, _SYSTEM_BUS)],
                [
                    (self._safety_address, self._safety_value, _SYSTEM_BUS),
                    (self._gameplay_type, bytes((gameplay,)), _SYSTEM_BUS),
                    (self._link_status, bytes((status,)), _SYSTEM_BUS),
                    (self._recv_index, recv_index_bytes, _SYSTEM_BUS),
                    (self._reset_marker, reset_marker, _SYSTEM_BUS),
                ],
            )
            if marker_written:
                self._reset_marker_initialized = True
            if reset_detected:
                ctx.diagnostic = (
                    f"LADX reset marker cleared; recovery armed "
                    f"recv={recv_index} items={len(ctx.items_received)} "
                    f"synced={ctx.received_items_synced} marker_written={marker_written}"
                )
        else:
            self._reset_marker_initialized = True

        # The first read used the previously reduced remaining-check list.
        # After a reset, return once so the next watcher pass reads every
        # check address before deciding which server checks rolled back.
        if full_rescan_needed:
            return

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
        found_set = set(found)
        for location_id in found_set:
            self._rolled_back_checks.pop(location_id, None)
        for check in still_missing:
            location_id = check.location_id
            if (
                location_id not in ctx.checked_locations
                or location_id not in ctx.locations_info
                or location_id in self._restored_check_locations
                or location_id in self._rolled_back_checks
            ):
                continue
            self._rolled_back_checks[location_id] = (check, ctx.locations_info[location_id])
        if found:
            await ctx.check_locations(set(found))

        if gameplay == 0x01 and not ctx.finished_game:
            await ctx.send_msgs([{"cmd": "StatusUpdate", "status": 30}])

        self.last_health = health
        # A reset can load a save from before Tarin's Gift while Archipelago
        # correctly retains that check and every item awarded after it. Trust
        # the server's checked-location record to reopen only this safety gate;
        # the guarded ROM-state checks below still protect item injection.
        start_found = (
            self.start_location in ctx.checked_locations
            or all(check.location_id != self.start_location for check in self.remaining_checks)
        )
        if self.recover_from_save and ctx.received_items_synced:
            # Read the battery backing store through mGBA's savedata snapshot.
            # LADX disables cartridge SRAM during gameplay, so the equivalent
            # A4A7 system-bus read returns FF instead of the saved counter.
            saved_recv_index_bytes = await bizhawk.read_savedata(
                ctx.bizhawk_ctx,
                self._saved_recv_index_offset,
                2,
            )
            saved_recv_index = int.from_bytes(saved_recv_index_bytes, "big")
            # Rewind only to the counter in that same battery save. This replays
            # every unsaved item without duplicating anything already saved.
            if saved_recv_index <= len(ctx.items_received):
                target_index = saved_recv_index
                target_index_bytes = target_index.to_bytes(2, "big")
                target_status = status & ~1
                if recv_index == target_index and status == target_status:
                    self.recover_from_save = False
                    ctx.diagnostic = (
                        f"LADX recovery ready recv={recv_index} saved={saved_recv_index} "
                        f"items={len(ctx.items_received)} start={start_found}"
                    )
                elif await bizhawk.guarded_write(
                    ctx.bizhawk_ctx,
                    [
                        (self._link_status, bytes((target_status,)), _SYSTEM_BUS),
                        (self._recv_index, target_index_bytes, _SYSTEM_BUS),
                    ],
                    [
                        (self._safety_address, self._safety_value, _SYSTEM_BUS),
                        (self._gameplay_type, bytes((gameplay,)), _SYSTEM_BUS),
                        (self._link_status, bytes((status,)), _SYSTEM_BUS),
                        (self._recv_index, recv_index_bytes, _SYSTEM_BUS),
                    ],
                ):
                    self.recover_from_save = False
                    self._last_recv_index = target_index
                    ctx.diagnostic = (
                        f"LADX recovery rewound recv={recv_index}->{target_index} "
                        f"items={len(ctx.items_received)} start={start_found}"
                    )
                else:
                    ctx.diagnostic = (
                        f"LADX recovery write blocked recv={recv_index} "
                        f"saved={saved_recv_index} items={len(ctx.items_received)}"
                    )
                return
            ctx.diagnostic = (
                f"LADX recovery waiting for complete item sync recv={recv_index} "
                f"saved={saved_recv_index} items={len(ctx.items_received)}"
            )

        if self._rolled_back_checks and status & 1 == 0:
            location_id = min(
                self._rolled_back_checks,
                key=lambda candidate: (candidate != self.start_location, candidate),
            )
            check, location_item = self._rolled_back_checks[location_id]
            restored_check_value = memory[check.address] | check.mask
            writes = [
                (check.address, bytes((restored_check_value,)), _SYSTEM_BUS),
            ]
            if int(location_item.player) == int(ctx.slot):
                local_item = int(location_item.item) - self._base_id
                if not 0 <= local_item <= 0xFF:
                    raise ValueError(f"LADX item {location_item.item} is outside the ROM protocol range")
                from_player = min(max(int(ctx.slot), 0), 101)
                writes.extend([
                    (self._give_item, bytes((local_item, from_player)), _SYSTEM_BUS),
                    (self._link_status, bytes((status | 1,)), _SYSTEM_BUS),
                ])
            restored = await bizhawk.guarded_write(
                ctx.bizhawk_ctx,
                writes,
                [
                    (self._safety_address, self._safety_value, _SYSTEM_BUS),
                    (self._gameplay_type, bytes((gameplay,)), _SYSTEM_BUS),
                    (self._link_status, bytes((status,)), _SYSTEM_BUS),
                    (self._recv_index, recv_index_bytes, _SYSTEM_BUS),
                    (check.address, bytes((memory[check.address],)), _SYSTEM_BUS),
                ],
            )
            if restored:
                self._rolled_back_checks.pop(location_id, None)
                self._restored_check_locations.add(location_id)
            ctx.diagnostic = (
                f"LADX restored rolled-back check={location_id} "
                f"item={int(location_item.item)} receiver={int(location_item.player)} "
                f"local={int(location_item.player) == int(ctx.slot)} written={restored}"
            )
            return

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


def _plain(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_plain(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


class AndroidClientContext:
    def __init__(self, backend: Any):
        self.backend = backend
        self.bizhawk_ctx = SimpleNamespace(backend=backend)
        self.command_processor = SimpleNamespace(commands={})
        self.client_handler = None
        self.game = None
        self.auth = None
        self.items_handling = 0b111
        self.want_slot_data = True
        self.seed_name = None
        self.server_seed_name = None
        self.server = None
        self.watcher_timeout = 0.5
        self.server_locations: set[int] = set()
        self.checked_locations: set[int] = set()
        self.locations_checked: set[int] = set()
        self.missing_locations: set[int] = set()
        self.items_received: list[NetworkItem] = []
        self.received_items_synced = False
        self.locations_info: dict[int, NetworkItem] = {}
        self.location_scouts_requested: set[int] = set()
        self.item_names = _NameLookup()
        self.location_names = _NameLookup()
        self.player_names: dict[int, str] = {0: "Archipelago"}
        self.slot_data: dict = {}
        self.stored_data: dict[str, Any] = {}
        self.slot = None
        self.team = None
        self.finished_game = False
        self.tags: set[str] = set()
        self.outgoing: list[dict] = []
        self.disconnect_requested = False
        self.last_death_link = 0.0
        self.diagnostic = ""

    async def send_msgs(self, messages: list[dict]) -> None:
        for message in messages:
            if message.get("cmd") == "StatusUpdate" and message.get("status") == 30:
                self.finished_game = True
            self.outgoing.append(_plain(message))

    async def update_death_link(self, enabled: bool) -> None:
        if enabled:
            self.tags.add("DeathLink")
        else:
            self.tags.discard("DeathLink")
        self.outgoing.append({"cmd": "ConnectUpdate", "tags": sorted(self.tags)})

    async def check_locations(self, locations: set[int]) -> set[int]:
        found = ({int(location) for location in locations} & self.missing_locations) - self.locations_checked
        if found:
            self.locations_checked.update(found)
            self.outgoing.append({"cmd": "LocationChecks", "locations": sorted(found)})
        return found

    def on_deathlink(self, data: dict) -> None:
        self.last_death_link = max(self.last_death_link, float(data.get("time", time.time())))

    async def send_death(self, death_text: str = "") -> None:
        source = self.auth or "Android player"
        self.last_death_link = time.time()
        self.outgoing.append({
            "cmd": "Bounce",
            "tags": ["DeathLink"],
            "data": {
                "time": self.last_death_link,
                "source": source,
                "cause": death_text or f"{source} died",
            },
        })

    async def disconnect(self, allow_autoreconnect: bool = False) -> None:
        self.disconnect_requested = True


class _NameLookup:
    def __init__(self) -> None:
        self.by_game: dict[str, dict[int, str]] = {}

    def replace(self, games: dict, key: str) -> None:
        self.by_game.clear()
        for game, data in games.items():
            names = data.get(key, {}) if isinstance(data, dict) else {}
            self.by_game[str(game)] = {int(identifier): str(name) for name, identifier in names.items()}

    def lookup_in_game(self, identifier: int, game: str | None = None) -> str:
        if game is not None:
            return self.by_game.get(game, {}).get(int(identifier), str(identifier))
        for names in self.by_game.values():
            if int(identifier) in names:
                return names[int(identifier)]
        return str(identifier)


class AndroidBizHawkRuntime:
    def __init__(self, work_directory: str, backend: Any, recover_after_bridge_reconnect: bool = False):
        self.work_directory = work_directory
        self.backend = backend
        self.recover_after_bridge_reconnect = bool(recover_after_bridge_reconnect)
        self.loop = asyncio.new_event_loop()
        self.handler = None
        self.ctx = None
        self.last_error = ""
        self._load_worlds()

    def _load_worlds(self) -> None:
        from offline_generator import _load_standard_gba_clients
        _load_standard_gba_clients(self.work_directory)

    def _run(self, awaitable):
        result = self.loop.run_until_complete(awaitable)
        self.loop.run_until_complete(asyncio.sleep(0))
        return result

    def probe(self) -> str:
        """Find the supported client which accepts the active mGBA ROM."""
        from worlds._bizhawk.client import AutoBizHawkClientRegister

        self.last_error = ""
        if str(self.backend.getSystem()) == "GB":
            handler = AndroidLadxClient(self.recover_after_bridge_reconnect)
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
            return json.dumps({"matched": False, "error": self.last_error})

        for systems, handlers in AutoBizHawkClientRegister.game_handlers.items():
            if "GBA" not in systems:
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
                    "game": ctx.game or getattr(handler, "game", "Imported GBA game"),
                    "auth": ctx.auth or "",
                    "items_handling": int(ctx.items_handling),
                    "want_slot_data": bool(ctx.want_slot_data),
                    "seed_name": ctx.seed_name or "",
                    "client": f"{type(handler).__module__}.{type(handler).__name__}",
                })
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
        args = json.loads(packet_json)
        cmd = args.get("cmd", "")
        ctx = self.ctx

        if cmd == "RoomInfo":
            ctx.seed_name = args.get("seed_name")
            ctx.server_seed_name = args.get("seed_name")
        elif cmd == "DataPackage":
            games = args.get("data", {}).get("games", {})
            ctx.item_names.replace(games, "item_name_to_id")
            ctx.location_names.replace(games, "location_name_to_id")
        elif cmd == "Connected":
            ctx.team = int(args.get("team", 0))
            ctx.slot = int(args.get("slot", 0))
            checked = {int(item) for item in args.get("checked_locations", [])}
            missing = {int(item) for item in args.get("missing_locations", [])}
            ctx.checked_locations.clear()
            ctx.checked_locations.update(checked)
            ctx.locations_checked.clear()
            ctx.locations_checked.update(checked)
            ctx.missing_locations.clear()
            ctx.missing_locations.update(missing)
            ctx.server_locations = checked | missing
            ctx.slot_data = args.get("slot_data", {})
            ctx.server = SimpleNamespace(socket=SimpleNamespace(closed=False))
            self._update_players(args.get("players", []))
            self._request_checked_location_info(checked)
        elif cmd == "RoomUpdate":
            newly_checked = {int(item) for item in args.get("checked_locations", [])}
            ctx.checked_locations.update(newly_checked)
            ctx.locations_checked.update(newly_checked)
            ctx.missing_locations.difference_update(newly_checked)
            self._request_checked_location_info(newly_checked)
            if "players" in args:
                self._update_players(args["players"])
        elif cmd == "ReceivedItems":
            index = int(args.get("index", 0))
            items = [self._network_item(item) for item in args.get("items", [])]
            if index <= len(ctx.items_received):
                del ctx.items_received[index:]
                ctx.items_received.extend(items)
                if index == 0:
                    ctx.received_items_synced = True
            else:
                ctx.outgoing.append({"cmd": "Sync"})
        elif cmd == "LocationInfo":
            for item_data in args.get("locations", []):
                item = self._network_item(item_data)
                ctx.locations_info[item.location] = item
        elif cmd in ("Retrieved", "SetReply"):
            ctx.stored_data.update(args.get("keys", {}))
            if "key" in args:
                ctx.stored_data[str(args["key"])] = args.get("value")

        if cmd == "Bounced" and "DeathLink" in args.get("tags", []):
            data = args.get("data", {})
            if float(data.get("time", 0)) > ctx.last_death_link:
                ctx.on_deathlink(data)

        self.handler.on_package(ctx, cmd, args)

    def restore_server_snapshot(self, snapshot_json: str) -> bool:
        """Restore a complete server snapshot before the live room reconnects."""
        if self.ctx is None or not isinstance(self.handler, AndroidLadxClient):
            return False
        snapshot = json.loads(snapshot_json)
        ctx = self.ctx
        if (
            int(snapshot.get("version", 0)) != 2
            or snapshot.get("game") != ctx.game
            or snapshot.get("auth") != (ctx.auth or "")
            or not snapshot.get("checked_locations_scouted", False)
            or int(snapshot.get("slot", 0)) <= 0
        ):
            return False

        checked = {int(item) for item in snapshot.get("checked_locations", [])}
        missing = {int(item) for item in snapshot.get("missing_locations", [])}
        ctx.team = int(snapshot.get("team", 0))
        ctx.slot = int(snapshot["slot"])
        ctx.checked_locations.clear()
        ctx.checked_locations.update(checked)
        ctx.locations_checked.clear()
        ctx.locations_checked.update(checked)
        ctx.missing_locations.clear()
        ctx.missing_locations.update(missing)
        ctx.server_locations = checked | missing
        ctx.items_received = [
            self._network_item(item)
            for item in snapshot.get("items_received", [])
        ]
        ctx.received_items_synced = bool(snapshot.get("received_items_synced", False))
        ctx.locations_info = {
            int(location): self._network_item(item)
            for location, item in snapshot.get("locations_info", {}).items()
        }
        ctx.location_scouts_requested = set(ctx.locations_info)
        ctx.slot_data = snapshot.get("slot_data", {})
        ctx.seed_name = snapshot.get("server_seed_name") or None
        ctx.server_seed_name = ctx.seed_name
        ctx.player_names.clear()
        ctx.player_names[0] = "Archipelago"
        for player, name in snapshot.get("player_names", {}).items():
            ctx.player_names[int(player)] = str(name)
        ctx.server = None
        self.handler.on_package(ctx, "Connected", {
            "team": ctx.team,
            "slot": ctx.slot,
            "checked_locations": sorted(checked),
            "missing_locations": sorted(missing),
            "slot_data": ctx.slot_data,
        })
        # Treat an absent volatile reset marker as a reset on the first watcher
        # pass. If the marker is still present, the running emulator state was
        # not reset and should not be rewound merely because Android restarted.
        self.handler._reset_marker_initialized = True
        ctx.diagnostic = (
            f"LADX restored server snapshot slot={ctx.slot} "
            f"checks={len(checked)} items={len(ctx.items_received)}"
        )
        return True

    def server_snapshot(self) -> str:
        """Return the latest complete state received from Archipelago."""
        ctx = self.ctx
        if (
            ctx is None
            or not isinstance(self.handler, AndroidLadxClient)
            or ctx.slot is None
        ):
            return json.dumps({"cacheable": False})
        checked_locations_scouted = ctx.checked_locations.issubset(ctx.locations_info)
        if not checked_locations_scouted:
            return json.dumps({"cacheable": False})
        return json.dumps({
            "cacheable": True,
            "version": 2,
            "game": ctx.game,
            "auth": ctx.auth or "",
            "server_seed_name": ctx.server_seed_name or ctx.seed_name or "",
            "team": int(ctx.team or 0),
            "slot": int(ctx.slot),
            "checked_locations": sorted(ctx.checked_locations),
            "missing_locations": sorted(ctx.missing_locations),
            "items_received": [
                {
                    "item": int(item.item),
                    "location": int(item.location),
                    "player": int(item.player),
                    "flags": int(item.flags),
                }
                for item in ctx.items_received
            ],
            "received_items_synced": bool(ctx.received_items_synced),
            "checked_locations_scouted": True,
            "locations_info": {
                str(location): {
                    "item": int(item.item),
                    "location": int(item.location),
                    "player": int(item.player),
                    "flags": int(item.flags),
                }
                for location, item in ctx.locations_info.items()
                if location in ctx.checked_locations
            },
            "slot_data": _plain(ctx.slot_data),
            "player_names": {
                str(player): name
                for player, name in ctx.player_names.items()
            },
        })

    def tick(self, watch_game: bool = True) -> str:
        if self.ctx is None or self.handler is None:
            return json.dumps({"messages": [], "disconnect": False, "error": "No mGBA client is active"})
        error = ""
        if watch_game:
            try:
                self._run(self.handler.game_watcher(self.ctx))
            except Exception as exc:
                error = f"{type(exc).__name__}: {exc}"
                self.last_error = error
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

    def reset_connection(self) -> None:
        if self.ctx is None:
            return
        self.ctx.server_locations.clear()
        self.ctx.checked_locations.clear()
        self.ctx.locations_checked.clear()
        self.ctx.missing_locations.clear()
        self.ctx.items_received.clear()
        self.ctx.received_items_synced = False
        self.ctx.locations_info.clear()
        self.ctx.location_scouts_requested.clear()
        self.ctx.player_names.clear()
        self.ctx.player_names[0] = "Archipelago"
        self.ctx.slot_data = {}
        self.ctx.stored_data.clear()
        self.ctx.slot = None
        self.ctx.team = None
        self.ctx.seed_name = None
        self.ctx.server_seed_name = None
        self.ctx.server = None
        self.ctx.finished_game = False
        self.ctx.outgoing.clear()
        self.ctx.disconnect_requested = False

    def close(self) -> None:
        pending = asyncio.all_tasks(self.loop)
        for task in pending:
            task.cancel()
        if pending:
            self.loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
        self.loop.close()

    def _update_players(self, players: list[dict]) -> None:
        for player in players:
            if self.ctx.team is not None and int(player.get("team", self.ctx.team)) != self.ctx.team:
                continue
            slot = int(player.get("slot", 0))
            self.ctx.player_names[slot] = player.get("alias") or player.get("name") or str(slot)

    def _request_checked_location_info(self, locations: set[int]) -> None:
        if self.ctx is None:
            return
        requested = {
            int(location)
            for location in locations
            if int(location) not in self.ctx.locations_info
            and int(location) not in self.ctx.location_scouts_requested
        }
        if not requested:
            return
        self.ctx.location_scouts_requested.update(requested)
        self.ctx.outgoing.append({
            "cmd": "LocationScouts",
            "locations": sorted(requested),
            "create_as_hint": 0,
        })

    @staticmethod
    def _network_item(item: dict) -> NetworkItem:
        return NetworkItem(
            int(item.get("item", 0)),
            int(item.get("location", 0)),
            int(item.get("player", 0)),
            int(item.get("flags", 0)),
        )
