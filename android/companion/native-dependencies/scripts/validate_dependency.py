#!/usr/bin/env python3
"""Validate a dependency cache artifact without loading any package code."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import stat
import zipfile
from pathlib import Path


MAX_PACKAGE_BYTES = 64 * 1024 * 1024
MAX_UNPACKED_BYTES = 256 * 1024 * 1024
MAX_FILES = 20_000
EXPECTED_MACHINE = {"arm64-v8a": 183}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_path(name: str) -> None:
    path = Path(name)
    if "\\" in name or path.is_absolute() or not path.parts or ".." in path.parts:
        raise ValueError(f"Unsafe dependency path: {name}")


def elf_machine(header: bytes) -> int:
    if len(header) < 20 or header[:4] != b"\x7fELF":
        raise ValueError("Native library is not an ELF object")
    if header[4] != 2:
        raise ValueError("Native library is not 64-bit")
    if header[5] == 1:
        return int.from_bytes(header[18:20], "little")
    if header[5] == 2:
        return int.from_bytes(header[18:20], "big")
    raise ValueError("Native library has invalid ELF byte order")


def module_present(names: list[str], module: str) -> bool:
    path = f"site-packages/{module.replace('.', '/')}"
    return any(name == f"{path}.py" or name == f"{path}/__init__.py" or
               (name.startswith(f"{path}.") and name.endswith(".so")) for name in names)


def validate(entry_path: Path, artifact_directory: Path) -> dict:
    entry = json.loads(entry_path.read_text(encoding="utf-8"))
    required = {
        "schema", "package", "version", "module", "python_abi", "android_abi",
        "minimum_sdk", "source_url", "source_sha256", "file_name", "sha256", "byte_count",
    }
    if required - entry.keys() or entry["schema"] != 1:
        raise ValueError("Catalog entry is incomplete or unsupported")
    if not re.fullmatch(r"[A-Za-z0-9._+-]+\.zip", entry["file_name"]):
        raise ValueError("Invalid dependency artifact filename")
    artifact = artifact_directory / entry["file_name"]
    if not artifact.is_file() or artifact.stat().st_size > MAX_PACKAGE_BYTES:
        raise ValueError("Dependency artifact is missing or too large")
    if artifact.stat().st_size != entry["byte_count"] or sha256(artifact) != entry["sha256"]:
        raise ValueError("Dependency artifact does not match its catalog entry")

    with zipfile.ZipFile(artifact) as package:
        infos = package.infolist()
        if len(infos) > MAX_FILES:
            raise ValueError("Dependency artifact contains too many files")
        total = 0
        names = []
        native = []
        for info in infos:
            safe_path(info.filename)
            if stat.S_ISLNK(info.external_attr >> 16):
                raise ValueError(f"Dependency artifact contains a symlink: {info.filename}")
            total += info.file_size
            if total > MAX_UNPACKED_BYTES:
                raise ValueError("Dependency artifact exceeds unpacked size limits")
            if not info.is_dir():
                names.append(info.filename)
                if info.filename.endswith(".so"):
                    native.append(info.filename)
        if names.count("dependency.json") != 1:
            raise ValueError("Dependency artifact must contain one manifest")
        manifest = json.loads(package.read("dependency.json"))
        for key in (required - {"file_name", "sha256", "byte_count"}):
            if manifest.get(key) != entry.get(key):
                raise ValueError(f"Dependency manifest disagrees on {key}")
        if not native:
            raise ValueError("Dependency artifact contains no native libraries")
        if not module_present(names, entry["module"]):
            raise ValueError(f"Dependency artifact does not provide module {entry['module']}")
        expected_machine = EXPECTED_MACHINE.get(entry["android_abi"])
        if expected_machine is None:
            raise ValueError("Unsupported Android ABI")
        for name in native:
            with package.open(name) as library:
                machine = elf_machine(library.read(64))
            if machine != expected_machine:
                raise ValueError(f"{name} targets ELF machine {machine}, expected {expected_machine}")

    return {
        "schema": 1,
        "artifact": artifact.name,
        "sha256": entry["sha256"],
        "byte_count": entry["byte_count"],
        "files": len(names),
        "unpacked_bytes": total,
        "native_libraries": native,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--entry", required=True, type=Path)
    parser.add_argument("--artifact-directory", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    arguments = parser.parse_args()
    report = validate(arguments.entry, arguments.artifact_directory)
    arguments.report.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
