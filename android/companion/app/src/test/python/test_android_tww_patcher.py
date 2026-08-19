import base64
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import yaml

import android_tww_patcher as patcher


def aptww(version=(3, 0, 0), game=patcher.GAME):
    plando = {
        "Version": list(version), "Seed": 1234, "Slot": 1, "Name": "Link",
        "Options": {}, "Required Bosses": [], "Locations": {}, "Entrances": {}, "Charts": [],
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("archipelago.json", json.dumps({"game": game}))
        archive.writestr("plando", base64.b64encode(yaml.safe_dump(plando).encode()))
    return output.getvalue()


class WindWakerPatcherTest(unittest.TestCase):
    def test_current_aptww_is_accepted(self):
        self.assertEqual("Link", patcher.load_plando(aptww())["Name"])

    def test_old_aptww_is_rejected_with_version(self):
        with self.assertRaisesRegex(ValueError, "2.6.0"):
            patcher.load_plando(aptww((2, 6, 0)))

    def test_other_game_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "not The Wind Waker"):
            patcher.load_plando(aptww(game="Metroid Prime"))

    def test_only_north_american_uncompressed_iso_header_is_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            iso = Path(temporary) / "ww.iso"
            iso.write_bytes(b"GZLE01" + bytes(32))
            patcher.validate_iso_path(str(iso))
            iso.write_bytes(b"GZLP01" + bytes(32))
            with self.assertRaisesRegex(ValueError, "North American"):
                patcher.validate_iso_path(str(iso))


if __name__ == "__main__":
    unittest.main()
