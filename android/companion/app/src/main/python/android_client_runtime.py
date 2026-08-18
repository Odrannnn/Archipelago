"""Shared Archipelago context and emulator lifecycle for Android runtimes."""

from __future__ import annotations

import json
import time
from types import SimpleNamespace
from typing import Any

from NetUtils import NetworkItem


def plain(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [plain(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def network_item(item: dict) -> NetworkItem:
    return NetworkItem(
        int(item.get("item", 0)),
        int(item.get("location", 0)),
        int(item.get("player", 0)),
        int(item.get("flags", 0)),
    )


class NameLookup:
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

    def lookup_in_slot(self, identifier: int, player: int) -> str:
        return self.lookup_in_game(identifier)


class EmulatorLifecycle:
    """Request upstream-style item synchronization after transport recovery."""

    def __init__(self, ctx: "AndroidClientContext") -> None:
        self.ctx = ctx
        self._available: bool | None = None
        self._tick_transport_available = False
        self._tick_io_failed = False

    def begin_tick(self, transport_available: bool) -> None:
        self._tick_transport_available = bool(transport_available)
        self._tick_io_failed = False

    def note_io_failure(self) -> None:
        self._tick_io_failed = True
        self.ctx.emulator_io_failures += 1

    def end_tick(self) -> None:
        available = self._tick_transport_available and not self._tick_io_failed
        recovered = available and self._available is False
        self._available = available

        if recovered:
            self.request_sync("emulator memory became available")

    def reattached(self) -> None:
        self._available = True
        self.request_sync("emulator transport reattached")

    def request_sync(self, reason: str) -> bool:
        if self.ctx.server is None:
            return False
        if not any(message.get("cmd") == "Sync" for message in self.ctx.outgoing):
            self.ctx.outgoing.append({"cmd": "Sync"})
            self.ctx.sync_requests += 1
        self.ctx.received_items_synced = False
        game = self.ctx.game or "Emulator"
        self.ctx.diagnostic = f"{game} lifecycle: {reason}; requested server Sync"
        return True

    def received_full_item_state(self) -> None:
        self.ctx.received_items_synced = True

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
        self.item_names = NameLookup()
        self.location_names = NameLookup()
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
        self.sync_requests = 0
        self.full_item_syncs = 0
        self.emulator_io_failures = 0
        self.emulator_writes = 0
        self.emulator_write_bytes = 0
        self.emulator_lifecycle = EmulatorLifecycle(self)

    async def send_msgs(self, messages: list[dict]) -> None:
        for message in messages:
            if message.get("cmd") == "StatusUpdate" and message.get("status") == 30:
                self.finished_game = True
            self.outgoing.append(plain(message))

    async def update_death_link(self, enabled: bool) -> None:
        if enabled == ("DeathLink" in self.tags):
            return
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


def update_players(ctx: AndroidClientContext, players: list[dict]) -> None:
    for player in players:
        if ctx.team is not None and int(player.get("team", ctx.team)) != ctx.team:
            continue
        slot = int(player.get("slot", 0))
        ctx.player_names[slot] = player.get("alias") or player.get("name") or str(slot)


def process_packet(
    ctx: AndroidClientContext,
    handler: Any,
    packet_json: str,
) -> tuple[str, dict]:
    args = json.loads(packet_json)
    cmd = args.get("cmd", "")

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
        ctx.missing_locations.clear()
        ctx.missing_locations.update(missing)
        ctx.server_locations = checked | missing
        ctx.slot_data = args.get("slot_data", {})
        ctx.server = SimpleNamespace(socket=SimpleNamespace(open=True, closed=False))
        update_players(ctx, args.get("players", []))
        if ctx.locations_checked:
            ctx.outgoing.append({
                "cmd": "LocationChecks",
                "locations": sorted(ctx.locations_checked),
            })
    elif cmd == "RoomUpdate":
        newly_checked = {int(item) for item in args.get("checked_locations", [])}
        ctx.checked_locations.update(newly_checked)
        ctx.missing_locations.difference_update(newly_checked)
        if "players" in args:
            update_players(ctx, args["players"])
    elif cmd == "ReceivedItems":
        index = int(args.get("index", 0))
        items = [network_item(item) for item in args.get("items", [])]
        if index == 0:
            ctx.items_received.clear()
            ctx.items_received.extend(items)
            ctx.emulator_lifecycle.received_full_item_state()
            ctx.full_item_syncs += 1
            ctx.diagnostic = (
                f"{ctx.game or 'Emulator'} flow: server ReceivedItems index=0 "
                f"items={len(ctx.items_received)} full_syncs={ctx.full_item_syncs}"
            )
        elif index == len(ctx.items_received):
            ctx.items_received.extend(items)
        else:
            ctx.emulator_lifecycle.request_sync(
                f"server item index mismatch {len(ctx.items_received)}->{index}"
            )
            if ctx.locations_checked:
                ctx.outgoing.append({
                    "cmd": "LocationChecks",
                    "locations": sorted(ctx.locations_checked),
                })
    elif cmd == "LocationInfo":
        for item_data in args.get("locations", []):
            item = network_item(item_data)
            ctx.locations_info[item.location] = item
    elif cmd in ("Retrieved", "SetReply"):
        ctx.stored_data.update(args.get("keys", {}))
        if "key" in args:
            ctx.stored_data[str(args["key"])] = args.get("value")

    if cmd == "Bounced" and "DeathLink" in args.get("tags", []):
        data = args.get("data", {})
        if float(data.get("time", 0)) > ctx.last_death_link:
            ctx.on_deathlink(data)

    handler.on_package(ctx, cmd, args)
    return cmd, args


def reset_connection(ctx: AndroidClientContext) -> None:
    ctx.server_locations.clear()
    ctx.checked_locations.clear()
    ctx.missing_locations.clear()
    ctx.items_received.clear()
    ctx.received_items_synced = False
    ctx.locations_info.clear()
    ctx.location_scouts_requested.clear()
    ctx.player_names.clear()
    ctx.player_names[0] = "Archipelago"
    ctx.slot_data = {}
    ctx.stored_data.clear()
    ctx.slot = None
    ctx.team = None
    ctx.seed_name = None
    ctx.server_seed_name = None
    ctx.server = None
    ctx.finished_game = False
    ctx.outgoing.clear()
    ctx.disconnect_requested = False
