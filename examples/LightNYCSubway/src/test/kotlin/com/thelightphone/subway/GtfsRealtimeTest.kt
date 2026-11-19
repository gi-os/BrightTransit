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
    fun tellsATrainOnItsWayFromOneStillOnTheTimetable() {
        // The L's feed says so with a one or a zero.
        assertEquals(14, Fixtures.lFeed.trips.count { it.assigned })
        assertEquals(9, Fixtures.lFeed.trips.count { !it.assigned })
        // The other feeds say so with a one or with nothing.
        val day = Fixtures.weekdayAfternoon.feeds
        assertEquals(820, day.values.sumOf { it.trips.size })
        assertEquals(456, day.values.sumOf { feed -> feed.trips.count { it.assigned } })
        assertEquals(188, day.getValue("gtfs").trips.count { it.assigned })
        assertEquals(113, day.getValue("gtfs").trips.count { !it.assigned })
    }

    @Test
    fun readsEachTripsIdWhichIsHowATrainIsFoundAgain() {
        val ids = Fixtures.lFeed.trips.map { it.id }
        assertEquals(23, ids.toSet().size)
        assertTrue(ids.all { Regex("\\d{6}_L\\.\\.[NS]").matches(it) }, ids.toString())
        // In all eight feeds every trip has one, and no two trips of a feed share it.
        Fixtures.weekdayAfternoon.feeds.forEach { (slug, feed) ->
            assertTrue(feed.trips.all { it.id.isNotEmpty() }, slug)
            assertEquals(feed.trips.size, feed.trips.map { it.id }.toSet().size, slug)
        }
    }

    @Test
    fun readsWhereTheFeedLastSawEachTrain() {
        val feed = Fixtures.lFeed
        // The L's feed has a position for each train on the move and none for a timetable slot.
        assertEquals(feed.trips.filter { it.assigned }.map { it.id }.toSet(), feed.positions.keys)
        assertEquals(14, feed.positions.size)
        // Standing at Jefferson St, seen eight seconds before the feed was built.
        assertEquals(GtfsRealtime.Position("L15N", GtfsRealtime.Status.STOPPED, feed.timestamp - 8), feed.positions["061050_L..N"])
        // On its way to 1 Av.
        assertEquals(GtfsRealtime.Position("L06N", GtfsRealtime.Status.MOVING, feed.timestamp - 19), feed.positions["060250_L..N"])
        assertEquals(setOf(GtfsRealtime.Status.STOPPED, GtfsRealtime.Status.MOVING), feed.positions.values.map { it.status }.toSet())
    }

    @Test
    fun aPositionCanSayComingInOrSayNothingOfHowTheTrainStands() {
        // The numbered lines send "coming in", and leave the status out for a train on its way.
        val statuses = Fixtures.oneCutShort.feeds.mapNotNull { it.positions.values.singleOrNull()?.status }
        val unsaid = Fixtures.oneCutShort.feeds.count { feed -> feed.positions.values.any { it.status == null } }
        assertEquals(setOf(GtfsRealtime.Status.STOPPED, GtfsRealtime.Status.INCOMING), statuses.toSet())
        assertEquals(6, unsaid)
        // A status the tool has never heard of is no status.
        val odd = GtfsRealtime.Feed(100, listOf(GtfsRealtime.Trip("L", listOf(GtfsRealtime.StopTime("L14N", 160)), id = "a")))
        val bytes = TrainFeeds.bytes(odd.copy(positions = mapOf("a" to GtfsRealtime.Position("L14N", GtfsRealtime.Status.MOVING, 95))))
        val patched = bytes.copyOf().also { copy ->
            // The status is the one byte after its tag (field 4, a number), and 2 is "on its way".
            val at = (0 until copy.size - 1).last { copy[it] == (4 shl 3).toByte() && copy[it + 1] == 2.toByte() }
            copy[at + 1] = 9
        }
        assertEquals(GtfsRealtime.Position("L14N", null, 95), GtfsRealtime.parse(patched).positions["a"])
    }

    @Test
    fun aPositionThatNamesNoTripOrNoStopIsPassedOver() {
        val trip = GtfsRealtime.Trip("L", listOf(GtfsRealtime.StopTime("L14N", 160)), id = "a")
        val nowhere = GtfsRealtime.Feed(100, listOf(trip), mapOf("a" to GtfsRealtime.Position("", GtfsRealtime.Status.STOPPED, 95)))
        assertTrue(GtfsRealtime.parse(TrainFeeds.bytes(nowhere)).positions.isEmpty())
        val nobody = GtfsRealtime.Feed(100, listOf(trip), mapOf("" to GtfsRealtime.Position("L14N", GtfsRealtime.Status.STOPPED, 95)))
        assertTrue(GtfsRealtime.parse(TrainFeeds.bytes(nobody)).positions.isEmpty())
        // The first snapshot was cut down to trips alone, and reads as feeds with no positions.
        assertTrue(Fixtures.weekdayAfternoon.feeds.values.all { it.positions.isEmpty() })
        // The second kept them: every train on the move has one.
        Fixtures.weekdayMidday.feeds.forEach { (slug, feed) ->
            val trains = feed.trips.filter { it.assigned && slug != "gtfs" }
            assertTrue(feed.positions.isNotEmpty() && trains.all { it.id in feed.positions }, slug)
        }
    }

    @Test
    fun aStopWithATimeToLeaveAndNoneToArriveIsReadAsOne() {
        // On the numbered lines that is the stop a train's run starts at, until the train has moved.
        val shuttle = Fixtures.shuttleStarting.feeds[4].trips.single()
        assertEquals(listOf(true, false), shuttle.stops.map { it.departsOnly })
        assertEquals(listOf("901N", "902N"), shuttle.stops.map { it.stopId })
        // Under way, its next stop has a time to arrive.
        assertEquals(listOf(false), Fixtures.shuttleStarting.feeds[14].trips.single().stops.map { it.departsOnly })
        // The lettered lines give every stop both times.
        assertTrue(Fixtures.weekdayMidday.feeds.getValue("gtfs-nqrw").trips.none { trip -> trip.stops.any { it.departsOnly } })
        // And it is written back as it was read.
        val feed = GtfsRealtime.Feed(100, listOf(GtfsRealtime.Trip("L", listOf(GtfsRealtime.StopTime("L14N", 160, departsOnly = true), GtfsRealtime.StopTime("L13N", 250)), id = "a")))
        assertEquals(feed, GtfsRealtime.parse(TrainFeeds.bytes(feed)))
    }

    @Test
    fun aFeedThatDoesNotSayIsTakenAsRunning() {
        // Plain GTFS-realtime, with none of the MTA's extensions: nothing calls the train scheduled.
        val plain = GtfsRealtime.Feed(100, listOf(GtfsRealtime.Trip("L", listOf(GtfsRealtime.StopTime("L14N", 160)))))
        assertTrue(GtfsRealtime.parse(TrainFeeds.bytes(plain)).trips.single().assigned)
        val scheduled = plain.copy(trips = plain.trips.map { it.copy(assigned = false) })
        assertEquals(scheduled, GtfsRealtime.parse(TrainFeeds.bytes(scheduled)))
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
