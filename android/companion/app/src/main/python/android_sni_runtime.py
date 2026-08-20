"""Run registered Archipelago SNI clients over RetroArch's UDP interface."""

from __future__ import annotations

import asyncio
import base64
import json
from typing import Any

from MultiServer import mark_raw
from SNIClient import DeathState, SNESState, snes_attached, snes_disconnected
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


class AndroidSNICommandProcessor(AndroidClientCommandProcessor):
    """SNI console commands adapted to Android's managed emulator bridge."""

    def _cmd_slow_mode(self, toggle: str = "") -> None:
        """Toggle slow mode, which limits how fast you send or receive items."""
        if toggle:
            self.ctx.slow_mode = toggle.lower() in {"1", "true", "on"}
        else:
            self.ctx.slow_mode = not self.ctx.slow_mode
        self.output(f"Setting slow mode to {self.ctx.slow_mode}")

    @mark_raw
    def _cmd_snes(self, _options: str = "") -> bool:
        """Reconnect the Android-managed SNES emulator bridge."""
        self.ctx.request_android_action("emulator_connect")
        self.output("Reconnecting the Android SNES emulator bridge…")
        return True

    def _cmd_snes_close(self) -> bool:
        """Pause the Android-managed SNES emulator bridge."""
        self.ctx.request_android_action("emulator_disconnect")
        self.output("Pausing the Android SNES emulator bridge…")
        return True


class AndroidSNIContext(AndroidClientContext):
    def __init__(self, backend: Any):
        super().__init__(backend, AndroidSNICommandProcessor)
        self.rom = None
        self.snes_write_buffer: list[tuple[int, bytes]] = []
        self.snes_state = SNESState.SNES_DISCONNECTED
        self.prev_rom = None
        self.awaiting_rom = False
        self.slow_mode = False
        self.locations_scouted: set[int] = set()
        self.hud_message_queue: list[str] = []
        self.death_state = DeathState.alive
        self.death_link_allow_survive = False
        self.allow_collect = False
        self.pending_death_link = False

    def on_deathlink(self, data: dict) -> None:
        super().on_deathlink(data)
        self.pending_death_link = True

    async def handle_deathlink_state(self, currently_dead: bool, death_text: str = "") -> None:
        if self.death_state == DeathState.alive and currently_dead:
            self.death_state = DeathState.dead
            await self.send_death(death_text)
        elif self.death_state == DeathState.dead and not currently_dead:
            self.death_state = DeathState.alive


class AndroidSNIRuntime:
    def __init__(self, work_directory: str, backend: Any):
        self.work_directory = work_directory
        self.backend = backend
        self.loop = asyncio.new_event_loop()
        self.handler = None
        self.client_type = None
        self.ctx = None
        self.last_error = ""
        self._load_clients()
        self._transport_attached()

    def _load_clients(self) -> None:
        from offline_generator import _load_standard_sni_clients
        _load_standard_sni_clients(self.work_directory)
        from worlds.AutoSNIClient import AutoSNIClientRegister
        self.client_types = tuple(dict.fromkeys(
            type(handler) for handler in AutoSNIClientRegister.game_handlers.values()
        ))

    def _run(self, awaitable):
        result = self.loop.run_until_complete(awaitable)
        self.loop.run_until_complete(asyncio.sleep(0))
        return result

    def _candidate(self, client_type, ctx=None):
        handler = client_type()
        ctx = ctx or AndroidSNIContext(self.backend)
        ctx.client_handler = handler
        ctx.rom = None
        if not self._run(handler.validate_rom(ctx)):
            return None, None
        if not ctx.rom:
            return None, None
        ctx.auth = base64.b64encode(bytes(ctx.rom)).decode("ascii")
        ctx.outgoing.clear()
        return handler, ctx

    def _transport_attached(self) -> None:
        if self.ctx is not None:
            snes_attached(self.ctx)

    def _transport_disconnected(self) -> None:
        if self.ctx is not None:
            snes_disconnected(self.ctx)

    def probe(self) -> str:
        self.last_error = ""
        handler = None
        ctx = self.ctx or AndroidSNIContext(self.backend)
        if ctx.snes_state != SNESState.SNES_ATTACHED:
            snes_attached(ctx)
        matched_type = None
        for client_type in self.client_types:
            try:
                handler, candidate_ctx = self._candidate(client_type, ctx)
            except Exception as exc:
                self.last_error = f"{type(exc).__name__}: {exc}"
                handler, candidate_ctx = None, None
            if handler is not None and candidate_ctx is not None:
                ctx = candidate_ctx
                matched_type = client_type
                break
        if handler is None:
            self.handler = None
            self.client_type = None
            self.ctx = ctx
            return json.dumps({"matched": False, "error": self.last_error})
        if ctx.prev_rom is not None and ctx.prev_rom != ctx.rom:
            ctx.locations_checked.clear()
            ctx.locations_scouted.clear()
            ctx.locations_info.clear()
        ctx.prev_rom = ctx.rom
        self.handler = handler
        self.client_type = matched_type
        self.ctx = ctx
        return json.dumps({
            "matched": True,
            "game": ctx.game,
            "auth": ctx.auth,
            "items_handling": int(ctx.items_handling),
            "want_slot_data": bool(ctx.want_slot_data),
            "seed_name": "",
            "client": f"{type(handler).__module__}.{type(handler).__name__}",
            "tags": sorted(ctx.tags),
        })

    def validate_active(self, game: str, auth: str) -> bool:
        if self.handler is None or self.ctx is None:
            return False
        if self.ctx.snes_state != SNESState.SNES_ATTACHED:
            return False
        try:
            self.ctx.rom = None
            valid = self._run(self.handler.validate_rom(self.ctx))
            current_auth = base64.b64encode(bytes(self.ctx.rom)).decode("ascii") if self.ctx.rom else ""
            return bool(valid and self.ctx.game == game and current_auth == auth)
        except Exception as exc:
            self.last_error = f"{type(exc).__name__}: {exc}"
            return False

    def process_packet(self, packet_json: str) -> None:
        if self.ctx is None or self.handler is None:
            return
        process_packet(self.ctx, self.handler, packet_json)

    def execute_command(self, raw: str) -> str:
        if self.ctx is None or self.handler is None:
            return json.dumps({
                "console": [{"kind": "error", "text": "No SNI game client is active."}],
                "actions": [],
            })
        with capture_console_logs(self.ctx):
            return json.dumps(plain(self._run(execute_console_command(self.ctx, raw))))

    def tick(self, watch_game: bool = True) -> str:
        if self.ctx is None or self.handler is None:
            return json.dumps({"messages": [], "disconnect": False, "error": "No SNI client is active"})
        error = ""
        self.ctx.emulator_lifecycle.begin_tick(watch_game)
        if watch_game and self.ctx.snes_state == SNESState.SNES_ATTACHED:
            try:
                self.ctx.rom = None
                rom_validated = self._run(self.handler.validate_rom(self.ctx))
                current_auth = (
                    base64.b64encode(bytes(self.ctx.rom)).decode("ascii")
                    if self.ctx.rom else ""
                )
                if not rom_validated or current_auth != self.ctx.auth:
                    if self.ctx.emulator_lifecycle.io_failed_this_tick:
                        raise RuntimeError(
                            "SNI memory temporarily unavailable; preserving the active room"
                        )
                    self.ctx.disconnect_requested = True
                    self.ctx.client_handler = None
                    self.ctx.rom = None
                    raise RuntimeError("ROM change or unavailable SNI device detected")
                if self.ctx.pending_death_link:
                    self.ctx.pending_death_link = False
                    self.ctx.death_state = DeathState.killing_player
                if self.ctx.death_state == DeathState.killing_player:
                    self._run(self.handler.deathlink_kill_player(self.ctx))
                with capture_console_logs(self.ctx):
                    self._run(self.handler.game_watcher(self.ctx))
            except Exception as exc:
                self.ctx.emulator_lifecycle.note_io_failure()
                error = f"{type(exc).__name__}: {exc}"
                self.last_error = error
                self.ctx.console_message("error", error)
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
        if self.ctx is None:
            return
        snes_attached(self.ctx)
        self.ctx.emulator_lifecycle.reattached()

    def emulator_detached(self) -> None:
        if self.ctx is None:
            return
        snes_disconnected(self.ctx)
        self.ctx.emulator_lifecycle.begin_tick(False)
        self.ctx.emulator_lifecycle.end_tick()

    def reset_connection(self) -> None:
        if self.ctx is None:
            return
        ctx = self.ctx
        reset_connection(ctx)
        ctx.pending_death_link = False
        ctx.death_state = DeathState.alive

    def close(self) -> None:
        pending = asyncio.all_tasks(self.loop)
        for task in pending:
            task.cancel()
        if pending:
            self.loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
        self.loop.close()
