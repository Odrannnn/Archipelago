"""Android-facing entry points for offline mGBA Archipelago generation."""

from __future__ import annotations

import ast
import importlib
import inspect
import json
import logging
import os
import pkgutil
import re
import shutil
import sys
import tempfile
import threading
import time
import typing
import zipfile
from io import BytesIO
from pathlib import Path

os.environ["SKIP_REQUIREMENTS_UPDATE"] = "1"

MGBA_ROM_EXTENSIONS = frozenset({".gb", ".gbc", ".gba"})
SNES_ROM_EXTENSIONS = frozenset({".sfc", ".smc"})
MAX_FILL_ATTEMPTS = 50
MAX_REPEATED_FILL_FAILURES = 20
MAX_PLAYER_MANIFEST_BYTES = 1024 * 1024
MAX_GENERATED_ARTIFACT_BYTES = 256 * 1024 * 1024
COMPONENT_OUTPUT_STABLE_SECONDS = 30.0
COMPONENT_OUTPUT_POLL_SECONDS = 0.5

# Launcher components do not currently declare their emulator transport. Keep
# this mapping on public desktop APIs instead of game names so an imported
# APWorld opts into a backend by using the same API as its desktop client.
CLIENT_BACKEND_MODULES = {
    "dolphin": frozenset({"dolphin_memory_engine"}),
    "sni": frozenset({"SNIClient", "worlds.AutoSNIClient"}),
    "bizhawk": frozenset({"BizHawkClient", "worlds._bizhawk"}),
}
MAX_CLIENT_SOURCE_BYTES = 2 * 1024 * 1024


def _activate_android_dependencies(root: Path) -> None:
    """Expose verified, app-private dependency packages before APWorld imports."""
    registry = root / "python_dependencies" / "installed.json"
    if not registry.is_file():
        return
    try:
        records = json.loads(registry.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        logging.getLogger(__name__).warning("Ignoring an unreadable Android dependency registry")
        return

    changed = False
    for record in records if isinstance(records, list) else ():
        if not isinstance(record, dict):
            continue
        relative = record.get("site_packages")
        if not isinstance(relative, str) or not relative:
            continue
        candidate = (root / "python_dependencies" / relative).resolve()
        dependency_root = (root / "python_dependencies").resolve()
        try:
            candidate.relative_to(dependency_root)
        except ValueError:
            continue
        if not candidate.is_dir():
            continue
        path = str(candidate)
        if path not in sys.path:
            # User-installed packages should take precedence over packages bundled
            # by Chaquopy, matching a desktop virtual environment's site-packages.
            sys.path.insert(0, path)
            changed = True
    if changed:
        importlib.invalidate_caches()


def _player_container_manifest(source) -> dict[str, object] | None:
    """Return an upstream APPlayerContainer manifest, independent of its suffix."""
    try:
        with zipfile.ZipFile(source, "r") as container:
            manifest_info = next(
                (info for info in container.infolist() if info.filename == "archipelago.json"),
                None,
            )
            if manifest_info is None:
                manifest_info = next(
                    (
                        info for info in container.infolist()
                        if info.filename.endswith("/archipelago.json")
                    ),
                    None,
                )
            if manifest_info is None or manifest_info.file_size > MAX_PLAYER_MANIFEST_BYTES:
                return None
            manifest = json.loads(container.read(manifest_info))
    except (OSError, ValueError, KeyError, RuntimeError, zipfile.BadZipFile, json.JSONDecodeError):
        return None
    if not isinstance(manifest, dict):
        return None
    if not isinstance(manifest.get("game"), str) or not manifest.get("game"):
        return None
    player = manifest.get("player")
    if not isinstance(player, int) or isinstance(player, bool) or player < 1:
        return None
    if not isinstance(manifest.get("player_name"), str):
        return None
    return manifest


def _extract_seed_artifacts(seed_archive: Path, output: Path) -> list[tuple[Path, str]]:
    """Extract all distributable APWorld outputs from a host seed archive."""
    extracted = []
    with zipfile.ZipFile(seed_archive, "r") as archive:
        for member in archive.infolist():
            if member.is_dir() or member.file_size <= 0:
                continue
            file_name = Path(member.filename).name
            if not file_name or Path(file_name).suffix.lower() in {".archipelago", ".apworld"}:
                continue
            if member.file_size > MAX_GENERATED_ARTIFACT_BYTES:
                raise RuntimeError(f"Generated player artifact is too large: {file_name}")
            try:
                with archive.open(member) as candidate:
                    # ZipExtFile seeking differs between the Android and desktop
                    # zipfile implementations. Player containers are small, so
                    # give the nested ZipFile an ordinary seekable buffer.
                    data = candidate.read()
                    manifest = _player_container_manifest(BytesIO(data))
            except (OSError, ValueError, zipfile.BadZipFile):
                continue
            artifact_path = output / file_name
            if artifact_path.exists():
                if artifact_path.read_bytes() != data:
                    raise RuntimeError(f"Generated player artifact name is duplicated: {file_name}")
            else:
                artifact_path.write_bytes(data)
            extracted.append((artifact_path, "patch" if manifest is not None else "player"))
    return extracted


def _extract_player_containers(seed_archive: Path, output: Path) -> list[Path]:
    """Compatibility wrapper returning only standard APPlayerContainer outputs."""
    return [
        path for path, kind in _extract_seed_artifacts(seed_archive, output)
        if kind == "patch"
    ]


def extract_player_containers(seed_archive: str, output_directory: str) -> str:
    """Repair history created before suffix-independent player discovery existed."""
    seed = Path(seed_archive).resolve()
    output = Path(output_directory).resolve()
    if not seed.is_file() or not output.is_dir():
        raise FileNotFoundError("The saved seed archive or history directory is missing")
    extracted = _extract_player_containers(seed, output)
    return json.dumps([
        {"name": path.name, "path": str(path), "kind": "patch"}
        for path in extracted
    ])


def extract_seed_artifacts(seed_archive: str, output_directory: str) -> str:
    """Recover every player-facing output retained inside a saved host seed ZIP."""
    seed = Path(seed_archive).resolve()
    output = Path(output_directory).resolve()
    if not seed.is_file() or not output.is_dir():
        raise FileNotFoundError("The saved seed archive or history directory is missing")
    extracted = _extract_seed_artifacts(seed, output)
    return json.dumps([
        {"name": path.name, "path": str(path), "kind": kind}
        for path, kind in extracted
    ])


def _all_subclasses(base: type) -> list[type]:
    result = []
    pending = list(base.__subclasses__())
    while pending:
        candidate = pending.pop()
        if candidate in result:
            continue
        result.append(candidate)
        pending.extend(candidate.__subclasses__())
    return result


def _player_container_type(game: str):
    """Find any upstream player-container declaration, including non-patches."""
    from worlds.Files import APPlayerContainer

    candidates = [
        candidate for candidate in _all_subclasses(APPlayerContainer)
        if getattr(candidate, "game", None) == game
        and isinstance(getattr(candidate, "patch_file_ending", None), str)
        and getattr(candidate, "patch_file_ending")
    ]
    if not candidates:
        return None
    # Prefer the most-derived declaration when an APWorld has an intermediate
    # base class carrying the same game name.
    return max(candidates, key=lambda candidate: len(candidate.mro()))


def _client_component_for_path(path: str):
    """Resolve an APWorld's ordinary desktop launcher registration."""
    from worlds.LauncherComponents import Type, components

    for component in reversed(components):
        identifier = getattr(component, "file_identifier", None)
        if getattr(component, "type", None) != Type.CLIENT or not callable(identifier):
            continue
        try:
            if identifier(path) and callable(getattr(component, "func", None)):
                return component
        except Exception:
            continue
    return None


def _client_component_for_game(game: str):
    """Resolve a world's registered client through its declared player container."""
    patch_type = _player_container_type(str(game))
    extension = getattr(patch_type, "patch_file_ending", "") if patch_type is not None else ""
    if extension:
        component = _client_component_for_path(f"Player{extension}")
        if component is not None:
            return component

    # Some patchless worlds identify their component by game rather than a
    # player-container suffix. This is part of LauncherComponents' public API.
    from worlds.LauncherComponents import Type, components
    for component in reversed(components):
        if (
            getattr(component, "type", None) == Type.CLIENT
            and callable(getattr(component, "func", None))
            and getattr(component, "game_name", None) == game
        ):
            return component

    # Older APWorlds may omit both a class-level patch suffix and game_name.
    # Their registered function still belongs to the same Python package as
    # their World class, which is stable LauncherComponents ownership data.
    try:
        from worlds.AutoWorld import AutoWorldRegister

        world = AutoWorldRegister.world_types.get(str(game))
        world_module = str(getattr(world, "__module__", ""))
        parts = world_module.split(".")
        package = ".".join(parts[:2]) if len(parts) >= 2 and parts[0] == "worlds" else world_module
    except Exception:
        package = ""
    if package:
        for component in reversed(components):
            function_module = str(getattr(getattr(component, "func", None), "__module__", ""))
            if (
                getattr(component, "type", None) == Type.CLIENT
                and callable(getattr(component, "func", None))
                and (function_module == package or function_module.startswith(f"{package}."))
            ):
                return component
    return None


def _module_source_roots(module_name: str) -> set[Path]:
    """Locate the source tree which owns a registered launcher component."""
    if not module_name:
        return set()
    candidates = [module_name]
    parts = module_name.split(".")
    if len(parts) >= 2 and parts[0] == "worlds":
        candidates.insert(0, ".".join(parts[:2]))

    roots: set[Path] = set()
    for candidate in candidates:
        try:
            module = importlib.import_module(candidate)
        except Exception:
            continue
        locations = getattr(module, "__path__", None)
        if locations:
            roots.update(Path(location).resolve() for location in locations)
            continue
        source = getattr(module, "__file__", None)
        if source:
            roots.add(Path(source).resolve())
    return roots


def _source_tree_imports(root: Path) -> set[str]:
    """Read import declarations without executing optional client modules."""
    sources = root.rglob("*.py") if root.is_dir() else (root,)
    imports: set[str] = set()
    for source in sources:
        try:
            if not source.is_file() or source.stat().st_size > MAX_CLIENT_SOURCE_BYTES:
                continue
            tree = ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
        except (OSError, UnicodeError, SyntaxError):
            continue
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imports.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imports.add(node.module)
    return imports


def _registered_client_backends(game: str) -> set[str]:
    """Infer emulator APIs used by a world's ordinary desktop client."""
    component = _client_component_for_game(str(game))
    if component is None:
        return set()

    module_names = {getattr(getattr(component, "func", None), "__module__", "")}
    try:
        from worlds.AutoWorld import AutoWorldRegister

        world = AutoWorldRegister.world_types.get(str(game))
        if world is not None:
            module_names.add(getattr(world, "__module__", ""))
    except Exception:
        pass

    imports: set[str] = set()
    for module_name in module_names:
        for root in _module_source_roots(module_name):
            imports.update(_source_tree_imports(root))

    return {
        backend
        for backend, api_modules in CLIENT_BACKEND_MODULES.items()
        if any(
            imported == api_module or imported.startswith(f"{api_module}.")
            for imported in imports
            for api_module in api_modules
        )
    }


def registered_client_backends(game: str, work_directory: str) -> set[str]:
    """Load installed worlds and report a client's compatible emulator APIs."""
    _load_emulator_clients(work_directory)
    return _registered_client_backends(str(game))


def _component_result_extension(requirements: list[dict[str, object]]) -> str:
    """Best-effort output suffix for clients which patch their selected ROM in place."""
    for requirement in requirements:
        suffix = Path(str(requirement.get("file_name", ""))).suffix.lower()
        if suffix:
            return suffix
    return ".rom"


def _standard_result_extension(handler) -> str:
    """Validate an upstream AutoPatch output suffix without naming consoles."""
    extension = str(getattr(handler, "result_file_ending", "")).lower()
    if not re.fullmatch(r"\.[a-z0-9][a-z0-9._-]{0,31}", extension):
        raise ValueError(
            f"{getattr(handler, 'game', 'The installed world')} declares an invalid ROM output suffix"
        )
    return extension


def _component_patch_capability(
    game: str,
    patch_type,
    patch_extension: str,
    work_directory: str,
) -> tuple[str, bool]:
    """Probe an optional desktop client without allowing one world to break the catalog."""
    if game == "The Wind Waker" or patch_type is not None or not patch_extension:
        return "", False
    if _client_component_for_path(f"player{patch_extension}") is None:
        return "", False
    try:
        _, requirements = _rom_requirements(game, work_directory, validated_only=False)
    except Exception as error:
        logging.getLogger(__name__).warning(
            "Could not inspect client-component ROM inputs for %s: %s",
            game,
            error,
        )
        return "", False
    if not requirements:
        return "", False
    return _component_result_extension(requirements), True


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
            "Open Game worlds to retry the APWorld's declared dependency installation. Universal wheels are "
            "installed from PyPI; packages with native code need a compatible artifact in the Android build cache.\n"
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
    _activate_android_dependencies(root)
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

    import settings
    import Utils

    # The embedded Android runtime has no desktop window server. This is the
    # programmatic equivalent of Archipelago's upstream ``--nogui`` mode and
    # keeps both APWorld validation messages and optional file settings from
    # falling through to tkinter.
    settings.no_gui = True
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


def _load_standard_bizhawk_clients(work_directory: str):
    """Register conventional BizHawk clients without naming individual games."""
    return _load_emulator_clients(work_directory)


def _load_standard_mgba_clients(work_directory: str):
    """Backward-compatible alias for older Android runtime callers."""
    return _load_standard_bizhawk_clients(work_directory)


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
    worlds, registry = _load_standard_bizhawk_clients(work_directory)
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
    standard_n64_clients = {
        game
        for systems, handlers in AutoBizHawkClientRegister.game_handlers.items()
        if "N64" in systems
        for game in handlers
    }
    from android_bizhawk_runtime import custom_client_games
    from android_dolphin_runtime import built_in_dolphin_games
    custom_mgba_games = custom_client_games({"GBA", "GB", "GBC"})
    custom_n64_games = custom_client_games({"N64"})
    sni_games = set(AutoSNIClientRegister.game_handlers)
    dolphin_games = built_in_dolphin_games()

    installed_root = Path(work_directory).resolve() / "worlds"
    installed_modules = {f"worlds.{path.name}" for path in installed_root.iterdir() if path.is_dir()}
    result = []
    for game, world in sorted(registry.world_types.items()):
        emulator_backends = _registered_client_backends(game)
        if game in standard_mgba_clients or game in custom_mgba_games:
            emulator_backends.add("mgba")
        if game in standard_n64_clients or game in custom_n64_games:
            emulator_backends.add("retroarch_n64")
        if game in sni_games:
            emulator_backends.add("sni")
        if game in dolphin_games:
            emulator_backends.add("dolphin")
        patch_type = AutoPatchRegister.patch_types.get(game)
        container_type = patch_type or _player_container_type(game)
        patch_extension = getattr(container_type, "patch_file_ending", "") if container_type else ""
        try:
            result_extension = _standard_result_extension(patch_type) if patch_type else ""
        except ValueError:
            result_extension = ""
        component_extension, component_patch = _component_patch_capability(
            game,
            patch_type,
            patch_extension,
            work_directory,
        )
        if component_patch:
            result_extension = component_extension
        if game == "The Wind Waker":
            from android_tww_patcher import PATCH_EXTENSION, RESULT_EXTENSION
            patch_extension = PATCH_EXTENSION
            result_extension = RESULT_EXTENSION
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
            "rom_patch": bool(
                game == "The Wind Waker"
                or (patch_type and result_extension)
                or component_patch
            ),
            "live_bridge": bool(emulator_backends),
            "emulator_backends": sorted(emulator_backends),
        })
    return json.dumps({"worlds": result, "failures": worlds.failed_world_loads})


def _fill_error_summary(error) -> str:
    lines = [line.strip() for line in str(error).splitlines() if line.strip()]
    return lines[0] if lines else "Unspecified fill failure"


def _fill_error_fingerprint(error) -> str:
    """Group equivalent fill failures whose incidental numeric counts differ."""
    summary = _fill_error_summary(error).lower()
    summary = re.sub(r"0x[0-9a-f]+", "{number}", summary)
    summary = re.sub(r"\b\d+\b", "{number}", summary)
    return re.sub(r"\s+", " ", summary).strip()


def _diagnostic_value(value, maximum: int = 120) -> str:
    try:
        rendered = json.dumps(_json_value(value), ensure_ascii=False, sort_keys=True)
    except Exception:
        rendered = str(value)
    if len(rendered) > maximum:
        return rendered[:maximum - 1] + "…"
    return rendered


def _non_default_option_descriptions(yaml_text: str, registry) -> list[str]:
    """Best-effort list of declared player settings which differ from APWorld defaults."""
    import yaml

    changed = []
    try:
        documents = list(yaml.safe_load_all(yaml_text))
    except Exception:
        return changed
    for index, document in enumerate(documents, start=1):
        if not isinstance(document, dict):
            continue
        game = str(document.get("game", "")).strip()
        world = registry.world_types.get(game)
        supplied = document.get(game, {})
        if world is None or not isinstance(supplied, dict):
            continue
        player = str(document.get("name") or f"Player {index}")
        for key, value in supplied.items():
            option = world.options_dataclass.type_hints.get(key)
            if option is None:
                changed.append(f"{player} ({game}) · {key} = {_diagnostic_value(value)} (custom option)")
                continue
            normalized = _form_option_value(option, value)
            default = _form_option_value(option, option.default)
            if normalized == default:
                continue
            label = getattr(option, "display_name", None) or str(key).replace("_", " ").title()
            changed.append(
                f"{player} ({game}) · {label} = {_diagnostic_value(normalized)} "
                f"(default: {_diagnostic_value(default)})"
            )
    return changed


def _fill_failure_diagnostic(attempts: int, failures: dict[str, dict], changed_settings: list[str]) -> str:
    ranked = sorted(failures.values(), key=lambda failure: (-failure["count"], failure["summary"]))
    lines = [
        f"Generation appears incompatible with the selected settings after {attempts} attempts.",
        "Archipelago repeatedly rejected item placement; further automatic retries are unlikely to help.",
        "",
        "Repeated fill failures:",
    ]
    for failure in ranked[:3]:
        lines.append(f"• {failure['count']}× {failure['summary']}")
    if len(ranked) > 3:
        lines.append(f"• {len(ranked) - 3} other failure pattern(s)")
    lines.extend(("", "Settings differing from their APWorld defaults:"))
    if changed_settings:
        lines.extend(f"• {setting}" for setting in changed_settings[:12])
        if len(changed_settings) > 12:
            lines.append(f"• …and {len(changed_settings) - 12} more changed setting(s)")
        lines.extend((
            "",
            "Reset likely related settings toward their defaults, or change Accessibility to Minimal if unreachable "
            "optional locations are acceptable.",
        ))
    else:
        lines.extend((
            "• None detected",
            "",
            "This is likely an APWorld logic or Archipelago-version compatibility problem rather than device performance.",
        ))
    lines.append("The Companion did not alter your YAML.")
    return "\n".join(lines)


def _retry_fill_failures(run_attempt, initial_seed: str, changed_settings: list[str] | None = None):
    """Retry stochastic fill failures while stopping probable deterministic conflicts."""
    candidate_seed = initial_seed.strip()
    attempts = 0
    failures = {}
    while attempts < MAX_FILL_ATTEMPTS:
        attempts += 1
        outcome = run_attempt(candidate_seed)
        fill_error = outcome.get("fill_error")
        if fill_error is None:
            return outcome, attempts
        error_summary = _fill_error_summary(fill_error)
        fingerprint = _fill_error_fingerprint(fill_error)
        failure = failures.setdefault(fingerprint, {"count": 0, "summary": error_summary})
        failure["count"] += 1
        repeated = failure["count"] >= MAX_REPEATED_FILL_FAILURES
        exhausted = attempts >= MAX_FILL_ATTEMPTS
        if repeated or exhausted:
            raise RuntimeError(_fill_failure_diagnostic(
                attempts,
                failures,
                list(changed_settings or []),
            ))
        logging.warning(
            "Seed %s failed during item placement on attempt %d; retrying with a new seed: %s",
            outcome["seed"], attempts, error_summary,
        )
        candidate_seed = str(int(outcome["seed"]) + 1)

    raise AssertionError("Fill retry loop exited without a result or diagnostic")


def generate(yaml_text: str, work_directory: str, seed: str = "") -> str:
    """Generate a seed, retrying randomized fill failures, and return metadata for Kotlin."""
    players, output = _prepare_runtime(work_directory)
    for old_file in players.iterdir():
        if old_file.is_file():
            old_file.unlink()
    for old_file in output.iterdir():
        if old_file.is_file():
            old_file.unlink()

    player_file = players / "Player.yaml"
    player_file.write_text(yaml_text, encoding="utf-8")

    _, registry = _load_worlds(work_directory)
    import Generate

    base_arguments = [
        "--player_files_path", str(players),
        "--outputpath", str(output),
        "--weights_file_path", str(players / "_no_weights.yaml"),
        "--meta_file_path", str(players / "_no_meta.yaml"),
        "--multi", "1",
        "--spoiler", "0",
    ]
    from Main import main as generate_multiworld
    from Fill import FillError

    def run_attempt(candidate_seed: str):
        for old_file in output.iterdir():
            if old_file.is_file():
                old_file.unlink()
        arguments = list(base_arguments)
        if candidate_seed:
            arguments.extend(("--seed", candidate_seed))
        args = Generate.mystery_argparse(arguments)
        args, numeric_seed = Generate.main(args)
        player_names = [args.name[player] for player in range(1, args.multi + 1)]
        try:
            generate_multiworld(args, numeric_seed)
        except FillError as error:
            return {"seed": numeric_seed, "fill_error": str(error)}
        return {"seed": numeric_seed, "players": player_names}

    changed_settings = _non_default_option_descriptions(yaml_text, registry)
    outcome, attempts = _retry_fill_failures(run_attempt, seed, changed_settings)
    numeric_seed = outcome["seed"]
    player_names = outcome["players"]

    files = []
    patches = []
    for path in sorted(output.iterdir()):
        if not path.is_file():
            continue
        files.append({"name": path.name, "path": str(path), "kind": "seed"})
        if path.suffix.lower() == ".zip":
            known_paths = {file["path"] for file in files}
            for artifact_path, kind in _extract_seed_artifacts(path, output):
                artifact_info = {"name": artifact_path.name, "path": str(artifact_path), "kind": kind}
                if kind == "patch":
                    patches.append(artifact_info)
                if str(artifact_path) not in known_paths:
                    files.append(artifact_info)
                    known_paths.add(str(artifact_path))

    if not any(Path(file["path"]).suffix.lower() == ".zip" for file in files):
        raise RuntimeError("Generation completed, but no hostable Archipelago seed ZIP was produced")
    return json.dumps({
        "seed": str(numeric_seed),
        "attempts": attempts,
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
    if game == "The Wind Waker":
        from android_tww_patcher import RESULT_EXTENSION, load_plando
        load_plando(bytes(patch_bytes))
        return RESULT_EXTENSION
    from worlds.Files import AutoPatchRegister
    handler = AutoPatchRegister.patch_types.get(game)
    if handler is None:
        patch_extension = str(manifest.get("patch_file_ending", ""))
        if not patch_extension:
            container_type = _player_container_type(str(game))
            patch_extension = str(getattr(container_type, "patch_file_ending", "")) if container_type else ""
        component = _client_component_for_path(f"player{patch_extension}") if patch_extension else None
        if component is None:
            raise ValueError(f"The installed {game} world does not register a ROM patch handler or client component")
        _, requirements = _rom_requirements(str(game), work_directory, validated_only=False)
        return _component_result_extension(requirements)
    return _standard_result_extension(handler)


def _rom_requirements(
    game: str,
    work_directory: str,
    validated_only: bool = True,
) -> tuple[object, list[dict[str, object]]]:
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
        if getattr(setting_type, "is_exe", False) or not getattr(setting_type, "required", True):
            continue
        if validated_only and not getattr(setting_type, "md5s", None):
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
    if game == "The Wind Waker":
        from android_tww_patcher import load_plando, requirements
        load_plando(bytes(patch_bytes))
        return json.dumps(requirements())
    from worlds.Files import AutoPatchRegister
    standard_patch = AutoPatchRegister.patch_types.get(game) is not None
    if standard_patch:
        _, requirements = _rom_requirements(game, work_directory)
    else:
        with zipfile.ZipFile(BytesIO(bytes(patch_bytes)), "r") as archive:
            manifest = json.loads(archive.read("archipelago.json"))
        patch_extension = str(manifest.get("patch_file_ending", ""))
        if not patch_extension:
            container_type = _player_container_type(game)
            patch_extension = str(getattr(container_type, "patch_file_ending", "")) if container_type else ""
        if not patch_extension or _client_component_for_path(f"player{patch_extension}") is None:
            raise ValueError(f"The installed {game} world does not register a ROM patch handler or client component")
        _, requirements = _rom_requirements(game, work_directory, validated_only=False)
    return json.dumps({
        "game": game,
        # SAF descriptors avoid loading and duplicating an arbitrarily-sized
        # console ROM in the Android/Kotlin heap. Standard AutoPatch handlers
        # still receive ordinary filesystem paths, exactly as on desktop;
        # custom client components receive the same descriptors in their worker.
        "streaming": True,
        "component_host": not standard_patch,
        "result_extension": patch_result_extension(patch_bytes, work_directory),
        "inputs": [
            {key: value for key, value in requirement.items() if not key.startswith("_")}
            for requirement in requirements
        ],
    })


def validate_rom_input(patch_bytes, input_key: str, rom_bytes, work_directory: str) -> None:
    """Run the APWorld's declared validator for one Android-selected ROM."""
    game = patch_game(patch_bytes, work_directory)
    if game == "The Wind Waker":
        raise ValueError("Wind Waker ISOs must be validated through streamed document access")
    from worlds.Files import AutoPatchRegister
    _, requirements = _rom_requirements(
        game,
        work_directory,
        validated_only=AutoPatchRegister.patch_types.get(game) is not None,
    )
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


def _uses_inherited_file_validator(setting_type: type) -> bool:
    """Return whether a setting uses Archipelago's stream-only hash validator."""
    from settings import FilePath

    return (
        getattr(setting_type.validate, "__func__", None)
        is getattr(FilePath.validate, "__func__", None)
    )


def _validate_requirement_fd(requirement: dict[str, object], input_fd: int) -> None:
    """Validate a SAF descriptor without reopening it through Android's procfs."""
    setting_type = requirement["_setting_type"]
    if _uses_inherited_file_validator(setting_type):
        # FilePath.validate only opens the path and calls this stream helper.
        # Calling the helper on a duplicate preserves upstream hash semantics,
        # and also works for SAF providers whose descriptors cannot be reopened
        # through /proc/self/fd.
        with os.fdopen(os.dup(int(input_fd)), "rb", buffering=0) as source:
            setting_type._validate_stream_hashes(source)
        return
    setting_type.validate(f"/proc/self/fd/{int(input_fd)}")


def _fd_path_is_reopenable(path: str) -> bool:
    try:
        with open(path, "rb", buffering=0):
            return True
    except OSError:
        return False


def _component_input_path(
    input_fd: int,
    requirement: dict[str, object],
    staging_directory: Path,
) -> tuple[str, Path | None]:
    """Expose a SAF input as a path, copying only when procfs cannot reopen it."""
    descriptor_path = f"/proc/self/fd/{int(input_fd)}"
    if _fd_path_is_reopenable(descriptor_path):
        return descriptor_path, None

    suffix = Path(str(requirement.get("file_name", ""))).suffix or ".rom"
    key = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(requirement.get("key", "input"))).strip("-") or "input"
    staging_directory.mkdir(parents=True, exist_ok=True)
    staged = staging_directory / f"{key}{suffix}"
    with os.fdopen(os.dup(int(input_fd)), "rb", buffering=0) as source, staged.open("wb") as destination:
        try:
            source.seek(0)
        except OSError:
            pass
        shutil.copyfileobj(source, destination, length=1024 * 1024)
    return str(staged), staged


def _standard_patch_registration(patch_data: bytes, work_directory: str):
    """Resolve an upstream AutoPatch handler and its declared ROM inputs."""
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
    result_extension = _standard_result_extension(handler)

    settings_group, requirements = _rom_requirements(game, work_directory)
    return game, handler, world, result_extension, settings_group, requirements


def _clear_standard_patch_caches(handler, package_prefix: str) -> None:
    for attribute in ("source_data", "base_rom_bytes"):
        if attribute in handler.__dict__:
            delattr(handler, attribute)
    for module_name, module in tuple(sys.modules.items()):
        if module is None or not (module_name == package_prefix or module_name.startswith(package_prefix + ".")):
            continue
        for value in vars(module).values():
            if callable(value) and "base_rom_bytes" in getattr(value, "__dict__", {}):
                delattr(value, "base_rom_bytes")


def _run_standard_patch(
    patch_data: bytes,
    rom_input_paths: dict[str, str],
    output: Path,
    work_directory: str,
) -> str:
    """Run a registered desktop AutoPatch handler against ordinary file paths."""
    game, handler, world, _, settings_group, requirements = _standard_patch_registration(
        patch_data,
        work_directory,
    )
    expected_keys = {str(requirement["key"]) for requirement in requirements}
    missing_keys = expected_keys - rom_input_paths.keys()
    if missing_keys:
        raise ValueError(f"Missing ROM input(s) requested by {game}: {', '.join(sorted(missing_keys))}")

    package_prefix = ".".join(world.__module__.split(".")[:2])
    patch_extension = getattr(handler, "patch_file_ending", ".ap")
    staged_patch = output.parent / f"player{patch_extension}"
    staged_patch.write_bytes(patch_data)
    missing = object()
    previous_settings = {}
    for requirement in requirements:
        key = str(requirement["key"])
        setting_type = requirement["_setting_type"]
        input_path = str(rom_input_paths[key])
        try:
            setting_type.validate(input_path)
        except Exception as error:
            raise ValueError(
                f"The selected file is not the required {requirement['description']}: {error}"
            ) from error
        previous_settings[key] = settings_group.__dict__.get(key, missing)
        setattr(settings_group, key, setting_type(input_path))

    _clear_standard_patch_caches(handler, package_prefix)
    try:
        patch = handler(str(staged_patch))
        patch.patch(str(output))
        if not output.is_file():
            raise ValueError(f"The {game} APWorld did not produce a patched ROM")
        return game
    finally:
        _clear_standard_patch_caches(handler, package_prefix)
        for key, previous in previous_settings.items():
            if previous is missing:
                settings_group.__dict__.pop(key, None)
            else:
                setattr(settings_group, key, previous)


def _apply_procedure_patch(
    patch_data: bytes,
    rom_inputs: dict[str, bytes],
    work_directory: str,
) -> tuple[bytes, str]:
    """Stage byte-backed Android inputs and run the normal desktop patch path."""
    _, handler, _, result_extension, _, requirements = _standard_patch_registration(
        patch_data,
        work_directory,
    )
    expected_keys = {str(requirement["key"]) for requirement in requirements}
    missing_keys = expected_keys - rom_inputs.keys()
    if missing_keys:
        game = getattr(handler, "game", "the selected game")
        raise ValueError(f"Missing ROM input(s) requested by {game}: {', '.join(sorted(missing_keys))}")
    temporary_root = Path(work_directory).resolve()
    with tempfile.TemporaryDirectory(prefix="apworld-patch-", dir=temporary_root) as temporary:
        temporary_path = Path(temporary)
        staged_inputs: dict[str, str] = {}
        for index, requirement in enumerate(requirements):
            key = str(requirement["key"])
            suffix = Path(str(requirement.get("file_name", ""))).suffix or ".rom"
            staged_rom = temporary_path / f"input-{index}{suffix}"
            staged_rom.write_bytes(rom_inputs[key])
            staged_inputs[key] = str(staged_rom)
        staged_output = temporary_path / f"result{result_extension}"
        game = _run_standard_patch(patch_data, staged_inputs, staged_output, work_directory)
        return staged_output.read_bytes(), game


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
    raw_paths = json.loads(rom_input_paths_json)
    if not isinstance(raw_paths, dict):
        raise ValueError("ROM inputs must be a key-to-file mapping")
    if game == "The Wind Waker":
        from android_tww_patcher import INPUT_KEY, patch
        input_fd = raw_paths.get(INPUT_KEY)
        if input_fd is None:
            raise ValueError("Missing the clean Wind Waker ISO")
        return patch(patch_data_bytes, int(input_fd), int(output_path), work_directory)
    destination = Path(output_path).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    rom_inputs = {
        str(key): Path(str(path)).read_bytes()
        for key, path in raw_paths.items()
    }
    result, _ = _apply_procedure_patch(patch_data_bytes, rom_inputs, work_directory)
    destination.write_bytes(result)
    return str(destination)


def patch_rom_documents(
    patch_bytes,
    rom_input_fds_json: str,
    output_fd: int,
    work_directory: str,
) -> str:
    """Apply any registered AutoPatch using SAF descriptors and bounded copies."""
    _prepare_runtime(work_directory)
    patch_data = bytes(patch_bytes)
    game = patch_game(patch_data, work_directory)
    raw_inputs = json.loads(rom_input_fds_json)
    if not isinstance(raw_inputs, dict):
        raise ValueError("ROM inputs must be a key-to-descriptor mapping")
    if game == "The Wind Waker":
        from android_tww_patcher import INPUT_KEY, patch
        input_fd = raw_inputs.get(INPUT_KEY)
        if input_fd is None:
            raise ValueError("Missing the clean Wind Waker ISO")
        return patch(patch_data, int(input_fd), int(output_fd), work_directory)

    _, _, _, result_extension, _, requirements = _standard_patch_registration(
        patch_data,
        work_directory,
    )
    expected_keys = {str(requirement["key"]) for requirement in requirements}
    missing_keys = expected_keys - raw_inputs.keys()
    if missing_keys:
        raise ValueError(f"Missing ROM input(s) requested by {game}: {', '.join(sorted(missing_keys))}")

    temporary_root = Path(work_directory).resolve()
    with tempfile.TemporaryDirectory(prefix="apworld-document-patch-", dir=temporary_root) as temporary:
        temporary_path = Path(temporary)
        input_paths: dict[str, str] = {}
        for requirement in requirements:
            key = str(requirement["key"])
            input_path, _ = _component_input_path(
                int(raw_inputs[key]),
                requirement,
                temporary_path / "rom-inputs",
            )
            input_paths[key] = input_path
        staged_output = temporary_path / f"result{result_extension}"
        _run_standard_patch(patch_data, input_paths, staged_output, work_directory)
        with staged_output.open("rb") as source, os.fdopen(os.dup(int(output_fd)), "wb") as destination:
            shutil.copyfileobj(source, destination, length=1024 * 1024)
            destination.flush()
    return json.dumps({"game": game, "extension": result_extension})


def patch_file_extension(patch_bytes, work_directory: str = "") -> str:
    """Return the player-container suffix declared by its own manifest or APWorld."""
    if work_directory:
        _load_worlds(work_directory)
    with zipfile.ZipFile(BytesIO(bytes(patch_bytes)), "r") as archive:
        manifest = json.loads(archive.read("archipelago.json"))
    extension = str(manifest.get("patch_file_ending", ""))
    if extension:
        return extension
    game = str(manifest.get("game", ""))
    container_type = _player_container_type(game)
    extension = str(getattr(container_type, "patch_file_ending", "")) if container_type else ""
    if not extension:
        raise ValueError(f"The installed {game or 'player'} world does not declare a player-container suffix")
    return extension


def uses_component_patch_host(patch_bytes, work_directory: str) -> bool:
    """Whether this container is handled by an upstream client component rather than AutoPatch."""
    game = patch_game(patch_bytes, work_directory)
    from worlds.Files import AutoPatchRegister
    if AutoPatchRegister.patch_types.get(game) is not None or game == "The Wind Waker":
        return False
    extension = patch_file_extension(patch_bytes, work_directory)
    return _client_component_for_path(f"player{extension}") is not None


def _copy_component_output(
    component,
    patch_path: Path,
    result_extension: str,
    output_fd: int,
    timeout_seconds: float,
) -> Path:
    """Run a long-lived desktop client and capture the ROM it creates beside its container."""
    import asyncio

    failure: list[BaseException] = []
    task_completion_wall_time = [0.0]
    original_create_task = asyncio.create_task

    def tracked_create_task(coro, *args, **kwargs):
        task = original_create_task(coro, *args, **kwargs)

        def completed(finished) -> None:
            task_completion_wall_time[0] = time.time()
            try:
                error = finished.exception()
            except BaseException as callback_error:
                error = callback_error
            if error is not None:
                failure.append(error)

        task.add_done_callback(completed)
        return task

    def run_component() -> None:
        try:
            component.func(str(patch_path))
        except BaseException as error:
            logging.getLogger(__name__).exception("Registered APWorld client component failed")
            failure.append(error)

    before = {
        candidate.resolve()
        for candidate in patch_path.parent.iterdir()
        if candidate.is_file()
    }
    worker = threading.Thread(
        target=run_component,
        name="apworld-launcher-component",
        daemon=True,
    )
    asyncio.create_task = tracked_create_task
    try:
        worker.start()

        preferred = patch_path.with_suffix(result_extension)
        deadline = time.monotonic() + timeout_seconds
        observed: tuple[Path, int, int] | None = None
        stable_since = 0.0
        while time.monotonic() < deadline:
            candidates = []
            if preferred.is_file():
                candidates.append(preferred)
            candidates.extend(
                candidate for candidate in patch_path.parent.iterdir()
                if candidate.is_file()
                and candidate.resolve() not in before
                and candidate != patch_path
                and candidate.suffix.lower() == result_extension.lower()
                and candidate not in candidates
            )
            for candidate in candidates:
                stat = candidate.stat()
                signature = (candidate, stat.st_size, stat.st_mtime_ns)
                if stat.st_size <= 0:
                    continue
                if signature != observed:
                    observed = signature
                    stable_since = time.monotonic()
                task_finished_after_write = task_completion_wall_time[0] >= stat.st_mtime
                stable_fallback = time.monotonic() - stable_since >= COMPONENT_OUTPUT_STABLE_SECONDS
                if worker.is_alive() and not task_finished_after_write and not stable_fallback:
                    continue
                with candidate.open("rb") as source, os.fdopen(os.dup(int(output_fd)), "wb") as destination:
                    shutil.copyfileobj(source, destination, length=1024 * 1024)
                    destination.flush()
                return candidate
            if failure and not worker.is_alive():
                raise RuntimeError(f"The registered client component failed: {failure[-1]}") from failure[-1]
            time.sleep(COMPONENT_OUTPUT_POLL_SECONDS)
        detail = f": {failure[-1]}" if failure else ""
        raise TimeoutError(
            f"The registered client component did not produce a {result_extension} ROM "
            f"within {int(timeout_seconds)} seconds{detail}"
        )
    finally:
        asyncio.create_task = original_create_task


def patch_component_rom(
    patch_path: str,
    rom_input_fds_json: str,
    output_fd: int,
    work_directory: str,
    timeout_seconds: float = 1800,
) -> str:
    """Execute an APWorld's registered desktop client in a disposable Android process."""
    _prepare_runtime(work_directory)
    patch = Path(patch_path).resolve()
    if not patch.is_file():
        raise FileNotFoundError(f"Player container is missing: {patch}")
    patch_data = patch.read_bytes()
    game = patch_game(patch_data, work_directory)
    if not uses_component_patch_host(patch_data, work_directory):
        raise ValueError(f"{game} does not register a custom client component for {patch.suffix}")
    component = _client_component_for_path(str(patch))
    if component is None:
        raise ValueError(f"No registered client component accepts {patch.name}")

    raw_inputs = json.loads(rom_input_fds_json)
    if not isinstance(raw_inputs, dict):
        raise ValueError("ROM inputs must be a key-to-descriptor mapping")
    settings_group, requirements = _rom_requirements(game, work_directory, validated_only=False)
    expected_keys = {str(requirement["key"]) for requirement in requirements}
    missing_keys = expected_keys - raw_inputs.keys()
    if missing_keys:
        raise ValueError(f"Missing ROM input(s) requested by {game}: {', '.join(sorted(missing_keys))}")

    missing = object()
    previous_settings = {}
    staged_inputs: list[Path] = []
    try:
        for requirement in requirements:
            key = str(requirement["key"])
            setting_type = requirement["_setting_type"]
            input_path, staged = _component_input_path(
                int(raw_inputs[key]),
                requirement,
                patch.parent / "rom-inputs",
            )
            if staged is not None:
                staged_inputs.append(staged)
            setting_type.validate(input_path)
            previous_settings[key] = settings_group.__dict__.get(key, missing)
            setattr(settings_group, key, setting_type(input_path))
        result_extension = _component_result_extension(requirements)
        output = _copy_component_output(
            component,
            patch,
            result_extension,
            int(output_fd),
            float(timeout_seconds),
        )
        return json.dumps({"game": game, "output": str(output), "extension": result_extension})
    finally:
        for key, previous in previous_settings.items():
            if previous is missing:
                settings_group.__dict__.pop(key, None)
            else:
                setattr(settings_group, key, previous)
        for staged in staged_inputs:
            staged.unlink(missing_ok=True)


def validate_rom_input_fd(
    patch_bytes, input_key: str, input_fd: int, work_directory: str,
) -> None:
    """Validate a large SAF-backed input through its already-open descriptor."""
    game = patch_game(patch_bytes, work_directory)
    if game == "The Wind Waker":
        from android_tww_patcher import INPUT_KEY, load_plando, validate_iso_fd
        if input_key != INPUT_KEY:
            raise ValueError(f"{game} did not request ROM input {input_key}")
        load_plando(bytes(patch_bytes))
        validate_iso_fd(input_fd)
        return
    from worlds.Files import AutoPatchRegister
    standard_patch = AutoPatchRegister.patch_types.get(game) is not None
    if not standard_patch and not uses_component_patch_host(patch_bytes, work_directory):
        raise ValueError(f"{game} does not use streamed ROM inputs")
    _, requirements = _rom_requirements(game, work_directory, validated_only=standard_patch)
    requirement = next((item for item in requirements if item["key"] == input_key), None)
    if requirement is None:
        raise ValueError(f"{game} did not request ROM input {input_key}")
    try:
        _validate_requirement_fd(requirement, input_fd)
    except Exception as error:
        raise ValueError(
            f"The selected file is not the required {requirement['description']}: {error}"
        ) from error
