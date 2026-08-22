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

from android_client_runtime import (
    AndroidClientCommandProcessor,
    AndroidClientContext,
    execute_console_command,
    process_packet,
    reset_connection,
    set_force_local_items,
)


class Handler:
    def __init__(self) -> None:
        self.packets = []

    def on_package(self, ctx, cmd, args) -> None:
        self.packets.append((cmd, args))


class DesktopCommandProcessor(AndroidClientCommandProcessor):
    pass


class DesktopStyleContext(AndroidClientContext):
    command_processor = DesktopCommandProcessor


class DesktopCompatibilityTest(unittest.IsolatedAsyncioTestCase):
    async def test_common_context_accepts_desktop_server_and_password_signature(self) -> None:
        ctx = DesktopStyleContext("archipelago.gg:38281", "secret")

        self.assertIsNone(ctx.backend)
        self.assertEqual("archipelago.gg:38281", ctx.server_address)
        self.assertEqual("secret", ctx.password)
        self.assertIsInstance(ctx.command_processor, DesktopCommandProcessor)
        self.assertIsNone(ctx.ui)

        ctx.run_cli()
        ctx.run_gui()
        await ctx.shutdown()
        self.assertTrue(ctx.exit_event.is_set())
        self.assertTrue(ctx.watcher_event.is_set())


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


class LocalItemHandlingOverrideTest(unittest.TestCase):
    def test_override_adds_only_local_item_delivery_and_restores_upstream_value(self) -> None:
        ctx = AndroidClientContext(object())
        ctx.items_handling = 0b101

        self.assertEqual(0b111, set_force_local_items(ctx, True))
        self.assertEqual(0b111, ctx.items_handling)
        self.assertEqual(0b101, set_force_local_items(ctx, False))

    def test_override_survives_upstream_recalculation_after_connected(self) -> None:
        ctx = AndroidClientContext(object())
        ctx.items_handling = 0b101
        set_force_local_items(ctx, True)

        class RecalculatingHandler(Handler):
            def on_package(self, packet_ctx, cmd, args) -> None:
                super().on_package(packet_ctx, cmd, args)
                packet_ctx.items_handling = 0b001

        process_packet(ctx, RecalculatingHandler(), json.dumps({"cmd": "Connected"}))

        self.assertEqual(0b011, ctx.items_handling)
        self.assertEqual(0b001, ctx.android_upstream_items_handling)
        self.assertEqual(0b001, set_force_local_items(ctx, False))


class ClientConsoleTest(unittest.IsolatedAsyncioTestCase):
    async def test_chat_and_ready_commands_use_the_active_context(self) -> None:
        ctx = AndroidClientContext(object())
        ctx.game = "Test Game"
        ctx.server = object()

        chat = await execute_console_command(ctx, "hello room")
        ready = await execute_console_command(ctx, "/ready")

        self.assertEqual({"cmd": "Say", "text": "hello room"}, ctx.outgoing[0])
        self.assertEqual({"cmd": "StatusUpdate", "status": 10}, ctx.outgoing[1])
        self.assertEqual("Readied up.", ready["console"][0]["text"])
        self.assertEqual([], chat["actions"])

    async def test_help_uses_upstream_parser_registry(self) -> None:
        ctx = AndroidClientContext(object())

        result = await execute_console_command(ctx, "/help")
        text = "\n".join(message["text"] for message in result["console"])

        self.assertIn("/received", text)
        self.assertIn("/force_item_sync", text)
        self.assertIn("/disconnect", text)
        self.assertIn("/ready", text)

    async def test_deathlink_command_delegates_to_supporting_game_client(self) -> None:
        class DeathLinkHandler(Handler):
            def toggle_deathlink(self, ctx) -> bool:
                ctx.outgoing.append({"cmd": "ConnectUpdate", "tags": ["DeathLink"]})
                return True

        ctx = AndroidClientContext(object())
        ctx.client_handler = DeathLinkHandler()

        result = await execute_console_command(ctx, "/deathlink")

        self.assertEqual([{"cmd": "ConnectUpdate", "tags": ["DeathLink"]}], ctx.outgoing)
        self.assertIn("enabled", result["console"][0]["text"])

    async def test_force_item_sync_requests_full_history_without_touching_rom(self) -> None:
        ctx = AndroidClientContext(object())
        ctx.game = "Test Game"
        ctx.server = object()
        ctx.items_received.extend([
            SimpleNamespace(item=1),
            SimpleNamespace(item=2),
        ])

        result = await execute_console_command(ctx, "/force_item_sync")

        self.assertEqual([], ctx.items_received)
        self.assertEqual([{"cmd": "Sync"}], ctx.outgoing)
        self.assertFalse(ctx.received_items_synced)
        self.assertIn("ROM memory unchanged", ctx.diagnostic)
        self.assertIn("complete replay", result["console"][0]["text"])

    async def test_force_item_sync_while_disconnected_preserves_cached_history(self) -> None:
        ctx = AndroidClientContext(object())
        ctx.items_received.append(SimpleNamespace(item=1))

        result = await execute_console_command(ctx, "/force_item_sync")

        self.assertEqual([1], [item.item for item in ctx.items_received])
        self.assertEqual([], ctx.outgoing)
        self.assertIn("disconnected", result["console"][0]["text"])

    async def test_print_json_resolves_player_item_and_location_names(self) -> None:
        ctx = AndroidClientContext(object())
        handler = Handler()
        ctx.slot = 1
        ctx.player_names[1] = "Kirby"
        ctx.item_names.by_game["Test Game"] = {100: "Heart Star"}
        ctx.location_names.by_game["Test Game"] = {200: "Grass Land 1"}
        ctx.slot_info[1] = SimpleNamespace(game="Test Game")

        process_packet(ctx, handler, json.dumps({
            "cmd": "PrintJSON",
            "data": [
                {"text": "1", "type": "player_id"},
                {"text": " found "},
                {"text": "100", "type": "item_id", "player": 1},
                {"text": " at "},
                {"text": "200", "type": "location_id", "player": 1},
            ],
        }))
        result = await execute_console_command(ctx, "")

        self.assertEqual("Kirby found Heart Star at Grass Land 1", result["console"][0]["text"])

    async def test_raw_item_group_command_preserves_spaces(self) -> None:
        ctx = AndroidClientContext(object())
        ctx.game = "Test Game"
        ctx.data_package[ctx.game] = {
            "item_name_groups": {"Useful Things": ["Heart Star", "1-Up"]},
        }

        result = await execute_console_command(ctx, "/item_groups Useful Things")
        text = [message["text"] for message in result["console"]]

        self.assertEqual('Items for Item Group "Useful Things"', text[0])
        self.assertEqual(["Heart Star", "1-Up"], text[1:])


if __name__ == "__main__":
    unittest.main()
