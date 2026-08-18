from __future__ import annotations

from collections import namedtuple
import json
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace
import unittest


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


worlds_module = ModuleType("worlds")
auto_sni_module = ModuleType("worlds.AutoSNIClient")
auto_sni_module.AutoSNIClientRegister = FakeRegistry
offline_generator_module = ModuleType("offline_generator")
offline_generator_module._load_standard_sni_clients = lambda work_directory: None
sys.modules["worlds"] = worlds_module
sys.modules["worlds.AutoSNIClient"] = auto_sni_module
sys.modules["offline_generator"] = offline_generator_module

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


if __name__ == "__main__":
    unittest.main()
