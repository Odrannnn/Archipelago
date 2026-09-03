from __future__ import annotations

import inspect
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True, slots=True)
class ChoiceValue:
    value: str
    label: str


@dataclass(frozen=True, slots=True)
class OptionSpec:
    key: str
    label: str
    description: str
    kind: str
    default: Any
    choices: tuple[ChoiceValue, ...] = ()
    special_values: tuple[ChoiceValue, ...] = ()
    minimum: int | None = None
    maximum: int | None = None
    supports_weighting: bool = False


@dataclass(frozen=True, slots=True)
class OptionGroupSpec:
    name: str
    options: tuple[OptionSpec, ...]
    start_collapsed: bool = False


@dataclass(frozen=True, slots=True)
class GameSpec:
    game: str
    groups: tuple[OptionGroupSpec, ...] = ()
    options_page: str = ""
    native_options: bool = True


def _plain(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): _plain(content) for key, content in value.items()}
    if isinstance(value, list | tuple | set | frozenset):
        return [_plain(content) for content in value]
    if value is None or isinstance(value, bool | int | float | str):
        return value
    return str(value)


def _normalized_value(option: type, value: Any) -> Any:
    """Use upstream parsing while keeping human-readable YAML values."""
    from Options import Choice, NamedRange, TextChoice, Toggle

    parsed = option.from_any(value)
    if issubclass(option, Toggle):
        return bool(parsed.value)
    if issubclass(option, Choice):
        if not issubclass(option, TextChoice) or isinstance(parsed.value, int):
            return parsed.current_key
    if issubclass(option, NamedRange):
        for name, number in option.special_range_names.items():
            if parsed.value == number:
                return name
    return _plain(parsed.value)


def _option_spec(key: str, option: type) -> OptionSpec:
    from Options import Choice, FreeText, NamedRange, OptionDict, OptionList, OptionSet, Range, TextChoice, Toggle

    choices: list[ChoiceValue] = []
    special_values: list[ChoiceValue] = []
    if issubclass(option, Toggle):
        kind = "toggle"
    elif issubclass(option, TextChoice):
        kind = "text_choice"
        choices = [ChoiceValue(name, option.get_option_name(number)) for number, name in option.name_lookup.items()]
    elif issubclass(option, Choice):
        kind = "choice"
        choices = [ChoiceValue(name, option.get_option_name(number)) for number, name in option.name_lookup.items()]
        if option.supports_weighting:
            choices.append(ChoiceValue("random", "Random"))
    elif issubclass(option, NamedRange):
        kind = "range"
        special_values = [
            ChoiceValue(name, name.replace("_", " ").title()) for name in option.special_range_names
        ]
    elif issubclass(option, Range):
        kind = "range"
    elif issubclass(option, FreeText):
        kind = "text"
    elif issubclass(option, OptionDict):
        kind = "dict"
    elif issubclass(option, OptionList):
        kind = "list"
    elif issubclass(option, OptionSet):
        kind = "set"
    else:
        kind = "custom"

    return OptionSpec(
        key=key,
        label=getattr(option, "display_name", None) or key.replace("_", " ").title(),
        description=inspect.cleandoc(option.__doc__ or "").strip(),
        kind=kind,
        default=_normalized_value(option, option.default),
        choices=tuple(choices),
        special_values=tuple(special_values),
        minimum=getattr(option, "range_start", None),
        maximum=getattr(option, "range_end", None),
        supports_weighting=bool(getattr(option, "supports_weighting", False)),
    )


def game_catalog() -> list[tuple[str, bool, str]]:
    """Return visible games and whether upstream exposes native simple options."""
    from worlds import AutoWorldRegister

    result = []
    for game, world in sorted(AutoWorldRegister.world_types.items()):
        if world.hidden:
            continue
        options_page = world.web.options_page
        result.append((game, options_page is True, options_page if isinstance(options_page, str) else ""))
    return result


def game_schema(game: str) -> GameSpec:
    from Options import Visibility, get_option_groups
    from worlds import AutoWorldRegister

    world = AutoWorldRegister.world_types.get(game)
    if world is None or world.hidden:
        raise ValueError(f"No installed world handles game {game}")
    options_page = world.web.options_page
    if options_page is not True:
        return GameSpec(game, options_page=options_page if isinstance(options_page, str) else "", native_options=False)

    collapsed = {group.name: bool(group.start_collapsed) for group in world.web.option_groups}
    groups = []
    for group_name, options in get_option_groups(world, Visibility.simple_ui).items():
        fields = tuple(_option_spec(key, option) for key, option in options.items())
        if fields:
            groups.append(OptionGroupSpec(group_name, fields, collapsed.get(group_name, False)))
    return GameSpec(game, tuple(groups))


def default_values(schema: GameSpec) -> dict[str, Any]:
    return {option.key: option.default for group in schema.groups for option in group.options}


def validate_values(game: str, values: dict[str, Any]) -> dict[str, Any]:
    from worlds import AutoWorldRegister

    world = AutoWorldRegister.world_types.get(game)
    if world is None:
        raise ValueError(f"No installed world handles game {game}")
    validated = {}
    for key, value in values.items():
        option = world.options_dataclass.type_hints.get(key)
        if option is None:
            validated[key] = _plain(value)
        elif isinstance(value, dict) and option.supports_weighting:
            validated[key] = _plain(value)
        elif isinstance(value, str) and value.startswith("random") and option.supports_weighting:
            validated[key] = value
        else:
            try:
                validated[key] = _normalized_value(option, value)
            except Exception as error:
                label = getattr(option, "display_name", None) or key.replace("_", " ").title()
                raise ValueError(f"{label}: {error}") from error
    return validated


def player_yaml(name: str, game: str, values: dict[str, Any], extras: dict[str, Any] | None = None) -> str:
    import Utils

    clean_name = name.strip()
    if not clean_name:
        raise ValueError("Player name must not be empty")
    if len(clean_name) > 16:
        raise ValueError("Player name cannot be longer than 16 characters")
    remaining = dict(extras or {})
    document = {"name": clean_name}
    document["description"] = remaining.pop(
        "description", f"YAML generated by Archipelago {Utils.__version__}."
    )
    document["game"] = game
    if "requires" in remaining:
        document["requires"] = remaining.pop("requires")
    document.update(remaining)
    document[game] = validate_values(game, values)
    return Utils.dump(document, sort_keys=False).rstrip() + "\n"


def read_player_yaml(path: Path) -> tuple[str, str, dict[str, Any], dict[str, Any]]:
    import Utils

    documents = list(Utils.parse_yamls(path.read_text(encoding="utf-8")))
    if len(documents) != 1 or not isinstance(documents[0], dict):
        raise ValueError("Choose a YAML containing exactly one player")
    document = documents[0]
    game = str(document.get("game", "")).strip()
    if not game:
        raise ValueError("The player YAML has no game")
    values = document.get(game) or {}
    if not isinstance(values, dict):
        raise ValueError(f"{game} options must be a mapping")
    extras = {key: value for key, value in document.items() if key not in {"name", "game", game}}
    return str(document.get("name") or "Player"), game, dict(values), extras


def safe_player_filename(name: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._ -]+", "", name).strip(" .") or "Player"
    return f"{cleaned}.yaml"
