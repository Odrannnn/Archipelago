"""Run built-in Dolphin Memory Engine clients through Android's managed room transport."""

from __future__ import annotations

import asyncio
import json
from typing import Any, Iterable

import dolphin_memory_engine as dme

from android_client_runtime import (
    AndroidClientCommandProcessor,
    AndroidClientContext,
    capture_console_logs,
    drain_console,
    execute_console_command,
    plain,
    process_packet,
    reset_connection,
)


class AndroidDolphinCommandProcessor(AndroidClientCommandProcessor):
    def _cmd_dolphin(self) -> bool:
        """Display the managed Dolphin memory connection status."""
        self.output(f"Dolphin Status: {self.ctx.dolphin_status}")
        return True


class AndroidDolphinContext(AndroidClientContext):
    def __init__(self) -> None:
        super().__init__(None, AndroidDolphinCommandProcessor)
        self.dolphin_status = "Dolphin connection has not been initiated."
        self.pending_death_link = False
        self.death_link_handler = None

    def on_deathlink(self, data: dict[str, Any]) -> None:
        super().on_deathlink(data)
        # Server packet handling must never touch Dolphin memory. The watcher
        # consumes this only while the Android memory transport is available.
        self.pending_death_link = True


class DolphinGameAdapter:
    """Game-facing contract; transport and Archipelago lifecycle stay shared."""

    game = ""
    game_ids: frozenset[str] = frozenset()
    items_handling = 0b111
    want_slot_data = True

    def probe(self, ctx: AndroidDolphinContext, game_id: str) -> bool:
        raise NotImplementedError

    def validate_active(self, ctx: AndroidDolphinContext, game_id: str, auth: str) -> bool:
        raise NotImplementedError

    def on_package(self, ctx: AndroidDolphinContext, cmd: str, args: dict[str, Any]) -> None:
        return None

    async def game_watcher(self, ctx: AndroidDolphinContext) -> None:
        raise NotImplementedError

    def reset_connection(self, ctx: AndroidDolphinContext) -> None:
        return None


class WindWakerDolphinAdapter(DolphinGameAdapter):
    """Thin lifecycle adapter around the bundled upstream TWW client functions."""

    game = "The Wind Waker"
    game_ids = frozenset({"GZLE99"})

    def __init__(self) -> None:
        from worlds.tww import TWWClient as client

        self.client = client

    def probe(self, ctx: AndroidDolphinContext, game_id: str) -> bool:
        if game_id not in self.game_ids or dme.read_bytes(0x80000000, 6) != b"GZLE99":
            return False
        auth = self.client.read_string(self.client.SLOT_NAME_ADDR, 0x40)
        if not auth:
            return False
        ctx.game = self.game
        ctx.auth = auth
        ctx.items_handling = self.items_handling
        ctx.want_slot_data = self.want_slot_data
        ctx.dolphin_status = self.client.CONNECTION_CONNECTED_STATUS
        ctx.awaiting_rom = False
        ctx.has_send_death = False
        ctx.received_magic_idx = -1
        ctx.salvage_locations_map = {}
        ctx.current_stage_name = ""
        ctx.visited_stage_names = None
        ctx.len_give_item_array = 0x10
        ctx.death_link_handler = lambda: self.client._give_death(ctx)
        return True

    def validate_active(self, ctx: AndroidDolphinContext, game_id: str, auth: str) -> bool:
        return (
            game_id in self.game_ids
            and dme.read_bytes(0x80000000, 6) == b"GZLE99"
            and self.client.read_string(self.client.SLOT_NAME_ADDR, 0x40) == auth
        )

    def _update_salvage_locations_map(self, ctx: AndroidDolphinContext) -> None:
        ctx.salvage_locations_map = {}
        for offset in range(49):
            island_name = self.client.ISLAND_NUMBER_TO_NAME[offset + 1]
            salvage_bit = self.client.ISLAND_NAME_TO_SALVAGE_BIT[island_name]
            shuffled_number = self.client.read_short(self.client.CHARTS_MAPPING_ADDR + offset * 2)
            shuffled_name = self.client.ISLAND_NUMBER_TO_NAME[shuffled_number]
            ctx.salvage_locations_map[f"{shuffled_name} - Sunken Treasure"] = salvage_bit

    def on_package(self, ctx: AndroidDolphinContext, cmd: str, args: dict[str, Any]) -> None:
        if cmd == "Connected":
            # Server packets must remain safe to process while Dolphin is detached. In
            # particular, a room reconnect can complete after Android has suspended or
            # closed Dolphin. Reading the chart table here would then turn a successful
            # Archipelago handshake into a session failure/reconnect loop.
            ctx.salvage_locations_map = {}
            death_link = bool(args.get("slot_data", {}).get("death_link", False))
            changed = death_link != ("DeathLink" in ctx.tags)
            if death_link:
                ctx.tags.add("DeathLink")
            else:
                ctx.tags.discard("DeathLink")
            if changed:
                ctx.outgoing.append({"cmd": "ConnectUpdate", "tags": sorted(ctx.tags)})
            if ctx.slot is not None:
                key = self.client.AP_VISITED_STAGE_NAMES_KEY_FORMAT % ctx.slot
                ctx.outgoing.append({"cmd": "Get", "keys": [key]})
        elif cmd == "Retrieved" and ctx.slot is not None:
            key = self.client.AP_VISITED_STAGE_NAMES_KEY_FORMAT % ctx.slot
            keys = args.get("keys", {})
            if key in keys:
                stored = keys[key]
                visited = set() if stored is None else set(stored.keys())
                if ctx.current_stage_name and ctx.current_stage_name not in visited:
                    visited.add(ctx.current_stage_name)
                    ctx.outgoing.append(self._visited_stage_message(ctx, ctx.current_stage_name))
                ctx.visited_stage_names = visited

    def _visited_stage_message(self, ctx: AndroidDolphinContext, stage_name: str) -> dict[str, Any]:
        return {
            "cmd": "Set",
            "key": self.client.AP_VISITED_STAGE_NAMES_KEY_FORMAT % ctx.slot,
            "default": {},
            "want_reply": False,
            "operations": [{"operation": "update", "value": {stage_name: True}}],
        }

    async def game_watcher(self, ctx: AndroidDolphinContext) -> None:
        async def update_visited_stages(stage_name: str) -> None:
            if ctx.slot is not None:
                await ctx.send_msgs([self._visited_stage_message(ctx, stage_name)])

        ctx.update_visited_stages = update_visited_stages
        if not ctx.salvage_locations_map:
            self._update_salvage_locations_map(ctx)
        if not self.client.check_ingame():
            dme.write_bytes(
                self.client.GIVE_ITEM_ARRAY_ADDR,
                bytes([0xFF] * ctx.len_give_item_array),
            )
            return
        if ctx.slot is None:
            return
        if ctx.pending_death_link:
            ctx.pending_death_link = False
            self.client._give_death(ctx)
        if "DeathLink" in ctx.tags:
            await self.client.check_death(ctx)
        await self.client.give_items(ctx)
        await self.client.check_locations(ctx)
        await self.client.check_current_stage_changed(ctx)

    def reset_connection(self, ctx: AndroidDolphinContext) -> None:
        ctx.salvage_locations_map = {}
        ctx.current_stage_name = ""
        ctx.visited_stage_names = None
        ctx.pending_death_link = False
        ctx.has_send_death = False


def built_in_dolphin_adapters() -> tuple[DolphinGameAdapter, ...]:
    """One registry keeps the runtime independent of individual game loops."""
    return (WindWakerDolphinAdapter(),)


def built_in_dolphin_games() -> set[str]:
    return {WindWakerDolphinAdapter.game}


class AndroidDolphinRuntime:
    def __init__(self, work_directory: str, adapters: Iterable[DolphinGameAdapter] | None = None):
        self.work_directory = work_directory
        self.loop = asyncio.new_event_loop()
        self.adapters = tuple(adapters) if adapters is not None else self._load_adapters()
        self.adapter: DolphinGameAdapter | None = None
        self.ctx: AndroidDolphinContext | None = None
        self.registered_host = None
        self.game_id = ""
        self.last_error = ""

    def _load_adapters(self) -> tuple[DolphinGameAdapter, ...]:
        from offline_generator import _load_emulator_clients

        _load_emulator_clients(self.work_directory)
        return built_in_dolphin_adapters()

    def _run(self, awaitable):
        result = self.loop.run_until_complete(awaitable)
        self.loop.run_until_complete(asyncio.sleep(0))
        return result

    def probe(self, game_id: str) -> str:
        self.last_error = ""
        self.game_id = str(game_id)
        for adapter in self.adapters:
            if adapter.game_ids and self.game_id not in adapter.game_ids:
                continue
            ctx = AndroidDolphinContext()
            ctx.client_handler = adapter
            try:
                dme.hook()
                if not dme.is_hooked() or not adapter.probe(ctx, self.game_id):
                    continue
            except Exception as exc:
                self.last_error = f"{type(exc).__name__}: {exc}"
                continue
            self.adapter = adapter
            self.ctx = ctx
            return json.dumps({
                "matched": True,
                "game": ctx.game or adapter.game,
                "auth": ctx.auth or "",
                "items_handling": int(ctx.items_handling),
                "want_slot_data": bool(ctx.want_slot_data),
                "seed_name": "",
                "client": f"{type(adapter).__module__}.{type(adapter).__name__}",
                "tags": sorted(ctx.tags),
            })
        self.adapter = None
        self.ctx = None
        return json.dumps({"matched": False, "error": self.last_error})

    def probe_registered(
        self,
        game_id: str,
        game: str,
        player_name: str,
        server_address: str,
        password: str,
    ) -> str:
        """Start or inspect the APWorld's ordinary registered client entry point."""
        from android_registered_client_host import start_or_probe

        self.game_id = str(game_id)
        self.registered_host, result = start_or_probe(
            self.registered_host,
            self.work_directory,
            str(game),
            str(player_name),
            str(server_address),
            str(password),
            self.game_id,
        )
        parsed = json.loads(result)
        self.last_error = str(parsed.get("error", ""))
        return result

    def validate_active(self, game: str, auth: str, game_id: str) -> bool:
        if self.registered_host is not None:
            return self.registered_host.validate(game, auth, game_id)
        if self.adapter is None or self.ctx is None or game != self.adapter.game:
            return False
        try:
            self.game_id = str(game_id)
            return bool(self.adapter.validate_active(self.ctx, self.game_id, auth))
        except Exception as exc:
            self.last_error = f"{type(exc).__name__}: {exc}"
            return False

    def process_packet(self, packet_json: str) -> None:
        if self.registered_host is not None:
            self.registered_host.process_packet(packet_json)
            return
        if self.ctx is not None and self.adapter is not None:
            process_packet(self.ctx, self.adapter, packet_json)

    def execute_command(self, raw: str) -> str:
        if self.registered_host is not None:
            return json.dumps(plain(self.registered_host.execute_command(raw)))
        if self.ctx is None or self.adapter is None:
            return json.dumps({
                "console": [{"kind": "error", "text": "No Dolphin game client is active."}],
                "actions": [],
            })
        with capture_console_logs(self.ctx):
            return json.dumps(plain(self._run(execute_console_command(self.ctx, raw))))

    def tick(self, emulator_available: bool = True) -> str:
        if self.registered_host is not None:
            return json.dumps(plain(self.registered_host.tick(emulator_available)))
        if self.ctx is None or self.adapter is None:
            return json.dumps({"messages": [], "disconnect": False, "error": "No Dolphin client is active"})
        error = ""
        self.ctx.emulator_lifecycle.begin_tick(emulator_available)
        if emulator_available:
            try:
                if not dme.is_hooked():
                    dme.hook()
                if not dme.is_hooked():
                    raise RuntimeError("Dolphin memory service is not hooked")
                with capture_console_logs(self.ctx):
                    self._run(self.adapter.game_watcher(self.ctx))
                self.ctx.dolphin_status = "Dolphin connected successfully."
            except Exception as exc:
                self.ctx.emulator_lifecycle.note_io_failure()
                error = f"{type(exc).__name__}: {exc}"
                self.last_error = error
                self.ctx.console_message("error", error)
                self.ctx.dolphin_status = "Dolphin connection was lost."
                dme.un_hook()
        self.ctx.emulator_lifecycle.end_tick()
        messages = self.ctx.outgoing
        self.ctx.outgoing = []
        disconnect = self.ctx.disconnect_requested
        self.ctx.disconnect_requested = False
        console = drain_console(self.ctx)["console"]
        return json.dumps({
            "messages": plain(messages),
            "console": plain(console),
            "disconnect": disconnect,
            "error": error,
            "diagnostic": self.ctx.diagnostic,
        })

    def emulator_reattached(self) -> None:
        dme.hook()
        if self.registered_host is not None:
            self.registered_host.emulator_reattached()
            return
        if self.ctx is not None:
            self.ctx.dolphin_status = "Dolphin connected successfully."
            self.ctx.emulator_lifecycle.reattached()

    def emulator_detached(self) -> None:
        if self.registered_host is not None:
            self.registered_host.emulator_detached()
            return
        if self.ctx is not None:
            self.ctx.dolphin_status = "Dolphin connection was lost."
            self.ctx.emulator_lifecycle.begin_tick(False)
            self.ctx.emulator_lifecycle.end_tick()

    def reset_connection(self) -> None:
        if self.registered_host is not None:
            self.registered_host.reset_connection()
            return
        if self.ctx is None:
            return
        reset_connection(self.ctx)
        if self.adapter is not None:
            self.adapter.reset_connection(self.ctx)

    def close(self) -> None:
        if self.registered_host is not None:
            self.registered_host.close()
            self.registered_host = None
        pending = asyncio.all_tasks(self.loop)
        for task in pending:
            task.cancel()
        if pending:
            self.loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
        self.loop.close()
