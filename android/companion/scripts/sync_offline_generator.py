"""Refresh the pinned Archipelago core and supported bundled mGBA worlds.

Run this after updating the repository checkout. The Android app intentionally
embeds only the core modules and official worlds supported by its mGBA
bridge. Community APWorlds remain user-imported.
"""

from __future__ import annotations

import shutil
from pathlib import Path


COMPANION = Path(__file__).resolve().parents[1]
ARCHIPELAGO = COMPANION.parents[1]
DESTINATION = COMPANION / "app" / "src" / "main" / "python"
BUNDLED_WORLDS = ("cvcotm", "ladx", "pokemon_emerald", "mlss", "yugioh06")

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
        ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "Logic_Test.py", "test", "tests"),
    )


def main() -> None:
    if not all((ARCHIPELAGO / "worlds" / world / "__init__.py").is_file() for world in BUNDLED_WORLDS):
        raise SystemExit(f"Bundled world source not found at {ARCHIPELAGO}")

    DESTINATION.mkdir(parents=True, exist_ok=True)
    mobile_bsdiff = (DESTINATION / "bsdiff4.py").read_bytes()
    android_bizhawk = {
        path.name: path.read_bytes()
        for path in (DESTINATION / "worlds" / "_bizhawk").glob("*.py")
    }
    if not android_bizhawk:
        raise RuntimeError("The Android BizHawk compatibility package is missing")
    managed_entries = (*CORE_MODULES, "rule_builder", "worlds", "bsdiff4.py", "jellyfish.py", "orjson.py",
                       "ARCHIPELAGO_LICENSE.txt")
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
    bizhawk = worlds / "_bizhawk"
    bizhawk.mkdir()
    for name, contents in android_bizhawk.items():
        (bizhawk / name).write_bytes(contents)
    for world in BUNDLED_WORLDS:
        copy_tree(ARCHIPELAGO / "worlds" / world, worlds / world)

    # LADX's package registers a desktop launcher entry while it is imported.
    # Android supplies its own launcher and live mGBA bridge, so keep the world
    # and patcher without pulling in the desktop Launcher dependency tree.
    ladx_init = worlds / "ladx" / "__init__.py"
    text = ladx_init.read_text(encoding="utf-8")
    launcher_import = (
        "from worlds.LauncherComponents import Component, components, SuffixIdentifier, "
        "Type, launch, icon_paths\n"
    )
    launcher_block = (
        "\ndef launch_client(*args):\n"
        "    from .LinksAwakeningClient import launch as ladx_launch\n"
        "    launch(ladx_launch, name=f\"{LINKS_AWAKENING} Client\", args=args)\n"
        "\n"
        "components.append(Component(f\"{LINKS_AWAKENING} Client\",\n"
        "                            func=launch_client,\n"
        "                            component_type=Type.CLIENT,\n"
        "                            icon=LINKS_AWAKENING,\n"
        "                            file_identifier=SuffixIdentifier('.apladx')))\n"
        "\n"
        "icon_paths[LINKS_AWAKENING] = \"ap:worlds.ladx/assets/MarinV-3_small.png\"\n"
    )
    if launcher_import not in text or launcher_block not in text:
        raise RuntimeError("Could not remove the desktop LADX launcher registration")
    ladx_init.write_text(
        text.replace(launcher_import, "", 1).replace(launcher_block, "\n", 1),
        encoding="utf-8",
        newline="\n",
    )

    # The procedure patch API already supplies the validated ROM bytes. Avoid
    # asking desktop settings for a second ROM path just to populate argparse.
    ladx_rom = worlds / "ladx" / "Rom.py"
    text = ladx_rom.read_text(encoding="utf-8")
    old = "        rom_name = get_base_rom_path()\n        out_name = f\"{patch_data['out_base']}{caller.result_file_ending}\"\n"
    new = "        rom_name = \"base.gbc\"\n        out_name = f\"{patch_data['out_base']}{caller.result_file_ending}\"\n"
    if old not in text:
        raise RuntimeError("Could not adapt the LADX procedure patch for Android")
    ladx_rom.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")

    # Pokémon Emerald uses pkg_resources only to enumerate physical JSON files.
    # Chaquopy extracts worlds, so the standard library is sufficient and avoids
    # shipping setuptools in the APK.
    emerald_data = worlds / "pokemon_emerald" / "data.py"
    text = emerald_data.read_text(encoding="utf-8")
    text = text.replace("from enum import IntEnum, Enum\n", "from enum import IntEnum, Enum\nimport os\n", 1)
    text = text.replace("import pkg_resources\n", "", 1)
    text = text.replace(
        '    for file in pkg_resources.resource_listdir(__name__, "data/regions"):\n'
        '        if not pkg_resources.resource_isdir(__name__, "data/regions/" + file):\n',
        '    regions_directory = os.path.join(os.path.dirname(__file__), "data", "regions")\n'
        '    for file in os.listdir(regions_directory):\n'
        '        if os.path.isfile(os.path.join(regions_directory, file)):\n',
        1,
    )
    emerald_data.write_text(text, encoding="utf-8", newline="\n")

    # Extracted user APWorlds live outside the Python package. Add their app-private
    # directory to the package search path so worlds.<package> remains importable.
    init_file = worlds / "__init__.py"
    text = init_file.read_text(encoding="utf-8")
    marker = "except OSError:  # can't access/write?\n    user_folder = None\n"
    addition = (
        marker + "\n"
        "if user_folder and user_folder not in __path__:\n"
        "    __path__.append(user_folder)\n"
    )
    if marker not in text:
        raise RuntimeError("Could not patch the Android user-world package path")
    init_file.write_text(text.replace(marker, addition, 1), encoding="utf-8", newline="\n")

    (DESTINATION / "bsdiff4.py").write_bytes(mobile_bsdiff)

    # Pokémon Emerald needs only loads/dumps from orjson. Keep the Android
    # runtime pure Python instead of introducing another native wheel.
    (DESTINATION / "orjson.py").write_text(
        '\"\"\"Small Android-compatible subset of orjson used by the bundled Emerald world.\"\"\"\n\n'
        "from __future__ import annotations\n\n"
        "import json\n\n\n"
        "def loads(value):\n"
        "    if isinstance(value, (bytes, bytearray, memoryview)):\n"
        "        value = bytes(value).decode(\"utf-8\")\n"
        "    return json.loads(value)\n\n\n"
        "def dumps(value) -> bytes:\n"
        "    return json.dumps(value, ensure_ascii=False, separators=(\",\", \":\")).encode(\"utf-8\")\n",
        encoding="utf-8",
        newline="\n",
    )

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
    print(f"Synced offline generator from {ARCHIPELAGO} to {DESTINATION}")


if __name__ == "__main__":
    main()
