"""Android-facing entry points for offline mGBA Archipelago generation."""

from __future__ import annotations

import importlib
import inspect
import json
import os
import pkgutil
import shutil
import sys
import tempfile
import typing
import zipfile
from io import BytesIO
from pathlib import Path

from world_compatibility import apply_world_compatibility

os.environ["SKIP_REQUIREMENTS_UPDATE"] = "1"

MGBA_ROM_EXTENSIONS = frozenset({".gb", ".gbc", ".gba"})
SNES_ROM_EXTENSIONS = frozenset({".sfc", ".smc"})
ANDROID_ROM_EXTENSIONS = MGBA_ROM_EXTENSIONS | SNES_ROM_EXTENSIONS


def _world_load_failure(package_name: str, game: str, error: Exception) -> str:
    """Turn an APWorld import exception into an actionable Android error."""
    current: BaseException | None = error
    seen: set[int] = set()
    missing: ModuleNotFoundError | None = None
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, ModuleNotFoundError):
            missing = current
            break
        current = current.__cause__ or current.__context__

    technical = f"{type(error).__name__}: {error}"
    if missing is not None:
        module = (missing.name or str(missing)).strip("'\"")
        own_module = f"worlds.{package_name}"
        if module == own_module or module.startswith(f"{own_module}."):
            return (
                f"Incomplete APWorld package: {game} could not find its own module '{module}'.\n"
                "The archive may be damaged or packaged incorrectly. Download it again from the APWorld author.\n"
                f"Technical details: {technical}"
            )
        return (
            f"Missing Python dependency '{module}' required by {game}.\n"
            "That module is not bundled with this companion, and the Android APWorld installer cannot add Python "
            "packages itself. Update the companion or use an Android-compatible APWorld release; if neither is "
            "available, report the dependency name to the companion or APWorld maintainer.\n"
            f"Technical details: {technical}"
        )

    if isinstance(error, ImportError):
        return (
            f"Incompatible Python import while loading {game}.\n"
            "This APWorld expects a Python module or Archipelago API that is different from the version embedded "
            "in the companion. Use an APWorld release compatible with this companion.\n"
            f"Technical details: {technical}"
        )

    return technical


def _prepare_runtime(work_directory: str) -> tuple[Path, Path]:
    root = Path(work_directory).resolve()
    players = root / "Players"
    output = root / "output"
    players.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)

    # Android starts Python in a read-only working directory. Imported APWorlds
    # occasionally write diagnostics or other incidental files using relative
    # paths, so give them a stable app-private directory. Keep this separate
    # from `output`: everything in that folder is treated as a generated seed
    # artifact and may be cleared before the next generation.
    os.chdir(root)

    import Utils

    # The embedded Android runtime has no desktop window server. This is the
    # programmatic equivalent of Archipelago's upstream ``--nogui`` mode and
    # keeps APWorld validation errors from falling through to tkinter.
    Utils.gui_enabled = False
    Utils.user_path.cached_path = str(root)
    Utils.home_path.cached_path = str(root)
    return players, output


def _load_worlds(work_directory: str):
    """Load safely extracted user APWorld packages into the embedded AP core."""
    _prepare_runtime(work_directory)
    import worlds
    from Utils import tuplize_version
    from worlds.AutoWorld import AutoWorldRegister

    user_worlds = Path(work_directory).resolve() / "worlds"
    user_worlds.mkdir(parents=True, exist_ok=True)
    user_path = str(user_worlds)
    if user_path not in worlds.__path__:
        worlds.__path__.append(user_path)

    for package in sorted(user_worlds.iterdir()):
        if not package.is_dir() or package.name.startswith(("_", ".")):
            continue
        if not (package / "__init__.py").is_file():
            continue
        module_name = f"worlds.{package.name}"
        if module_name in sys.modules:
            continue
        game = package.name
        try:
            manifest = json.loads((package / "archipelago.json").read_text(encoding="utf-8"))
            game = manifest.get("game")
            if not game:
                raise ValueError("archipelago.json does not declare a game")
            if game in AutoWorldRegister.world_types:
                raise RuntimeError(f"Game {game} is already registered")
            importlib.import_module(module_name)
            world = AutoWorldRegister.world_types.get(game)
            if world is None:
                raise RuntimeError(f"{package.name} did not register game {game}")
            version = str(manifest.get("world_version", "0.0.0"))
            world.world_version = tuplize_version(version)
            world.manifest = {
                key: value for key, value in manifest.items()
                if key not in ("version", "compatible_version")
            }
            apply_world_compatibility(module_name, game, version)
            worlds.network_data_package["games"][game] = world.get_data_package_data()
        except Exception as error:
            worlds.failed_world_loads[package.name] = _world_load_failure(
                package.name,
                str(game),
                error,
            )
    return worlds, AutoWorldRegister


def _load_emulator_clients(work_directory: str):
    """Import conventional client modules from every installed world."""
    worlds, registry = _load_worlds(work_directory)
    for module in pkgutil.iter_modules(worlds.__path__, "worlds."):
        if module.name.rsplit(".", 1)[-1].startswith("_"):
            continue
        for suffix in ("client", "Client"):
            try:
                client_module = f"{module.name}.{suffix}"
                if importlib.util.find_spec(client_module) is not None:
                    importlib.import_module(client_module)
            except Exception:
                # Generation-only worlds remain available when an optional
                # desktop client needs modules which Android does not bundle.
                continue
    return worlds, registry


def _load_standard_mgba_clients(work_directory: str):
    """Register conventional GBA, GB, and GBC BizHawk clients."""
    return _load_emulator_clients(work_directory)


def _load_standard_sni_clients(work_directory: str):
    """Register conventional SNI clients without naming individual games."""
    return _load_emulator_clients(work_directory)


def _yaml_scalar(value) -> str:
    import yaml
    def plain(item):
        if isinstance(item, dict):
            return {plain(key): plain(content) for key, content in item.items()}
        if isinstance(item, (list, tuple, set, frozenset)):
            return [plain(content) for content in item]
        return item
    return yaml.safe_dump(plain(value), default_flow_style=True, allow_unicode=True).replace("...\n", "").strip()


def _comment_lines(text: str, indent: str = "    ") -> list[str]:
    cleaned = inspect.cleandoc(text or "").strip()
    return [f"{indent}# {line}" if line else f"{indent}#" for line in cleaned.splitlines()]


def template_for_game(work_directory: str, game: str) -> str:
    """Generate a complete, functional YAML template from a loaded APWorld."""
    _, registry = _load_worlds(work_directory)
    world = registry.world_types.get(game)
    if world is None:
        raise ValueError(f"No installed world handles game {game}")

    from Options import Choice, Range, get_option_groups
    from Utils import __version__

    version = world.world_version.as_simple_string()
    lines = [
        "# Generated on-device from the installed APWorld.",
        "# Scalar defaults are used so every visible option remains easy to edit.",
        "name: Player{number}",
        f"description: {_yaml_scalar('Default ' + game + ' Template')}",
        f"game: {_yaml_scalar(game)}",
        "requires:",
        f"  version: {__version__}",
    ]
    if version != "0.0.0":
        lines.extend(("  game:", f"    {_yaml_scalar(game)}: {version}"))
    lines.extend(("", f"{_yaml_scalar(game)}:"))

    for group_name, options in get_option_groups(world).items():
        lines.extend(("", f"  # --- {group_name} ---"))
        for option_key, option in options.items():
            lines.append("")
            lines.extend(_comment_lines(option.__doc__))
            if issubclass(option, Range):
                lines.append(f"    # Range: {option.range_start} to {option.range_end}")
            elif issubclass(option, Choice):
                choices = ", ".join(str(name) for name in option.options)
                if choices:
                    lines.append(f"    # Choices: {choices}")
            default = option.default
            if issubclass(option, Choice) and default in option.name_lookup:
                default = option.name_lookup[default]
            dumped = _yaml_scalar(default)
            if "\n" in dumped:
                lines.append(f"  {option_key}:")
                lines.extend(f"    {line}" for line in dumped.splitlines())
            else:
                lines.append(f"  {option_key}: {dumped}")
    lines.append("")
    return "\n".join(lines)


def _json_value(value):
    """Convert option defaults and YAML values into JSON-compatible data."""
    if isinstance(value, dict):
        return {str(key): _json_value(content) for key, content in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_json_value(content) for content in value]
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    return str(value)


def _form_option_value(option, value):
    """Normalize ordinary scalar values while preserving weighted/custom structures."""
    from Options import Choice, NamedRange, TextChoice, Toggle

    if isinstance(value, (dict, list, tuple, set, frozenset)):
        return _json_value(value)
    if isinstance(value, str) and (
            value.lower() == "random" or value.lower().startswith("random-")):
        return value
    try:
        parsed = option.from_any(value)
    except Exception:
        return _json_value(value)
    if issubclass(option, Toggle):
        return bool(parsed.value)
    if issubclass(option, Choice):
        if not issubclass(option, TextChoice) or isinstance(parsed.value, int):
            return parsed.current_key
    if issubclass(option, NamedRange):
        for name, number in option.special_range_names.items():
            if parsed.value == number:
                return name
    return _json_value(parsed.value)


def _option_schema(world) -> dict:
    from Options import (Choice, FreeText, NamedRange, OptionDict, OptionList,
                         OptionSet, Range, TextChoice, Toggle, get_option_groups)

    collapsed_groups = {
        group.name: bool(group.start_collapsed)
        for group in world.web.option_groups
    }
    groups = []
    for group_name, options in get_option_groups(world).items():
        fields = []
        for key, option in options.items():
            choices = []
            special_values = []
            if issubclass(option, Toggle):
                kind = "toggle"
            elif issubclass(option, TextChoice):
                kind = "text_choice"
                choices = [
                    {"value": name, "label": option.get_option_name(number)}
                    for number, name in option.name_lookup.items()
                ]
            elif issubclass(option, Choice):
                kind = "choice"
                choices = [
                    {"value": name, "label": option.get_option_name(number)}
                    for number, name in option.name_lookup.items()
                ]
                if "random" not in option.options:
                    choices.append({"value": "random", "label": "Random"})
            elif issubclass(option, NamedRange):
                kind = "range"
                special_values = [
                    {"value": name, "label": name.replace("_", " ").title()}
                    for name in option.special_range_names
                ]
            elif issubclass(option, Range):
                kind = "range"
            elif issubclass(option, FreeText):
                kind = "text"
            elif issubclass(option, OptionDict):
                kind = "dict"
            elif issubclass(option, OptionList):
                kind = "list"
            elif issubclass(option, OptionSet):
                kind = "set"
            else:
                kind = "custom"

            field = {
                "key": key,
                "label": getattr(option, "display_name", None) or key.replace("_", " ").title(),
                "description": inspect.cleandoc(option.__doc__ or "").strip(),
                "kind": kind,
                "default": _form_option_value(option, option.default),
                "choices": choices,
                "special_values": special_values,
                "supports_weighting": bool(getattr(option, "supports_weighting", False)),
            }
            if issubclass(option, Range):
                field["minimum"] = option.range_start
                field["maximum"] = option.range_end
            fields.append(field)
        groups.append({
            "name": group_name,
            "start_collapsed": collapsed_groups.get(group_name, False),
            "options": fields,
        })
    return {"game": world.game, "groups": groups}


def option_schema_for_game(work_directory: str, game: str) -> str:
    """Return the native-form description for an installed APWorld."""
    _, registry = _load_worlds(work_directory)
    world = registry.world_types.get(game)
    if world is None:
        raise ValueError(f"No installed world handles game {game}")
    return json.dumps(_option_schema(world))


def player_forms_from_yaml(work_directory: str, yaml_text: str) -> str:
    """Parse one or more player YAML documents into normalized native-form data."""
    import yaml

    _, registry = _load_worlds(work_directory)
    players = []
    for index, document in enumerate(yaml.safe_load_all(yaml_text), start=1):
        if not isinstance(document, dict):
            continue
        game = str(document.get("game", "")).strip()
        world = registry.world_types.get(game)
        if world is None:
            raise ValueError(f"Player {index} uses unavailable game {game or '(missing)'}")
        supplied = document.get(game, {})
        if supplied is None:
            supplied = {}
        if not isinstance(supplied, dict):
            raise ValueError(f"{game} options for player {index} must be a mapping")
        values = {}
        for key, option in world.options_dataclass.type_hints.items():
            values[key] = _form_option_value(option, supplied.get(key, option.default))
        # Preserve fields from future or custom APWorlds even if this embedded
        # Archipelago core does not yet expose them in the form schema.
        for key, value in supplied.items():
            if key not in values:
                values[str(key)] = _json_value(value)
        extras = {
            str(key): _json_value(value)
            for key, value in document.items()
            if key not in {"name", "game", game}
        }
        players.append({
            "name": str(document.get("name") or f"Player {index}"),
            "game": game,
            "values": values,
            "extras": extras,
        })
    if not players:
        raise ValueError("Add at least one player before generating")
    return json.dumps({"players": players})


def yaml_from_player_forms(players_json: str) -> str:
    """Serialize native-form data into standard multi-document Archipelago YAML."""
    import yaml

    payload = json.loads(players_json)
    documents = []
    for index, player in enumerate(payload.get("players", []), start=1):
        game = str(player.get("game", "")).strip()
        if not game:
            raise ValueError(f"Player {index} has no game")
        name = str(player.get("name", "")).strip()
        if not name:
            raise ValueError(f"Player {index} has no name")
        extras = dict(player.get("extras") or {})
        document = {"name": name}
        if "description" in extras:
            document["description"] = extras.pop("description")
        document["game"] = game
        if "requires" in extras:
            document["requires"] = extras.pop("requires")
        document.update(extras)
        document[game] = dict(player.get("values") or {})
        documents.append(document)
    if not documents:
        raise ValueError("Add at least one player before generating")
    return yaml.safe_dump_all(
        documents,
        sort_keys=False,
        allow_unicode=True,
        default_flow_style=False,
    ).strip() + "\n"


def world_catalog(work_directory: str) -> str:
    """Describe loaded world capabilities without promising a live adapter."""
    worlds, registry = _load_standard_mgba_clients(work_directory)
    _load_standard_sni_clients(work_directory)
    from worlds.Files import AutoPatchRegister
    from worlds.AutoSNIClient import AutoSNIClientRegister
    from worlds._bizhawk.client import AutoBizHawkClientRegister

    standard_mgba_clients = {
        game
        for systems, handlers in AutoBizHawkClientRegister.game_handlers.items()
        if not {"GBA", "GB", "GBC"}.isdisjoint(systems)
        for game in handlers
    }
    from android_bizhawk_runtime import custom_client_games
    bridge_games = (
        standard_mgba_clients
        | custom_client_games()
        | set(AutoSNIClientRegister.game_handlers)
    )

    installed_root = Path(work_directory).resolve() / "worlds"
    installed_modules = {f"worlds.{path.name}" for path in installed_root.iterdir() if path.is_dir()}
    result = []
    for game, world in sorted(registry.world_types.items()):
        patch_type = AutoPatchRegister.patch_types.get(game)
        patch_extension = getattr(patch_type, "patch_file_ending", "") if patch_type else ""
        result_extension = getattr(patch_type, "result_file_ending", "") if patch_type else ""
        result.append({
            "game": game,
            "version": world.world_version.as_simple_string(),
            "source": "imported" if world.__module__ in installed_modules or any(
                world.__module__.startswith(module + ".") for module in installed_modules
            ) else "bundled",
            "patch_extension": patch_extension,
            "result_extension": result_extension,
            "generation": True,
            "template": True,
            "rom_patch": bool(patch_type and result_extension.lower() in ANDROID_ROM_EXTENSIONS),
            "live_bridge": game in bridge_games,
        })
    return json.dumps({"worlds": result, "failures": worlds.failed_world_loads})


def generate(yaml_text: str, work_directory: str, seed: str = "") -> str:
    """Generate a seed and return JSON metadata for Kotlin."""
    players, output = _prepare_runtime(work_directory)
    for old_file in players.iterdir():
        if old_file.is_file():
            old_file.unlink()
    for old_file in output.iterdir():
        if old_file.is_file():
            old_file.unlink()

    player_file = players / "Player.yaml"
    player_file.write_text(yaml_text, encoding="utf-8")

    _load_worlds(work_directory)
    import Generate

    arguments = [
        "--player_files_path", str(players),
        "--outputpath", str(output),
        "--weights_file_path", str(players / "_no_weights.yaml"),
        "--meta_file_path", str(players / "_no_meta.yaml"),
        "--multi", "1",
        "--spoiler", "0",
    ]
    if seed.strip():
        arguments.extend(("--seed", seed.strip()))
    args = Generate.mystery_argparse(arguments)
    args, numeric_seed = Generate.main(args)
    player_names = [args.name[player] for player in range(1, args.multi + 1)]
    from Main import main as generate_multiworld

    generate_multiworld(args, numeric_seed)

    from worlds.Files import AutoPatchRegister
    patch_endings = {ending.lower() for ending in AutoPatchRegister.file_endings}
    files = []
    patches = []
    for path in sorted(output.iterdir()):
        if not path.is_file():
            continue
        files.append({"name": path.name, "path": str(path), "kind": "seed"})
        if path.suffix.lower() == ".zip":
            with zipfile.ZipFile(path, "r") as archive:
                for member in archive.namelist():
                    if Path(member).suffix.lower() in patch_endings:
                        patch_path = output / Path(member).name
                        with archive.open(member) as source, patch_path.open("wb") as target:
                            shutil.copyfileobj(source, target)
                        patch_info = {"name": patch_path.name, "path": str(patch_path), "kind": "patch"}
                        patches.append(patch_info)
                        files.append(patch_info)

    if not any(Path(file["path"]).suffix.lower() == ".zip" for file in files):
        raise RuntimeError("Generation completed, but no hostable Archipelago seed ZIP was produced")
    return json.dumps({
        "seed": str(numeric_seed),
        "players": player_names,
        "files": files,
        "patches": patches,
    })


def patch_game(patch_bytes, work_directory: str = "") -> str:
    """Return the game declared by a player patch."""
    if work_directory:
        _load_worlds(work_directory)
    with zipfile.ZipFile(BytesIO(bytes(patch_bytes)), "r") as archive:
        try:
            manifest = json.loads(archive.read("archipelago.json"))
        except KeyError as error:
            raise ValueError("The selected file has no standard Archipelago patch manifest") from error
        game = manifest.get("game")
        if not isinstance(game, str) or not game.strip():
            raise ValueError("The Archipelago patch does not declare a game")
        return game.strip()


def patch_result_extension(patch_bytes, work_directory: str = "") -> str:
    """Return the emulator ROM extension produced by a player patch."""
    if work_directory:
        _load_worlds(work_directory)
    with zipfile.ZipFile(BytesIO(bytes(patch_bytes)), "r") as archive:
        try:
            manifest = json.loads(archive.read("archipelago.json"))
        except KeyError as error:
            raise ValueError("The selected file is not a supported Archipelago ROM patch") from error
    game = manifest.get("game")
    from worlds.Files import AutoPatchRegister
    handler = AutoPatchRegister.patch_types.get(game)
    if handler is None:
        raise ValueError(f"The installed {game} world does not register a ROM patch handler")
    extension = str(getattr(handler, "result_file_ending", "")).lower()
    if extension not in ANDROID_ROM_EXTENSIONS:
        raise ValueError(f"{game} produces {extension or 'an unsupported ROM format'}")
    return extension


def _rom_requirements(game: str, work_directory: str) -> tuple[object, list[dict[str, object]]]:
    """Discover validated user files declared by an APWorld's settings group."""
    _, registry = _load_worlds(work_directory)
    world = registry.world_types.get(game)
    if world is None:
        raise ValueError(f"No installed world handles game {game}")

    from settings import UserFilePath, get_settings

    settings_group = get_settings()[world.settings_key]
    def user_file_type(annotation: object) -> type[UserFilePath] | None:
        if isinstance(annotation, type) and issubclass(annotation, UserFilePath):
            return annotation
        for candidate in typing.get_args(annotation):
            match = user_file_type(candidate)
            if match is not None:
                return match
        return None

    requirements = []
    for name, annotation in settings_group.__class__.get_type_hints().items():
        setting_type = user_file_type(annotation)
        if setting_type is None:
            continue
        if not getattr(setting_type, "md5s", None):
            continue
        declared_value = next(
            (group_type.__dict__[name] for group_type in settings_group.__class__.__mro__ if name in group_type.__dict__),
            "",
        )
        current_value = settings_group.__dict__.get(name, declared_value)
        description = getattr(setting_type, "description", None)
        if not description:
            documentation = inspect.cleandoc(setting_type.__doc__ or "").strip()
            description = documentation.splitlines()[0] if documentation else None
        declared_name = getattr(setting_type, "copy_to", None) or str(current_value)
        requirements.append({
            "key": name,
            "description": description or f"Clean, unmodified file required by {game}",
            "file_name": Path(str(declared_name)).name,
            "_setting_type": setting_type,
        })
    if not requirements:
        raise ValueError(f"The installed {game} world does not declare any validated ROM files")
    return settings_group, requirements


def rom_requirements(patch_bytes, work_directory: str) -> str:
    """Describe every validated ROM input requested by the registered APWorld."""
    game = patch_game(patch_bytes, work_directory)
    _, requirements = _rom_requirements(game, work_directory)
    return json.dumps({
        "game": game,
        "inputs": [
            {key: value for key, value in requirement.items() if not key.startswith("_")}
            for requirement in requirements
        ],
    })


def validate_rom_input(patch_bytes, input_key: str, rom_bytes, work_directory: str) -> None:
    """Run the APWorld's declared validator for one Android-selected ROM."""
    game = patch_game(patch_bytes, work_directory)
    _, requirements = _rom_requirements(game, work_directory)
    requirement = next(
        (item for item in requirements if item["key"] == input_key),
        None,
    )
    if requirement is None:
        raise ValueError(f"{game} did not request ROM input {input_key}")
    setting_type = requirement["_setting_type"]
    suffix = Path(str(requirement.get("file_name", ""))).suffix or ".rom"
    temporary_root = Path(work_directory).resolve()
    with tempfile.NamedTemporaryFile(prefix="rom-validation-", suffix=suffix, dir=temporary_root, delete=False) as file:
        temporary_path = Path(file.name)
        file.write(bytes(rom_bytes))
    try:
        setting_type.validate(str(temporary_path))
    except Exception as error:
        description = requirement["description"]
        raise ValueError(f"The selected file is not the required {description}.") from error
    finally:
        temporary_path.unlink(missing_ok=True)


def _apply_procedure_patch(
    patch_data: bytes,
    rom_inputs: dict[str, bytes],
    work_directory: str,
) -> tuple[bytes, str]:
    """Stage Android-selected data and run the APWorld's normal desktop patch path."""
    _, registry = _load_worlds(work_directory)
    from worlds.Files import AutoPatchRegister

    with zipfile.ZipFile(BytesIO(patch_data), "r") as archive:
        try:
            manifest = json.loads(archive.read("archipelago.json"))
        except KeyError as error:
            raise ValueError("The selected file has no Archipelago patch manifest") from error
    game = manifest.get("game")
    handler = AutoPatchRegister.patch_types.get(game)
    if handler is None:
        raise ValueError(f"The installed {game} world does not register a ROM patch handler")
    world = registry.world_types.get(game)
    if world is None:
        raise ValueError(f"No installed world handles game {game}")
    result_extension = getattr(handler, "result_file_ending", "").lower()
    if result_extension not in ANDROID_ROM_EXTENSIONS:
        raise ValueError(
            f"{game} produces {getattr(handler, 'result_file_ending', 'an unsupported ROM format')}"
        )

    settings_group, requirements = _rom_requirements(game, work_directory)
    expected_keys = {str(requirement["key"]) for requirement in requirements}
    missing_keys = expected_keys - rom_inputs.keys()
    if missing_keys:
        raise ValueError(f"Missing ROM input(s) requested by {game}: {', '.join(sorted(missing_keys))}")

    package_prefix = ".".join(world.__module__.split(".")[:2])

    def clear_source_caches() -> None:
        for attribute in ("source_data", "base_rom_bytes"):
            if attribute in handler.__dict__:
                delattr(handler, attribute)
        for module_name, module in tuple(sys.modules.items()):
            if module is None or not (module_name == package_prefix or module_name.startswith(package_prefix + ".")):
                continue
            for value in vars(module).values():
                if callable(value) and "base_rom_bytes" in getattr(value, "__dict__", {}):
                    delattr(value, "base_rom_bytes")

    temporary_root = Path(work_directory).resolve()
    with tempfile.TemporaryDirectory(prefix="apworld-patch-", dir=temporary_root) as temporary:
        temporary_path = Path(temporary)
        patch_extension = getattr(handler, "patch_file_ending", ".ap")
        staged_patch = temporary_path / f"player{patch_extension}"
        staged_output = temporary_path / f"result{result_extension}"
        staged_patch.write_bytes(patch_data)

        missing = object()
        previous_settings = {}
        for index, requirement in enumerate(requirements):
            key = str(requirement["key"])
            setting_type = requirement["_setting_type"]
            previous_settings[key] = settings_group.__dict__.get(key, missing)
            suffix = Path(str(requirement.get("file_name", ""))).suffix or ".rom"
            staged_rom = temporary_path / f"input-{index}{suffix}"
            staged_rom.write_bytes(rom_inputs[key])
            setattr(settings_group, key, setting_type(str(staged_rom)))

        clear_source_caches()
        try:
            patch = handler(str(staged_patch))
            patch.patch(str(staged_output))
            if not staged_output.is_file():
                raise ValueError(f"The {game} APWorld did not produce a patched ROM")
            return staged_output.read_bytes(), game
        finally:
            clear_source_caches()
            for key, previous in previous_settings.items():
                if previous is missing:
                    settings_group.__dict__.pop(key, None)
                else:
                    setattr(settings_group, key, previous)


def patch_rom(patch_bytes, rom_input_paths_json: str, output_path: str, work_directory: str) -> str:
    """Apply a standard APWorld player patch to Android-selected ROM inputs."""
    # Invite patching can be the first Python operation after the Android process
    # starts. Configure Archipelago's writable paths before importing the APWorld;
    # otherwise its default relative Players path resolves against Android's `/`.
    _prepare_runtime(work_directory)
    patch_data_bytes = bytes(patch_bytes)
    game = patch_game(patch_data_bytes, work_directory)
    _, registry = _load_worlds(work_directory)
    if game not in registry.world_types:
        raise ValueError(f"Install the {game} APWorld before patching this ROM")
    destination = Path(output_path).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    raw_paths = json.loads(rom_input_paths_json)
    if not isinstance(raw_paths, dict):
        raise ValueError("ROM inputs must be a key-to-file mapping")
    rom_inputs = {
        str(key): Path(str(path)).read_bytes()
        for key, path in raw_paths.items()
    }
    result, _ = _apply_procedure_patch(patch_data_bytes, rom_inputs, work_directory)
    destination.write_bytes(result)
    return str(destination)
