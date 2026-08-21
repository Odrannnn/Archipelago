#!/usr/bin/env python3
"""Build one reviewed Android Python dependency recipe."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path


PACKAGE = re.compile(r"[a-z0-9][a-z0-9._-]*")
MODULE = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*")
DIGEST = re.compile(r"[0-9a-f]{64}")
SUPPORTED_ABIS = {"arm64-v8a": "aarch64-linux-android"}
FIXED_ZIP_TIME = (2020, 1, 1, 0, 0, 0)


def read_recipe(path: Path) -> dict:
    recipe = json.loads(path.read_text(encoding="utf-8"))
    required = {
        "schema", "id", "build_kind", "package", "version", "module", "python_package",
        "python_abi", "python_version", "android_abi", "rust_target", "minimum_sdk",
        "rust_toolchain", "source_url", "source_sha256", "python_target_url",
        "python_target_sha256", "cargo_directory", "cargo_library",
        "extension_destination", "worlds",
    }
    missing = sorted(required - recipe.keys())
    if missing:
        raise ValueError(f"Recipe is missing: {', '.join(missing)}")
    if recipe["schema"] != 1 or recipe["build_kind"] != "cargo-pyo3":
        raise ValueError("Unsupported recipe schema or build kind")
    if not PACKAGE.fullmatch(recipe["package"]) or not MODULE.fullmatch(recipe["module"]):
        raise ValueError("Invalid package or module name")
    if recipe["android_abi"] not in SUPPORTED_ABIS:
        raise ValueError("Unsupported Android ABI")
    if recipe["rust_target"] != SUPPORTED_ABIS[recipe["android_abi"]]:
        raise ValueError("Rust target does not match Android ABI")
    if recipe["python_abi"] not in {"abi3", "cp312"} or recipe["python_version"] != "3.12":
        raise ValueError("Unsupported embedded Python ABI")
    if not 26 <= int(recipe["minimum_sdk"]) <= 35:
        raise ValueError("Unsupported minimum Android SDK")
    if not recipe["source_url"].startswith("https://files.pythonhosted.org/"):
        raise ValueError("Recipes currently accept only immutable PyPI source URLs")
    if not DIGEST.fullmatch(recipe["source_sha256"]):
        raise ValueError("Invalid source digest")
    if not recipe["python_target_url"].startswith(
            "https://repo1.maven.org/maven2/com/chaquo/python/target/"):
        raise ValueError("Untrusted Android Python target URL")
    if not DIGEST.fullmatch(recipe["python_target_sha256"]):
        raise ValueError("Invalid Android Python target digest")
    if not recipe["worlds"] or any(not world.get("game") for world in recipe["worlds"]):
        raise ValueError("Recipe must identify at least one APWorld")
    for field in ("cargo_directory", "python_package", "extension_destination"):
        value = Path(recipe[field])
        if value.is_absolute() or ".." in value.parts:
            raise ValueError(f"Unsafe recipe path: {field}")
    if not re.fullmatch(r"lib[A-Za-z0-9_]+\.so", recipe["cargo_library"]):
        raise ValueError("Invalid Cargo library name")
    return recipe


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_extract(archive: Path, destination: Path) -> Path:
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if not members:
            raise ValueError("Source archive is empty")
        for member in members:
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts or member.issym() or member.islnk():
                raise ValueError(f"Unsafe source archive path: {member.name}")
        source.extractall(destination, members=members, filter="data")
    roots = [entry for entry in destination.iterdir() if entry.is_dir()]
    if len(roots) != 1:
        raise ValueError("Source archive must contain one root directory")
    return roots[0]


def prepare_pyo3_cross_lib(recipe: dict, python_target: Path) -> Path:
    """Add the minimal target sysconfig which old PyO3 needs for Android."""
    library_directory = python_target / "jniLibs" / recipe["android_abi"]
    target_library = library_directory / f"libpython{recipe['python_version']}.so"
    if not target_library.is_file():
        raise FileNotFoundError(
            f"Android Python target does not contain {target_library.name}"
        )

    # PyO3 0.13 predates two-digit CPython minor versions and reads VERSION[2].
    # Configure its stable ABI at the newest version it understands, while
    # LDVERSION deliberately selects Chaquopy's real Android libpython.
    sysconfig = {
        "VERSION": "3.9",
        "WITH_THREAD": 1,
        "Py_DEBUG": 0,
        "Py_ENABLE_SHARED": 1,
        "LDVERSION": recipe["python_version"],
        "SIZEOF_VOID_P": 8,
    }
    (library_directory / "_sysconfigdata_android.py").write_text(
        f"build_time_vars = {sysconfig!r}\n",
        encoding="utf-8",
    )
    return library_directory


def run_build(recipe: dict, source: Path, cross_lib_directory: Path) -> Path:
    cargo_root = source / recipe["cargo_directory"]
    environment = os.environ.copy()
    environment.update({
        "PYO3_CROSS": "1",
        "PYO3_CROSS_PYTHON_VERSION": recipe["python_version"],
        "PYO3_CROSS_LIB_DIR": str(cross_lib_directory),
    })
    command = [
        "cargo", f"+{recipe['rust_toolchain']}", "ndk",
        "--target", recipe["android_abi"],
        "--platform", str(recipe["minimum_sdk"]),
        "build", "--release", "--locked", "--lib",
    ]
    subprocess.run(command, cwd=cargo_root, env=environment, check=True)
    library = cargo_root / "target" / recipe["rust_target"] / "release" / recipe["cargo_library"]
    if not library.is_file():
        raise FileNotFoundError(f"Cargo did not produce {library}")
    return library


def zip_tree(source: Path, destination: Path, manifest: dict) -> None:
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        manifest_info = zipfile.ZipInfo("dependency.json", FIXED_ZIP_TIME)
        manifest_info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(manifest_info, json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        for path in sorted(source.rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts:
                continue
            relative = path.relative_to(source).as_posix()
            info = zipfile.ZipInfo(f"site-packages/{relative}", FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())


def download_verified(url: str, expected_digest: str, destination: Path) -> None:
    with urllib.request.urlopen(url, timeout=60) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)
    actual = sha256(destination)
    if actual != expected_digest:
        raise ValueError(f"Downloaded source hash mismatch: {actual}")


def safe_extract_zip(archive: Path, destination: Path) -> None:
    with zipfile.ZipFile(archive) as source:
        for info in source.infolist():
            path = Path(info.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in info.filename:
                raise ValueError(f"Unsafe Android Python target path: {info.filename}")
        source.extractall(destination)


def package_build(recipe: dict, source: Path, library: Path, output: Path) -> tuple[Path, Path]:
    site_packages = output / "site-packages"
    package_source = source / recipe["python_package"]
    package_destination = site_packages / recipe["python_package"]
    if not (package_source / "__init__.py").is_file():
        raise FileNotFoundError(f"Python package not found: {package_source}")
    shutil.copytree(package_source, package_destination, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    extension = site_packages / recipe["extension_destination"]
    extension.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, extension)

    normalized = recipe["package"].replace("-", "_")
    dist_info = site_packages / f"{normalized}-{recipe['version']}.dist-info"
    dist_info.mkdir()
    (dist_info / "METADATA").write_text(
        "Metadata-Version: 2.1\n"
        f"Name: {recipe['package']}\n"
        f"Version: {recipe['version']}\n"
        "Summary: Curated Android build from unmodified upstream source\n",
        encoding="utf-8",
    )
    (dist_info / "WHEEL").write_text(
        "Wheel-Version: 1.0\nGenerator: Archipelago Companion Android dependency builder\n"
        "Root-Is-Purelib: false\nTag: cp310-abi3-android_26_arm64_v8a\n",
        encoding="utf-8",
    )

    artifact_name = (
        f"{normalized}-{recipe['version']}-cp310-{recipe['python_abi']}-"
        f"android_{recipe['minimum_sdk']}_{recipe['android_abi'].replace('-', '_')}.zip"
    )
    artifact = output / artifact_name
    manifest = {
        "schema": 1,
        "package": recipe["package"],
        "version": recipe["version"],
        "module": recipe["module"],
        "python_abi": recipe["python_abi"],
        "android_abi": recipe["android_abi"],
        "minimum_sdk": recipe["minimum_sdk"],
        "source_url": recipe["source_url"],
        "source_sha256": recipe["source_sha256"],
    }
    zip_tree(site_packages, artifact, manifest)
    entry = {
        **manifest,
        "file_name": artifact.name,
        "sha256": sha256(artifact),
        "byte_count": artifact.stat().st_size,
        "worlds": recipe["worlds"],
    }
    entry_path = output / "catalog-entry.json"
    entry_path.write_text(json.dumps(entry, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return artifact, entry_path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--recipe", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    recipe = read_recipe(arguments.recipe)
    arguments.output.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="android-python-dependency-") as temporary_name:
        temporary = Path(temporary_name)
        archive = temporary / "source.tar.gz"
        download_verified(recipe["source_url"], recipe["source_sha256"], archive)
        source = safe_extract(archive, temporary / "source")
        target_archive = temporary / "python-target.zip"
        python_target = temporary / "python-target"
        download_verified(recipe["python_target_url"], recipe["python_target_sha256"], target_archive)
        safe_extract_zip(target_archive, python_target)
        cross_lib_directory = prepare_pyo3_cross_lib(recipe, python_target)
        library = run_build(recipe, source, cross_lib_directory)
        artifact, entry = package_build(recipe, source, library, arguments.output)
        print(f"artifact={artifact}")
        print(f"catalog_entry={entry}")


if __name__ == "__main__":
    main()
