"""Discover and install user-approved APWorld Python dependencies on Android.

APWorld code is executable and already trusted by the user. This module does not
act as an allowlist: it statically reads common desktop declarations, installs
universal wheels from PyPI, and leaves native wheels for the Android build cache.
"""

from __future__ import annotations

import ast
from email.parser import Parser
import hashlib
from importlib import metadata
import json
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import time
from typing import Iterable
from urllib.parse import quote
import zipfile

from packaging.requirements import Requirement
from packaging.specifiers import SpecifierSet
from packaging.utils import canonicalize_name
from packaging.version import InvalidVersion, Version
import requests


MAX_SOURCE_BYTES = 4 * 1024 * 1024
MAX_WHEEL_BYTES = 64 * 1024 * 1024
MAX_WHEEL_ENTRIES = 10_000
MAX_EXPANDED_BYTES = 128 * 1024 * 1024


def _dependency(requirement: Requirement, module: str | None = None, declared_by: str = "requirements.txt") -> dict:
    exact = None
    for specifier in requirement.specifier:
        if specifier.operator in ("==", "===") and not specifier.version.endswith(".*"):
            exact = specifier.version
            break
    package = canonicalize_name(requirement.name)
    return {
        "package": package,
        "module": module or package.replace("-", "_"),
        "requirement": str(requirement),
        "specifier": str(requirement.specifier),
        "version": exact or "",
        "declared_by": declared_by,
    }


def _read_requirements(path: Path) -> Iterable[dict]:
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith(("-r", "--requirement", "-c", "--constraint")):
            continue
        line = line.split(" --hash=", 1)[0].strip()
        try:
            requirement = Requirement(line)
        except ValueError:
            continue
        if requirement.marker is not None and not requirement.marker.evaluate():
            continue
        yield _dependency(requirement, declared_by=path.name)


def _literal_string(node: ast.AST) -> str | None:
    try:
        value = ast.literal_eval(node)
    except (ValueError, TypeError):
        return None
    return value if isinstance(value, str) else None


def _walk_ast_dependency_tables(node: ast.AST, source_name: str) -> Iterable[dict]:
    if isinstance(node, ast.Dict):
        for key_node, declaration_node in zip(node.keys, node.values):
            module = _literal_string(key_node) if key_node is not None else None
            if module and isinstance(declaration_node, ast.Dict):
                fields = {
                    key: value
                    for raw_key, value in zip(declaration_node.keys, declaration_node.values)
                    if raw_key is not None and (key := _literal_string(raw_key)) is not None
                }
                version = _literal_string(fields["version"]) if "version" in fields else None
                links_node = fields.get("links")
                urls = [
                    child.value for child in ast.walk(links_node)
                    if isinstance(child, ast.Constant) and isinstance(child.value, str)
                    and child.value.startswith("https://")
                ] if links_node is not None else []
                if version and urls:
                    try:
                        yield _dependency(
                            Requirement(f"{module.replace('_', '-')}=={version}"),
                            module=module,
                            declared_by=source_name,
                        )
                    except ValueError:
                        pass
            yield from _walk_ast_dependency_tables(declaration_node, source_name)
    else:
        for child in ast.iter_child_nodes(node):
            yield from _walk_ast_dependency_tables(child, source_name)


def _read_python_declarations(path: Path) -> Iterable[dict]:
    if path.stat().st_size > MAX_SOURCE_BYTES:
        return
    try:
        tree = ast.parse(path.read_text(encoding="utf-8", errors="replace"), filename=str(path))
    except (OSError, SyntaxError):
        return
    yield from _walk_ast_dependency_tables(tree, path.name)


def scan_package(package_directory: str) -> str:
    package = Path(package_directory).resolve()
    if not package.is_dir():
        raise ValueError("APWorld package directory does not exist")
    declarations: dict[str, dict] = {}
    for requirements in sorted(package.rglob("requirements.txt")):
        for dependency in _read_requirements(requirements):
            declarations[dependency["package"]] = dependency
    for source in sorted(package.rglob("*.py")):
        for dependency in _read_python_declarations(source):
            declarations.setdefault(dependency["package"], dependency)
    return json.dumps(list(declarations.values()), sort_keys=True)


def _registry(root: Path) -> tuple[Path, list[dict]]:
    registry = root / "python_dependencies" / "installed.json"
    try:
        records = json.loads(registry.read_text(encoding="utf-8")) if registry.is_file() else []
    except (OSError, ValueError):
        records = []
    return registry, records if isinstance(records, list) else []


def _satisfied(requirement: Requirement, root: Path, records: list[dict]) -> bool:
    package = canonicalize_name(requirement.name)
    for record in records:
        if canonicalize_name(str(record.get("package", ""))) != package:
            continue
        site_packages = root / "python_dependencies" / str(record.get("site_packages", ""))
        if not site_packages.is_dir():
            continue
        try:
            if requirement.specifier.contains(Version(str(record.get("version", ""))), prereleases=True):
                return True
        except InvalidVersion:
            continue
    try:
        return requirement.specifier.contains(Version(metadata.version(requirement.name)), prereleases=True)
    except (metadata.PackageNotFoundError, InvalidVersion):
        return False


def _select_universal_wheel(requirement: Requirement) -> dict | None:
    response = requests.get(
        f"https://pypi.org/pypi/{quote(canonicalize_name(requirement.name))}/json",
        timeout=30,
        headers={"User-Agent": "Archipelago-Companion-Android"},
    )
    response.raise_for_status()
    document = response.json()
    candidates = []
    for raw_version, files in document.get("releases", {}).items():
        try:
            version = Version(raw_version)
        except InvalidVersion:
            continue
        if not requirement.specifier.contains(version, prereleases=None):
            continue
        for file in files:
            filename = str(file.get("filename", ""))
            url = str(file.get("url", ""))
            digest = str(file.get("digests", {}).get("sha256", "")).lower()
            size = int(file.get("size", -1))
            if file.get("yanked") or file.get("packagetype") != "bdist_wheel":
                continue
            if not filename.endswith(("-py3-none-any.whl", "-py2.py3-none-any.whl")):
                continue
            if not url.startswith("https://files.pythonhosted.org/"):
                continue
            if len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest):
                continue
            if size not in range(1, MAX_WHEEL_BYTES + 1):
                continue
            candidates.append((version, filename, url, digest, size))
    if not candidates:
        return None
    version, filename, url, digest, size = max(candidates, key=lambda item: item[0])
    return {"version": str(version), "filename": filename, "url": url, "sha256": digest, "size": size}


def _safe_wheel_entries(archive: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
    entries = archive.infolist()
    if len(entries) > MAX_WHEEL_ENTRIES:
        raise ValueError("Python wheel contains too many files")
    total = 0
    for entry in entries:
        path = PurePosixPath(entry.filename)
        if path.is_absolute() or ".." in path.parts or "\\" in entry.filename or "\0" in entry.filename:
            raise ValueError(f"Unsafe Python wheel path: {entry.filename}")
        total += max(0, entry.file_size)
        if total > MAX_EXPANDED_BYTES:
            raise ValueError("Python wheel expands beyond the permitted size")
    return entries


def _install_wheel(root: Path, requirement: Requirement, declaration: dict, wheel: dict) -> tuple[dict, list[str]]:
    dependency_root = root / "python_dependencies"
    dependency_root.mkdir(parents=True, exist_ok=True)
    package = canonicalize_name(requirement.name)
    with tempfile.TemporaryDirectory(prefix="pure-wheel-", dir=dependency_root) as temporary_name:
        temporary = Path(temporary_name)
        archive_path = temporary / wheel["filename"]
        with requests.get(
            wheel["url"], stream=True, timeout=60,
            headers={"User-Agent": "Archipelago-Companion-Android"},
        ) as response:
            response.raise_for_status()
            digest = hashlib.sha256()
            written = 0
            with archive_path.open("wb") as output:
                for chunk in response.iter_content(64 * 1024):
                    if not chunk:
                        continue
                    written += len(chunk)
                    if written > wheel["size"]:
                        raise ValueError("Python wheel exceeded its declared size")
                    digest.update(chunk)
                    output.write(chunk)
        if written != wheel["size"] or digest.hexdigest() != wheel["sha256"]:
            raise ValueError("Python wheel failed PyPI SHA-256 verification")
        staged = temporary / "site-packages"
        staged.mkdir()
        with zipfile.ZipFile(archive_path) as archive:
            entries = _safe_wheel_entries(archive)
            archive.extractall(staged, members=entries)
        metadata_files = list(staged.glob("*.dist-info/METADATA"))
        if len(metadata_files) != 1:
            raise ValueError("Python wheel has no unique package metadata")
        message = Parser().parsestr(metadata_files[0].read_text(encoding="utf-8", errors="replace"))
        metadata_name = canonicalize_name(message.get("Name", ""))
        metadata_version = message.get("Version", "")
        if metadata_name != package or metadata_version != wheel["version"]:
            raise ValueError("Python wheel metadata does not match the requested package")
        requires = message.get_all("Requires-Dist", [])
        destination = dependency_root / package / wheel["version"] / "site-packages"
        package_root = destination.parent.parent
        if package_root.exists():
            shutil.rmtree(package_root)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(staged), str(destination))
    record = {
        "package": package,
        "version": wheel["version"],
        "module": declaration.get("module") or package.replace("-", "_"),
        "sha256": wheel["sha256"],
        "site_packages": f"{package}/{wheel['version']}/site-packages",
        "installed_at": int(time.time() * 1000),
        "source": "pypi-universal-wheel",
    }
    return record, requires


def install_pure(declarations_json: str, work_directory: str) -> str:
    root = Path(work_directory).resolve()
    declarations = json.loads(declarations_json)
    registry_path, records = _registry(root)
    queue: list[tuple[Requirement, dict]] = []
    for declaration in declarations:
        try:
            requirement = Requirement(str(declaration["requirement"]))
        except (KeyError, ValueError):
            continue
        if requirement.marker is None or requirement.marker.evaluate():
            queue.append((requirement, declaration))
    installed = []
    unresolved = []
    seen = set()
    while queue:
        requirement, declaration = queue.pop(0)
        identity = str(requirement)
        if identity in seen:
            continue
        seen.add(identity)
        if _satisfied(requirement, root, records):
            continue
        try:
            wheel = _select_universal_wheel(requirement)
        except Exception as error:
            unresolved.append({"requirement": identity, "reason": str(error)})
            continue
        if wheel is None:
            unresolved.append({"requirement": identity, "reason": "No compatible universal wheel; an Android native build is required."})
            continue
        try:
            record, transitive = _install_wheel(root, requirement, declaration, wheel)
        except Exception as error:
            unresolved.append({"requirement": identity, "reason": str(error)})
            continue
        records = [record_item for record_item in records
                   if canonicalize_name(str(record_item.get("package", ""))) != record["package"]]
        records.append(record)
        installed.append(record)
        for child in transitive:
            try:
                child_requirement = Requirement(child)
            except ValueError:
                continue
            if child_requirement.marker is None or child_requirement.marker.evaluate():
                queue.append((child_requirement, {
                    "requirement": str(child_requirement),
                    "module": canonicalize_name(child_requirement.name).replace("-", "_"),
                    "declared_by": f"{record['package']} METADATA",
                }))
    registry_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = registry_path.with_suffix(".tmp")
    temporary.write_text(json.dumps(sorted(records, key=lambda item: item.get("package", "")), indent=2), encoding="utf-8")
    temporary.replace(registry_path)
    return json.dumps({"installed": installed, "unresolved": unresolved}, sort_keys=True)
