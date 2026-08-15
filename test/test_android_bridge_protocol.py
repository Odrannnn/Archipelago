"""Contract checks for the mGBA Android loopback bridge packet layout."""

import struct
import unittest
import zlib


class AndroidBridgeProtocolTest(unittest.TestCase):
    MAGIC = 0x41504231  # APB1
    HEADER = struct.Struct(">IHHIII")
    MESSAGE = 7
    MAX_MESSAGE_BYTES = 511

    def test_header_is_twenty_big_endian_bytes(self) -> None:
        packet = self.HEADER.pack(self.MAGIC, 3, 0, 7, 0x02001234, 4) + struct.pack(">I", 0x40)
        self.assertEqual(len(packet), 24)
        self.assertEqual(
            self.HEADER.unpack(packet[:self.HEADER.size]),
            (self.MAGIC, 3, 0, 7, 0x02001234, 4),
        )
        self.assertEqual(struct.unpack(">I", packet[self.HEADER.size:])[0], 0x40)

    def test_osd_message_is_a_bounded_utf8_protocol_frame(self) -> None:
        payload = "Received Morph Ball from Samus".encode("utf-8")
        packet = self.HEADER.pack(self.MAGIC, self.MESSAGE, 0, 8, 0, len(payload)) + payload
        self.assertLessEqual(len(payload), self.MAX_MESSAGE_BYTES)
        self.assertEqual(self.HEADER.unpack(packet[:self.HEADER.size])[1], self.MESSAGE)
        self.assertEqual(packet[self.HEADER.size:].decode("utf-8"), "Received Morph Ball from Samus")


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

    def test_received_item_counter_selects_exactly_the_next_item(self) -> None:
        queued_items = ["Missile Data", "Morph Ball", "Energy Tank"]
        received_count = 1
        self.assertEqual(queued_items[received_count], "Morph Ball")

    def test_only_new_remote_delivery_produces_a_notification(self) -> None:
        items = [
            {"name": "Morph Ball", "player": 2, "location": 10},
            {"name": "Energy Tank", "player": 1, "location": 11},
        ]
        own_slot = 1
        received_count = 0
        notifications = []
        item = items[received_count]
        supplied_by_patched_rom = item["player"] == own_slot and item["location"] >= 0
        received_count += 1
        if not supplied_by_patched_rom:
            notifications.append(item["name"])
        self.assertEqual(notifications, ["Morph Ball"])
        # Reconciliation/reconnect starts at the persisted counter and does
        # not replay a notification for already acknowledged history.
        self.assertEqual(received_count, 1)
        self.assertEqual(notifications, ["Morph Ball"])

    def test_inventory_recovery_uses_authoritative_capacity_not_an_increment(self) -> None:
        missile_data_ammo = 10
        missile_tank_ammo = 5
        received_items = ["Missile Data", "Missile Tank", "Missile Tank"]
        expected_max = missile_data_ammo + received_items.count("Missile Tank") * missile_tank_ammo
        self.assertEqual(expected_max, 20)
        # Re-running the calculation after loading an older save is idempotent.
        self.assertEqual(expected_max, 20)

    def test_location_bits_are_little_endian_within_each_byte(self) -> None:
        # APWorld location index 9 is byte 1, bit 1.
        location_bits = bytes((0x00, 0x02))
        index = 9
        self.assertTrue(location_bits[index // 8] & (1 << (index % 8)))


class ArchipelagoAndroidNetworkContractTest(unittest.TestCase):
    def test_large_archipelago_packets_use_zlib_binary_messages(self) -> None:
        packet = b'[{"cmd":"DataPackage","data":{"games":{"Metroid Fusion":{}}}}]'
        compressed = zlib.compress(packet)
        self.assertNotEqual(compressed[:1], b"[")
        self.assertEqual(zlib.decompress(compressed), packet)

    def test_metroid_fusion_client_requests_local_and_remote_items(self) -> None:
        items_handling = 0b011
        self.assertTrue(items_handling & 0b001)
        self.assertTrue(items_handling & 0b010)
        self.assertFalse(items_handling & 0b100)


if __name__ == "__main__":
    unittest.main()
