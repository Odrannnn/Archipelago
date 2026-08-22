"""Host an APWorld's registered desktop client behind Android's managed transport.

The APWorld keeps ownership of its ordinary context and game watcher. Android
only supplies the selected room identity, the emulator compatibility module,
and the existing websocket session. This keeps imported clients on the same
code path as their desktop entry points without opening a duplicate server
connection.
"""

from __future__ import annotations

import asyncio
import json
import logging
import threading
import time
from typing import Any

from android_client_runtime import (
    drain_console,
    execute_console_command,
    plain,
    process_packet,
    reset_connection,
    set_force_local_items as set_context_force_local_items,
)


_active_host: "RegisteredClientHost | None" = None
_active_lock = threading.RLock()


def register_context(ctx: Any) -> None:
    """Called by ``CommonContext`` when a registered client creates its context."""
    with _active_lock:
        host = _active_host
    if host is not None:
        host.register_context(ctx)


class _ContextPacketHandler:
    def on_package(self, ctx: Any, cmd: str, args: dict[str, Any]) -> None:
        ctx.on_package(cmd, args)


class _ClientLogHandler(logging.Handler):
    def __init__(self, host: "RegisteredClientHost") -> None:
        super().__init__(logging.INFO)
        self.host = host

    def emit(self, record: logging.LogRecord) -> None:
        if threading.current_thread() is not self.host.thread:
            return
        try:
            kind = "error" if record.levelno >= logging.ERROR else "output"
            self.host.console_message(kind, self.format(record))
        except Exception:
            self.handleError(record)


class RegisteredClientHost:
    """Long-lived, stoppable host for one conventional ``Type.CLIENT`` component."""

    def __init__(
        self,
        work_directory: str,
        game: str,
        player_name: str,
        server_address: str,
        password: str,
        game_id: str,
    ) -> None:
        self.work_directory = str(work_directory)
        self.game = str(game)
        self.player_name = str(player_name)
        self.server_address = str(server_address)
        self.password = str(password)
        self.game_id = str(game_id)
        self.ctx: Any | None = None
        self.loop: asyncio.AbstractEventLoop | None = None
        self.component: Any | None = None
        self.error = ""
        self._console: list[dict[str, str]] = []
        self._bridge_messages: list[dict[str, Any]] = []
        self._bridge_console: list[dict[str, str]] = []
        self._bridge_disconnect = False
        self._bridge_diagnostic = ""
        self._bridge_lock = threading.RLock()
        self._tick_scheduled = False
        self._emulator_available = False
        self._context_ready = threading.Event()
        self._closed = threading.Event()
        self.thread = threading.Thread(
            target=self._run_component,
            name="apworld-live-client",
            daemon=True,
        )

    @property
    def identity(self) -> tuple[str, str, str, str]:
        return self.game, self.player_name, self.server_address, self.game_id

    @property
    def is_alive(self) -> bool:
        return self.thread.is_alive() and not self._closed.is_set()

    def start(self) -> None:
        global _active_host
        from offline_generator import _client_component_for_game, _prepare_runtime

        _prepare_runtime(self.work_directory)
        self.component = _client_component_for_game(self.game)
        if self.component is None:
            raise ValueError(f"{self.game} does not register a desktop client component")
        with _active_lock:
            if _active_host not in (None, self):
                raise RuntimeError("Another registered APWorld client is already active")
            _active_host = self
        self.thread.start()

    def _run_component(self) -> None:
        handler = _ClientLogHandler(self)
        root = logging.getLogger()
        previous_level = root.level
        root.addHandler(handler)
        if previous_level > logging.INFO:
            root.setLevel(logging.INFO)
        try:
            args: list[str] = []
            if self.server_address:
                args.extend(("--connect", self.server_address))
            if self.password:
                args.extend(("--password", self.password))
            self.component.func(*args)
        except BaseException as exc:
            self.error = f"{type(exc).__name__}: {exc}"
            logging.getLogger(__name__).exception("Registered APWorld client component failed")
            self.console_message("error", self.error)
        finally:
            root.removeHandler(handler)
            root.setLevel(previous_level)
            self._closed.set()
            self._context_ready.set()

    def register_context(self, ctx: Any) -> None:
        if threading.current_thread() is not self.thread or self.ctx is not None:
            return
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            return
        self.ctx = ctx
        self.loop = loop
        if not getattr(ctx, "auth", None):
            ctx.auth = self.player_name
        if not getattr(ctx, "game", None):
            ctx.game = self.game
        # The hook runs from the base CommonContext constructor. Defer exposing
        # the context until the subclass constructor has returned to its loop.
        loop.call_soon(self._context_initialized, ctx)

    def _context_initialized(self, ctx: Any) -> None:
        if self.ctx is not ctx:
            return
        self._context_ready.set()
        self.console_message(
            "status",
            f"Registered desktop client active · {ctx.game or self.game} · {ctx.auth or self.player_name}",
        )

    def console_message(self, kind: str, text: str) -> None:
        target = self.ctx
        if target is not None:
            target.console_message(kind, str(text))
        else:
            self._console.extend(
                {"kind": str(kind), "text": line}
                for line in (str(text).splitlines() or [""])
            )

    def probe(self, wait_seconds: float = 0.0) -> dict[str, Any]:
        if wait_seconds > 0:
            self._context_ready.wait(wait_seconds)
        ctx = self.ctx
        if ctx is None or not self._context_ready.is_set():
            return {
                "matched": False,
                "pending": self.is_alive,
                "error": self.error,
            }
        return {
            "matched": self.is_alive,
            "game": str(ctx.game or self.game),
            "auth": str(ctx.auth or self.player_name),
            "items_handling": int(ctx.items_handling if ctx.items_handling is not None else 7),
            "want_slot_data": bool(ctx.want_slot_data),
            "seed_name": str(ctx.seed_name or ""),
            "client": f"registered:{self.component.display_name}",
            "tags": sorted(map(str, ctx.tags)),
            "error": self.error,
        }

    def _submit(self, coroutine: Any, timeout: float = 10.0) -> Any:
        loop = self.loop
        if loop is None or not loop.is_running():
            raise RuntimeError(self.error or "Registered APWorld client event loop is unavailable")
        return asyncio.run_coroutine_threadsafe(coroutine, loop).result(timeout)

    def process_packet(self, packet_json: str) -> None:
        ctx = self.ctx
        if ctx is None:
            return
        self._call_soon(self._deliver_packet, ctx, packet_json)

    def set_force_local_items(self, enabled: bool) -> int:
        ctx = self.ctx
        if ctx is None:
            return 0b111 | (0b010 if enabled else 0)

        async def apply() -> int:
            return set_context_force_local_items(ctx, bool(enabled))

        return int(self._submit(apply()))

    def _call_soon(self, callback: Any, *args: Any) -> None:
        loop = self.loop
        if loop is None or not loop.is_running():
            raise RuntimeError(self.error or "Registered APWorld client event loop is unavailable")
        loop.call_soon_threadsafe(callback, *args)

    def _deliver_packet(self, ctx: Any, packet_json: str) -> None:
        try:
            process_packet(ctx, _ContextPacketHandler(), packet_json)
        except Exception as exc:
            self._record_async_error("packet handler", exc)

    def execute_command(self, raw: str) -> dict[str, Any]:
        if self.ctx is None:
            return {
                "console": [{"kind": "error", "text": "Registered APWorld client is still starting."}],
                "actions": [],
            }
        return plain(self._submit(execute_console_command(self.ctx, raw)))

    def tick(self, emulator_available: bool) -> dict[str, Any]:
        ctx = self.ctx
        if ctx is None:
            return {
                "messages": [],
                "console": self._drain_pre_context_console(),
                "disconnect": False,
                "error": self.error,
                "diagnostic": "Registered APWorld client is starting.",
            }

        # A desktop client's watcher and websocket normally share one asyncio
        # loop. Some GameCube watchers perform long synchronous memory scans on
        # that loop. Never make Android's websocket/service thread wait for the
        # scan: request a same-loop drain and return the most recently captured
        # mailbox contents. This preserves upstream ordering without a second
        # network connection or an arbitrary timeout.
        with self._bridge_lock:
            self._emulator_available = bool(emulator_available)
            schedule_tick = not self._tick_scheduled
            if schedule_tick:
                self._tick_scheduled = True
        if schedule_tick:
            try:
                self._call_soon(self._capture_tick, ctx)
            except Exception:
                with self._bridge_lock:
                    self._tick_scheduled = False
                raise

        with self._bridge_lock:
            messages = self._bridge_messages
            console = self._bridge_console
            disconnect = self._bridge_disconnect
            self._bridge_messages = []
            self._bridge_console = []
            self._bridge_disconnect = False
            diagnostic = self._bridge_diagnostic
        return {
            "messages": messages,
            "console": console,
            "disconnect": disconnect,
            "error": self.error,
            "diagnostic": diagnostic,
        }

    def _capture_tick(self, ctx: Any) -> None:
        try:
            with self._bridge_lock:
                emulator_available = self._emulator_available
            ctx.emulator_lifecycle.begin_tick(emulator_available)
            ctx.emulator_lifecycle.end_tick()
            messages = ctx.outgoing
            ctx.outgoing = []
            disconnect = bool(ctx.disconnect_requested)
            ctx.disconnect_requested = False
            console = self._drain_pre_context_console() + drain_console(ctx)["console"]
            with self._bridge_lock:
                self._bridge_messages.extend(plain(messages))
                self._bridge_console.extend(plain(console))
                self._bridge_disconnect = self._bridge_disconnect or disconnect
                self._bridge_diagnostic = str(ctx.diagnostic)
        except Exception as exc:
            self._record_async_error("bridge mailbox", exc)
        finally:
            with self._bridge_lock:
                self._tick_scheduled = False

    def _record_async_error(self, operation: str, exc: Exception) -> None:
        message = f"{type(exc).__name__}: {exc}"
        self.error = message
        logging.getLogger(__name__).exception(
            "Registered APWorld client %s failed", operation,
        )
        with self._bridge_lock:
            self._bridge_console.append({"kind": "error", "text": message})

    def _drain_pre_context_console(self) -> list[dict[str, str]]:
        messages = self._console
        self._console = []
        return messages

    def reset_connection(self) -> None:
        ctx = self.ctx
        if ctx is None:
            return
        with self._bridge_lock:
            self._bridge_messages = []
            self._bridge_disconnect = False
        try:
            self._call_soon(self._reset_connection, ctx)
        except RuntimeError:
            # Cleanup must remain idempotent when the component has already
            # exited or its loop is shutting down.
            if self.is_alive:
                raise

    def _reset_connection(self, ctx: Any) -> None:
        try:
            reset_connection(ctx)
        except Exception as exc:
            self._record_async_error("connection reset", exc)

    def validate(self, game: str, auth: str, game_id: str) -> bool:
        ctx = self.ctx
        return bool(
            ctx is not None
            and self.is_alive
            and str(ctx.game or self.game) == str(game)
            and str(ctx.auth or self.player_name) == str(auth)
            and self.game_id == str(game_id)
        )

    def emulator_reattached(self) -> None:
        if self.ctx is not None:
            self._call_soon(self.ctx.emulator_lifecycle.reattached)

    def emulator_detached(self) -> None:
        if self.ctx is not None:
            self._call_soon(self._mark_emulator_detached, self.ctx)

    @staticmethod
    def _mark_emulator_detached(ctx: Any) -> None:
        ctx.emulator_lifecycle.begin_tick(False)
        ctx.emulator_lifecycle.end_tick()

    def close(self) -> None:
        global _active_host
        self._closed.set()
        ctx = self.ctx
        loop = self.loop
        if ctx is not None and loop is not None and loop.is_running():
            async def stop() -> None:
                ctx.exit_event.set()
                ctx.watcher_event.set()
                await asyncio.sleep(0)

            try:
                asyncio.run_coroutine_threadsafe(stop(), loop).result(3.0)
            except Exception:
                pass
        if self.thread.is_alive() and threading.current_thread() is not self.thread:
            self.thread.join(4.0)
        with _active_lock:
            if _active_host is self:
                _active_host = None


def start_or_probe(
    current: RegisteredClientHost | None,
    work_directory: str,
    game: str,
    player_name: str,
    server_address: str,
    password: str,
    game_id: str,
) -> tuple[RegisteredClientHost | None, str]:
    identity = (str(game), str(player_name), str(server_address), str(game_id))
    if current is not None and current.identity != identity:
        current.close()
        current = None
    if current is None:
        current = RegisteredClientHost(
            work_directory,
            game,
            player_name,
            server_address,
            password,
            game_id,
        )
        try:
            current.start()
        except Exception as exc:
            current.close()
            return None, json.dumps({"matched": False, "error": f"{type(exc).__name__}: {exc}"})
    return current, json.dumps(current.probe(0.25))
