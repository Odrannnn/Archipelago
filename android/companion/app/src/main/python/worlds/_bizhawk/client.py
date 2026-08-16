"""BizHawk client registration API used by imported generation worlds.

This mirrors the AP 0.6.8 base classes without registering desktop launcher
components. It allows an APWorld which eagerly imports its client to load while
keeping Android live support capability-gated by the companion.
"""

from __future__ import annotations

import abc
from typing import TYPE_CHECKING, Any, ClassVar

if TYPE_CHECKING:
    from .context import BizHawkClientContext


class AutoBizHawkClientRegister(abc.ABCMeta):
    game_handlers: ClassVar[dict[tuple[str, ...], dict[str, "BizHawkClient"]]] = {}

    def __new__(cls, name: str, bases: tuple[type, ...], namespace: dict[str, Any]):
        new_class = super().__new__(cls, name, bases, namespace)
        if "system" in namespace:
            system_value = namespace["system"]
            systems = (system_value,) if isinstance(system_value, str) else tuple(sorted(system_value))
            handlers = AutoBizHawkClientRegister.game_handlers.setdefault(systems, {})
            if "game" in namespace:
                handlers[namespace["game"]] = new_class()
        return new_class

    @staticmethod
    async def get_handler(ctx: "BizHawkClientContext", system: str) -> "BizHawkClient | None":
        for systems, handlers in AutoBizHawkClientRegister.game_handlers.items():
            if system in systems:
                for handler in handlers.values():
                    if await handler.validate_rom(ctx):
                        return handler
        return None


class BizHawkClient(abc.ABC, metaclass=AutoBizHawkClientRegister):
    system: ClassVar[str | tuple[str, ...]]
    game: ClassVar[str]
    patch_suffix: ClassVar[str | tuple[str, ...] | None]

    @abc.abstractmethod
    async def validate_rom(self, ctx: "BizHawkClientContext") -> bool:
        ...

    async def set_auth(self, ctx: "BizHawkClientContext") -> None:
        pass

    @abc.abstractmethod
    async def game_watcher(self, ctx: "BizHawkClientContext") -> None:
        ...

    def on_package(self, ctx: "BizHawkClientContext", cmd: str, args: dict) -> None:
        pass
