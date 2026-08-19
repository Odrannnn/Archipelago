"""Android adapter for the official AP Wind Waker Randomizer 2.5.1."""

from __future__ import annotations

import base64
import builtins
import os
import sys
import types
import zipfile
from dataclasses import MISSING, fields
from io import BytesIO
from pathlib import Path

from ruamel.yaml import YAML

GAME = "The Wind Waker"
PATCH_EXTENSION = ".aptww"
RESULT_EXTENSION = ".iso"
INPUT_KEY = "clean_iso"
INPUT_DESCRIPTION = "North American (GZLE01) The Wind Waker GameCube ISO"


def requirements() -> dict[str, object]:
    return {
        "game": GAME,
        "streaming": True,
        "result_extension": RESULT_EXTENSION,
        "inputs": [{"key": INPUT_KEY, "description": INPUT_DESCRIPTION, "file_name": "Wind Waker.iso"}],
    }


def load_plando(patch_bytes: bytes) -> dict[str, object]:
    try:
        with zipfile.ZipFile(BytesIO(patch_bytes), "r") as archive:
            manifest = __import__("json").loads(archive.read("archipelago.json"))
            if manifest.get("game") != GAME:
                raise ValueError(f"The selected patch is for {manifest.get('game') or 'an unknown game'}, not {GAME}")
            encoded = archive.read("plando")
        document = YAML(typ="safe").load(base64.b64decode(encoded))
    except (KeyError, zipfile.BadZipFile, ValueError, TypeError) as error:
        if isinstance(error, ValueError) and "not The Wind Waker" in str(error):
            raise
        raise ValueError("The selected file is not a valid Wind Waker .aptww patch") from error
    if not isinstance(document, dict):
        raise ValueError("The Wind Waker .aptww plando is invalid")
    version = document.get("Version")
    if not isinstance(version, (list, tuple)) or not version or version[0] != 3:
        shown = ".".join(map(str, version)) if isinstance(version, (list, tuple)) else "pre-3.0"
        raise ValueError(
            f"This .aptww uses APWorld {shown}. The bundled official patcher supports current 3.x .aptww files."
        )
    required = ("Seed", "Slot", "Name", "Options", "Required Bosses", "Locations", "Entrances", "Charts")
    missing = [key for key in required if key not in document]
    if missing:
        raise ValueError(f"The .aptww plando is missing: {', '.join(missing)}")
    return document


def validate_iso_fd(fd: int) -> None:
    try:
        with os.fdopen(os.dup(int(fd)), "rb") as stream:
            stream.seek(0)
            header = stream.read(6)
    except OSError as error:
        raise ValueError("The selected Wind Waker ISO could not be opened") from error
    if header[:4] == b"CISO":
        raise ValueError("CISO images are unsupported. Select an uncompressed GCM-format ISO.")
    if header != b"GZLE01":
        raise ValueError("Select the North American Wind Waker ISO (game ID GZLE01).")


def _runtime():
    # Chaquopy may load this adapter from its asset importer, while the patcher
    # package itself is explicitly extracted because upstream opens data by path.
    import twwrando
    root = Path(twwrando.__file__).resolve().parent
    root_string = str(root)
    if root_string not in sys.path:
        sys.path.insert(0, root_string)
    from options.wwrando_options import EntranceMixMode, KeyLunacyMode, Options, SwordMode, TrickDifficulty
    from randomizer import Plando, WWRandomizer
    return Options, SwordMode, EntranceMixMode, TrickDifficulty, KeyLunacyMode, Plando, WWRandomizer


def _options_and_plando(document):
    Options, SwordMode, EntranceMixMode, TrickDifficulty, KeyLunacyMode, Plando, _ = _runtime()
    options = Options()
    enum_values = {
        SwordMode: list(SwordMode),
        EntranceMixMode: list(EntranceMixMode),
        TrickDifficulty: list(TrickDifficulty),
        KeyLunacyMode: [
            KeyLunacyMode.START_WITH, KeyLunacyMode.VANILLA, KeyLunacyMode.DUNGEON,
            KeyLunacyMode.ANY_DUNGEON, KeyLunacyMode.LOCAL, KeyLunacyMode.KEYLUNACY,
        ],
    }
    supplied = document["Options"]
    for field in fields(options):
        if field.name in supplied:
            value = supplied[field.name]
            if field.type is bool:
                value = bool(value)
            elif field.type is int:
                value = int(value)
            elif field.type in enum_values:
                try:
                    value = enum_values[field.type][int(value)]
                except (IndexError, TypeError, ValueError) as error:
                    raise ValueError(f"Invalid Wind Waker option {field.name}: {value}") from error
            setattr(options, field.name, value)
        elif field.name in ("randomized_gear", "starting_gear") and field.default_factory is not MISSING:
            setattr(options, field.name, field.default_factory())
        elif getattr(options, field.name) is None and field.default is not MISSING:
            setattr(options, field.name, field.default)
    plando = Plando(
        f"AP_{document['Seed']}_P{document['Slot']}", int(document["Slot"]), str(document["Name"]),
        document["Required Bosses"], document["Locations"], document["Entrances"], document["Charts"],
    )
    return options, plando


def patch(patch_bytes: bytes, input_fd: int, output_fd: int, work_directory: str) -> str:
    document = load_plando(patch_bytes)
    validate_iso_fd(input_fd)
    *_, WWRandomizer = _runtime()
    options, plando = _options_and_plando(document)
    args = types.SimpleNamespace(
        dry=False, disassemble=False, exportfolder=False, bulk=False, printflags=False,
        noitemrando=False, mapselect=False, heap=False, test=None,
    )
    os.makedirs(work_directory, exist_ok=True)
    input_path = str(Path(work_directory) / ".android-saf-input.iso")
    output_path = str(Path(work_directory) / ".android-saf-output.iso")
    Path(input_path).touch(exist_ok=True)
    original_open = builtins.open

    def descriptor_open(file, mode="r", *args, **kwargs):
        try:
            path = os.fspath(file)
        except TypeError:
            path = None
        if path == input_path:
            stream = os.fdopen(os.dup(int(input_fd)), mode)
            stream.seek(0)
            return stream
        if path == output_path:
            return os.fdopen(os.dup(int(output_fd)), mode)
        return original_open(file, mode, *args, **kwargs)

    builtins.open = descriptor_open
    try:
        randomizer = WWRandomizer(plando.seed, input_path, work_directory, options, plando, args)
        original_export = randomizer.gcm.export_disc_to_iso_with_changed_files

        def export_to_selected_document(_ignored_path):
            yield from original_export(output_path)

        randomizer.gcm.export_disc_to_iso_with_changed_files = export_to_selected_document
        for _progress in randomizer.randomize():
            pass
    finally:
        builtins.open = original_open
        Path(input_path).unlink(missing_ok=True)
        Path(output_path).unlink(missing_ok=True)
    return "document"
