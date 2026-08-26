package eu.odran.archipelago

/** Keeps the room library compact for short lists and scrollable on smaller screens. */
internal fun roomLibraryHeightDp(roomCount: Int, screenHeightDp: Int): Int {
    if (roomCount <= 0) return 88
    val usableScreenHeight = screenHeightDp.coerceAtLeast(480)
    val maximumHeight = (usableScreenHeight * 0.58f).toInt().coerceIn(320, 680)
    return (roomCount * 320).coerceIn(320, maximumHeight)
}
