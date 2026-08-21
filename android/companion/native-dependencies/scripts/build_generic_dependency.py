#!/usr/bin/env python3
"""Build a hash-pinned CPython 3.12 Android wheel with Chaquopy's build tool."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile
from email.parser import BytesParser
from pathlib import Path


MAX_SOURCE_BYTES = 64 * 1024 * 1024
MAX_SOURCE_FILES = 20_000
MAX_UNPACKED_BYTES = 256 * 1024 * 1024
FIXED_ZIP_TIME = (2020, 1, 1, 0, 0, 0)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_verified(url: str, expected_digest: str, destination: Path) -> None:
    if not url.startswith("https://files.pythonhosted.org/"):
        raise ValueError("Generic builds only accept PyPI file storage")
    request = urllib.request.Request(url, headers={"User-Agent": "Archipelago-Companion-Builder/1"})
    with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
        if not response.geturl().startswith("https://files.pythonhosted.org/"):
            raise ValueError("Source download redirected to an unexpected host")
        declared = response.headers.get("Content-Length")
        if declared and int(declared) > MAX_SOURCE_BYTES:
            raise ValueError("Source distribution is too large")
        total = 0
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > MAX_SOURCE_BYTES:
                raise ValueError("Source distribution is too large")
            output.write(chunk)
    actual = sha256(destination)
    if actual != expected_digest:
        raise ValueError(f"Downloaded source hash mismatch: {actual}")


def safe_member_path(name: str) -> Path:
    if "\\" in name:
        raise ValueError(f"Unsafe archive path: {name}")
    path = Path(name)
    if path.is_absolute() or not path.parts or ".." in path.parts:
        raise ValueError(f"Unsafe archive path: {name}")
    return path


def extract_source(archive: Path, destination: Path) -> Path:
    destination.mkdir(parents=True)
    count = 0
    total = 0
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as source:
            for info in source.infolist():
                safe_member_path(info.filename)
                if stat.S_ISLNK(info.external_attr >> 16):
                    raise ValueError(f"Source archive contains a symlink: {info.filename}")
                count += 1
                total += info.file_size
                if count > MAX_SOURCE_FILES or total > MAX_UNPACKED_BYTES:
                    raise ValueError("Source archive exceeds extraction limits")
            source.extractall(destination)
    else:
        with tarfile.open(archive, "r:*") as source:
            members = source.getmembers()
            for member in members:
                safe_member_path(member.name)
                if not (member.isfile() or member.isdir()):
                    raise ValueError(f"Source archive contains a special entry: {member.name}")
                count += 1
                total += member.size
                if count > MAX_SOURCE_FILES or total > MAX_UNPACKED_BYTES:
                    raise ValueError("Source archive exceeds extraction limits")
            source.extractall(destination, members=members, filter="data")
    roots = list(destination.iterdir())
    if len(roots) == 1 and roots[0].is_dir():
        return roots[0]
    return destination


def wheel_metadata(archive: zipfile.ZipFile) -> tuple[str, str]:
    names = [name for name in archive.namelist() if name.endswith(".dist-info/METADATA")]
    if len(names) != 1:
        raise ValueError("Built wheel must contain exactly one METADATA file")
    metadata = BytesParser().parsebytes(archive.read(names[0]))
    return str(metadata["Name"] or ""), str(metadata["Version"] or "")


def module_present(names: list[str], module: str) -> bool:
    path = module.replace(".", "/")
    return any(name == f"{path}.py" or name == f"{path}/__init__.py" or
               (name.startswith(f"{path}.") and name.endswith(".so")) for name in names)


def package_wheel(wheel: Path, request: dict, output: Path) -> tuple[Path, Path]:
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(wheel) as source:
        infos = source.infolist()
        if len(infos) > MAX_SOURCE_FILES:
            raise ValueError("Built wheel contains too many files")
        total = 0
        for info in infos:
            safe_member_path(info.filename)
            if stat.S_ISLNK(info.external_attr >> 16):
                raise ValueError(f"Built wheel contains a symlink: {info.filename}")
            total += info.file_size
            if total > MAX_UNPACKED_BYTES:
                raise ValueError("Built wheel exceeds extraction limits")
        name, version = wheel_metadata(source)
        normalized_name = name.lower().replace("_", "-").replace(".", "-")
        if normalized_name != request["package"] or version != request["version"]:
            raise ValueError("Built wheel identity does not match the request")
        names = [info.filename for info in infos if not info.is_dir()]
        if not any(name.endswith(".so") for name in names):
            raise ValueError("The generic builder produced no native extension")
        if not module_present(names, request["module"]):
            raise ValueError(f"Built wheel does not provide module {request['module']}")

        normalized = request["package"].replace("-", "_")
        artifact_name = (
            f"{normalized}-{request['version']}-{request['python_abi']}-"
            f"android_{request['minimum_sdk']}_{request['android_abi'].replace('-', '_')}.zip"
        )
        artifact = output / artifact_name
        manifest = {
            "schema": 1,
            "package": request["package"],
            "version": request["version"],
            "module": request["module"],
            "python_abi": request["python_abi"],
            "android_abi": request["android_abi"],
            "minimum_sdk": request["minimum_sdk"],
            "source_url": request["source_url"],
            "source_sha256": request["source_sha256"],
        }
        with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as package:
            manifest_info = zipfile.ZipInfo("dependency.json", FIXED_ZIP_TIME)
            manifest_info.compress_type = zipfile.ZIP_DEFLATED
            package.writestr(manifest_info, json.dumps(manifest, indent=2, sort_keys=True) + "\n")
            for info in sorted(infos, key=lambda value: value.filename):
                if info.is_dir() or "__pycache__" in Path(info.filename).parts:
                    continue
                destination = zipfile.ZipInfo(f"site-packages/{info.filename}", FIXED_ZIP_TIME)
                destination.compress_type = zipfile.ZIP_DEFLATED
                destination.external_attr = 0o644 << 16
                package.writestr(destination, source.read(info))

    entry = {
        **manifest,
        "file_name": artifact.name,
        "sha256": sha256(artifact),
        "byte_count": artifact.stat().st_size,
        "worlds": [],
    }
    entry_path = output / "catalog-entry.json"
    entry_path.write_text(json.dumps(entry, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return artifact, entry_path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True, type=Path)
    parser.add_argument("--chaquopy-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    request = json.loads(arguments.request.read_text(encoding="utf-8"))
    if request.get("schema") != 1 or request.get("strategy") != "generic-chaquopy":
        raise ValueError("Unsupported generic build request")

    chaquopy_root = arguments.chaquopy_root.resolve()
    tool_directory = chaquopy_root / "server" / "pypi"
    build_tool = tool_directory / "build-wheel.py"
    if not build_tool.is_file():
        raise FileNotFoundError("Pinned Chaquopy build tool is missing")

    with tempfile.TemporaryDirectory(prefix="generic-android-python-") as temporary_name:
        temporary = Path(temporary_name)
        archive = temporary / request["source_filename"]
        download_verified(request["source_url"], request["source_sha256"], archive)
        source = extract_source(archive, temporary / "unpacked")
        recipe = temporary / "recipe"
        recipe.mkdir()
        relative_source = os.path.relpath(source, recipe)
        meta = {
            "package": {"name": request["package"], "version": request["version"]},
            "source": {"path": relative_source},
            "build": {"number": 0},
            "requirements": {"host": ["python"]},
        }
        (recipe / "meta.yaml").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
        command = [
            str(build_tool), "--python", request["python_version"],
            "--abi", request["android_abi"], "--api-level", str(request["minimum_sdk"]),
            str(recipe),
        ]
        subprocess.run(command, cwd=tool_directory, check=True)
        wheel_directory = tool_directory / "dist" / request["package"]
        abi_tag = request["android_abi"].replace("-", "_")
        wheels = sorted(wheel_directory.glob(f"*-android_*_{abi_tag}.whl"))
        if len(wheels) != 1:
            raise FileNotFoundError(f"Expected one Android wheel, found {len(wheels)}")
        artifact, entry = package_wheel(wheels[0], request, arguments.output)
        print(f"wheel={wheels[0]}")
        print(f"artifact={artifact}")
        print(f"catalog_entry={entry}")


if __name__ == "__main__":
    main()
