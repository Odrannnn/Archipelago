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
        with tempfile.TemporaryDirectory() as directory:
            try:
                with patch.dict(sys.modules, {"Utils": utils}):
                    OFFLINE_GENERATOR._prepare_runtime(directory)
                    self.assertFalse(utils.gui_enabled)
            finally:
                os.chdir(previous_directory)


if __name__ == "__main__":
    unittest.main()
