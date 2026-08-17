"""Headless subset of Archipelago launcher registration for imported APWorlds.

The Android companion owns its UI and never executes desktop launcher entries,
but some otherwise compatible worlds register optional tools while importing.
"""

from enum import Enum
from typing import Callable, Iterable


class Type(str, Enum):
    TOOL = "TOOL"
    MISC = "MISC"
    CLIENT = "CLIENT"
    ADJUSTER = "ADJUSTER"
    HIDDEN = "HIDDEN"


class SuffixIdentifier:
    def __init__(self, *suffixes: str) -> None:
        self.suffixes: Iterable[str] = suffixes

    def __call__(self, path: str) -> bool:
        return isinstance(path, str) and any(path.endswith(suffix) for suffix in self.suffixes)


class Component:
    def __init__(
        self,
        display_name: str,
        script_name: str | None = None,
        frozen_name: str | None = None,
        cli: bool = False,
        icon: str = "icon",
        component_type: Type | None = None,
        func: Callable | None = None,
        file_identifier: Callable[[str], bool] | None = None,
        game_name: str | None = None,
        supports_uri: bool = False,
        description: str = "",
    ) -> None:
        self.display_name = display_name
        self.script_name = script_name
        self.frozen_name = frozen_name
        self.cli = cli
        self.icon = icon
        self.type = component_type or Type.MISC
        self.func = func
        self.file_identifier = file_identifier
        self.game_name = game_name
        self.supports_uri = supports_uri
        self.description = description


components: list[Component] = []
icon_paths: dict[str, str] = {}


def launch(func: Callable, name: str | None = None, args: tuple[str, ...] = ()) -> None:
    del name
    func(*args)
