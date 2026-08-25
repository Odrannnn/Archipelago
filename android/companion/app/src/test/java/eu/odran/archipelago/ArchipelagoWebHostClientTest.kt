package eu.odran.archipelago

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchipelagoWebHostClientTest {
    @Test
    fun hiddenHostedRoomsStayOutOfRefreshedResults() {
        val visible = room("visible-room-1234")
        val removed = room("removed-room-1234")

        assertEquals(
            listOf(visible),
            visibleHostedRooms(listOf(visible, removed), setOf(removed.roomId)),
        )
    }

    @Test
    fun clearingHiddenRoomsRestoresAllResults() {
        val rooms = listOf(room("first-room-123456"), room("second-room-12345"))

        assertEquals(rooms, visibleHostedRooms(rooms, emptySet()))
    }

    @Test
    fun hostedRoomsAreNewestFirstWithRoomIdAsTieBreaker() {
        val older = room("older-room-123456", "Tue, 18 Aug 2026 14:00:00 GMT")
        val newestB = room("newest-b-room-1234", "Wed, 19 Aug 2026 14:00:00 GMT")
        val newestA = room("newest-a-room-1234", "Wed, 19 Aug 2026 14:00:00 GMT")

        assertEquals(
            listOf(newestA, newestB, older),
            orderedHostedRooms(listOf(older, newestB, newestA)),
        )
    }

    @Test
    fun roomsWithoutAValidCreationTimeFollowDatedRoomsInRoomIdOrder() {
        val dated = room("dated-room-123456", "Wed, 19 Aug 2026 14:00:00 GMT")
        val unknownB = room("unknown-b-room-123", "null")
        val unknownA = room("unknown-a-room-123", "")

        assertEquals(
            listOf(dated, unknownA, unknownB),
            orderedHostedRooms(listOf(unknownB, dated, unknownA)),
        )
    }

    @Test
    fun refreshedRuntimeDetailsPreserveCachedSeedMetadata() {
        val cached = room("remembered-room-12", "Wed, 19 Aug 2026 14:00:00 GMT")
        val refreshed = cached.copy(
            seedId = "",
            creationTime = "",
            lastPort = 41_000,
            trackerId = "new-tracker",
        )

        assertEquals(
            refreshed.copy(seedId = cached.seedId, creationTime = cached.creationTime),
            mergeHostedRoom(cached, refreshed),
        )
    }

    @Test
    fun rememberedInviteRoomsSurviveWebsiteSessionRefresh() {
        val website = room("website-room-1234")
        val remembered = room("invite-room-123456")
        val staleWebsite = room("deleted-room-12345")

        assertEquals(
            orderedHostedRooms(listOf(website, remembered)),
            mergeHostedRoomLists(
                websiteRooms = listOf(website),
                rememberedRoomIds = setOf(remembered.roomId),
                cachedRooms = listOf(remembered, staleWebsite),
            ),
        )
    }

    private fun room(id: String, creationTime: String = "") = HostedRoom(
        roomId = id,
        seedId = "seed-$id",
        creationTime = creationTime,
        lastActivity = "",
        lastPort = 0,
        timeoutSeconds = 0,
        trackerId = "",
        players = emptyList(),
    )
}
