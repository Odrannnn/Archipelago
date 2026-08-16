"""Run unmodified standard GBA BizHawk APWorld clients inside the companion."""

from __future__ import annotations

import asyncio
import json
import time
from types import SimpleNamespace
from typing import Any

from NetUtils import NetworkItem


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
        self.locations_info: dict[int, NetworkItem] = {}
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
    def __init__(self, work_directory: str, backend: Any):
        self.work_directory = work_directory
        self.backend = backend
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
        """Find the first imported standard GBA client which accepts this ROM."""
        from worlds._bizhawk.client import AutoBizHawkClientRegister

        self.last_error = ""
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
        elif cmd == "RoomUpdate":
            newly_checked = {int(item) for item in args.get("checked_locations", [])}
            ctx.checked_locations.update(newly_checked)
            ctx.locations_checked.update(newly_checked)
            ctx.missing_locations.difference_update(newly_checked)
            if "players" in args:
                self._update_players(args["players"])
        elif cmd == "ReceivedItems":
            index = int(args.get("index", 0))
            items = [self._network_item(item) for item in args.get("items", [])]
            if index <= len(ctx.items_received):
                del ctx.items_received[index:]
                ctx.items_received.extend(items)
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

    def tick(self) -> str:
        if self.ctx is None or self.handler is None:
            return json.dumps({"messages": [], "disconnect": False, "error": "No GBA client is active"})
        error = ""
        try:
            self._run(self.handler.game_watcher(self.ctx))
        except Exception as exc:
            error = f"{type(exc).__name__}: {exc}"
            self.last_error = error
        messages = self.ctx.outgoing
        self.ctx.outgoing = []
        disconnect = self.ctx.disconnect_requested
        self.ctx.disconnect_requested = False
        return json.dumps({"messages": _plain(messages), "disconnect": disconnect, "error": error})

    def reset_connection(self) -> None:
        if self.ctx is None:
            return
        self.ctx.server_locations.clear()
        self.ctx.checked_locations.clear()
        self.ctx.locations_checked.clear()
        self.ctx.missing_locations.clear()
        self.ctx.items_received.clear()
        self.ctx.locations_info.clear()
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

    @staticmethod
    def _network_item(item: dict) -> NetworkItem:
        return NetworkItem(
            int(item.get("item", 0)),
            int(item.get("location", 0)),
            int(item.get("player", 0)),
            int(item.get("flags", 0)),
        )
