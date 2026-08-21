#!/usr/bin/env python3
"""Resolve a constrained Android dependency build request against PyPI."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import urllib.parse
import urllib.request
from pathlib import Path


PACKAGE = re.compile(r"[a-z0-9][a-z0-9._-]*")
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]*")
MODULE = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*")
SUPPORTED_PYTHON = {"3.12"}
SUPPORTED_ABIS = {"arm64-v8a"}
SUPPORTED_SDKS = set(range(26, 36))


def normalize_package(value: str) -> str:
    return re.sub(r"[-_.]+", "-", value.strip()).lower()


def read_recipes(directory: Path) -> dict[tuple[str, str], tuple[Path, dict]]:
    result: dict[tuple[str, str], tuple[Path, dict]] = {}
    for path in sorted(directory.glob("*.json")):
        recipe = json.loads(path.read_text(encoding="utf-8"))
        key = (normalize_package(str(recipe.get("package", ""))), str(recipe.get("version", "")))
        if not PACKAGE.fullmatch(key[0]) or not VERSION.fullmatch(key[1]):
            raise ValueError(f"Invalid package identity in adapter {path}")
        if key in result:
            raise ValueError(f"Duplicate Android build adapter for {key[0]} {key[1]}")
        result[key] = (path, recipe)
    return result


def load_pypi_metadata(package: str, version: str) -> dict:
    package_path = urllib.parse.quote(package, safe="")
    version_path = urllib.parse.quote(version, safe="")
    request = urllib.request.Request(
        f"https://pypi.org/pypi/{package_path}/{version_path}/json",
        headers={"Accept": "application/json", "User-Agent": "Archipelago-Companion-Builder/1"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        if not response.geturl().startswith("https://pypi.org/"):
            raise ValueError("PyPI metadata redirected to an unexpected host")
        payload = response.read(4 * 1024 * 1024 + 1)
    if len(payload) > 4 * 1024 * 1024:
        raise ValueError("PyPI metadata response is too large")
    return json.loads(payload)


def select_sdist(metadata: dict, preferred_digest: str | None = None) -> dict:
    candidates = []
    for item in metadata.get("urls", []):
        digest = str(item.get("digests", {}).get("sha256", "")).lower()
        url = str(item.get("url", ""))
        filename = str(item.get("filename", ""))
        if item.get("packagetype") != "sdist":
            continue
        if not url.startswith("https://files.pythonhosted.org/"):
            continue
        if Path(filename).name != filename or not filename.endswith((".tar.gz", ".tar.bz2", ".tar.xz", ".zip")):
            continue
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            continue
        if preferred_digest and digest != preferred_digest:
            continue
        candidates.append({"url": url, "sha256": digest, "filename": filename})
    if not candidates:
        suffix = " matching the committed adapter" if preferred_digest else ""
        raise ValueError(f"PyPI does not provide a hash-pinned source distribution{suffix}")
    candidates.sort(key=lambda item: (not item["filename"].endswith(".tar.gz"), item["filename"]))
    return candidates[0]


def resolve_request(
    package: str,
    version: str,
    module: str,
    strategy: str,
    python_version: str,
    android_abi: str,
    minimum_sdk: int,
    recipes: Path,
    metadata: dict,
) -> dict:
    normalized = normalize_package(package)
    if not PACKAGE.fullmatch(normalized):
        raise ValueError("Invalid package name")
    if not VERSION.fullmatch(version):
        raise ValueError("Invalid package version")
    if not MODULE.fullmatch(module):
        raise ValueError("Invalid import module")
    if strategy not in {"auto", "generic", "adapter"}:
        raise ValueError("Invalid build strategy")
    if python_version not in SUPPORTED_PYTHON:
        raise ValueError("This companion build only supports Python 3.12 dependencies")
    if android_abi not in SUPPORTED_ABIS:
        raise ValueError("Unsupported Android ABI")
    if minimum_sdk not in SUPPORTED_SDKS:
        raise ValueError("Unsupported minimum Android SDK")

    info = metadata.get("info", {})
    canonical = normalize_package(str(info.get("name", "")))
    resolved_version = str(info.get("version", ""))
    if canonical != normalized or resolved_version != version:
        raise ValueError("PyPI returned a different package identity")

    adapter = read_recipes(recipes).get((normalized, version))
    if strategy == "adapter" and adapter is None:
        raise ValueError("No committed Android adapter exists for this package version")
    selected_strategy = "adapter" if adapter and strategy != "generic" else "generic-chaquopy"
    selected_module = module
    preferred_digest = None
    adapter_path = ""
    if selected_strategy == "adapter":
        adapter_path, recipe = adapter
        selected_module = str(recipe["module"])
        preferred_digest = str(recipe["source_sha256"])
        if str(recipe["python_version"]) != python_version:
            raise ValueError("The committed adapter targets a different Python version")
        if str(recipe["android_abi"]) != android_abi or int(recipe["minimum_sdk"]) != minimum_sdk:
            raise ValueError("The committed adapter targets a different Android runtime")

    source = select_sdist(metadata, preferred_digest)
    identity = "\0".join([
        normalized, version, source["sha256"], selected_module, python_version,
        android_abi, str(minimum_sdk), selected_strategy, "builder-v1",
    ])
    request_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()
    return {
        "schema": 1,
        "request_id": request_id,
        "strategy": selected_strategy,
        "adapter": Path(adapter_path).as_posix() if adapter_path else "",
        "package": normalized,
        "version": version,
        "module": selected_module,
        "python_version": python_version,
        "python_abi": f"cp{python_version.replace('.', '')}",
        "android_abi": android_abi,
        "minimum_sdk": minimum_sdk,
        "source_url": source["url"],
        "source_sha256": source["sha256"],
        "source_filename": source["filename"],
    }


def write_github_output(request: dict, path: Path) -> None:
    values = {
        "request_id": request["request_id"],
        "strategy": request["strategy"],
        "adapter": request["adapter"],
        "package": request["package"],
        "version": request["version"],
    }
    with path.open("a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--module", required=True)
    parser.add_argument("--strategy", choices=("auto", "generic", "adapter"), default="auto")
    parser.add_argument("--python-version", default="3.12")
    parser.add_argument("--android-abi", default="arm64-v8a")
    parser.add_argument("--minimum-sdk", default=26, type=int)
    parser.add_argument("--recipes", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    normalized = normalize_package(arguments.package)
    metadata = load_pypi_metadata(normalized, arguments.version)
    request = resolve_request(
        normalized, arguments.version, arguments.module, arguments.strategy,
        arguments.python_version, arguments.android_abi, arguments.minimum_sdk,
        arguments.recipes, metadata,
    )
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(request, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        write_github_output(request, Path(github_output))
    print(json.dumps(request, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
