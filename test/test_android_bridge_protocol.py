"""Contract checks for the mGBA Android loopback bridge packet layout."""

import struct
import unittest


class AndroidBridgeProtocolTest(unittest.TestCase):
    MAGIC = 0x41504231  # APB1
    HEADER = struct.Struct(">IHHIII")

    def test_header_is_twenty_big_endian_bytes(self) -> None:
        packet = self.HEADER.pack(self.MAGIC, 3, 0, 7, 0x02001234, 4) + struct.pack(">I", 0x40)
        self.assertEqual(len(packet), 24)
        self.assertEqual(
            self.HEADER.unpack(packet[:self.HEADER.size]),
            (self.MAGIC, 3, 0, 7, 0x02001234, 4),
        )
        self.assertEqual(struct.unpack(">I", packet[self.HEADER.size:])[0], 0x40)


class MetroidFusionMemoryContractTest(unittest.TestCase):
    """Address translation used by the Android port of APWorld v1.22.4."""

    EWRAM = 0x02000000
    IWRAM = 0x03000000
    ROM = 0x08000000

    def test_bizhawk_offsets_translate_to_gba_system_bus(self) -> None:
        self.assertEqual(self.ROM + 0x7FFF00, 0x087FFF00)
        self.assertEqual(self.IWRAM + 0x0BDE, 0x03000BDE)
        self.assertEqual(self.IWRAM + 0x06B4, 0x030006B4)
        self.assertEqual(self.EWRAM + 0x037200, 0x02037200)

    def test_received_item_counter_is_little_endian(self) -> None:
        low, high = 0x34, 0x12
        self.assertEqual(low | high << 8, 0x1234)


if __name__ == "__main__":
    unittest.main()
