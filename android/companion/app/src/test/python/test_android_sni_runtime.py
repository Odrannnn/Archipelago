from __future__ import annotations

from collections import namedtuple
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


class FakeSNIClient:
    game = "Registry Test"

    async def validate_rom(self, ctx) -> bool:
        ctx.game = self.game
        ctx.rom = b"registry-test-rom"
        return True

    async def game_watcher(self, ctx) -> None:
        while ctx.backend.delivery_cursor < len(ctx.items_received):
            ctx.backend.delivered.append(ctx.items_received[ctx.backend.delivery_cursor].item)
            ctx.backend.delivery_cursor += 1

    async def deathlink_kill_player(self, ctx) -> None:
        return None

    def on_package(self, ctx, cmd, args) -> None:
        return None


class FakeRegistry:
    game_handlers = {FakeSNIClient.game: FakeSNIClient()}


class FailedValidationClient(FakeSNIClient):
    async def validate_rom(self, ctx) -> bool:
        ctx.rom = None
        ctx.emulator_lifecycle.note_io_failure()
        return False


class ChangedRomClient(FakeSNIClient):
    async def validate_rom(self, ctx) -> bool:
        ctx.rom = b"different-rom"
        return True


worlds_module = ModuleType("worlds")
auto_sni_module = ModuleType("worlds.AutoSNIClient")
auto_sni_module.AutoSNIClientRegister = FakeRegistry
offline_generator_module = ModuleType("offline_generator")
offline_generator_module._load_standard_sni_clients = lambda work_directory: None

from android_sni_runtime import AndroidSNIRuntime


class Backend:
    def __init__(self) -> None:
        self.delivery_cursor = 0
        self.delivered = []

    def read(self, address: int, length: int) -> bytes:
        return bytes(length)

    def write(self, address: int, data: bytes) -> None:
        return None


class AndroidSNIRegistryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.module_patch = patch.dict(sys.modules, {
            "worlds": worlds_module,
            "worlds.AutoSNIClient": auto_sni_module,
            "offline_generator": offline_generator_module,
        })
        self.module_patch.start()

    def tearDown(self) -> None:
        self.module_patch.stop()

    def test_runtime_discovers_registered_client_without_game_import(self) -> None:
        runtime = AndroidSNIRuntime("unused", Backend())
        try:
            result = json.loads(runtime.probe())
        finally:
            runtime.close()

        self.assertTrue(result["matched"])
        self.assertEqual(FakeSNIClient.game, result["game"])
        self.assertEqual(
            f"{FakeSNIClient.__module__}.{FakeSNIClient.__name__}",
            result["client"],
        )

    def test_desktop_style_detach_reattach_preserves_items_for_redelivery(self) -> None:
        backend = Backend()
        runtime = AndroidSNIRuntime("unused", backend)
        try:
            detected = json.loads(runtime.probe())
            runtime.process_packet(json.dumps({
                "cmd": "Connected",
                "team": 0,
                "slot": 1,
                "checked_locations": [],
                "missing_locations": [],
                "players": [{"team": 0, "slot": 1, "name": "Player"}],
                "slot_data": {},
            }))
            runtime.process_packet(json.dumps({
                "cmd": "ReceivedItems",
                "index": 0,
                "items": [{"item": 42, "location": 7, "player": 1, "flags": 0}],
            }))

            runtime.tick(True)
            self.assertEqual([42], backend.delivered)

            backend.delivery_cursor = 0
            runtime.emulator_detached()
            self.assertEqual(1, len(runtime.ctx.items_received))
            self.assertIsNone(runtime.ctx.rom)

            runtime.emulator_reattached()
            self.assertTrue(runtime.validate_active(detected["game"], detected["auth"]))
            runtime.tick(True)

            self.assertEqual([42, 42], backend.delivered)
            self.assertEqual(1, len(runtime.ctx.items_received))
        finally:
            runtime.close()

    def test_transient_validation_io_failure_preserves_active_room(self) -> None:
        runtime = AndroidSNIRuntime("unused", Backend())
        try:
            json.loads(runtime.probe())
            runtime.handler = FailedValidationClient()

            result = json.loads(runtime.tick(True))

            self.assertFalse(result["disconnect"])
            self.assertIn("temporarily unavailable", result["error"])
            self.assertIsNotNone(runtime.ctx.client_handler)
        finally:
            runtime.close()

    def test_active_probe_io_failure_preserves_registered_game_client(self) -> None:
        runtime = AndroidSNIRuntime("unused", Backend())
        try:
            detected = json.loads(runtime.probe())
            runtime.handler = FailedValidationClient()

            with self.assertRaisesRegex(RuntimeError, "temporarily unavailable"):
                runtime.validate_active(detected["game"], detected["auth"])

            self.assertIsInstance(runtime.handler, FailedValidationClient)
            self.assertIsNotNone(runtime.ctx.client_handler)
        finally:
            runtime.close()

    def test_active_probe_successfully_read_changed_rom_returns_false(self) -> None:
        runtime = AndroidSNIRuntime("unused", Backend())
        try:
            detected = json.loads(runtime.probe())
            runtime.handler = ChangedRomClient()

            self.assertFalse(runtime.validate_active(detected["game"], detected["auth"]))
        finally:
            runtime.close()

    def test_successfully_read_changed_rom_still_rejects_active_room(self) -> None:
        runtime = AndroidSNIRuntime("unused", Backend())
        try:
            json.loads(runtime.probe())
            runtime.handler = ChangedRomClient()

            result = json.loads(runtime.tick(True))

            self.assertTrue(result["disconnect"])
            self.assertIn("ROM change", result["error"])
            self.assertIsNone(runtime.ctx.client_handler)
        finally:
            runtime.close()


if __name__ == "__main__":
    unittest.main()
