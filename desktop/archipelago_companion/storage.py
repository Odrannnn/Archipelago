from __future__ import annotations

import json
import os
import shutil
import tempfile
import zipfile
from pathlib import Path

from .models import AppState


def data_root() -> Path:
    override = os.environ.get("ARCHIPELAGO_COMPANION_HOME")
    if override:
        return Path(override).expanduser().resolve()
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
        return base / "Archipelago Companion"
    return Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / "archipelago-companion"


class StateStore:
    def __init__(self, root: Path | None = None) -> None:
        self.root = (root or data_root()).resolve()
        self.state_path = self.root / "state.json"
        self.seed_dir = self.root / "seeds"
        self.yaml_dir = self.root / "yamls"
        self.world_dir = self.root / "worlds"
        self.job_dir = self.root / "jobs"
        for directory in (self.root, self.seed_dir, self.yaml_dir, self.world_dir, self.job_dir):
            directory.mkdir(parents=True, exist_ok=True)

    def load(self) -> AppState:
        if not self.state_path.is_file():
            return AppState()
        try:
            value = json.loads(self.state_path.read_text(encoding="utf-8"))
            if not isinstance(value, dict) or value.get("version", 1) != 1:
                raise ValueError("Unsupported desktop companion state version")
            return AppState.from_dict(value)
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            damaged = self.state_path.with_suffix(".damaged.json")
            if not damaged.exists():
                shutil.copy2(self.state_path, damaged)
            return AppState()

    def save(self, state: AppState) -> None:
        encoded = json.dumps(state.to_dict(), indent=2, ensure_ascii=False) + "\n"
        fd, temporary_name = tempfile.mkstemp(prefix="state-", suffix=".json", dir=self.root)
        temporary = Path(temporary_name)
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as output:
                output.write(encoded)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, self.state_path)
        finally:
            temporary.unlink(missing_ok=True)

    def import_file(self, source: Path, destination: Path, allowed_suffixes: set[str]) -> Path:
        source = source.resolve()
        if not source.is_file() or source.suffix.lower() not in allowed_suffixes:
            raise ValueError(f"Unsupported file: {source.name}")
        target = destination / source.name
        counter = 2
        while target.exists() and target.read_bytes() != source.read_bytes():
            target = destination / f"{source.stem}-{counter}{source.suffix}"
            counter += 1
        if not target.exists():
            shutil.copy2(source, target)
        return target

    def import_yaml(self, source: Path) -> Path:
        return self.import_file(source, self.yaml_dir, {".yaml", ".yml"})

    def import_world(self, source: Path) -> Path:
        if not zipfile.is_zipfile(source):
            raise ValueError("The selected APWorld is not a valid ZIP container")
        return self.import_file(source, self.world_dir, {".apworld"})

    def create_backup(self, destination: Path) -> None:
        destination = destination.resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
            for source in sorted(self.root.rglob("*")):
                if source.is_file() and source != destination and "jobs" not in source.relative_to(self.root).parts:
                    archive.write(source, source.relative_to(self.root).as_posix())

    def restore_backup(self, source: Path) -> None:
        if not zipfile.is_zipfile(source):
            raise ValueError("The selected backup is not a ZIP archive")
        with zipfile.ZipFile(source) as archive:
            total_size = 0
            for info in archive.infolist():
                relative = Path(info.filename)
                if relative.is_absolute() or ".." in relative.parts:
                    raise ValueError("The backup contains an unsafe path")
                if info.file_size > 2 * 1024 * 1024 * 1024:
                    raise ValueError("The backup contains an unexpectedly large file")
                total_size += info.file_size
                if total_size > 4 * 1024 * 1024 * 1024:
                    raise ValueError("The backup is unexpectedly large")
            archive.extractall(self.root)
