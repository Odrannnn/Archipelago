from __future__ import annotations

import os
import tempfile
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

try:
    from PySide6.QtWidgets import QApplication
    from archipelago_companion.app import APP_STYLE, MainWindow
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
            self.assertEqual(6, window.stack.count())
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

