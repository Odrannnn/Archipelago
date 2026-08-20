from __future__ import annotations

from collections import namedtuple
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest


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


if __name__ == "__main__":
    unittest.main()
