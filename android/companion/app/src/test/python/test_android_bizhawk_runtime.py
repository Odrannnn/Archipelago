from __future__ import annotations

from collections import namedtuple
import asyncio
import json
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import patch


PYTHON_SOURCE = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SOURCE))
sys.modules["NetUtils"] = SimpleNamespace(
    NetworkItem=namedtuple("NetworkItem", "item location player flags", defaults=(0,)),
)

from android_bizhawk_runtime import AndroidBizHawkRuntime, AndroidLadxClient, _LadxCheck
from android_client_runtime import AndroidClientContext


class UpstreamStyleHandler:
    game = "The Legend of Zelda - Oracle of Seasons"

    def __init__(self) -> None:
        self.packets = []

    def on_package(self, ctx, cmd, args) -> None:
        self.packets.append((cmd, args))


class AndroidBizHawkParityTest(unittest.TestCase):
    def runtime(self) -> AndroidBizHawkRuntime:
        runtime = AndroidBizHawkRuntime.__new__(AndroidBizHawkRuntime)
        runtime.handler = UpstreamStyleHandler()
        runtime.ctx = AndroidClientContext(object())
        runtime.ctx.client_handler = runtime.handler
        runtime.ctx.game = runtime.handler.game
        runtime.ctx.auth = "Player1"
        return runtime

    def test_generic_apworld_does_not_scout_checked_locations_for_recovery(self) -> None:
        runtime = self.runtime()

        runtime.process_packet(json.dumps({
            "cmd": "Connected",
            "team": 0,
            "slot": 1,
            "checked_locations": [101, 102],
            "missing_locations": [103],
            "players": [],
            "slot_data": {},
        }))

        self.assertEqual([], runtime.ctx.outgoing)
        self.assertEqual(set(), runtime.ctx.location_scouts_requested)

    def test_runtime_has_no_server_snapshot_replay_interface(self) -> None:
        runtime = self.runtime()

        self.assertFalse(hasattr(runtime, "server_snapshot"))
        self.assertFalse(hasattr(runtime, "restore_server_snapshot"))


class FakeBizHawk:
    def __init__(self, check_value: int) -> None:
        self.check_value = check_value
        self.writes = []

    async def guarded_read(self, ctx, reads, guards):
        return [b"\x06", b"\x10", b"\x00", b"\x00\x00", bytes((self.check_value,))]

    async def guarded_write(self, ctx, writes, guards):
        self.writes.append(writes)
        return True


class AndroidLadxParityTest(unittest.TestCase):
    def client(self) -> AndroidLadxClient:
        start = _LadxCheck("0x2A3", 0xDAA3, 0x20, None)
        with patch.object(AndroidLadxClient, "_build_checks", return_value=[start]):
            return AndroidLadxClient()

    def context(self) -> AndroidClientContext:
        ctx = AndroidClientContext(object())
        ctx.slot = 1
        ctx.server = object()
        ctx.items_received = [sys.modules["NetUtils"].NetworkItem(10_000_001, 1, 1, 0)]
        return ctx

    def run_watcher(self, client: AndroidLadxClient, ctx: AndroidClientContext, bizhawk: FakeBizHawk) -> None:
        worlds = ModuleType("worlds")
        worlds._bizhawk = bizhawk
        with patch.dict(sys.modules, {"worlds": worlds}):
            asyncio.run(client.game_watcher(ctx))

    def test_server_checked_start_does_not_override_local_tarin_gate(self) -> None:
        client = self.client()
        ctx = self.context()
        ctx.checked_locations.add(client.start_location)
        bizhawk = FakeBizHawk(0)

        self.run_watcher(client, ctx, bizhawk)

        self.assertEqual([], bizhawk.writes)

    def test_local_tarin_flag_allows_one_live_index_delivery(self) -> None:
        client = self.client()
        ctx = self.context()
        bizhawk = FakeBizHawk(0x20)

        self.run_watcher(client, ctx, bizhawk)

        self.assertEqual(1, len(bizhawk.writes))
        self.assertEqual(
            [(0xDDF8, b"\x01\x01", "System Bus"),
             (0xDDF7, b"\x01", "System Bus"),
             (0xDDFD, b"\x00\x01", "System Bus")],
            bizhawk.writes[0],
        )

    def test_client_has_no_android_recovery_state(self) -> None:
        client = self.client()

        self.assertFalse(hasattr(client, "recover_from_save"))
        self.assertFalse(hasattr(client, "_rolled_back_checks"))
        self.assertFalse(hasattr(client, "_reset_marker_initialized"))

    def test_emulator_reconnect_clears_ladx_receive_cache_before_sync(self) -> None:
        client = self.client()
        ctx = self.context()
        runtime = AndroidBizHawkRuntime.__new__(AndroidBizHawkRuntime)
        runtime.handler = client
        runtime.ctx = ctx

        runtime.bridge_reconnected()

        self.assertEqual([], ctx.items_received)
        self.assertEqual([{"cmd": "Sync"}], ctx.outgoing)


if __name__ == "__main__":
    unittest.main()
