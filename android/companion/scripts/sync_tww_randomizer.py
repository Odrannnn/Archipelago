#!/usr/bin/env python3
"""Vendor the official AP Wind Waker Randomizer runtime used for .aptww files."""

from __future__ import annotations

import argparse
import shutil
import subprocess
from pathlib import Path

EXPECTED_COMMIT = "807b4051f80df897fb52ae1efc9e30c0f331b531"
EXPECTED_GCLIB_COMMIT = "3072ee11fd2293fe0beb137616e91311e384176f"
DIRECTORIES = ("asm", "assets", "data", "logic", "models", "options", "randomizers", "wwlib")
ROOT_FILES = (
    "customizer.py", "data_tables.py", "LICENSE.txt", "packedbits.py", "randomizer.py",
    "tweaks.py", "version.py", "version.txt", "wwrando_paths.py",
)


def commit(path: Path) -> str:
    return subprocess.check_output(
        ["git", "-c", f"safe.directory={path.as_posix()}", "-C", str(path), "rev-parse", "HEAD"], text=True
    ).strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    destination = args.destination.resolve()
    if commit(source) != EXPECTED_COMMIT:
        raise SystemExit(f"Expected wwrando {EXPECTED_COMMIT}, found {commit(source)}")
    if commit(source / "gclib") != EXPECTED_GCLIB_COMMIT:
        raise SystemExit("The checked-out gclib submodule is not the ap_2.5.1 revision")

    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    for name in DIRECTORIES:
        shutil.copytree(source / name, destination / name, ignore=shutil.ignore_patterns(
            "__pycache__", "*.pyc", "test", "tests",
        ))
    shutil.copytree(source / "gclib" / "gclib", destination / "gclib")
    for name in ROOT_FILES:
        shutil.copy2(source / name, destination / name)

    inventory = destination / "wwr_ui"
    inventory.mkdir()
    (inventory / "__init__.py").write_text("", encoding="utf-8")
    shutil.copy2(source / "wwr_ui" / "inventory.py", inventory / "inventory.py")
    (destination / "__init__.py").write_text("", encoding="utf-8")
    (destination / "UPSTREAM.md").write_text(
        "# Upstream provenance\n\n"
        "Vendored from `tanjo3/wwrando` tag `ap_2.5.1` at commit "
        f"`{EXPECTED_COMMIT}`, with gclib commit `{EXPECTED_GCLIB_COMMIT}`.\n\n"
        "This is the official headless patching engine for Archipelago Wind Waker "
        "3.x `.aptww` files. Upstream is MIT licensed; see `LICENSE.txt`.\n",
        encoding="utf-8",
    )

    # Source mode never consults appdirs, and Android does not ship it.
    paths = destination / "wwrando_paths.py"
    text = paths.read_text(encoding="utf-8").replace("\nimport appdirs\n", "\n")
    text = text.replace("    userdata_path = appdirs.user_data_dir", "    import appdirs\n    userdata_path = appdirs.user_data_dir")
    paths.write_text(text, encoding="utf-8")

    # SAF destinations are exposed as /proc/self/fd/N. They can be truncated but
    # cannot be unlinked if an export fails, so do not mask the actual exception.
    gcm = destination / "gclib" / "gcm.py"
    text = gcm.read_text(encoding="utf-8").replace(
        "      os.remove(output_file_path)\n      raise",
        "      try:\n        os.remove(output_file_path)\n      except OSError:\n        pass\n      raise",
    )
    gcm.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
