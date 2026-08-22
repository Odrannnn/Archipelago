"""Shared Archipelago context and emulator lifecycle for Android runtimes."""

from __future__ import annotations

import asyncio
from contextlib import contextmanager
import json
import logging
import re
import time
from types import SimpleNamespace
from typing import Any

from MultiServer import CommandProcessor, mark_raw
from NetUtils import NetworkItem


_ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*m")
_MISSING = object()


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
    def __init__(self, ctx: "AndroidClientContext") -> None:
        self.ctx = ctx
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
        slot = self.ctx.slot_info.get(int(player))
        return self.lookup_in_game(identifier, getattr(slot, "game", None))


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

    @property
    def io_failed_this_tick(self) -> bool:
        return self._tick_io_failed

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


class AndroidClientCommandProcessor(CommandProcessor):
    """Desktop-style client commands backed by the Android context."""

    def __init__(self, ctx: "AndroidClientContext") -> None:
        self.ctx = ctx

    def output(self, text: str) -> None:
        self.ctx.console_message("output", str(text))

    def _cmd_connect(self, address: str = "") -> bool:
        """Connect to the configured MultiWorld server, or optionally use a new address."""
        self.ctx.request_android_action("connect", address=address)
        self.output(f"Connecting to {address}…" if address else "Reconnecting to the configured server…")
        return True

    def _cmd_disconnect(self) -> bool:
        """Disconnect from the MultiWorld server."""
        self.ctx.request_android_action("disconnect")
        self.output("Disconnecting from the Archipelago server…")
        return True

    def _cmd_exit(self) -> bool:
        """Stop the Android bridge service."""
        self.ctx.request_android_action("stop")
        self.output("Stopping the Archipelago bridge service…")
        return True

    def _cmd_received(self) -> bool:
        """List all received items, sorted by time."""
        self.output(f"{len(self.ctx.items_received)} received items, sorted by time:")
        for index, item in enumerate(self.ctx.items_received, 1):
            item_name = self.ctx.item_names.lookup_in_slot(item.item, self.ctx.slot or item.player)
            location_name = self.ctx.location_names.lookup_in_slot(item.location, item.player)
            player_name = self.ctx.player_names.get(item.player, str(item.player))
            self.output(f"{index}. {item_name} from {location_name} by {player_name}")
        return True

    def _cmd_force_item_sync(self) -> bool:
        """Request a fresh full item history as an optional compatibility enhancement."""
        if self.ctx.server is None:
            self.output("Cannot force an item sync while Archipelago is disconnected.")
            return False
        now = time.monotonic()
        retry_after = 10.0 - (now - self.ctx.last_forced_item_sync)
        if retry_after > 0:
            self.output(f"A forced item sync was already requested; wait {retry_after:.0f} seconds.")
            return False

        cached_items = len(self.ctx.items_received)
        if not self.ctx.emulator_lifecycle.request_sync("manual compatibility item resync"):
            self.output("Could not request a full item history from Archipelago.")
            return False
        self.ctx.items_received.clear()
        self.ctx.received_items_synced = False
        self.ctx.last_forced_item_sync = now
        self.ctx.diagnostic = (
            f"{self.ctx.game or 'Emulator'} compatibility: requested full server item history; "
            f"cleared {cached_items} cached entries; ROM memory unchanged"
        )
        self.output(
            "Forced item sync requested. The companion cleared its cached history and asked the server "
            "for a complete replay; the active game client will compare it with the ROM's receive counter."
        )
        return True

    def _cmd_missing(self, filter_text: str = "") -> bool:
        """List missing location checks, optionally filtered by text."""
        if not self.ctx.game:
            self.output("No game set, cannot determine missing checks.")
            return False
        names = []
        for location in sorted(self.ctx.missing_locations):
            name = self.ctx.location_names.lookup_in_game(location, self.ctx.game)
            if not filter_text or filter_text.casefold() in name.casefold():
                names.append(name)
        for name in names:
            self.output("Missing: " + name)
        self.output(f"Found {len(names)} missing location checks." if names else "No missing location checks found.")
        return True

    def _cmd_items(self) -> bool:
        """List all item names for the currently running game."""
        return self._output_names(self.ctx.item_names, "Item Names")

    def _cmd_locations(self) -> bool:
        """List all location names for the currently running game."""
        return self._output_names(self.ctx.location_names, "Location Names")

    @mark_raw
    def _cmd_item_groups(self, key: str = "") -> bool:
        """List item group names, or the items in one named group."""
        return self._output_group("item_name_groups", key, "Item")

    @mark_raw
    def _cmd_location_groups(self, key: str = "") -> bool:
        """List location group names, or the locations in one named group."""
        return self._output_group("location_name_groups", key, "Location")

    def _output_group(self, group_key: str, filter_key: str, name: str) -> bool:
        if not self.ctx.game:
            self.output(f"No game set, cannot determine existing {name} Groups.")
            return False
        groups = self.ctx.data_package.get(self.ctx.game, {}).get(group_key, {})
        if filter_key:
            if filter_key not in groups:
                self.output(f"Unknown {name} Group {filter_key}")
                return False
            self.output(f'{name}s for {name} Group "{filter_key}"')
            for entry in groups[filter_key]:
                self.output(str(entry))
        else:
            self.output(f"{name} Groups for {self.ctx.game}")
            for group in groups:
                self.output(str(group))
        return True

    def _output_names(self, lookup: NameLookup, title: str) -> bool:
        if not self.ctx.game:
            self.output(f"No game set, cannot determine {title}.")
            return False
        self.output(f"{title} for {self.ctx.game}")
        for name in sorted(lookup.by_game.get(self.ctx.game, {}).values(), key=str.casefold):
            self.output(name)
        return True

    def _cmd_ready(self) -> bool:
        """Toggle ready status on the server."""
        self.ctx.ready = not self.ctx.ready
        self.ctx.outgoing.append({"cmd": "StatusUpdate", "status": 10 if self.ctx.ready else 5})
        self.output("Readied up." if self.ctx.ready else "Unreadied.")
        return True

    def default(self, raw: str) -> None:
        if self.ctx.server is None:
            self.output("Not connected to an Archipelago server.")
            return
        self.ctx.outgoing.append({"cmd": "Say", "text": raw})


class AndroidClientContext:
    def __init__(self, backend: Any = None, command_processor_type: Any = _MISSING):
        """Initialize either an Android runtime or a desktop-style APWorld context.

        Managed Android runtimes pass ``(backend, CommandProcessor)``. Ordinary
        desktop clients subclass CommonContext and pass ``(server_address,
        password)``. Supporting both signatures lets launcher components run
        unchanged inside the isolated patch host.
        """
        desktop_signature = (
            command_processor_type is not _MISSING and not callable(command_processor_type)
        ) or (
            command_processor_type is _MISSING and (backend is None or isinstance(backend, str))
        )
        if desktop_signature:
            self.server_address = backend
            self.password = None if command_processor_type is _MISSING else command_processor_type
            runtime_backend = None
            processor_type = getattr(type(self), "command_processor", AndroidClientCommandProcessor)
            if not callable(processor_type):
                processor_type = AndroidClientCommandProcessor
        else:
            self.server_address = None
            self.password = None
            runtime_backend = backend
            processor_type = (
                AndroidClientCommandProcessor
                if command_processor_type is _MISSING
                else command_processor_type
            )

        self.backend = runtime_backend
        self.bizhawk_ctx = SimpleNamespace(backend=runtime_backend)
        self.console_messages: list[dict[str, str]] = []
        self.android_actions: list[dict[str, str]] = []
        self.command_processor = processor_type(self)
        self.client_handler = None
        self.game = None
        self.auth = None
        self.items_handling = (
            getattr(type(self), "items_handling", None) if desktop_signature else None
        )
        if self.items_handling is None:
            self.items_handling = 0b111
        self.android_upstream_items_handling = int(self.items_handling)
        self.android_force_local_items = False
        self.want_slot_data = bool(
            getattr(type(self), "want_slot_data", True) if desktop_signature else True
        )
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
        self.slot_info: dict[int, Any] = {
            0: SimpleNamespace(name="Archipelago", game="Archipelago", type=0, group_members=()),
        }
        self.item_names = NameLookup(self)
        self.location_names = NameLookup(self)
        self.player_names: dict[int, str] = {0: "Archipelago"}
        self.slot_data: dict = {}
        self.data_package: dict[str, dict] = {}
        self.stored_data: dict[str, Any] = {}
        self.stored_data_notification_keys: set[str] = set()
        self.slot = None
        self.team = None
        self.finished_game = False
        self.ready = False
        self.tags: set[str] = set(
            getattr(type(self), "tags", set()) if desktop_signature else set()
        )
        self.outgoing: list[dict] = []
        self.disconnect_requested = False
        self.last_death_link = 0.0
        self.diagnostic = ""
        self.sync_requests = 0
        self.full_item_syncs = 0
        self.last_forced_item_sync = 0.0
        self.emulator_io_failures = 0
        self.emulator_writes = 0
        self.emulator_write_bytes = 0
        self.emulator_lifecycle = EmulatorLifecycle(self)
        self.ui = None
        self.server_task = None
        self.exit_event = asyncio.Event()
        self.watcher_event = asyncio.Event()

        # A conventional APWorld client creates this same context from its own
        # asyncio loop. The optional Android host records it so the managed
        # websocket transport can feed the upstream client unchanged.
        try:
            from android_registered_client_host import register_context
            register_context(self)
        except ImportError:
            pass

    def run_cli(self) -> None:
        """Desktop console lifecycle is owned by the companion UI."""

    def run_gui(self) -> None:
        """Desktop GUI lifecycle is owned by the companion UI."""

    async def shutdown(self) -> None:
        self.exit_event.set()
        self.watcher_event.set()

    async def get_username(self) -> None:
        return None

    def on_package(self, _cmd: str, _args: dict) -> None:
        """Desktop-compatible no-op overridden by ordinary game contexts."""

    async def send_connect(self) -> None:
        return None

    def console_message(self, kind: str, text: str) -> None:
        for line in str(text).splitlines() or [""]:
            self.console_messages.append({"kind": str(kind), "text": line})

    def request_android_action(self, action: str, **values: Any) -> None:
        self.android_actions.append({
            "action": str(action),
            **{str(key): str(value) for key, value in values.items() if value is not None},
        })

    async def send_msgs(self, messages: list[dict]) -> None:
        for message in messages:
            if message.get("cmd") == "StatusUpdate" and message.get("status") == 30:
                self.finished_game = True
            self.outgoing.append(plain(message))

    @property
    def player(self) -> int | None:
        return self.slot

    def set_notify(self, *keys: str) -> None:
        new_keys = set(map(str, keys)) - self.stored_data_notification_keys
        if not new_keys:
            return
        self.stored_data_notification_keys.update(new_keys)
        ordered = sorted(new_keys)
        self.outgoing.extend([
            {"cmd": "Get", "keys": ordered},
            {"cmd": "SetNotify", "keys": ordered},
        ])

    def gui_error(self, title: str, text: Any) -> None:
        self.diagnostic = f"{title or 'Error'}: {text}"

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


LOCAL_ITEMS_HANDLING_BIT = 0b010


def set_force_local_items(ctx: Any, enabled: bool) -> int:
    """Apply Android's opt-in local-item delivery policy without changing the upstream default."""
    current = int(getattr(ctx, "items_handling", 0b111) or 0)
    was_forced = bool(getattr(ctx, "android_force_local_items", False))
    if not hasattr(ctx, "android_upstream_items_handling") or not was_forced:
        ctx.android_upstream_items_handling = current
    ctx.android_force_local_items = bool(enabled)
    upstream = int(ctx.android_upstream_items_handling)
    ctx.items_handling = upstream | LOCAL_ITEMS_HANDLING_BIT if enabled else upstream
    return int(ctx.items_handling)


def _preserve_forced_local_items(ctx: Any, handling_before: int) -> None:
    """Keep the override active when an upstream handler recalculates handling after login."""
    handling_after = int(getattr(ctx, "items_handling", handling_before) or 0)
    if bool(getattr(ctx, "android_force_local_items", False)):
        if handling_after != handling_before:
            ctx.android_upstream_items_handling = handling_after
        ctx.items_handling = handling_after | LOCAL_ITEMS_HANDLING_BIT
    else:
        ctx.android_upstream_items_handling = handling_after


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
        ctx.console_message("status", f"Room information received · seed {ctx.seed_name or 'unknown'}")
    elif cmd == "DataPackage":
        games = args.get("data", {}).get("games", {})
        ctx.data_package.update(games)
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
        ctx.slot_info.clear()
        ctx.slot_info[0] = SimpleNamespace(
            name="Archipelago", game="Archipelago", type=0, group_members=(),
        )
        for player, data in args.get("slot_info", {}).items():
            if isinstance(data, dict):
                ctx.slot_info[int(player)] = SimpleNamespace(
                    name=str(data.get("name", player)),
                    game=str(data.get("game", "")),
                    type=int(data.get("type", 0)),
                    group_members=tuple(map(int, data.get("group_members", ()))),
                )
            elif isinstance(data, (list, tuple)) and len(data) >= 3:
                ctx.slot_info[int(player)] = SimpleNamespace(
                    name=str(data[0]),
                    game=str(data[1]),
                    type=int(data[2]),
                    group_members=tuple(map(int, data[3] if len(data) > 3 else ())),
                )
        ctx.server = SimpleNamespace(socket=SimpleNamespace(open=True, closed=False))
        update_players(ctx, args.get("players", []))
        ctx.console_message(
            "status",
            f"Connected as {ctx.player_names.get(ctx.slot, ctx.slot)} · {len(ctx.missing_locations)} locations remaining",
        )
        if ctx.locations_checked:
            ctx.outgoing.append({
                "cmd": "LocationChecks",
                "locations": sorted(ctx.locations_checked),
            })
        if ctx.stored_data_notification_keys:
            keys = sorted(ctx.stored_data_notification_keys)
            ctx.outgoing.extend([
                {"cmd": "Get", "keys": keys},
                {"cmd": "SetNotify", "keys": keys},
            ])
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
    elif cmd == "PrintJSON":
        ctx.console_message("server", format_print_json(ctx, args.get("data", [])))

    if cmd == "Bounced" and "DeathLink" in args.get("tags", []):
        data = args.get("data", {})
        if float(data.get("time", 0)) > ctx.last_death_link:
            ctx.on_deathlink(data)

    handling_before = int(getattr(ctx, "items_handling", 0b111) or 0)
    handler.on_package(ctx, cmd, args)
    _preserve_forced_local_items(ctx, handling_before)
    return cmd, args


def format_print_json(ctx: AndroidClientContext, parts: list[dict]) -> str:
    """Resolve Archipelago structured text with the same player/game lookups as desktop."""
    result: list[str] = []
    for part in parts:
        value = part.get("text", "")
        node_type = part.get("type", "text")
        try:
            if node_type == "player_id":
                value = ctx.player_names.get(int(value), str(value))
            elif node_type == "item_id":
                value = ctx.item_names.lookup_in_slot(int(value), int(part.get("player", ctx.slot or 0)))
            elif node_type == "location_id":
                value = ctx.location_names.lookup_in_slot(int(value), int(part.get("player", ctx.slot or 0)))
        except (TypeError, ValueError):
            pass
        result.append(str(value))
    return "".join(result)


async def execute_console_command(ctx: AndroidClientContext, raw: str) -> dict:
    """Run one line while the runtime event loop is active for async APWorld commands."""
    ctx.command_processor(str(raw).strip())
    await __import__("asyncio").sleep(0)
    return drain_console(ctx)


def drain_console(ctx: AndroidClientContext) -> dict:
    messages = ctx.console_messages
    actions = ctx.android_actions
    ctx.console_messages = []
    ctx.android_actions = []
    return {"console": messages, "actions": actions}


class _ConsoleLogHandler(logging.Handler):
    def __init__(self, ctx: AndroidClientContext) -> None:
        super().__init__(logging.INFO)
        self.ctx = ctx

    def emit(self, record: logging.LogRecord) -> None:
        try:
            kind = "error" if record.levelno >= logging.ERROR else "output"
            self.ctx.console_message(kind, _ANSI_ESCAPE.sub("", self.format(record)))
        except Exception:
            self.handleError(record)


@contextmanager
def capture_console_logs(ctx: AndroidClientContext):
    """Capture client logs produced during one command/watcher invocation."""
    root = logging.getLogger()
    previous_level = root.level
    handler = _ConsoleLogHandler(ctx)
    root.addHandler(handler)
    if previous_level > logging.INFO:
        root.setLevel(logging.INFO)
    try:
        yield
    finally:
        root.removeHandler(handler)
        root.setLevel(previous_level)


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
    ctx.slot_info.clear()
    ctx.slot_info[0] = SimpleNamespace(
        name="Archipelago", game="Archipelago", type=0, group_members=(),
    )
    ctx.slot_data = {}
    ctx.data_package.clear()
    ctx.stored_data.clear()
    ctx.slot = None
    ctx.team = None
    ctx.seed_name = None
    ctx.server_seed_name = None
    ctx.server = None
    ctx.finished_game = False
    ctx.ready = False
    ctx.outgoing.clear()
    ctx.android_actions.clear()
    ctx.disconnect_requested = False
