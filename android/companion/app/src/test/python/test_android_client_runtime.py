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

from android_client_runtime import AndroidClientContext, process_packet, reset_connection


class Handler:
    def __init__(self) -> None:
        self.packets = []

    def on_package(self, ctx, cmd, args) -> None:
        self.packets.append((cmd, args))


class EmulatorLifecycleTest(unittest.TestCase):
    def context(self) -> AndroidClientContext:
        ctx = AndroidClientContext(object())
        ctx.game = "Test Game"
        ctx.server = object()
        return ctx

    def test_transport_reattach_queues_one_sync(self) -> None:
        ctx = self.context()
        ctx.emulator_lifecycle.reattached()
        ctx.emulator_lifecycle.reattached()

        self.assertEqual([{"cmd": "Sync"}], ctx.outgoing)
        self.assertFalse(ctx.received_items_synced)
        self.assertIn("transport reattached", ctx.diagnostic)

    def test_io_recovery_queues_sync(self) -> None:
        ctx = self.context()
        ctx.emulator_lifecycle.begin_tick(True)
        ctx.emulator_lifecycle.note_io_failure()
        ctx.emulator_lifecycle.end_tick()
        ctx.emulator_lifecycle.begin_tick(True)
        ctx.emulator_lifecycle.end_tick()

        self.assertEqual([{"cmd": "Sync"}], ctx.outgoing)
        self.assertIn("memory became available", ctx.diagnostic)

    def test_stable_transport_does_not_periodically_request_sync(self) -> None:
        ctx = self.context()
        for _ in range(300):
            ctx.emulator_lifecycle.begin_tick(True)
            ctx.emulator_lifecycle.end_tick()

        self.assertEqual([], ctx.outgoing)

    def test_full_received_items_completes_reconciliation(self) -> None:
        ctx = self.context()
        handler = Handler()
        ctx.emulator_lifecycle.reattached()
        ctx.outgoing.clear()

        process_packet(
            ctx,
            handler,
            json.dumps({
                "cmd": "ReceivedItems",
                "index": 0,
                "items": [{"item": 1, "location": 2, "player": 3, "flags": 4}],
            }),
        )

        self.assertTrue(ctx.received_items_synced)
        self.assertEqual(1, len(ctx.items_received))
        self.assertEqual("ReceivedItems", handler.packets[-1][0])

    def test_server_reset_preserves_emulator_lifecycle_but_clears_room_state(self) -> None:
        ctx = self.context()
        ctx.slot = 2
        ctx.locations_checked.add(42)
        ctx.items_received.append(SimpleNamespace(item=1))
        ctx.outgoing.append({"cmd": "LocationChecks", "locations": [1]})

        reset_connection(ctx)

        self.assertIsNone(ctx.server)
        self.assertIsNone(ctx.slot)
        self.assertEqual([], ctx.items_received)
        self.assertEqual({42}, ctx.locations_checked)
        self.assertEqual([], ctx.outgoing)

    def test_received_item_index_mismatch_preserves_cache_and_matches_desktop_sync(self) -> None:
        ctx = self.context()
        handler = Handler()
        ctx.locations_checked.add(42)
        ctx.items_received.append(SimpleNamespace(item=7))

        process_packet(
            ctx,
            handler,
            json.dumps({
                "cmd": "ReceivedItems",
                "index": 3,
                "items": [{"item": 8, "location": 9, "player": 1, "flags": 0}],
            }),
        )

        self.assertEqual([7], [item.item for item in ctx.items_received])
        self.assertEqual(
            [{"cmd": "Sync"}, {"cmd": "LocationChecks", "locations": [42]}],
            ctx.outgoing,
        )


if __name__ == "__main__":
    unittest.main()
