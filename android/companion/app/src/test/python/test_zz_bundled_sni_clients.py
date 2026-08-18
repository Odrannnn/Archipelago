from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


PYTHON_SOURCE = Path(__file__).resolve().parents[2] / "main" / "python"
BUNDLED_WORLD_MANIFEST = PYTHON_SOURCE.parent / "assets" / "bundled_worlds.json"

SNES_SIGNATURES = {
    "A Link to the Past": {
        0xE02000: b"AP" + bytes(19),
    },
    "EarthBound": {
        0x00FFC0: b"MOM2AP" + bytes(15),
        0x3FF0A0: b"4.3.1" + bytes(11),
    },
    "Final Fantasy Mystic Quest": {
        0x007FC0: b"MQ" + bytes(19),
    },
    "Kirby's Dream Land 3": {
        0xE08100: b"KDL3" + bytes(17),
    },
    "Lufia II Ancient Cave": {
        0x007FC0: b"L2AC" + bytes(17),
    },
    "SMZ3": {
        0x00FFC0: b"ZSM" + bytes(18),
    },
    "Super Mario World": {
        0x007FC0: b"SMW" + bytes(18),
    },
    "Super Metroid": {
        0x007FC0: b"SM030" + bytes(16),
    },
    "Yoshi's Island": {
        0x007FC0: b"YOSHIAP" + bytes(14),
    },
}


class SignatureBackend:
    def __init__(self, regions: dict[int, bytes]) -> None:
        self.regions = regions
        self.writes: list[tuple[int, bytes]] = []

    def read(self, address: int, length: int) -> bytes:
        result = bytearray(length)
        end = address + length
        for region_address, data in self.regions.items():
            overlap_start = max(address, region_address)
            overlap_end = min(end, region_address + len(data))
            if overlap_start < overlap_end:
                result[overlap_start - address:overlap_end - address] = data[
                    overlap_start - region_address:overlap_end - region_address
                ]
        return bytes(result)

    def write(self, address: int, data: bytes) -> None:
        self.writes.append((address, bytes(data)))


def verify_bundled_sni_clients() -> dict[str, str]:
    sys.path.insert(0, str(PYTHON_SOURCE))
    from android_sni_runtime import AndroidSNIRuntime

    manifest = json.loads(BUNDLED_WORLD_MANIFEST.read_text(encoding="utf-8"))
    ui_games = {entry["game"] for entry in manifest if entry.get("platform") == "SNES"}
    if ui_games != set(SNES_SIGNATURES):
        raise AssertionError(
            f"SNES UI manifest mismatch: expected {sorted(SNES_SIGNATURES)}, got {sorted(ui_games)}"
        )

    detected: dict[str, str] = {}
    previous_directory = os.getcwd()
    with tempfile.TemporaryDirectory() as work_directory:
        try:
            registry_runtime = AndroidSNIRuntime(work_directory, SignatureBackend({}))
            try:
                registered = {client_type.game for client_type in registry_runtime.client_types}
            finally:
                registry_runtime.close()
            if registered != set(SNES_SIGNATURES):
                raise AssertionError(
                    f"Bundled SNI registry mismatch: expected {sorted(SNES_SIGNATURES)}, got {sorted(registered)}"
                )

            for expected_game, regions in SNES_SIGNATURES.items():
                runtime = AndroidSNIRuntime(work_directory, SignatureBackend(regions))
                try:
                    result = json.loads(runtime.probe())
                finally:
                    runtime.close()
                if not result.get("matched"):
                    raise AssertionError(f"{expected_game} signature did not match: {result}")
                if result["game"] != expected_game:
                    raise AssertionError(f"Expected {expected_game}, detected {result['game']}")
                detected[expected_game] = result["client"]

            # KDL3 additionally uses SRAM handshakes, data-storage
            # subscriptions, gifting commands, and a two-byte received-item
            # cursor. Exercise those paths instead of stopping at ROM detect.
            kdl3_backend = SignatureBackend({
                **SNES_SIGNATURES["Kirby's Dream Land 3"],
                0xE080F0: b"halken",
                0xE08FF0: b"ninten",
                0xE0733E: b"\x01",
            })
            runtime = AndroidSNIRuntime(work_directory, kdl3_backend)
            try:
                result = json.loads(runtime.probe())
                if result.get("game") != "Kirby's Dream Land 3":
                    raise AssertionError(f"KDL3 runtime setup failed: {result}")
                runtime.process_packet(json.dumps({
                    "cmd": "DataPackage",
                    "data": {"games": {"Kirby's Dream Land 3": {
                        "item_name_to_id": {"Test Item": 0x770001},
                        "location_name_to_id": {"Test Location": 0x770001},
                    }}},
                }))
                runtime.process_packet(json.dumps({
                    "cmd": "Connected",
                    "team": 0,
                    "slot": 1,
                    "checked_locations": [],
                    "missing_locations": [0x770001],
                    "players": [{"team": 0, "slot": 1, "name": "Kirby"}],
                    "slot_info": {"1": ["Kirby", "Kirby's Dream Land 3", 0]},
                    "slot_data": {},
                }))
                runtime.process_packet(json.dumps({
                    "cmd": "ReceivedItems",
                    "index": 0,
                    "items": [{"item": 0x770001, "location": 0x770001, "player": 1, "flags": 0}],
                }))
                tick = json.loads(runtime.tick(True))
                if tick["error"] or tick["disconnect"]:
                    raise AssertionError(f"KDL3 connected watcher failed: {tick}")
                commands = {message["cmd"] for message in tick["messages"]}
                if not {"Get", "SetNotify", "Set"}.issubset(commands):
                    raise AssertionError(f"KDL3 gifting setup was incomplete: {tick['messages']}")
                written_addresses = {address for address, _ in kdl3_backend.writes}
                if not {0xE08050, 0xE0C000}.issubset(written_addresses):
                    raise AssertionError(f"KDL3 item delivery writes were incomplete: {kdl3_backend.writes}")
                command = json.loads(runtime.execute_command("/gift"))
                command_text = "\n".join(message["text"] for message in command["console"])
                if "Gifting set to" not in command_text:
                    raise AssertionError(f"KDL3 /gift command was not dispatched: {command}")
            finally:
                runtime.close()
        finally:
            # The production loader deliberately changes into its writable
            # runtime directory, so leave it before TemporaryDirectory cleanup.
            os.chdir(previous_directory)
    return detected


class BundledSNIClientIntegrationTest(unittest.TestCase):
    def test_every_ui_listed_snes_game_registers_and_detects_its_rom(self) -> None:
        environment = os.environ.copy()
        environment["AP_VERIFY_BUNDLED_SNI_CHILD"] = "1"
        completed = subprocess.run(
            [sys.executable, "-B", str(Path(__file__).resolve())],
            capture_output=True,
            text=True,
            env=environment,
            timeout=120,
        )
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        detected = json.loads(completed.stdout)
        self.assertEqual(set(SNES_SIGNATURES), set(detected))


if __name__ == "__main__":
    if os.environ.get("AP_VERIFY_BUNDLED_SNI_CHILD"):
        print(json.dumps(verify_bundled_sni_clients(), sort_keys=True))
    else:
        unittest.main()
