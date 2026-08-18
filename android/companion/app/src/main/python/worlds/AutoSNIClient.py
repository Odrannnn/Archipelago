"""Small Android-compatible subset of Archipelago's SNI client registry."""

from __future__ import annotations

import abc
from typing import ClassVar, Iterable


class AutoSNIClientRegister(abc.ABCMeta):
    game_handlers: ClassVar[dict[str, "SNIClient"]] = {}

    def __new__(cls, name, bases, namespace):
        new_class = super().__new__(cls, name, bases, namespace)
        if namespace.get("game"):
            cls.game_handlers[namespace["game"]] = new_class()
        return new_class

    @classmethod
    async def get_handler(cls, ctx):
        """Match a handler exactly as the desktop SNI watcher does."""
        for handler in cls.game_handlers.values():
            if await handler.validate_rom(ctx):
                return handler
        return None


class SNIClient(abc.ABC, metaclass=AutoSNIClientRegister):
    patch_suffix: ClassVar[str | Iterable[str]] = ()

    @abc.abstractmethod
    async def validate_rom(self, ctx) -> bool:
        raise NotImplementedError

    @abc.abstractmethod
    async def game_watcher(self, ctx) -> None:
        raise NotImplementedError

    async def deathlink_kill_player(self, ctx) -> None:
        return None

    def on_package(self, ctx, cmd: str, args: dict) -> None:
        return None
