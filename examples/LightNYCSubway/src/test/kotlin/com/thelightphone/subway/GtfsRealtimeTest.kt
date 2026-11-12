package com.thelightphone.subway

import com.thelightphone.subway.data.GtfsRealtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GtfsRealtimeTest {

    @Test
    fun readsEveryTripWithItsStopsInOrder() {
        val feed = Fixtures.lFeed
        assertEquals(1794497422L, feed.timestamp)
        assertEquals(23, feed.trips.size)
        assertTrue(feed.trips.all { it.route == "L" })
        feed.trips.forEach { trip ->
            assertTrue(trip.stops.isNotEmpty())
            assertEquals(trip.stops.map { it.time }.sorted(), trip.stops.map { it.time }, "stops run forward in time")
        }
    }

    @Test
    fun aCutOffDownloadIsRejectedNotHalfRead() {
        val bytes = Fixtures.lFeedBytes
        assertFailsWith<GtfsRealtime.BadFeed> { GtfsRealtime.parse(bytes.copyOf(bytes.size - 7)) }
    }

    @Test
    fun aWebPageIsNotAFeed() {
        val portal = "<html><body>Sign in to Wi-Fi</body></html>".toByteArray()
        assertFailsWith<GtfsRealtime.BadFeed> { GtfsRealtime.parse(portal) }
        assertFailsWith<GtfsRealtime.BadFeed> { GtfsRealtime.parse(ByteArray(0)) }
    }
}
