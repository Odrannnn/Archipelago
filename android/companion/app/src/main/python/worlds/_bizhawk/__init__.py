"""Android implementation of Archipelago's standard BizHawk connector API.

The imported game client stays unchanged. Calls are forwarded to the companion
and executed atomically by the custom mGBA core on its emulation thread.
"""

from __future__ import annotations

import base64
import json
from typing import Any, Sequence


class NotConnectedError(Exception):
    pass


class RequestFailedError(Exception):
    pass


class ConnectorError(Exception):
    pass


class SyncError(Exception):
    pass


def _backend(ctx: Any) -> Any:
    backend = getattr(ctx, "backend", None)
    if backend is None:
        raise NotConnectedError("The Android mGBA bridge is not connected")
    return backend


def _encode_reads(values: Sequence[Any]) -> str:
    return json.dumps([[int(address), int(length), str(domain)] for address, length, domain in values])


def _encode_writes(values: Sequence[Any]) -> str:
    return json.dumps([
        [int(address), base64.b64encode(bytes(value)).decode("ascii"), str(domain)]
        for address, value, domain in values
    ])


def _decode_result(value: str) -> list[bytes]:
    return [base64.b64decode(item) for item in json.loads(value)]


def _call(function, *args):
    try:
        return function(*args)
    except (NotConnectedError, RequestFailedError):
        raise
    except Exception as exc:
        raise RequestFailedError(str(exc)) from exc


async def read(ctx: Any, read_list: Sequence[Any]) -> list[bytes]:
    return _decode_result(_call(_backend(ctx).read, _encode_reads(read_list)))


async def guarded_read(ctx: Any, read_list: Sequence[Any], guard_list: Sequence[Any]) -> list[bytes] | None:
    result = _call(_backend(ctx).guardedRead, _encode_reads(read_list), _encode_writes(guard_list))
    return None if result is None else _decode_result(result)


async def write(ctx: Any, write_list: Sequence[Any]) -> None:
    _call(_backend(ctx).write, _encode_writes(write_list))


async def guarded_write(ctx: Any, write_list: Sequence[Any], guard_list: Sequence[Any]) -> bool:
    return bool(_call(_backend(ctx).guardedWrite, _encode_writes(write_list), _encode_writes(guard_list)))


async def display_message(ctx: Any, message: str) -> None:
    _call(_backend(ctx).displayMessage, message)


async def get_hash(ctx: Any) -> str:
    return str(_call(_backend(ctx).getHash))


async def get_system(ctx: Any) -> str:
    return str(_call(_backend(ctx).getSystem))


async def get_memory_size(ctx: Any, domain: str) -> int:
    return int(_call(_backend(ctx).getMemorySize, domain))
