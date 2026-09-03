from __future__ import annotations

import os
import tempfile
import time
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
os.environ.setdefault("AP_TEST_WORLDS", "adventure")

try:
    from archipelago_companion.app import APP_STYLE, MainWindow
    from PySide6.QtWidgets import QApplication
except ImportError:
    QApplication = None


@unittest.skipIf(QApplication is None, "PySide6 is not installed")
class DesktopUiSmokeTests(unittest.TestCase):
    def test_window_constructs_at_compact_and_wide_sizes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            os.environ["ARCHIPELAGO_COMPANION_HOME"] = directory
            application = QApplication.instance() or QApplication([])
            application.setStyle("Fusion")
            application.setStyleSheet(APP_STYLE)
            window = MainWindow()
            self.assertEqual(7, window.stack.count())
            self.assertIn("Player creator", window.page_rows)
            deadline = time.monotonic() + 5
            while window.player_creator.catalog_loading and time.monotonic() < deadline:
                application.processEvents()
                time.sleep(0.01)
            self.assertGreater(window.player_creator.game_combo.count(), 0)
            player_yaml = window.player_creator.yaml_text()
            self.assertIn("name: Player1", player_yaml)
            self.assertIn(f"game: {window.player_creator.schema.game}", player_yaml)
            window.resize(720, 520)
            window.show()
            application.processEvents()
            self.assertEqual(720, window.width())
            window.resize(1600, 900)
            application.processEvents()
            self.assertEqual(1600, window.width())
            window.close()


if __name__ == "__main__":
    unittest.main()

