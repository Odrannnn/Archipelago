"""Android-facing entry points for offline GBA Archipelago generation."""

from __future__ import annotations

import hashlib
import importlib
import inspect
import json
import os
import shutil
import sys
import tempfile
import zipfile
from io import BytesIO
from pathlib import Path

os.environ["SKIP_REQUIREMENTS_UPDATE"] = "1"


def _prepare_runtime(work_directory: str) -> tuple[Path, Path]:
    root = Path(work_directory).resolve()
    players = root / "Players"
    output = root / "output"
    players.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)

    import Utils

    Utils.user_path.cached_path = str(root)
    Utils.home_path.cached_path = str(root)
    return players, output


def _load_worlds(work_directory: str):
    """Load bundled worlds and any safely extracted user APWorld packages."""
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
            world.world_version = tuplize_version(manifest.get("world_version", "0.0.0"))
            world.manifest = {
                key: value for key, value in manifest.items()
                if key not in ("version", "compatible_version")
            }
            worlds.network_data_package["games"][game] = world.get_data_package_data()
        except Exception as error:
            worlds.failed_world_loads[package.name] = f"{type(error).__name__}: {error}"
    return worlds, AutoWorldRegister


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


def world_catalog(work_directory: str) -> str:
    """Describe loaded world capabilities without promising a live adapter."""
    worlds, registry = _load_worlds(work_directory)
    from worlds.Files import AutoPatchRegister
    from worlds._bizhawk.client import AutoBizHawkClientRegister

    standard_gba_clients = {
        game
        for systems, handlers in AutoBizHawkClientRegister.game_handlers.items()
        if "GBA" in systems
        for game in handlers
    }

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
            "rom_patch": bool(patch_type and result_extension.lower() == ".gba"),
            "live_bridge": game in ("Metroid Fusion", "The Minish Cap") or game in standard_gba_clients,
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
    # ArchipelagoMine's legacy container predates AutoPatchRegister.
    patch_endings.add(".apmetfus")

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

    if not patches:
        raise RuntimeError("Generation completed, but no Archipelago player patch was produced")
    return json.dumps({
        "seed": str(numeric_seed),
        "players": player_names,
        "files": files,
        "patches": patches,
    })


def _safe_patch_output_name(value: object) -> str:
    """Return a plain .gba filename from an untrusted patch manifest value."""
    name = Path(str(value)).name
    if name in {"", ".", ".."} or not name.lower().endswith(".gba"):
        raise ValueError("The Metroid Fusion patch contains an invalid output filename")
    return name


def patch_game(patch_bytes, work_directory: str = "") -> str:
    """Return the game declared by a player patch."""
    if work_directory:
        _load_worlds(work_directory)
    with zipfile.ZipFile(BytesIO(bytes(patch_bytes)), "r") as archive:
        if "patch_file.json" in archive.namelist():
            return "Metroid Fusion"
        try:
            manifest = json.loads(archive.read("archipelago.json"))
        except KeyError as error:
            raise ValueError("The selected file is not a supported Archipelago GBA patch") from error
        game = manifest.get("game")
        if not isinstance(game, str) or not game.strip():
            raise ValueError("The Archipelago patch does not declare a game")
        return game.strip()


def _matching_base_rom(raw_rom: bytes, checksum: str) -> bytes:
    """Validate common AP checksums, accepting a legacy 512-byte copier header."""
    expected = checksum.lower()
    candidates = [raw_rom]
    if len(raw_rom) % 0x400 == 0x200:
        candidates.append(raw_rom[0x200:])
    algorithms = {32: hashlib.md5, 40: hashlib.sha1, 64: hashlib.sha256}
    algorithm = algorithms.get(len(expected))
    if algorithm is None:
        raise ValueError(f"Unsupported base ROM checksum format ({len(expected)} hexadecimal characters)")
    for candidate in candidates:
        if algorithm(candidate).hexdigest().lower() == expected:
            return candidate
    actual = algorithm(candidates[-1]).hexdigest().upper()
    raise ValueError(f"Wrong base ROM for this patch. Its {algorithm().name.upper()} is {actual}.")


def _apply_procedure_patch(patch_data: bytes, raw_rom: bytes, work_directory: str) -> tuple[bytes, str]:
    """Apply an APProcedurePatch using the user-supplied ROM instead of desktop settings."""
    _load_worlds(work_directory)
    from worlds.Files import AutoPatchExtensionRegister, AutoPatchRegister

    with zipfile.ZipFile(BytesIO(patch_data), "r") as archive:
        try:
            manifest = json.loads(archive.read("archipelago.json"))
        except KeyError as error:
            raise ValueError("The selected file has no Archipelago patch manifest") from error
    game = manifest.get("game")
    handler = AutoPatchRegister.patch_types.get(game)
    if handler is None:
        raise ValueError(f"The installed {game} world does not register a ROM patch handler")
    if getattr(handler, "result_file_ending", "").lower() != ".gba":
        raise ValueError(f"{game} produces {getattr(handler, 'result_file_ending', 'an unsupported format')}, not a GBA ROM")
    checksum = manifest.get("base_checksum")
    if not isinstance(checksum, str) or not checksum:
        raise ValueError(f"The {game} patch does not declare a base ROM checksum")
    rom = _matching_base_rom(raw_rom, checksum)

    patch = handler()
    patch.read(BytesIO(patch_data))
    extender = AutoPatchExtensionRegister.get_handler(game)
    procedure = getattr(patch, "procedure", manifest.get("procedure"))
    if not isinstance(procedure, list):
        raise ValueError(f"The {game} patch has no supported patch procedure")
    for step in procedure:
        if not isinstance(step, (list, tuple)) or len(step) != 2:
            raise ValueError(f"The {game} patch contains an invalid procedure step")
        name, arguments = step
        handlers = extender if isinstance(extender, list) else [extender]
        operation = next((getattr(item, name, None) for item in handlers if hasattr(item, name)), None)
        if operation is None:
            raise ValueError(f"The installed {game} world does not support patch operation {name}")
        rom = operation(patch, rom, *arguments)
    return bytes(rom), game


def _apply_tokens(rom: bytes, token_data: bytes) -> bytes:
    result = bytearray(rom)
    if len(token_data) < 4:
        raise ValueError("Invalid Minish Cap token data")
    count = int.from_bytes(token_data[:4], "little")
    position = 4
    for _ in range(count):
        if position + 9 > len(token_data):
            raise ValueError("Truncated Minish Cap token data")
        token_type = token_data[position]
        offset = int.from_bytes(token_data[position + 1:position + 5], "little")
        size = int.from_bytes(token_data[position + 5:position + 9], "little")
        position += 9
        if position + size > len(token_data):
            raise ValueError("Truncated Minish Cap token payload")
        data = token_data[position:position + size]
        position += size
        end = offset + size
        if token_type == 0:  # WRITE
            if end > len(result):
                result.extend(bytes(end - len(result)))
            result[offset:end] = data
        elif token_type == 1:  # COPY: source offset + length
            if size != 8:
                raise ValueError("Invalid Minish Cap COPY token")
            length = int.from_bytes(data[:4], "little")
            source = int.from_bytes(data[4:], "little")
            copied = bytes(result[source:source + length])
            if offset + length > len(result):
                result.extend(bytes(offset + length - len(result)))
            result[offset:offset + length] = copied
        elif token_type == 2:  # RLE: length + byte
            if size != 8:
                raise ValueError("Invalid Minish Cap RLE token")
            length = int.from_bytes(data[:4], "little")
            value = int.from_bytes(data[4:], "little") & 0xFF
            if offset + length > len(result):
                result.extend(bytes(offset + length - len(result)))
            result[offset:offset + length] = bytes([value]) * length
        elif token_type in (3, 4, 5):
            if size != 1 or offset >= len(result):
                raise ValueError("Invalid Minish Cap bitwise token")
            if token_type == 3:
                result[offset] &= data[0]
            elif token_type == 4:
                result[offset] |= data[0]
            else:
                result[offset] ^= data[0]
        else:
            raise ValueError(f"Unknown Minish Cap token type {token_type}")
    if position != len(token_data):
        raise ValueError("Unexpected trailing Minish Cap token data")
    return bytes(result)


def _patch_minish_cap(patch_data: bytes, rom: bytes) -> bytes:
    expected_md5 = "2af78edbe244b5de44471368ae2b6f0b"
    actual_md5 = hashlib.md5(rom).hexdigest()
    if actual_md5 != expected_md5:
        raise ValueError(
            "Wrong base ROM. Select an unmodified European The Legend of Zelda: "
            f"The Minish Cap ROM. Its MD5 is {actual_md5.upper()}."
        )
    with zipfile.ZipFile(BytesIO(patch_data), "r") as archive:
        try:
            manifest = json.loads(archive.read("archipelago.json"))
            base_patch = archive.read("base_patch.bsdiff4")
            tokens = archive.read("token_data.bin")
        except KeyError as error:
            raise ValueError("The selected file is not a complete Minish Cap Archipelago patch") from error
    if manifest.get("game") != "The Minish Cap" or manifest.get("base_checksum") != expected_md5:
        raise ValueError("The selected file is not a compatible Minish Cap Archipelago patch")
    expected_procedure = [["apply_bsdiff4", ["base_patch.bsdiff4"]], ["apply_tokens", ["token_data.bin"]]]
    if manifest.get("procedure") != expected_procedure:
        raise ValueError("The Minish Cap patch declares an unsupported patching procedure")
    import bsdiff4
    return _apply_tokens(bsdiff4.patch(rom, base_patch), tokens)


def patch_rom(patch_bytes, base_rom_bytes, output_path: str, work_directory: str) -> str:
    """Apply a supported GBA player patch to a legally supplied base ROM."""
    # Invite patching can be the first Python operation after the Android process
    # starts. Configure Archipelago's writable paths before importing the APWorld;
    # otherwise its default relative Players path resolves against Android's `/`.
    _prepare_runtime(work_directory)
    patch_data_bytes = bytes(patch_bytes)
    raw_rom = bytes(base_rom_bytes)
    raw_md5 = hashlib.md5(raw_rom).hexdigest()
    # Match Utils.read_snes_rom, which Archipelago also uses for GBA files:
    # remove a legacy 512-byte copier header before applying the BPS patch.
    rom = raw_rom[0x200:] if len(raw_rom) % 0x400 == 0x200 else raw_rom
    stripped_md5 = hashlib.md5(rom).hexdigest()
    game = patch_game(patch_data_bytes, work_directory)
    destination = Path(output_path).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if game == "The Minish Cap":
        result = _patch_minish_cap(patch_data_bytes, rom)
        destination.write_bytes(result)
        return str(destination)

    if game != "Metroid Fusion":
        result, _ = _apply_procedure_patch(patch_data_bytes, raw_rom, work_directory)
        destination.write_bytes(result)
        return str(destination)

    accepted_md5s = {
        "af5040fc0f579800151ee2a683e2e5b5",
        "5d07cc8a45eae858bea6dfc97f63e813",
        "27d02a4f03e172e029c9b82ac3db79f7",
    }
    if raw_md5 not in accepted_md5s and stripped_md5 not in accepted_md5s:
        raise ValueError(
            "Wrong base ROM. Select an unmodified North American Metroid Fusion ROM. "
            f"Its MD5 is {raw_md5.upper()}."
        )

    with zipfile.ZipFile(BytesIO(patch_data_bytes), "r") as archive:
        try:
            placement = json.loads(archive.read("patch_file.json"))
        except KeyError as error:
            raise ValueError("The selected file is not a Metroid Fusion Archipelago patch") from error

    from worlds.metroidfusion import MetroidFusionWorld
    from worlds.metroidfusion.data import memory
    from worlds.metroidfusion.mars_patcher import patcher
    import Utils

    patcher.validate_patch_data_mf(placement)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temp_directory:
        temp = Path(temp_directory)
        base_path = temp / "base.gba"
        intermediate_path = temp / _safe_patch_output_name(placement.get("OutputFile"))
        base_path.write_bytes(rom)
        patcher.patch(str(base_path), str(intermediate_path), placement, lambda _message, _progress: None)
        result = bytearray(intermediate_path.read_bytes())

    rom_name = bytearray(placement["RomName"], "utf-8")[:20]
    rom_name.extend([0] * (20 - len(rom_name)))
    result[memory.rom_name_location:memory.rom_name_location + 20] = rom_name

    generation_version = placement.get("GenerationVersion")
    if isinstance(generation_version, str):
        parsed_generation = Utils.tuplize_version(generation_version)
    else:
        parsed_generation = Utils.Version(0, 0, int(generation_version))
    result[memory.generation_version_location:memory.generation_version_location + 3] = bytes(parsed_generation)
    result[memory.patching_version_location:memory.patching_version_location + 3] = bytes(
        map(int, MetroidFusionWorld.version.split("."))
    )
    destination.write_bytes(result)
    return str(destination)
