"""Small pure-Python BSDIFF40 patch reader for Android generation.

Some GBA APWorlds ship a static bsdiff base patch. Creating new bsdiff
files is intentionally unsupported, but applying that patch needs no native
extension.
"""

from __future__ import annotations

import bz2


def _offtin(value: bytes) -> int:
    if len(value) != 8:
        raise ValueError("Invalid BSDIFF40 integer")
    result = value[7] & 0x7F
    for index in range(6, -1, -1):
        result = result * 256 + value[index]
    return -result if value[7] & 0x80 else result


def patch(source: bytes, delta: bytes) -> bytes:
    source = bytes(source)
    delta = bytes(delta)
    if len(delta) < 32 or delta[:8] != b"BSDIFF40":
        raise ValueError("Invalid BSDIFF40 patch")
    control_length = _offtin(delta[8:16])
    diff_length = _offtin(delta[16:24])
    output_length = _offtin(delta[24:32])
    if min(control_length, diff_length, output_length) < 0:
        raise ValueError("Invalid BSDIFF40 lengths")
    diff_start = 32 + control_length
    extra_start = diff_start + diff_length
    if extra_start > len(delta):
        raise ValueError("Truncated BSDIFF40 patch")

    control = memoryview(bz2.decompress(delta[32:diff_start]))
    differences = memoryview(bz2.decompress(delta[diff_start:extra_start]))
    extra = memoryview(bz2.decompress(delta[extra_start:]))
    control_position = diff_position = extra_position = 0
    source_position = output_position = 0
    output = bytearray(output_length)

    while output_position < output_length:
        if control_position + 24 > len(control):
            raise ValueError("Truncated BSDIFF40 control stream")
        add_length = _offtin(control[control_position:control_position + 8])
        copy_length = _offtin(control[control_position + 8:control_position + 16])
        seek = _offtin(control[control_position + 16:control_position + 24])
        control_position += 24
        if add_length < 0 or copy_length < 0 or output_position + add_length + copy_length > output_length:
            raise ValueError("Invalid BSDIFF40 control tuple")
        if diff_position + add_length > len(differences):
            raise ValueError("Truncated BSDIFF40 difference stream")

        for index in range(add_length):
            old_index = source_position + index
            old_byte = source[old_index] if 0 <= old_index < len(source) else 0
            output[output_position + index] = (differences[diff_position + index] + old_byte) & 0xFF
        output_position += add_length
        source_position += add_length
        diff_position += add_length

        if extra_position + copy_length > len(extra):
            raise ValueError("Truncated BSDIFF40 extra stream")
        output[output_position:output_position + copy_length] = extra[extra_position:extra_position + copy_length]
        output_position += copy_length
        extra_position += copy_length
        source_position += seek

    return bytes(output)


def diff(_source: bytes, _target: bytes) -> bytes:
    raise NotImplementedError("Creating BSDIFF40 files is unavailable in the Android generator")
