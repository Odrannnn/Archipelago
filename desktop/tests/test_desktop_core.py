from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from archipelago_companion.models import AppState, Room, Settings
from archipelago_companion.player_options import read_player_yaml, safe_player_filename
from archipelago_companion.services import DesktopServices
from archipelago_companion.storage import StateStore


class ModelTests(unittest.TestCase):
    def test_state_round_trip_preserves_active_room(self) -> None:
        older = Room(id="old", name="Older", updated_at=10)
        newer = Room(id="new", name="Newer", updated_at=20)
        state = AppState.from_dict({
            "rooms": [older.to_dict(), newer.to_dict()],
            "active_room_id": "old",
            "settings": {"poptracker_executable": "poptracker"},
        })
        self.assertEqual(["new", "old"], [room.id for room in state.rooms])
        self.assertEqual("old", state.active_room.id)
        self.assertEqual("poptracker", state.settings.poptracker_executable)

    def test_unknown_active_room_is_discarded(self) -> None:
        state = AppState.from_dict({"rooms": [], "active_room_id": "missing"})
        self.assertEqual("", state.active_room_id)


class StorageTests(unittest.TestCase):
    def test_generated_yaml_is_saved_atomically_without_overwriting(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = StateStore(Path(directory) / "data")
            first = store.save_yaml("Player.yaml", "name: One\n")
            duplicate = store.save_yaml("Player.yaml", "name: One\n")
            second = store.save_yaml("Player.yaml", "name: Two\n")
            self.assertEqual(first, duplicate)
            self.assertEqual("Player-2.yaml", second.name)
            self.assertEqual([second, first], store.list_yamls())

    def test_atomic_state_and_backup_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = StateStore(Path(directory) / "source")
            state = AppState(rooms=[Room(id="one", name="Test")], active_room_id="one")
            store.save(state)
            yaml = Path(directory) / "Player.yaml"
            yaml.write_text("name: Player\ngame: Test\n", encoding="utf-8")
            store.import_yaml(yaml)
            backup = Path(directory) / "backup.zip"
            store.create_backup(backup)
            restored = StateStore(Path(directory) / "restored")
            restored.restore_backup(backup)
            self.assertEqual("Test", restored.load().active_room.name)
            self.assertTrue((restored.yaml_dir / "Player.yaml").is_file())

    def test_restore_rejects_parent_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "bad.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("../escape.txt", "bad")
            with self.assertRaisesRegex(ValueError, "unsafe path"):
                StateStore(Path(directory) / "data").restore_backup(archive)

    def test_apworld_must_be_zip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "broken.apworld"
            source.write_text("not a zip", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "valid ZIP"):
                StateStore(Path(directory) / "data").import_world(source)


class PlayerCreatorTests(unittest.TestCase):
    def test_player_filename_removes_unsafe_characters(self) -> None:
        self.assertEqual("Link Player.yaml", safe_player_filename(" Link:/ Player "))

    def test_import_preserves_upstream_top_level_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Player.yaml"
            source.write_text(
                "name: Link\ndescription: Custom player\ngame: Test Game\n"
                "requires:\n  version: 0.6.0\nTest Game:\n  setting: enabled\n",
                encoding="utf-8",
            )
            name, game, values, extras = read_player_yaml(source)
            self.assertEqual(("Link", "Test Game"), (name, game))
            self.assertEqual({"setting": "enabled"}, values)
            self.assertEqual("Custom player", extras["description"])
            self.assertEqual({"version": "0.6.0"}, extras["requires"])


class ServiceTests(unittest.TestCase):
    def test_uri_uses_upstream_game_query_and_escaped_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            services = DesktopServices(StateStore(Path(directory)), Path(directory))
            uri = services.archipelago_uri(Room(
                game="Metroid Fusion", server="archipelago.gg:38281",
                slot="A Player", password="p@ss word",
            ))
            self.assertEqual(
                "archipelago://A%20Player:p%40ss%20word@archipelago.gg:38281?game=Metroid+Fusion", uri,
            )

    def test_client_requires_game_for_component_selection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            services = DesktopServices(StateStore(Path(directory)), Path(directory))
            with self.assertRaisesRegex(ValueError, "room's game"):
                services.client_command(Room(server="host:1", slot="Player"), Settings())

    def test_text_client_uses_direct_upstream_entrypoint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            services = DesktopServices(StateStore(Path(directory)), Path(directory))
            command = services.text_client_command(
                Room(server="host:1234", slot="Player", password="secret"), Settings(),
            )
            self.assertTrue(command.arguments[0].endswith("CommonClient.py"))
            self.assertEqual(
                ["--connect", "host:1234", "--name", "Player", "--password", "secret"],
                command.arguments[1:],
            )

    def test_frozen_build_uses_sibling_archipelago_executables(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            services = DesktopServices(StateStore(Path(directory)), Path(directory))
            room = Room(game="Metroid Fusion", server="host:1234", slot="Player")
            with patch.object(__import__("sys"), "frozen", True, create=True), patch.object(
                __import__("sys"), "executable", str(Path(directory) / "ArchipelagoCompanion.exe"),
            ), patch.object(__import__("sys"), "platform", "win32"):
                client = services.client_command(room, Settings())
                generator = services.generation_command([self._yaml(Path(directory))], "", Settings())
            self.assertTrue(client.program.endswith("ArchipelagoLauncher.exe"))
            self.assertTrue(generator.program.endswith("ArchipelagoGenerate.exe"))
            self.assertNotIn("Generate.py", generator.arguments)

    @staticmethod
    def _yaml(root: Path) -> Path:
        source = root / "Frozen.yaml"
        source.write_text("name: Player\n", encoding="utf-8")
        return source

    def test_generation_stages_player_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            store = StateStore(root / "data")
            yaml = root / "Player.yaml"
            yaml.write_text("name: Player\n", encoding="utf-8")
            command = DesktopServices(store, root).generation_command([yaml], "123", Settings())
            self.assertIn("--seed", command.arguments)
            self.assertEqual("123", command.arguments[-1])
            staged = list(store.job_dir.rglob("*.yaml"))
            self.assertEqual(1, len(staged))
            self.assertEqual(yaml.read_text(), staged[0].read_text())

    def test_poptracker_receives_pack_and_room_data(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "poptracker.exe"
            executable.touch()
            services = DesktopServices(StateStore(Path(directory) / "data"), Path(directory))
            command = services.poptracker_command(
                Room(game="Metroid Fusion", server="host:1234", slot="Player", password="secret"),
                Settings(poptracker_executable=str(executable)),
            )
            self.assertEqual([
                "--load-game", "Metroid Fusion", "--ap-host", "host:1234",
                "--ap-slot", "Player", "--ap-password", "secret",
            ], command.arguments)


if __name__ == "__main__":
    unittest.main()
