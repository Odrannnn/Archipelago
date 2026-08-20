from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import sys
import tempfile
from types import ModuleType
import unittest
from unittest.mock import patch


PYTHON_SOURCE = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SOURCE))

OFFLINE_GENERATOR_PATH = PYTHON_SOURCE / "offline_generator.py"
SPEC = importlib.util.spec_from_file_location("offline_generator_runtime_under_test", OFFLINE_GENERATOR_PATH)
assert SPEC is not None and SPEC.loader is not None
OFFLINE_GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OFFLINE_GENERATOR)


class OfflineGeneratorRuntimeTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
