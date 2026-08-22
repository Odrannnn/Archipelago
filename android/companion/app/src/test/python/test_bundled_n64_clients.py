from __future__ import annotations

import json
from pathlib import Path
import re
import unittest


TEST_ROOT = Path(__file__).resolve()
APP_ROOT = TEST_ROOT.parents[3]
REPOSITORY_ROOT = TEST_ROOT.parents[6]
BUNDLED_WORLD_MANIFEST = APP_ROOT / "src" / "main" / "assets" / "bundled_worlds.json"
N64_DECLARATION = re.compile(r"system\s*=\s*[\"']N64[\"']")


class BundledN64ClientTest(unittest.TestCase):
    def test_every_listed_n64_world_has_an_android_runtime(self) -> None:
        entries = [
            entry
            for entry in json.loads(BUNDLED_WORLD_MANIFEST.read_text(encoding="utf-8"))
            if entry["platform"] == "N64"
        ]

        self.assertGreater(len(entries), 0)
        for entry in entries:
            package = REPOSITORY_ROOT / "worlds" / entry["package"]
            if entry["game"] == "Ocarina of Time":
                self.assertTrue((REPOSITORY_ROOT / "data" / "lua" / "connector_oot.lua").is_file())
                android_runtime = (
                    APP_ROOT / "src" / "main" / "python" / "android_bizhawk_runtime.py"
                ).read_text(encoding="utf-8")
                self.assertIn("class AndroidOotClient", android_runtime)
                continue
            sources = "\n".join(
                source.read_text(encoding="utf-8")
                for source in package.rglob("*.py")
            )
            self.assertIn("BizHawkClient", sources, entry["game"])
            self.assertRegex(sources, N64_DECLARATION, entry["game"])


if __name__ == "__main__":
    unittest.main()
