"""Android-facing entry points for offline Metroid Fusion generation."""

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


DEFAULT_YAML = """# Metroid Fusion APWorld 1.22.4
# Edit any value, or add another player after a line containing only ---.
name: Player
description: Generated offline by Archipelago Android Companion
game: Metroid Fusion

Metroid Fusion:
  # Common Archipelago options
  progression_balancing: 50
  accessibility: items
  local_items: []
  non_local_items: []
  start_inventory: {}
  start_hints: []
  start_location_hints: []
  exclude_locations: []
  priority_locations: []
  item_links: []

  # Main options
  GameMode: vanilla
  InfantMetroidsInPool: 5
  InfantMetroidsRequired: 5
  InfantMetroidLocations: anywhere

  # Logic options
  EarlyProgression: normal
  SectorTubeShuffle: false
  ElevatorShuffle: none
  PointOfNoReturnsInLogic: true

  # Trick options
  ShinesparkTrickDifficulty: none
  WallJumpTrickDifficulty: none
  CombatDifficulty: beginner

  # Custom game-mode options (used only when GameMode is custom)
  StartingLocation: docking_bay
  StartingMajorUpgrades: 0
  StartingEnergyTanks: 0
  FillerItems: [Missile Tank, Power Bomb Tank]
  OpenSectorElevators: false
  SectorNavigationRoomHintLocks: false

  # Minor options
  PaletteRandomization: false
  EnableHints: true
  NerfGeronWeaknesses: false
  RevealHiddenBlocks: true
  FastDoorTransitions: true
  MissileDataAmmo: 10
  MissileTankAmmo: 5
  PowerBombDataAmmo: 10
  PowerBombTankAmmo: 2

  start_inventory_from_pool: {}
  death_link: false
"""


def default_yaml() -> str:
    return DEFAULT_YAML


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
                    if member.lower().endswith(".apmetfus"):
                        patch_path = output / Path(member).name
                        with archive.open(member) as source, patch_path.open("wb") as target:
                            shutil.copyfileobj(source, target)
                        patch_info = {"name": patch_path.name, "path": str(patch_path), "kind": "patch"}
                        patches.append(patch_info)
                        files.append(patch_info)

    if not patches:
        raise RuntimeError("Generation completed, but no .apmetfus patch was produced")
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


def patch_rom(patch_bytes, base_rom_bytes, output_path: str, work_directory: str) -> str:
    """Apply an .apmetfus patch to a legally supplied USA Metroid Fusion ROM."""
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
    destination = Path(output_path).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
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
