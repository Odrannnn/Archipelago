"""Android-facing entry points for offline GBA Archipelago generation."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
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

    files = []
    patches = []
    for path in sorted(output.iterdir()):
        if not path.is_file():
            continue
        files.append({"name": path.name, "path": str(path), "kind": "seed"})
        if path.suffix.lower() == ".zip":
            with zipfile.ZipFile(path, "r") as archive:
                for member in archive.namelist():
                    if member.lower().endswith((".apmetfus", ".aptmc")):
                        patch_path = output / Path(member).name
                        with archive.open(member) as source, patch_path.open("wb") as target:
                            shutil.copyfileobj(source, target)
                        patch_info = {"name": patch_path.name, "path": str(patch_path), "kind": "patch"}
                        patches.append(patch_info)
                        files.append(patch_info)

    if not patches:
        raise RuntimeError("Generation completed, but no supported GBA player patch was produced")
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


def patch_game(patch_bytes) -> str:
    """Return the game declared by a supported player patch."""
    with zipfile.ZipFile(BytesIO(bytes(patch_bytes)), "r") as archive:
        if "patch_file.json" in archive.namelist():
            return "Metroid Fusion"
        try:
            manifest = json.loads(archive.read("archipelago.json"))
        except KeyError as error:
            raise ValueError("The selected file is not a supported Archipelago GBA patch") from error
        game = manifest.get("game")
        if game != "The Minish Cap":
            raise ValueError(f"Unsupported Archipelago patch game: {game}")
        return game


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
    game = patch_game(patch_data_bytes)
    destination = Path(output_path).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if game == "The Minish Cap":
        result = _patch_minish_cap(patch_data_bytes, rom)
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
