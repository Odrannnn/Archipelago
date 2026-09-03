from __future__ import annotations

import json
import shutil
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from time import time

from .models import Room, Settings
from .storage import StateStore


@dataclass(frozen=True, slots=True)
class Command:
    program: str
    arguments: list[str]
    working_directory: str


class DesktopServices:
    def __init__(self, store: StateStore, repository_root: Path | None = None) -> None:
        self.store = store
        self.repository_root = (repository_root or Path(__file__).resolve().parents[2]).resolve()

    def python(self, settings: Settings) -> str:
        return settings.python_executable.strip() or sys.executable

    @property
    def frozen(self) -> bool:
        return bool(getattr(sys, "frozen", False))

    def frozen_program(self, name: str) -> str:
        suffix = ".exe" if sys.platform == "win32" else ""
        return str(Path(sys.executable).resolve().parent / f"{name}{suffix}")

    def archipelago_uri(self, room: Room) -> str:
        if not room.server.strip() or not room.slot.strip():
            raise ValueError("The room needs both a server address and player name")
        credentials = urllib.parse.quote(room.slot, safe="")
        if room.password:
            credentials += ":" + urllib.parse.quote(room.password, safe="")
        query = urllib.parse.urlencode({"game": room.game})
        return f"archipelago://{credentials}@{room.server.strip()}?{query}"

    def client_command(self, room: Room, settings: Settings) -> Command:
        if not room.game.strip():
            raise ValueError("Choose the room's game so Archipelago can select its desktop client")
        if self.frozen:
            program = self.frozen_program("ArchipelagoLauncher")
            return Command(program, [self.archipelago_uri(room)], str(Path(program).parent))
        return Command(self.python(settings), [
            str(self.repository_root / "Launcher.py"), self.archipelago_uri(room),
        ], str(self.repository_root))

    def text_client_command(self, room: Room, settings: Settings) -> Command:
        if not room.server.strip() or not room.slot.strip():
            raise ValueError("The room needs both a server address and player name")
        arguments = ["--connect", room.server.strip(),
            "--name", room.slot.strip(),
        ]
        if room.password:
            arguments.extend(("--password", room.password))
        if self.frozen:
            program = self.frozen_program("ArchipelagoTextClient")
            return Command(program, arguments, str(Path(program).parent))
        return Command(
            self.python(settings), [str(self.repository_root / "CommonClient.py"), *arguments],
            str(self.repository_root),
        )

    def patch_command(self, patch: Path, settings: Settings) -> Command:
        if not patch.is_file():
            raise ValueError("The selected patch file no longer exists")
        if self.frozen:
            program = self.frozen_program("ArchipelagoLauncher")
            return Command(program, [str(patch.resolve())], str(Path(program).parent))
        return Command(self.python(settings), [
            str(self.repository_root / "Launcher.py"), str(patch.resolve()),
        ], str(self.repository_root))

    def install_world_command(self, world: Path, settings: Settings) -> Command:
        if not world.is_file() or world.suffix.lower() != ".apworld":
            raise ValueError("Select an .apworld file")
        if self.frozen:
            program = self.frozen_program("ArchipelagoLauncher")
            return Command(program, [str(world.resolve())], str(Path(program).parent))
        return Command(self.python(settings), [
            str(self.repository_root / "Launcher.py"), str(world.resolve()),
        ], str(self.repository_root))

    def generation_command(self, yamls: list[Path], seed: str, settings: Settings) -> Command:
        if not yamls:
            raise ValueError("Add at least one player YAML")
        job = self.store.job_dir / f"generate-{int(time() * 1000)}"
        players = job / "Players"
        players.mkdir(parents=True)
        for index, source in enumerate(yamls, start=1):
            if not source.is_file() or source.suffix.lower() not in {".yaml", ".yml"}:
                raise ValueError(f"Invalid player YAML: {source}")
            shutil.copy2(source, players / f"{index:03d}-{source.name}")
        arguments = ["--player_files_path", str(players),
            "--outputpath", str(self.store.seed_dir),
            "--multi", str(len(yamls)),
        ]
        if seed.strip():
            try:
                int(seed)
            except ValueError as error:
                raise ValueError("Seed must be an integer") from error
            arguments.extend(("--seed", seed.strip()))
        if self.frozen:
            program = self.frozen_program("ArchipelagoGenerate")
            return Command(program, arguments, str(Path(program).parent))
        return Command(
            self.python(settings), [str(self.repository_root / "Generate.py"), *arguments],
            str(self.repository_root),
        )

    @staticmethod
    def executable_command(executable: str, file_path: str = "") -> Command:
        executable_path = Path(executable).expanduser()
        if not executable_path.is_file():
            raise ValueError("Configure the application executable in Settings first")
        arguments = [str(Path(file_path).expanduser().resolve())] if file_path else []
        return Command(str(executable_path.resolve()), arguments, str(executable_path.resolve().parent))

    @staticmethod
    def latest_release() -> tuple[str, str]:
        request = urllib.request.Request(
            "https://api.github.com/repos/Odrannnn/Archipelago/releases/latest",
            headers={"Accept": "application/vnd.github+json", "User-Agent": "Archipelago-Companion-Desktop"},
        )
        with urllib.request.urlopen(request, timeout=15) as response:
            value = json.load(response)
        return str(value["tag_name"]), str(value["html_url"])
