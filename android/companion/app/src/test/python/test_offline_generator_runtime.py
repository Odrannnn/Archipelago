from __future__ import annotations

import importlib.util
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
from types import ModuleType, SimpleNamespace
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

            # History repair is idempotent when the already-extracted file is identical.
            self.assertEqual(extracted, OFFLINE_GENERATOR._extract_player_containers(seed, output))

    def test_accepts_registered_standard_patch_output_without_console_allowlist(self) -> None:
        handler = SimpleNamespace(game="Paper Mario", result_file_ending=".z64")

        self.assertEqual(".z64", OFFLINE_GENERATOR._standard_result_extension(handler))

    def test_rejects_unsafe_registered_patch_output_suffix(self) -> None:
        handler = SimpleNamespace(game="Unsafe World", result_file_ending="../../output.rom")

        with self.assertRaisesRegex(ValueError, "invalid ROM output suffix"):
            OFFLINE_GENERATOR._standard_result_extension(handler)

    def test_standard_patch_streams_saf_descriptors_without_a_console_rule(self) -> None:
        requirement = {
            "key": "rom_file",
            "file_name": "Example.z64",
            "description": "clean example ROM",
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.z64"
            destination = root / "destination.z64"
            source.write_bytes(b"clean-rom")
            input_fd = os.open(source, os.O_RDONLY)
            output_fd = os.open(destination, os.O_CREAT | os.O_TRUNC | os.O_WRONLY)

            def run_patch(_patch_data, input_paths, output, _work_directory):
                self.assertEqual(b"clean-rom", Path(input_paths["rom_file"]).read_bytes())
                output.write_bytes(b"patched-z64")
                return "Example Game"

            try:
                with patch.object(OFFLINE_GENERATOR, "_prepare_runtime"), \
                        patch.object(OFFLINE_GENERATOR, "patch_game", return_value="Example Game"), \
                        patch.object(
                            OFFLINE_GENERATOR,
                            "_standard_patch_registration",
                            return_value=(
                                "Example Game",
                                object(),
                                object(),
                                ".z64",
                                object(),
                                [requirement],
                            ),
                        ), \
                        patch.object(OFFLINE_GENERATOR, "_run_standard_patch", side_effect=run_patch):
                    result = json.loads(OFFLINE_GENERATOR.patch_rom_documents(
                        b"player-patch",
                        json.dumps({"rom_file": input_fd}),
                        output_fd,
                        directory,
                    ))
            finally:
                os.close(input_fd)
                os.close(output_fd)

            self.assertEqual({"game": "Example Game", "extension": ".z64"}, result)
            self.assertEqual(b"patched-z64", destination.read_bytes())

    def test_resolves_registered_client_component_by_suffix_without_game_rule(self) -> None:
        launcher = ModuleType("worlds.LauncherComponents")
        launcher.Type = SimpleNamespace(CLIENT="CLIENT")
        launcher.components = []
        component = SimpleNamespace(
            display_name="Example Client",
            type="CLIENT",
            file_identifier=lambda path: path.endswith(".apexample"),
            func=lambda *_args: None,
        )
        launcher.components.append(component)
        worlds = ModuleType("worlds")
        with patch.dict(sys.modules, {"worlds": worlds, "worlds.LauncherComponents": launcher}):
            self.assertIs(component, OFFLINE_GENERATOR._client_component_for_path("player.apexample"))
            self.assertIsNone(OFFLINE_GENERATOR._client_component_for_path("player.apother"))

    def test_discovers_registered_client_backend_from_desktop_api_import(self) -> None:
        component = SimpleNamespace(func=lambda: None)
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Client.py"
            source.write_text(
                "import dolphin_memory_engine as dme\n"
                "from unrelated import helper\n",
                encoding="utf-8",
            )
            with patch.object(OFFLINE_GENERATOR, "_client_component_for_game", return_value=component), \
                    patch.object(OFFLINE_GENERATOR, "_module_source_roots", return_value={source}):
                self.assertEqual(
                    {"dolphin"},
                    OFFLINE_GENERATOR._registered_client_backends("Imported Game"),
                )

    def test_resolves_client_component_from_world_package_ownership(self) -> None:
        def run_component() -> None:
            pass

        run_component.__module__ = "worlds.example.client"
        component = SimpleNamespace(
            display_name="Example Client",
            type="CLIENT",
            file_identifier=None,
            game_name=None,
            func=run_component,
        )
        launcher = ModuleType("worlds.LauncherComponents")
        launcher.Type = SimpleNamespace(CLIENT="CLIENT")
        launcher.components = [component]
        auto_world = ModuleType("worlds.AutoWorld")
        auto_world.AutoWorldRegister = SimpleNamespace(
            world_types={"Example Game": SimpleNamespace(__module__="worlds.example")},
        )
        worlds = ModuleType("worlds")
        with patch.object(OFFLINE_GENERATOR, "_player_container_type", return_value=None), \
                patch.dict(sys.modules, {
                    "worlds": worlds,
                    "worlds.AutoWorld": auto_world,
                    "worlds.LauncherComponents": launcher,
                }):
            self.assertIs(
                component,
                OFFLINE_GENERATOR._client_component_for_game("Example Game"),
            )

    def test_does_not_treat_plain_text_as_a_backend_import(self) -> None:
        component = SimpleNamespace(func=lambda: None)
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Client.py"
            source.write_text(
                "HELP = 'Install dolphin_memory_engine before playing'\n",
                encoding="utf-8",
            )
            with patch.object(OFFLINE_GENERATOR, "_client_component_for_game", return_value=component), \
                    patch.object(OFFLINE_GENERATOR, "_module_source_roots", return_value={source}):
                self.assertEqual(
                    set(),
                    OFFLINE_GENERATOR._registered_client_backends("Unrelated Game"),
                )

    def test_component_output_is_captured_from_registered_client_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            player = root / "player.apexample"
            player.write_bytes(b"container")
            destination = root / "saved.rom"

            def launch(path: str) -> None:
                Path(path).with_suffix(".rom").write_bytes(b"patched-rom")

            descriptor = os.open(destination, os.O_CREAT | os.O_TRUNC | os.O_WRONLY)
            try:
                with patch.object(OFFLINE_GENERATOR, "COMPONENT_OUTPUT_STABLE_SECONDS", 0.01), \
                        patch.object(OFFLINE_GENERATOR, "COMPONENT_OUTPUT_POLL_SECONDS", 0.01):
                    result = OFFLINE_GENERATOR._copy_component_output(
                        SimpleNamespace(func=launch),
                        player,
                        ".rom",
                        descriptor,
                        1.0,
                    )
            finally:
                os.close(descriptor)

            self.assertEqual(player.with_suffix(".rom"), result)
            self.assertEqual(b"patched-rom", destination.read_bytes())

    def test_component_probe_failure_does_not_abort_world_catalog(self) -> None:
        with patch.object(OFFLINE_GENERATOR, "_client_component_for_path", return_value=object()), \
                patch.object(
                    OFFLINE_GENERATOR,
                    "_rom_requirements",
                    side_effect=AttributeError("Settings has no matching group"),
                ):
            self.assertEqual(
                ("", False),
                OFFLINE_GENERATOR._component_patch_capability(
                    "Example Game",
                    None,
                    ".apexample",
                    "unused",
                ),
            )
            self.assertEqual(
                ("", False),
                OFFLINE_GENERATOR._component_patch_capability(
                    "The Wind Waker",
                    None,
                    ".aptww",
                    "unused",
                ),
            )

    def test_validates_inherited_file_setting_directly_from_descriptor(self) -> None:
        from settings import FilePath

        class RomFile(FilePath):
            md5s = [hashlib.md5(b"disc-image").hexdigest()]

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.iso"
            source.write_bytes(b"disc-image")
            descriptor = os.open(source, os.O_RDONLY)
            try:
                OFFLINE_GENERATOR._validate_requirement_fd(
                    {"_setting_type": RomFile},
                    descriptor,
                )
            finally:
                os.close(descriptor)

    def test_stages_component_input_when_descriptor_path_cannot_be_reopened(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.iso"
            source.write_bytes(b"disc-image")
            descriptor = os.open(source, os.O_RDONLY)
            try:
                with patch.object(OFFLINE_GENERATOR, "_fd_path_is_reopenable", return_value=False):
                    path, staged = OFFLINE_GENERATOR._component_input_path(
                        descriptor,
                        {"key": "rom_file", "file_name": "Metroid_Prime.iso"},
                        root / "staged",
                    )
            finally:
                os.close(descriptor)

            self.assertIsNotNone(staged)
            self.assertEqual(".iso", Path(path).suffix)
            self.assertEqual(b"disc-image", Path(path).read_bytes())

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
