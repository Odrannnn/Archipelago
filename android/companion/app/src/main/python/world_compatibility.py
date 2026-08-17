"""Narrow, versioned adapters for community APWorld defects.

Keep these corrections out of the generic generator and patch pipeline. Each
adapter is explicitly registered for the exact APWorld release it understands.
"""

from __future__ import annotations

import importlib
from collections.abc import Callable


CompatibilityAdapter = Callable[[str], None]
_ADAPTERS: dict[tuple[str, str], CompatibilityAdapter] = {}


def compatibility_adapter(game: str, version: str):
    def register(adapter: CompatibilityAdapter) -> CompatibilityAdapter:
        _ADAPTERS[(game, version)] = adapter
        return adapter
    return register


def apply_world_compatibility(module_name: str, game: str, version: str) -> None:
    adapter = _ADAPTERS.get((game, version))
    if adapter is not None:
        adapter(module_name)


@compatibility_adapter("The Legend of Zelda - Oracle of Ages", "1.0.2")
def _fix_ooa_unreachable_sea_of_storms_event(module_name: str) -> None:
    creation_regions = importlib.import_module(f"{module_name}.generation.CreationRegions")
    if getattr(creation_regions.create_events, "_android_ooa_sea_of_storms_fix", False):
        return

    original_create_events = creation_regions.create_events

    def create_events(world) -> None:
        original_create_events(world)
        secret_locations = getattr(world.options.secret_locations, "value", world.options.secret_locations)
        if secret_locations:
            return

        # OoA 1.0.2 creates this internal event even though its region is only
        # connected when Secret Locations is enabled. Full accessibility then
        # rejects an otherwise valid seed because the hidden event is unreachable.
        region = world.multiworld.get_region("sea of storms spot", world.player)
        for index in range(len(region.locations) - 1, -1, -1):
            if region.locations[index].name == "sea of storms spot.event":
                del region.locations[index]

    create_events._android_ooa_sea_of_storms_fix = True
    creation_regions.create_events = create_events
