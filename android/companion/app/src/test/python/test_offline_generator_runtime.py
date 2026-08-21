from __future__ import annotations

import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import ModuleType
import unittest
from unittest.mock import patch
import zipfile


PYTHON_SOURCE = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SOURCE))

OFFLINE_GENERATOR_PATH = PYTHON_SOURCE / "offline_generator.py"
SPEC = importlib.util.spec_from_file_location("offline_generator_runtime_under_test", OFFLINE_GENERATOR_PATH)
assert SPEC is not None and SPEC.loader is not None
OFFLINE_GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OFFLINE_GENERATOR)


class OfflineGeneratorRuntimeTest(unittest.TestCase):
    @staticmethod
    def _container(manifest: dict, payload: bytes = b"payload") -> bytes:
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("archipelago.json", json.dumps(manifest))
            archive.writestr("config.json", payload)
        return output.getvalue()

    def test_extracts_custom_player_container_without_known_suffix(self) -> None:
        player_container = self._container({
            "game": "Example Game",
            "player": 1,
            "player_name": "Tester",
            "compatible_version": 7,
        })
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            seed = output / "seed.zip"
            with zipfile.ZipFile(seed, "w") as archive:
                archive.writestr("nested/AP_1_P1_Tester.apcustom", player_container)

            extracted = OFFLINE_GENERATOR._extract_player_containers(seed, output)

            self.assertEqual([output / "AP_1_P1_Tester.apcustom"], extracted)
            self.assertEqual(player_container, extracted[0].read_bytes())

    def test_ignores_apworld_container_without_player_manifest_fields(self) -> None:
        world_container = self._container({
            "game": "Example Game",
            "world_version": "1.0.0",
            "compatible_version": 7,
        })
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            seed = output / "seed.zip"
            with zipfile.ZipFile(seed, "w") as archive:
                archive.writestr("example.apworld", world_container)

            extracted = OFFLINE_GENERATOR._extract_player_containers(seed, output)

            self.assertEqual([], extracted)

    def test_prepare_runtime_disables_desktop_gui(self) -> None:
        previous_directory = os.getcwd()
        utils = ModuleType("Utils")
        utils.gui_enabled = True
        utils.user_path = lambda path="": path
        utils.home_path = lambda path="": path
        settings = ModuleType("settings")
        settings.no_gui = False
        with tempfile.TemporaryDirectory() as directory:
            try:
                with patch.dict(sys.modules, {"Utils": utils, "settings": settings}):
                    OFFLINE_GENERATOR._prepare_runtime(directory)
                    self.assertFalse(utils.gui_enabled)
                    self.assertTrue(settings.no_gui)
            finally:
                os.chdir(previous_directory)

    def test_fill_failures_retry_with_new_seed_until_success(self) -> None:
        candidates = []

        def run_attempt(candidate_seed: str):
            candidates.append(candidate_seed)
            numeric_seed = 4100 if not candidate_seed else int(candidate_seed)
            if len(candidates) < 3:
                return {"seed": numeric_seed, "fill_error": "Unable to fill all locations"}
            return {"seed": numeric_seed, "players": ["Player1"]}

        outcome, attempts = OFFLINE_GENERATOR._retry_fill_failures(run_attempt, "")

        self.assertEqual(["", "4101", "4102"], candidates)
        self.assertEqual(3, attempts)
        self.assertEqual(4102, outcome["seed"])

    def test_non_fill_errors_are_not_retried(self) -> None:
        attempts = 0

        def run_attempt(_candidate_seed: str):
            nonlocal attempts
            attempts += 1
            raise OSError("storage unavailable")

        with self.assertRaisesRegex(OSError, "storage unavailable"):
            OFFLINE_GENERATOR._retry_fill_failures(run_attempt, "123")
        self.assertEqual(1, attempts)

    def test_repeated_equivalent_fill_failures_stop_with_diagnostics(self) -> None:
        attempts = 0

        def run_attempt(candidate_seed: str):
            nonlocal attempts
            attempts += 1
            return {
                "seed": int(candidate_seed),
                "fill_error": f"No more spots to place {attempts} items. Remaining locations are invalid.",
            }

        with self.assertRaisesRegex(RuntimeError, "Blast Shield Randomization") as raised:
            OFFLINE_GENERATOR._retry_fill_failures(
                run_attempt,
                "1000",
                ["Player1 (Metroid Prime) · Blast Shield Randomization = high (default: none)"],
            )

        self.assertEqual(OFFLINE_GENERATOR.MAX_REPEATED_FILL_FAILURES, attempts)
        self.assertIn("20× No more spots", str(raised.exception))
        self.assertIn("did not alter your YAML", str(raised.exception))

    def test_varied_fill_failures_stop_at_total_attempt_limit(self) -> None:
        attempts = 0

        def run_attempt(candidate_seed: str):
            nonlocal attempts
            attempts += 1
            return {
                "seed": int(candidate_seed),
                "fill_error": f"Unique failure pattern {chr(0x400 + attempts)}",
            }

        with self.assertRaisesRegex(RuntimeError, "None detected"):
            OFFLINE_GENERATOR._retry_fill_failures(run_attempt, "2000")

        self.assertEqual(OFFLINE_GENERATOR.MAX_FILL_ATTEMPTS, attempts)

    def test_non_default_settings_are_described_without_game_specific_rules(self) -> None:
        class HardMode:
            default = False
            display_name = "Hard Mode"

        class OptionData:
            type_hints = {"hard_mode": HardMode}

        class ExampleWorld:
            options_dataclass = OptionData

        class Registry:
            world_types = {"Example Game": ExampleWorld}

        yaml = ModuleType("yaml")
        yaml.safe_load_all = lambda _text: iter([{
            "name": "Tester",
            "game": "Example Game",
            "Example Game": {"hard_mode": True},
        }])
        with patch.dict(sys.modules, {"yaml": yaml}):
            with patch.object(OFFLINE_GENERATOR, "_form_option_value", lambda _option, value: value):
                descriptions = OFFLINE_GENERATOR._non_default_option_descriptions("ignored", Registry)

        self.assertEqual(
            ['Tester (Example Game) · Hard Mode = true (default: false)'],
            descriptions,
        )


if __name__ == "__main__":
    unittest.main()
