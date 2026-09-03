from __future__ import annotations

from dataclasses import asdict, dataclass, field
from time import time
from typing import Any
from uuid import uuid4


@dataclass(slots=True)
class Room:
    id: str = field(default_factory=lambda: uuid4().hex)
    name: str = "New room"
    game: str = ""
    server: str = ""
    slot: str = ""
    password: str = ""
    patch_path: str = ""
    created_at: float = field(default_factory=time)
    updated_at: float = field(default_factory=time)

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "Room":
        allowed = cls.__dataclass_fields__.keys()
        return cls(**{key: value[key] for key in allowed if key in value})

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(slots=True)
class Settings:
    python_executable: str = ""
    retroarch_executable: str = ""
    dolphin_executable: str = ""
    poptracker_executable: str = ""

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "Settings":
        allowed = cls.__dataclass_fields__.keys()
        return cls(**{key: value[key] for key in allowed if key in value})

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(slots=True)
class AppState:
    rooms: list[Room] = field(default_factory=list)
    active_room_id: str = ""
    settings: Settings = field(default_factory=Settings)

    @property
    def active_room(self) -> Room | None:
        return next((room for room in self.rooms if room.id == self.active_room_id), None)

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "AppState":
        rooms = [Room.from_dict(item) for item in value.get("rooms", []) if isinstance(item, dict)]
        active_id = str(value.get("active_room_id", ""))
        if active_id and all(room.id != active_id for room in rooms):
            active_id = ""
        return cls(
            rooms=sorted(rooms, key=lambda room: (-room.updated_at, room.id)),
            active_room_id=active_id,
            settings=Settings.from_dict(value.get("settings", {})),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": 1,
            "rooms": [room.to_dict() for room in self.rooms],
            "active_room_id": self.active_room_id,
            "settings": self.settings.to_dict(),
        }
