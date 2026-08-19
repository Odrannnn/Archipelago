from __future__ import annotations

from pathlib import Path
import struct
import sys
import unittest


PYTHON_SOURCE = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SOURCE))

import dolphin_memory_engine as dme


class FakeBackend:
    def __init__(self) -> None:
        self.hooked = False
        self.memory: dict[int, int] = {}

    def hook(self) -> None:
        self.hooked = True

    def unHook(self) -> None:
        self.hooked = False

    def isHooked(self) -> bool:
        return self.hooked

    def isSocketConnected(self) -> bool:
        return True

    def readBytes(self, address: int, size: int) -> bytes:
        if not self.hooked:
            raise RuntimeError("not hooked")
        return bytes(self.memory.get(address + offset, 0) for offset in range(size))

    def writeBytes(self, address: int, data: bytes) -> None:
        if not self.hooked:
            raise RuntimeError("not hooked")
        for offset, value in enumerate(data):
            self.memory[address + offset] = value


class DolphinMemoryEngineCompatibilityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.backend = FakeBackend()
        dme.configure_backend(self.backend)

    def tearDown(self) -> None:
        dme.configure_backend(None)

    def test_hook_status_and_logical_unhook_match_desktop_surface(self) -> None:
        self.assertFalse(dme.is_hooked())
        self.assertEqual(3, int(dme.get_status()))

        dme.hook()
        self.assertTrue(dme.is_hooked())
        self.assertEqual(0, int(dme.get_status()))

        dme.un_hook()
        self.assertFalse(dme.is_hooked())
        with self.assertRaisesRegex(RuntimeError, "not hooked"):
            dme.assert_hooked()

    def test_typed_values_are_powerpc_big_endian_and_raw_bytes_are_unchanged(self) -> None:
        dme.hook()
        dme.write_word(0x80001000, 0x12345678)
        dme.write_float(0x80001004, 1.5)
        dme.write_double(0x80001008, -2.25)

        self.assertEqual(b"\x12\x34\x56\x78", dme.read_bytes(0x80001000, 4))
        self.assertEqual(struct.pack(">f", 1.5), dme.read_bytes(0x80001004, 4))
        self.assertEqual(0x12345678, dme.read_word(0x80001000))
        self.assertAlmostEqual(1.5, dme.read_float(0x80001004))
        self.assertAlmostEqual(-2.25, dme.read_double(0x80001008))

    def test_follows_mem1_and_mem2_pointer_chains(self) -> None:
        dme.hook()
        dme.write_word(0x80002000, 0x80003000)
        dme.write_word(0x80003010, 0x90004000)

        self.assertEqual(
            0x90004020,
            dme.follow_pointers(0x80002000, [0x10, 0x20]),
        )

        dme.write_word(0x80005000, 0x70000000)
        with self.assertRaisesRegex(RuntimeError, "not valid"):
            dme.follow_pointers(0x80005000, [0])

    def test_memwatch_reads_writes_and_uses_decimal_input(self) -> None:
        dme.hook()
        watch = dme.MemWatch("counter", 0x80006000, False)
        # Desktop MemWatch stores offsets for pointer watches only.
        watch.add_offset(4)
        dme.write_word(0x80006000, 27)

        self.assertTrue(watch.read_memory_from_ram())
        self.assertEqual(27, watch.get_value())
        self.assertTrue(watch.write_memory_from_string("42"))
        self.assertEqual(42, dme.read_word(0x80006000))
        self.assertTrue(watch.write_memory_from_string("-1"))
        self.assertEqual(0xFFFFFFFF, dme.read_word(0x80006000))
        self.assertFalse(watch.write_memory_from_string("0x2a"))

    def test_hook_without_configured_android_backend_is_non_throwing(self) -> None:
        dme.configure_backend(None)
        dme.hook()
        self.assertFalse(dme.is_hooked())
        self.assertEqual(1, int(dme.get_status()))


if __name__ == "__main__":
    unittest.main()
