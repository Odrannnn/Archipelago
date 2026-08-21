from __future__ import annotations

from collections import namedtuple
import json
from pathlib import Path
import sys
import asyncio
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch


PYTHON_SOURCE = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SOURCE))
sys.modules["NetUtils"] = SimpleNamespace(
    NetworkItem=namedtuple("NetworkItem", "item location player flags", defaults=(0,)),
)

import dolphin_memory_engine as dme
from android_dolphin_runtime import (
    AndroidDolphinContext,
    AndroidDolphinRuntime,
    DolphinGameAdapter,
    WindWakerDolphinAdapter,
)
from android_client_runtime import AndroidClientContext


class Backend:
    def __init__(self) -> None:
        self.hooked = False

    def hook(self) -> None:
        self.hooked = True

    def unHook(self) -> None:
        self.hooked = False

    def isHooked(self) -> bool:
        return self.hooked

    def isSocketConnected(self) -> bool:
        return True


class FakeDolphinAdapter(DolphinGameAdapter):
    game = "Generic GameCube Test"
    game_ids = frozenset({"TEST01"})

    def __init__(self) -> None:
        self.delivered = []

    def probe(self, ctx, game_id: str) -> bool:
        if game_id not in self.game_ids:
            return False
        ctx.game = self.game
        ctx.auth = "DolphinPlayer"
        return True

    def validate_active(self, ctx, game_id: str, auth: str) -> bool:
        return game_id in self.game_ids and auth == "DolphinPlayer"

    async def game_watcher(self, ctx) -> None:
        while len(self.delivered) < len(ctx.items_received):
            self.delivered.append(ctx.items_received[len(self.delivered)].item)
        if 101 in ctx.missing_locations:
            await ctx.check_locations({101})


class AndroidDolphinRuntimeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.backend = Backend()
        dme.configure_backend(self.backend)

    def tearDown(self) -> None:
        dme.configure_backend(None)

    def test_generic_adapter_uses_shared_packet_and_live_sync_lifecycle(self) -> None:
        adapter = FakeDolphinAdapter()
        runtime = AndroidDolphinRuntime("unused", [adapter])
        try:
            detected = json.loads(runtime.probe("TEST01"))
            self.assertTrue(detected["matched"])
            self.assertEqual(adapter.game, detected["game"])
            self.assertEqual("DolphinPlayer", detected["auth"])

            runtime.process_packet(json.dumps({
                "cmd": "Connected",
                "team": 0,
                "slot": 1,
                "checked_locations": [],
                "missing_locations": [101],
                "players": [{"team": 0, "slot": 1, "name": "DolphinPlayer"}],
                "slot_data": {},
            }))
            runtime.process_packet(json.dumps({
                "cmd": "ReceivedItems",
                "index": 0,
                "items": [{"item": 42, "location": 7, "player": 1, "flags": 0}],
            }))

            tick = json.loads(runtime.tick(True))
            self.assertEqual([42], adapter.delivered)
            self.assertIn({"cmd": "LocationChecks", "locations": [101]}, tick["messages"])
            self.assertFalse(tick["disconnect"])
            self.assertEqual("", tick["error"])
        finally:
            runtime.close()

    def test_reconnect_requests_one_upstream_style_full_item_sync(self) -> None:
        runtime = AndroidDolphinRuntime("unused", [FakeDolphinAdapter()])
        try:
            runtime.probe("TEST01")
            runtime.process_packet(json.dumps({
                "cmd": "Connected",
                "team": 0,
                "slot": 1,
                "checked_locations": [],
                "missing_locations": [],
                "players": [],
                "slot_data": {},
            }))
            runtime.emulator_detached()
            runtime.emulator_reattached()
            runtime.emulator_reattached()

            messages = json.loads(runtime.tick(True))["messages"]
            self.assertEqual(1, sum(message.get("cmd") == "Sync" for message in messages))
        finally:
            runtime.close()

    def test_unrecognized_game_id_does_not_claim_the_emulator(self) -> None:
        runtime = AndroidDolphinRuntime("unused", [FakeDolphinAdapter()])
        try:
            result = json.loads(runtime.probe("OTHER1"))
            self.assertFalse(result["matched"])
        finally:
            runtime.close()

    def test_registered_component_owns_upstream_game_loop_with_managed_packets(self) -> None:
        created = []
        component_args = []

        class ImportedContext(AndroidClientContext):
            game = "Imported GameCube Game"
            items_handling = 0b101

            def __init__(self, address, password):
                super().__init__(address, password)
                self.packages = []
                created.append(self)

            def on_package(self, cmd, args):
                self.packages.append(cmd)
                if cmd == "Connected":
                    self.outgoing.append({"cmd": "LocationChecks", "locations": [123]})

        def run_component(*args):
            component_args.extend(args)

            async def main():
                ctx = ImportedContext("example.test:38281", "secret")
                await ctx.exit_event.wait()

            asyncio.run(main())

        component = SimpleNamespace(display_name="Imported Client", func=run_component)
        runtime = AndroidDolphinRuntime("unused", [])
        try:
            with patch("offline_generator._prepare_runtime"), patch(
                "offline_generator._client_component_for_game",
                return_value=component,
            ):
                detected = json.loads(runtime.probe_registered(
                    "GAME01",
                    "Imported GameCube Game",
                    "Player1",
                    "example.test:38281",
                    "secret",
                ))
                self.assertTrue(detected["matched"])
                self.assertEqual("Player1", detected["auth"])
                self.assertEqual(0b101, detected["items_handling"])
                self.assertEqual(
                    ["--connect", "example.test:38281", "--password", "secret"],
                    component_args,
                )

                runtime.process_packet(json.dumps({
                    "cmd": "Connected",
                    "team": 0,
                    "slot": 1,
                    "checked_locations": [],
                    "missing_locations": [123],
                    "players": [{"team": 0, "slot": 1, "name": "Player1"}],
                    "slot_data": {},
                }))
                deadline = time.monotonic() + 2.0
                tick = {"messages": []}
                while time.monotonic() < deadline:
                    tick = json.loads(runtime.tick(True))
                    if created[0].packages and tick["messages"]:
                        break
                    time.sleep(0.01)
                self.assertEqual(["Connected"], created[0].packages)
                self.assertIn({"cmd": "LocationChecks", "locations": [123]}, tick["messages"])
        finally:
            runtime.close()

    def test_registered_component_without_dolphin_api_is_not_started(self) -> None:
        runtime = AndroidDolphinRuntime("unused", [])
        try:
            with patch(
                "offline_generator._registered_client_backends",
                return_value={"sni"},
            ) as detect, patch("android_registered_client_host.start_or_probe") as start:
                result = json.loads(runtime.probe_registered(
                    "GAME01",
                    "Imported SNES Game",
                    "Player1",
                    "example.test:38281",
                    "",
                ))
                runtime.probe_registered(
                    "GAME01",
                    "Imported SNES Game",
                    "Player1",
                    "example.test:38281",
                    "",
                )

            self.assertFalse(result["matched"])
            self.assertTrue(result["unsupported"])
            detect.assert_called_once_with("Imported SNES Game")
            start.assert_not_called()
        finally:
            runtime.close()

    def test_registered_bridge_does_not_wait_for_busy_upstream_event_loop(self) -> None:
        loop_blocked = threading.Event()
        release_loop = threading.Event()

        class BusyContext(AndroidClientContext):
            game = "Busy GameCube Game"

            def __init__(self, address, password):
                super().__init__(address, password)

        def run_component(*_args):
            async def main():
                ctx = BusyContext("example.test:38281", None)
                # Let the host's deferred context-ready callback run first,
                # then model an upstream watcher doing synchronous DME scans.
                await asyncio.sleep(0)
                loop_blocked.set()
                release_loop.wait(2.0)
                await ctx.exit_event.wait()

            asyncio.run(main())

        component = SimpleNamespace(display_name="Busy Client", func=run_component)
        runtime = AndroidDolphinRuntime("unused", [])
        try:
            with patch("offline_generator._prepare_runtime"), patch(
                "offline_generator._client_component_for_game",
                return_value=component,
            ):
                detected = json.loads(runtime.probe_registered(
                    "BUSY01",
                    "Busy GameCube Game",
                    "Player1",
                    "example.test:38281",
                    "",
                ))
                self.assertTrue(detected["matched"])
                self.assertTrue(loop_blocked.wait(1.0))

                started = time.monotonic()
                tick = json.loads(runtime.tick(True))
                self.assertLess(time.monotonic() - started, 0.5)
                self.assertEqual("", tick["error"])

                started = time.monotonic()
                runtime.reset_connection()
                self.assertLess(time.monotonic() - started, 0.5)
        finally:
            release_loop.set()
            runtime.close()

    def test_wind_waker_defers_chart_memory_reads_until_game_watcher(self) -> None:
        adapter = WindWakerDolphinAdapter.__new__(WindWakerDolphinAdapter)
        adapter.client = SimpleNamespace(
            AP_VISITED_STAGE_NAMES_KEY_FORMAT="tww_visited_stage_names_%s",
            check_ingame=lambda: True,
        )
        ctx = AndroidDolphinContext()
        ctx.salvage_locations_map = {"stale": 1}

        adapter.on_package(ctx, "Connected", {"slot_data": {}})
        self.assertEqual({}, ctx.salvage_locations_map)

        initialized = []
        adapter._update_salvage_locations_map = lambda current: (
            initialized.append(True),
            setattr(current, "salvage_locations_map", {"ready": 1}),
        )
        ctx.slot = None
        runtime = AndroidDolphinRuntime("unused", [adapter])
        try:
            runtime.adapter = adapter
            runtime.ctx = ctx
            tick = json.loads(runtime.tick(True))
            self.assertEqual([True], initialized)
            self.assertEqual({"ready": 1}, ctx.salvage_locations_map)
            self.assertEqual("", tick["error"])
        finally:
            runtime.close()

    def test_dolphin_deathlink_is_deferred_until_game_watcher(self) -> None:
        ctx = AndroidDolphinContext()
        memory_calls = []
        ctx.death_link_handler = lambda: memory_calls.append(True)

        ctx.on_deathlink({"time": 123.0, "source": "Other player"})

        self.assertEqual([], memory_calls)
        self.assertTrue(ctx.pending_death_link)


if __name__ == "__main__":
    unittest.main()
