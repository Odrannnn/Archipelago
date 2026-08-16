"""Refresh the pinned core and Metroid Fusion generator Android sources.

Run this after updating the adjacent ArchipelagoMine checkout. The Android app
intentionally embeds only the core modules required for generation and the
Metroid Fusion world from that checkout. The independently pinned Minish Cap
APWorld and pure-Python BSDIFF40 reader are preserved across the refresh.
"""

from __future__ import annotations

import shutil
from pathlib import Path


COMPANION = Path(__file__).resolve().parents[1]
ARCHIPELAGO = COMPANION.parents[2] / "ArchipelagoMine"
DESTINATION = COMPANION / "app" / "src" / "main" / "python"

CORE_MODULES = (
    "BaseClasses.py",
    "Fill.py",
    "Generate.py",
    "Main.py",
    "ModuleUpdate.py",
    "NetUtils.py",
    "Options.py",
    "Utils.py",
    "entrance_rando.py",
    "settings.py",
)


def copy_tree(source: Path, destination: Path) -> None:
    shutil.copytree(
        source,
        destination,
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "Logic_Test.py", "tests"),
    )


def main() -> None:
    if not (ARCHIPELAGO / "worlds" / "metroidfusion" / "MFOptions.py").is_file():
        raise SystemExit(f"Metroid Fusion APWorld source not found at {ARCHIPELAGO}")

    DESTINATION.mkdir(parents=True, exist_ok=True)
    minish_cap_path = DESTINATION / "worlds" / "tmc"
    minish_cap_files = {
        path.relative_to(minish_cap_path): path.read_bytes()
        for path in minish_cap_path.rglob("*")
        if path.is_file()
    }
    mobile_bsdiff = (DESTINATION / "bsdiff4.py").read_bytes()
    managed_entries = (*CORE_MODULES, "rule_builder", "worlds", "bsdiff4.py", "jellyfish.py",
                       "ARCHIPELAGO_LICENSE.txt", "METROID_FUSION_APWORLD_LICENSE.txt")
    for name in managed_entries:
        path = DESTINATION / name
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()
    for cache_directory in DESTINATION.rglob("__pycache__"):
        shutil.rmtree(cache_directory)

    for module in CORE_MODULES:
        shutil.copy2(ARCHIPELAGO / module, DESTINATION / module)
    copy_tree(ARCHIPELAGO / "rule_builder", DESTINATION / "rule_builder")

    worlds = DESTINATION / "worlds"
    worlds.mkdir()
    for module in ("__init__.py", "AutoWorld.py", "Files.py"):
        shutil.copy2(ARCHIPELAGO / "worlds" / module, worlds / module)
    copy_tree(ARCHIPELAGO / "worlds" / "generic", worlds / "generic")
    copy_tree(ARCHIPELAGO / "worlds" / "metroidfusion", worlds / "metroidfusion")
    for relative_path, data in minish_cap_files.items():
        destination = worlds / "tmc" / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)

    # The desktop BizHawk client is unrelated to seed generation and pulls in
    # networking/emulator modules which aren't part of the Android runtime.
    init_file = worlds / "metroidfusion" / "__init__.py"
    text = init_file.read_text(encoding="utf-8")
    text = text.replace("from .Client import MetroidFusionClient\n", "")
    init_file.write_text(text, encoding="utf-8", newline="\n")

    (DESTINATION / "bsdiff4.py").write_bytes(mobile_bsdiff)

    # Utils only needs jellyfish to improve error suggestions. This compact,
    # pure-Python implementation keeps that path available on Android.
    (DESTINATION / "jellyfish.py").write_text(
        '"""Small compatibility subset used by Archipelago option errors."""\n\n'
        "def damerau_levenshtein_distance(left, right):\n"
        "    left, right = str(left), str(right)\n"
        "    table = {}\n"
        "    for i in range(-1, len(left) + 1): table[(i, -1)] = i + 1\n"
        "    for j in range(-1, len(right) + 1): table[(-1, j)] = j + 1\n"
        "    for i, a in enumerate(left):\n"
        "        for j, b in enumerate(right):\n"
        "            cost = 0 if a == b else 1\n"
        "            table[(i, j)] = min(table[(i - 1, j)] + 1, table[(i, j - 1)] + 1, table[(i - 1, j - 1)] + cost)\n"
        "            if i and j and a == right[j - 1] and left[i - 1] == b:\n"
        "                table[(i, j)] = min(table[(i, j)], table[(i - 2, j - 2)] + cost)\n"
        "    return table[(len(left) - 1, len(right) - 1)]\n",
        encoding="utf-8",
        newline="\n",
    )

    shutil.copy2(ARCHIPELAGO / "LICENSE", DESTINATION / "ARCHIPELAGO_LICENSE.txt")
    apworld_license = ARCHIPELAGO / "worlds" / "metroidfusion" / "LICENSE"
    if apworld_license.exists():
        shutil.copy2(apworld_license, DESTINATION / "METROID_FUSION_APWORLD_LICENSE.txt")

    print(f"Synced offline generator from {ARCHIPELAGO} to {DESTINATION}")


if __name__ == "__main__":
    main()
