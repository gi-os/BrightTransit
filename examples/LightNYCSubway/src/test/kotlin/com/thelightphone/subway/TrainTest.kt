package com.thelightphone.subway

import com.thelightphone.subway.ByHand.EIGHTH_AV
import com.thelightphone.subway.ByHand.GRAHAM
import com.thelightphone.subway.ByHand.GRAND
import com.thelightphone.subway.ByHand.ID
import com.thelightphone.subway.ByHand.JEFFERSON
import com.thelightphone.subway.ByHand.LINE
import com.thelightphone.subway.ByHand.LORIMER
import com.thelightphone.subway.ByHand.MONTROSE
import com.thelightphone.subway.ByHand.MORGAN
import com.thelightphone.subway.ByHand.SIXTH_AV
import com.thelightphone.subway.ByHand.T0
import com.thelightphone.subway.ByHand.THIRD_AV
import com.thelightphone.subway.ByHand.UNION_SQ
import com.thelightphone.subway.ByHand.feed
import com.thelightphone.subway.ByHand.platforms
import com.thelightphone.subway.ByHand.seen
import com.thelightphone.subway.ByHand.trip
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.RideState
import com.thelightphone.subway.data.Sighting
import com.thelightphone.subway.data.Train
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Following one train from download to download: finding it again, counting its stops, and
 * each way the feeds are known to mislead, one at a time.
 */
class TrainTest {
    private val catalog = Fixtures.catalog

    /** A train first seen leaving Jefferson St, half a minute away. */
    private val leavingJefferson = seen(feed(T0, trip(JEFFERSON, T0 + 30)))

    private fun Train.then(feed: GtfsRealtime.Feed) = after(feed, catalog)

    @Test
    fun theStemOfAnIdIsWhenItSetOutItsLineAndWhichWay() {
        assertEquals("085350_1..N", Train.stem("085350_1..N15R"))
        assertEquals("085350_1..N", Train.stem("085350_1..N"))
        assertEquals("085000_L..S", Train.stem("085000_L..S"))
        assertEquals("092050_7X..N", Train.stem("092050_7X..N"))
        // The shuttles and the railway have a two-letter line and one dot.
        assertEquals("084100_SI.N", Train.stem("084100_SI.N03X020"))
        assertEquals("087300_GS.S", Train.stem("087300_GS.S01R"))
        // Anything that is not shaped like that is its own stem.
        assertEquals("a train", Train.stem("a train"))
        assertEquals("090000_L..", Train.stem("090000_L.."))
        assertEquals("", Train.stem(""))
    }

    @Test
    fun everyTripIdInARealFeedHasAStemShorterThanOrEqualToItself() {
        val ids = Fixtures.weekdayAfternoon.feeds.values.flatMap { feed -> feed.trips.map { it.id } }
        assertEquals(820, ids.size)
        ids.forEach { id ->
            val stem = Train.stem(id)
            assertTrue(id.startsWith(stem) && stem.last() in "NS" && stem.length >= 10, id)
        }
        // The ending comes and goes on the numbered lines, so most ids there are longer than their stem.
        assertTrue(ids.count { Train.stem(it) != it } > 500)
    }

    @Test
    fun aTrainIsReadWithTheStopsItStillHasToMake() {
        assertEquals("gtfs-l", leavingJefferson.feed)
        assertEquals(ID, leavingJefferson.id)
        assertEquals(LINE, leavingJefferson.platforms)
        assertEquals(T0, leavingJefferson.asOf)
        assertEquals(T0 + 30 + 9 * 90, leavingJefferson.stop(UNION_SQ)!!.time)
        assertEquals(EIGHTH_AV, leavingJefferson.lastStop!!.platform)
        assertFalse(leavingJefferson.standing)
        assertTrue(leavingJefferson.assigned)
    }

    @Test
    fun aTripWithNoIdCannotBeFollowed() {
        assertNull(Train.find("gtfs-l", feed(T0, trip(JEFFERSON, T0 + 30, id = "")), "", catalog))
        assertNull(Train.find("gtfs-l", feed(T0, trip(JEFFERSON, T0 + 30)), "091000_L..N", catalog))
    }

    @Test
    fun pointsThatAreNotStationsAreNotCountedAsStops() {
        // R60 and R65 are places a train is timed at on its way, not places it stops.
        val base = trip(JEFFERSON, T0 + 30)
        val padded = base.copy(
            stops = listOf(base.stops[0], GtfsRealtime.StopTime("R60N", T0 + 60)) + base.stops.drop(1) + GtfsRealtime.StopTime("R65N", T0 + 2000),
        )
        val train = seen(feed(T0, padded))
        assertEquals(LINE, train.platforms)
        assertEquals(2, train.place(MONTROSE))
        assertEquals(EIGHTH_AV, train.lastStop!!.platform)
    }

    @Test
    fun theTimingPointsInARealFeedAreLeftOut() {
        // The A to the Rockaways is timed at H19, past Broad Channel, and the Q at points north of 57 St.
        var withPoints = 0
        Fixtures.weekdayAfternoon.feeds.forEach { (slug, feed) ->
            feed.trips.forEach { trip ->
                val stations = trip.stops.filter { catalog.stop(it.stopId.dropLast(1)) != null }
                if (stations.size < trip.stops.size) withPoints++
                val train = Train.of(slug, feed, trip, catalog)
                assertEquals(stations.map { it.stopId }, train.stops.map { it.id }, trip.id)
                assertTrue(train.stops.all { catalog.stopName(it.platform).isNotEmpty() }, trip.id)
            }
        }
        assertTrue(withPoints > 20, "$withPoints trips listed a point that is not a station")
    }

    @Test
    fun theStopsShrinkFromTheFrontAndTheOnesLeftAreRemembered() {
        val atMorgan = leavingJefferson.then(feed(T0 + 60, trip(MORGAN, T0 + 110)))
        assertEquals(LINE.drop(1), atMorgan.platforms)
        assertEquals(listOf(JEFFERSON), atMorgan.behind.map { it.platform })
        assertEquals(T0 + 30, atMorgan.left(JEFFERSON)!!.time)
        assertEquals(T0 + 60, atMorgan.asOf)
        assertEquals(0, atMorgan.changed)
        assertTrue(atMorgan.dropped.isEmpty())

        // Three stops go by between two downloads, as after a tunnel.
        val atLorimer = atMorgan.then(feed(T0 + 400, trip(LORIMER, T0 + 420)))
        assertEquals(listOf(JEFFERSON, MORGAN, MONTROSE, GRAND, GRAHAM), atLorimer.behind.map { it.platform })
        assertEquals(0, atLorimer.changed)
        assertEquals(GRAHAM, atLorimer.before(LORIMER)!!.platform)
        assertEquals(MORGAN, atLorimer.before(MONTROSE)!!.platform)
    }

    @Test
    fun aStopCannotHaveBeenLeftLaterThanTheFeedThatNoLongerListsIt() {
        // Due at Jefferson St in five minutes, and gone from the list a minute later: the time was wrong.
        val early = seen(feed(T0, trip(JEFFERSON, T0 + 300))).then(feed(T0 + 60, trip(MORGAN, T0 + 100)))
        assertEquals(T0 + 60, early.left(JEFFERSON)!!.time)
    }

    @Test
    fun aTrainIsFoundAgainWhenTheEndOfItsIdDropsOff() {
        val named = seen(feed(T0, trip(JEFFERSON, T0 + 30, id = "090000_L..N12R")), "090000_L..N12R")
        val shorter = named.then(feed(T0 + 60, trip(MORGAN, T0 + 110, id = "090000_L..N")))
        assertEquals("090000_L..N", shorter.id)
        assertEquals(T0 + 60, shorter.asOf)
        assertEquals(LINE.drop(1), shorter.platforms)
        // And back again.
        assertEquals("090000_L..N12R", shorter.then(feed(T0 + 120, trip(MONTROSE, T0 + 150, id = "090000_L..N12R"))).id)
    }

    @Test
    fun theWholeIdIsPreferredToTheStem() {
        val named = seen(feed(T0, trip(JEFFERSON, T0 + 30, id = "090000_L..N12R")), "090000_L..N12R")
        val both = feed(T0 + 60, trip(GRAHAM, T0 + 100, id = "090000_L..N07R"), trip(MORGAN, T0 + 110, id = "090000_L..N12R"))
        assertEquals(LINE.drop(1), named.then(both).platforms)
    }

    @Test
    fun aTimetableSlotThatSharesTheStemIsNotTakenForTheTrain() {
        // On the numbered lines a train and a slot can differ only in the ending of the id.
        val named = seen(feed(T0, trip(JEFFERSON, T0 + 30, id = "090000_L..N12R")), "090000_L..N12R")
        val onlyTheSlot = named.then(feed(T0 + 60, trip(JEFFERSON, T0 + 900, id = "090000_L..N07R", assigned = false)))
        assertEquals(T0, onlyTheSlot.asOf)
        assertEquals(T0 + 60, onlyTheSlot.checked)
        assertEquals("090000_L..N12R", onlyTheSlot.id)

        // A slot that is being followed can become a train under another ending.
        val slot = seen(feed(T0, trip(JEFFERSON, T0 + 900, id = "090000_L..N07R", assigned = false)), "090000_L..N07R")
        assertFalse(slot.assigned)
        val running = slot.then(feed(T0 + 60, trip(JEFFERSON, T0 + 880, id = "090000_L..N")))
        assertTrue(running.assigned)
        assertEquals(T0 + 60, running.asOf)
    }

    @Test
    fun anotherTrainWithTheSameStemIsNotTakenForThisOne() {
        // Two trains set out in the same minute the same way, and differ only in the ending of the id.
        val mine = trip(SIXTH_AV, T0 + 20, id = "090000_L..N01R")
        val other = trip(JEFFERSON, T0 + 30, id = "090000_L..N06R")
        val followed = seen(feed(T0, mine, other), "090000_L..N01R")
        // Mine finishes and leaves the feed. The other is then the only one with the stem.
        val finished = followed.then(feed(T0 + 60, trip(MORGAN, T0 + 100, id = "090000_L..N06R")))
        assertEquals("090000_L..N01R", finished.id)
        assertTrue(finished.ended)
        assertTrue(finished.dropped.isEmpty())
        assertEquals(listOf(SIXTH_AV, EIGHTH_AV), finished.platforms)
        // In the middle of its run, one download without mine does not hand the ride to the other train.
        val midRun = seen(feed(T0, trip(MORGAN, T0 + 30, id = "090000_L..N01R"), other), "090000_L..N01R")
        val missed = midRun.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70, id = "090000_L..N06R")))
        assertEquals("090000_L..N01R", missed.id)
        assertEquals(midRun.stops, missed.stops)
        assertFalse(missed.lost)
        val back = missed.then(feed(T0 + 120, trip(MONTROSE, T0 + 130, id = "090000_L..N01R"), trip(JEFFERSON, T0 + 125, id = "090000_L..N06R")))
        assertEquals("090000_L..N01R", back.id)
        assertEquals(LINE.drop(2), back.platforms)
        // An id with its ending dropped is this train only while it still makes one of this train's stops.
        val elsewhere = GtfsRealtime.Trip("L", listOf(GtfsRealtime.StopTime("G29N", T0 + 100)), id = "090000_L..N")
        assertEquals(T0, midRun.then(feed(T0 + 60, elsewhere)).asOf)
        assertTrue(Train.sameTrain("090000_L..N01R", "090000_L..N") && Train.sameTrain("090000_L..N", "090000_L..N01R"))
        assertFalse(Train.sameTrain("090000_L..N01R", "090000_L..N06R"))
        assertFalse(Train.sameTrain("090000_L..N", "091000_L..N"))
        assertTrue(midRun.runsAs("090000_L..N") && !midRun.runsAs("090000_L..N06R"))
    }

    @Test
    fun twoTripsWithTheStemAreNotGuessedBetweenAndAnotherLineIsNotIt() {
        val named = seen(feed(T0, trip(JEFFERSON, T0 + 30, id = "090000_L..N12R")), "090000_L..N12R")
        val two = named.then(feed(T0 + 60, trip(MORGAN, T0 + 110, id = "090000_L..N07R"), trip(MORGAN, T0 + 115, id = "090000_L..N")))
        assertEquals(T0, two.asOf)
        // The W rides under the N's ids. The same stem on another line is another train.
        val other = named.then(feed(T0 + 60, trip(MORGAN, T0 + 110, id = "090000_L..N", route = "G")))
        assertEquals(T0, other.asOf)
    }

    @Test
    fun oneMissedDownloadIsRiddenThroughAndASecondIsNot() {
        val other = trip(JEFFERSON, T0 + 600, id = "091000_L..N")
        val missed = leavingJefferson.then(feed(T0 + 60, other))
        assertEquals(LINE, missed.platforms)
        assertEquals(T0, missed.asOf)
        assertEquals(T0 + 60, missed.checked)
        assertFalse(missed.lost)
        assertFalse(missed.ended)

        val missedTwice = missed.then(feed(T0 + 120, other))
        assertTrue(missedTwice.lost)
        assertIs<Sighting.Lost>(missedTwice.seenFrom(MORGAN, T0 + 120))

        // It can still come back, and is then a train like any other.
        val back = missedTwice.then(feed(T0 + 180, other, trip(MONTROSE, T0 + 200)))
        assertFalse(back.lost)
        assertEquals(T0 + 180, back.asOf)
        assertEquals(listOf(JEFFERSON, MORGAN), back.behind.map { it.platform })
    }

    @Test
    fun aTrainMissingAtHalfMinuteDownloadsIsLostOnTheThird() {
        val other = trip(JEFFERSON, T0 + 600, id = "091000_L..N")
        var train = leavingJefferson
        val lost = listOf(30L, 60L, 90L).map { later ->
            train = train.then(feed(T0 + later, other))
            train.lost
        }
        assertEquals(listOf(false, false, true), lost)
    }

    @Test
    fun aTrainThatLeavesTheFeedWithTwoStopsToGoHasEndedItsRun() {
        // The L's feed lets go of a train as it leaves 6 Av, with 8 Av still listed.
        val nearlyThere = seen(feed(T0, trip(SIXTH_AV, T0 + 20)))
        val gone = nearlyThere.then(feed(T0 + 60))
        assertTrue(gone.ended)
        assertFalse(gone.lost)
        assertEquals(listOf(SIXTH_AV, EIGHTH_AV), gone.platforms)
        // However long it stays gone, it is not lost: it finished.
        assertFalse(gone.then(feed(T0 + 600)).lost)
        // Its last stops are reckoned from their times at once, with no wait for the list to grow old.
        assertEquals(Train.Grade.RECKONED, gone.grade(T0 + 60))
        assertEquals(listOf(EIGHTH_AV), gone.ahead(T0 + 60).map { it.platform })
    }

    @Test
    fun aTrainThatLeavesTheFeedThreeMinutesFromItsLastStopHasEndedToo() {
        val threeStops = seen(feed(T0, trip(UNION_SQ, T0 + 20, every = 60)))
        assertEquals(3, threeStops.stops.size)
        assertTrue(threeStops.then(feed(T0 + 60)).ended)
        // With a long way still to go it is missing, not finished.
        assertFalse(leavingJefferson.then(feed(T0 + 60)).ended)
        // A timetable slot taken out of the feed never ran, however short its list was.
        val slot = seen(feed(T0, trip(SIXTH_AV, T0 + 20, assigned = false))).then(feed(T0 + 60)).then(feed(T0 + 120))
        assertFalse(slot.ended)
        assertTrue(slot.lost)
    }

    @Test
    fun aTrainLostInTheMiddleOfItsRunStaysLostWhenTheClockComesRoundToItsOldLastTime() {
        // Jefferson St at +30 and 8 Av, eleven stops on, at +1020. It leaves the feed with all of that to go.
        var train = leavingJefferson
        val said = (1..40).map { round ->
            train = train.then(feed(T0 + round * 30L))
            Triple(train.lost, train.ended, train.grade(T0 + round * 30L))
        }
        assertEquals(listOf(false, false), said.take(2).map { it.first })
        assertTrue(said.drop(2).all { it.first && !it.second })
        // Past five minutes it is unknown and stays so: nothing is reckoned from it again.
        assertTrue(said.drop(10).all { it.third == Train.Grade.UNKNOWN })
        assertIs<Sighting.Lost>(train.seenFrom(EIGHTH_AV, T0 + 1200))
        // A ride picked up after a long time away is not taken for finished either, unless it was all but done.
        assertFalse(leavingJefferson.then(feed(T0 + 3600)).ended)
        assertTrue(seen(feed(T0, trip(SIXTH_AV, T0 + 20))).then(feed(T0 + 3600)).ended)
    }

    @Test
    fun aTrainMissedWhenItsTimesHadAllGoneByHadRunOutAndOneMissedMidRunHadNot() {
        // Watched as it went, with stops and times still ahead: it did not finish, whatever the clock says later.
        var watched = leavingJefferson
        (1..40).forEach { watched = watched.then(feed(T0 + it * 30L)) }
        assertFalse(watched.ranOut)
        assertEquals(Sighting.Lost(ranOut = false), watched.seenFrom(MORGAN, T0 + 1200))
        // Looked at again half an hour on, and gone: every time it had is past.
        val unseen = leavingJefferson.then(feed(T0 + 1800)).then(feed(T0 + 1830))
        assertTrue(unseen.lost && unseen.ranOut)
        assertEquals(Sighting.Lost(ranOut = true), unseen.seenFrom(MORGAN, T0 + 1830))
        // A trip with no train, covered by another screen while it got one, ran and finished.
        val slot = seen(feed(T0, trip(SIXTH_AV, T0 + 20, assigned = false))).then(feed(T0 + 600)).then(feed(T0 + 630))
        assertFalse(slot.ended)
        assertEquals(Sighting.Lost(ranOut = true), slot.seenFrom(SIXTH_AV, T0 + 630))
        // Found again, it is a train like any other.
        assertFalse(unseen.then(feed(T0 + 1860, trip(EIGHTH_AV, T0 + 1900))).ranOut)
    }

    @Test
    fun aStopTheTrainHasLeftCanComeBackAndIsNotCountedAgain() {
        val atMorgan = leavingJefferson.then(feed(T0 + 60, trip(MORGAN, T0 + 110)))
        // The lettered lines and the G do this for a minute: Jefferson St is at the front again.
        val flapped = atMorgan.then(feed(T0 + 120, trip(JEFFERSON, T0 + 115), standing = mapOf(ID to JEFFERSON)))
        assertEquals(LINE.drop(1), flapped.platforms)
        assertEquals(listOf(JEFFERSON), flapped.behind.map { it.platform })
        assertEquals(0, flapped.changed)
        assertTrue(flapped.dropped.isEmpty())
        // The train was seen at the stop that came back, which is not the one it is counted from.
        assertFalse(flapped.standing)
        assertEquals(atMorgan.place(UNION_SQ), flapped.place(UNION_SQ))
        // Gone again in the next download, it was never given back.
        val on = flapped.then(feed(T0 + 180, trip(MORGAN, T0 + 185), standing = mapOf(ID to MORGAN)))
        assertEquals(listOf(JEFFERSON), on.behind.map { it.platform })
        assertEquals(0, on.changed)
    }

    @Test
    fun aStopListedAtTheFrontAgainTwiceRunningIsGivenBackAndTheChangeIsDated() {
        // The feed took Morgan Av off the list before the train got there, as it does on its timetable's say-so.
        val early = leavingJefferson.then(feed(T0 + 60, trip(MORGAN, T0 + 110))).then(feed(T0 + 120, trip(MONTROSE, T0 + 200)))
        assertEquals(listOf(JEFFERSON, MORGAN), early.behind.map { it.platform })
        assertIs<Sighting.Left>(early.seenFrom(MORGAN, T0 + 120))
        // Once is a flap, and it stays left.
        val once = early.then(feed(T0 + 180, trip(MORGAN, T0 + 175), standing = mapOf(ID to MORGAN)))
        assertEquals(listOf(JEFFERSON, MORGAN), once.behind.map { it.platform })
        assertEquals(LINE.drop(2), once.platforms)
        assertEquals(0, once.changed)
        // Twice running, the train is at Morgan Av after all.
        val twice = once.then(feed(T0 + 240, trip(MORGAN, T0 + 235), standing = mapOf(ID to MORGAN)))
        assertEquals(listOf(JEFFERSON), twice.behind.map { it.platform })
        assertEquals(LINE.drop(1), twice.platforms)
        assertTrue(twice.standing)
        assertEquals(T0 + 240, twice.changed)
        assertEquals(0, assertIs<Sighting.Coming>(twice.seenFrom(MORGAN, T0 + 240)).stopsAway)
        // And when it does leave, it has left like any other.
        val gone = twice.then(feed(T0 + 300, trip(MONTROSE, T0 + 330)))
        assertEquals(listOf(JEFFERSON, MORGAN), gone.behind.map { it.platform })
        // A train with every stop behind it is not brought back by a record that lingers.
        val over = seen(feed(T0, trip(SIXTH_AV, T0 + 20))).then(feed(T0 + 60, trip(EIGHTH_AV, T0 + 80)))
            .then(feed(T0 + 120, trip(SIXTH_AV, T0 + 119, skipping = setOf(EIGHTH_AV))))
            .then(feed(T0 + 180, trip(SIXTH_AV, T0 + 179, skipping = setOf(EIGHTH_AV))))
        assertTrue(over.ended && over.stops.isEmpty())
    }

    @Test
    fun aListHandedBackWithStopsGoneFromItDropsThemAndIsNotForgotten() {
        // Morgan Av came off the list too soon, and is first on it again twice running. The second time the list
        // has lost Montrose Av, Grand St and Graham Av as well: the train will run from Morgan Av to Lorimer St.
        val early = leavingJefferson.then(feed(T0 + 60, trip(MORGAN, T0 + 110))).then(feed(T0 + 120, trip(MONTROSE, T0 + 200)))
        val once = early.then(feed(T0 + 180, trip(MORGAN, T0 + 175), standing = mapOf(ID to MORGAN)))
        val cut = setOf(MONTROSE, GRAND, GRAHAM)
        val back = once.then(feed(T0 + 240, trip(MORGAN, T0 + 235, skipping = cut), standing = mapOf(ID to MORGAN)))
        assertEquals(LINE.drop(1) - cut, back.platforms)
        assertEquals(listOf(JEFFERSON), back.behind.map { it.platform })
        assertEquals(listOf(MONTROSE, GRAND, GRAHAM), back.dropped.map { it.platform })
        assertEquals(T0 + 240, back.changed)
        // Every stop it ever listed is still ahead, behind or dropped.
        LINE.forEach { platform -> assertTrue(back.place(platform) >= 0 || back.left(platform) != null || back.skips(platform) != null, platform) }
        assertIs<Sighting.Skipping>(back.seenFrom(GRAND, T0 + 240))
        // A rider aboard for Grand St, and one waiting there for Union Sq, are both told it no longer stops there.
        assertEquals(RideState.Dropped(GRAND, back.stops.take(2)), Ride(back, JEFFERSON, GRAND).state(T0 + 240))
        assertEquals(RideState.Dropped(GRAND, back.stops.take(2)), Ride(back, GRAND, UNION_SQ).state(T0 + 240))
        // What was dropped before and is listed again is a stop again.
        val restored = back.then(feed(T0 + 300, trip(MORGAN, T0 + 295, skipping = setOf(GRAND)), standing = mapOf(ID to MORGAN)))
        assertEquals(listOf(GRAND), restored.dropped.map { it.platform })
        // A stop that was behind the one given back, and is not on the list that gives it back, is dropped too.
        val further = early.then(feed(T0 + 180, trip(GRAND, T0 + 260)))
        assertEquals(listOf(JEFFERSON, MORGAN, MONTROSE), further.behind.map { it.platform })
        val twice = further
            .then(feed(T0 + 240, trip(MORGAN, T0 + 235, skipping = setOf(MONTROSE)), standing = mapOf(ID to MORGAN)))
            .then(feed(T0 + 300, trip(MORGAN, T0 + 295, skipping = setOf(MONTROSE)), standing = mapOf(ID to MORGAN)))
        assertEquals(listOf(JEFFERSON), twice.behind.map { it.platform })
        assertEquals(listOf(MONTROSE), twice.dropped.map { it.platform })
        assertEquals(LINE.drop(1) - MONTROSE, twice.platforms)
    }

    @Test
    fun stopsTakenAwayUnderAMovingTrainAreDroppedAndTheChangeIsDated() {
        val skipping = leavingJefferson.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70, skipping = setOf(MONTROSE, GRAND, GRAHAM))))
        assertEquals(LINE - setOf(MONTROSE, GRAND, GRAHAM), skipping.platforms)
        assertEquals(listOf(MONTROSE, GRAND, GRAHAM), skipping.dropped.map { it.platform })
        assertEquals(T0 + 60, skipping.changed)
        assertTrue(skipping.behind.isEmpty())
        assertEquals(T0 + 30 + 2 * 90, skipping.skips(MONTROSE)!!.time)
        assertEquals(MORGAN, skipping.before(LORIMER)!!.platform)

        // It runs on with the shorter list, and the date of the change stays.
        val later = skipping.then(feed(T0 + 120, trip(MORGAN, T0 + 130, skipping = setOf(MONTROSE, GRAND, GRAHAM))))
        assertEquals(T0 + 60, later.changed)
        assertEquals(listOf(MONTROSE, GRAND, GRAHAM), later.dropped.map { it.platform })

        // A stop given back is a stop again.
        val restored = later.then(feed(T0 + 180, trip(MORGAN, T0 + 190, skipping = setOf(MONTROSE, GRAND))))
        assertEquals(listOf(MONTROSE, GRAND), restored.dropped.map { it.platform })
        assertEquals(T0 + 180, restored.changed)
        assertEquals(1, restored.place(GRAHAM))
    }

    @Test
    fun aTrainCutShortDropsTheEndOfItsRun() {
        val cut = leavingJefferson.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70, skipping = setOf(UNION_SQ, SIXTH_AV, EIGHTH_AV))))
        assertEquals(THIRD_AV, cut.lastStop!!.platform)
        assertEquals(listOf(UNION_SQ, SIXTH_AV, EIGHTH_AV), cut.dropped.map { it.platform })
        assertEquals(T0 + 60, cut.changed)
    }

    @Test
    fun aTrainSentAnotherWayDropsEveryStopItNoLongerLists() {
        // The G's stops from Metropolitan Av, as an F sent over the G line would list them.
        val elsewhere = GtfsRealtime.Trip("L", listOf("G29N", "G28N", "G26N").mapIndexed { i, stop -> GtfsRealtime.StopTime(stop, T0 + 100 + i * 90L) }, id = ID)
        val rerouted = leavingJefferson.then(feed(T0 + 60, elsewhere))
        assertEquals(listOf("G29", "G28", "G26"), rerouted.platforms)
        assertEquals(LINE, rerouted.dropped.map { it.platform })
        assertTrue(rerouted.behind.isEmpty())
        assertEquals(T0 + 60, rerouted.changed)
    }

    @Test
    fun aStopAddedAheadIsTakenAsListedAndTheChangeIsDated() {
        val express = seen(feed(T0, trip(JEFFERSON, T0 + 30, skipping = setOf(MONTROSE, GRAND))))
        val local = express.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70)))
        assertEquals(LINE, local.platforms)
        assertEquals(T0 + 60, local.changed)
        assertTrue(local.dropped.isEmpty())
    }

    @Test
    fun aTrainSeenStandingIsAtTheFirstOfItsStops() {
        val standing = seen(feed(T0, trip(JEFFERSON, T0 - 10), standing = mapOf(ID to JEFFERSON)))
        assertTrue(standing.standing)
        assertEquals(LINE, standing.platforms)
    }

    @Test
    fun whereTheTrainWasSeenIsNewerThanAListThatStillHasTheStopBefore() {
        // The lettered lines go on listing a stop after the train has been seen at the next one.
        val ahead = seen(feed(T0, trip(JEFFERSON, T0 - 28), standing = mapOf(ID to MORGAN)))
        assertTrue(ahead.standing)
        assertEquals(LINE.drop(1), ahead.platforms)

        // Followed, the stop the list was late to let go of is behind it like any other.
        val followed = leavingJefferson.then(feed(T0 + 60, trip(JEFFERSON, T0 + 32), standing = mapOf(ID to MORGAN)))
        assertEquals(listOf(JEFFERSON), followed.behind.map { it.platform })
        assertEquals(0, followed.changed)
    }

    @Test
    fun aPositionFurtherDownTheListThanTheSecondStopIsNoSighting() {
        // The lettered lines now and then name a stop four down a list whose stops are a second apart.
        val far = seen(feed(T0, trip(JEFFERSON, T0 - 9, every = 1), standing = mapOf(ID to GRAHAM)))
        assertFalse(far.standing)
        assertEquals(LINE, far.platforms)
        assertEquals(0, far.stoodSince)
        // Followed, nothing is filed as left on that word either.
        val followed = leavingJefferson.then(feed(T0 + 60, trip(JEFFERSON, T0 + 51, every = 1), standing = mapOf(ID to GRAHAM)))
        assertTrue(followed.behind.isEmpty())
        assertEquals(LINE, followed.platforms)
        // The stop after the first is as far as a sighting is believed.
        assertEquals(LINE.drop(1), seen(feed(T0, trip(JEFFERSON, T0 - 9, every = 1), standing = mapOf(ID to MORGAN))).platforms)
        assertEquals(LINE, seen(feed(T0, trip(JEFFERSON, T0 - 9, every = 1), standing = mapOf(ID to MONTROSE))).platforms)
    }

    @Test
    fun aPositionDatedAheadOfItsFeedIsAForecastAndNotASighting() {
        val forecast = GtfsRealtime.Position("${MORGAN}N", GtfsRealtime.Status.STOPPED, T0 + 235)
        val train = seen(GtfsRealtime.Feed(T0, listOf(trip(JEFFERSON, T0 + 30)), mapOf(ID to forecast)))
        assertFalse(train.standing)
        assertEquals(LINE, train.platforms)
    }

    @Test
    fun aTrainOnItsWayOrComingInIsNotStanding() {
        listOf(GtfsRealtime.Status.MOVING, GtfsRealtime.Status.INCOMING, null).forEach { status ->
            val position = GtfsRealtime.Position("${JEFFERSON}N", status, T0 - 5)
            assertFalse(seen(GtfsRealtime.Feed(T0, listOf(trip(JEFFERSON, T0 + 30)), mapOf(ID to position))).standing, "$status")
        }
        // Stopped at a stop that is no longer in its list is how the lettered lines say "between stops".
        val between = GtfsRealtime.Position("L16N", GtfsRealtime.Status.STOPPED, T0 - 40)
        assertFalse(seen(GtfsRealtime.Feed(T0, listOf(trip(JEFFERSON, T0 + 30)), mapOf(ID to between))).standing)
    }

    @Test
    fun onceSeenStandingATrainStaysThereUntilTheStopLeavesItsList() {
        val standing = seen(feed(T0, trip(JEFFERSON, T0 - 10), standing = mapOf(ID to JEFFERSON)))
        // The numbered lines' status wavers between stopped and coming in at the same stop.
        val wavering = GtfsRealtime.Position("${JEFFERSON}N", GtfsRealtime.Status.INCOMING, T0 + 55)
        val still = standing.then(GtfsRealtime.Feed(T0 + 60, listOf(trip(JEFFERSON, T0 + 80)), mapOf(ID to wavering)))
        assertTrue(still.standing)
        val moved = still.then(feed(T0 + 120, trip(MORGAN, T0 + 150)))
        assertFalse(moved.standing)
    }

    @Test
    fun theSameFeedAgainOrAnOlderOneChangesNothing() {
        val first = feed(T0, trip(JEFFERSON, T0 + 30))
        val atMorgan = leavingJefferson.then(feed(T0 + 60, trip(MORGAN, T0 + 110)))
        // A download that failed hands back the copy from before it.
        assertSame(atMorgan, atMorgan.then(feed(T0 + 60, trip(MORGAN, T0 + 110))))
        assertSame(atMorgan, atMorgan.then(first))
    }

    @Test
    fun aTrainWithEveryStopBehindItHasEnded() {
        // A finished train can be listed again for a while, with a stop it has already made.
        val over = seen(feed(T0, trip(SIXTH_AV, T0 + 20)))
            .then(feed(T0 + 60, trip(EIGHTH_AV, T0 + 80)))
            .then(feed(T0 + 120, trip(SIXTH_AV, T0 + 119, skipping = setOf(EIGHTH_AV))))
        assertTrue(over.ended)
        assertFalse(over.lost)
        assertEquals(listOf(SIXTH_AV, EIGHTH_AV), over.behind.map { it.platform })
        assertTrue(over.stops.isEmpty())
        assertSame(Sighting.Ended, over.seenFrom(SIXTH_AV, T0 + 120))
        assertSame(Sighting.Ended, over.seenFrom(MORGAN, T0 + 120))
    }

    @Test
    fun aTrainTheFeedLetGoOfIsFinishedOnceItsLastStopsAreReckonedMade() {
        // Out of the feed leaving 6 Av at +20, with 8 Av due at +110.
        val gone = seen(feed(T0, trip(SIXTH_AV, T0 + 20))).then(feed(T0 + 60))
        assertTrue(gone.ended)
        val coming = assertIs<Sighting.Coming>(gone.seenFrom(EIGHTH_AV, T0 + 60))
        assertFalse(coming.sure)
        assertEquals(1, coming.stopsAway)
        assertSame(Sighting.Ended, gone.seenFrom(EIGHTH_AV, T0 + 80))
        assertSame(Sighting.Ended, gone.seenFrom(MORGAN, T0 + 80))
        // The railway's last stop has no time to be reckoned by, so there it goes by how long
        // ago the train was heard of. Only someone asking at that very stop is still told it is coming.
        val untimed = gone.copy(stops = gone.stops.map { if (it.platform == EIGHTH_AV) it.copy(time = 0) else it })
        assertIs<Sighting.Left>(untimed.seenFrom(SIXTH_AV, T0 + 300))
        assertSame(Sighting.Ended, untimed.seenFrom(SIXTH_AV, T0 + 301))
        assertSame(Sighting.Ended, untimed.seenFrom(MORGAN, T0 + 301))
        assertIs<Sighting.Coming>(untimed.seenFrom(EIGHTH_AV, T0 + 3000))
    }

    /** The railway's own stops, taken from a real trip toward St George. */
    private val railway = Fixtures.weekdayAfternoon.feeds.getValue("gtfs-si").trips.first { it.stops.size > 5 }

    @Test
    fun theRailwaysTripsAreTrainsOnlyOnceTheFeedHasAStatusForThem() {
        val at = Fixtures.weekdayAfternoon.feeds.getValue("gtfs-si").timestamp
        assertTrue(railway.assigned)
        fun with(position: GtfsRealtime.Position?) =
            Train.of("gtfs-si", GtfsRealtime.Feed(at, listOf(railway), listOfNotNull(position?.let { railway.id to it }).toMap()), railway, catalog)
        val first = railway.stops.first().stopId
        // Before it sets out, the feed has a position with no status, dated when it is due to leave.
        assertFalse(with(GtfsRealtime.Position(first, null, at + 900)).assigned)
        assertTrue(with(GtfsRealtime.Position(first, GtfsRealtime.Status.INCOMING, at - 6)).assigned)
        assertTrue(with(GtfsRealtime.Position(first, GtfsRealtime.Status.STOPPED, at - 50)).assigned)
        // A feed with no positions at all cannot say, and the trip's own word is taken.
        assertTrue(with(null).assigned)
        // On any other line a position with no status is a train on its way.
        val numbered = GtfsRealtime.Feed(T0, listOf(trip(JEFFERSON, T0 + 30)), mapOf(ID to GtfsRealtime.Position("L15N", null, T0 + 900)))
        assertTrue(seen(numbered).assigned)
    }

    @Test
    fun theRailwaysLastStopHasNoTime() {
        val at = Fixtures.weekdayAfternoon.feeds.getValue("gtfs-si").timestamp
        val train = Train.of("gtfs-si", GtfsRealtime.Feed(at, listOf(railway)), railway, catalog)
        assertEquals(railway.stops.size, train.stops.size)
        assertEquals(0, train.stops.last().time)
        assertTrue(train.stops.dropLast(1).all { it.time > 0 })
        // The same trip on any other feed keeps its last time.
        assertTrue(Train.of("gtfs", GtfsRealtime.Feed(at, listOf(railway)), railway, catalog).stops.last().time > 0)
        // A stop with no time cannot be reckoned past, however long ago the rest were due.
        val soon = train.copy(stops = train.stops.map { if (it.time > 0) it.copy(time = at + 10) else it })
        assertEquals(listOf(train.stops.last()), soon.ahead(at + 100))
    }

    @Test
    fun howFarAListCanBeTrustedGoesByItsAge() {
        assertEquals(Train.Grade.LIVE, leavingJefferson.grade(T0))
        assertEquals(Train.Grade.LIVE, leavingJefferson.grade(T0 + 60))
        assertEquals(Train.Grade.RECKONED, leavingJefferson.grade(T0 + 61))
        assertEquals(Train.Grade.RECKONED, leavingJefferson.grade(T0 + 300))
        assertEquals(Train.Grade.UNKNOWN, leavingJefferson.grade(T0 + 301))
        // A clock set back does not make a list old.
        assertEquals(Train.Grade.LIVE, leavingJefferson.grade(T0 - 500))
    }

    @Test
    fun aLiveListIsTakenAsItIsAndAnOldOneIsReckonedHalfAMinuteEarly() {
        // Live, a stop whose time has passed is still ahead: the train is late, not gone.
        val late = seen(feed(T0, trip(JEFFERSON, T0 - 100)))
        assertEquals(LINE, late.ahead(T0 + 30).map { it.platform })
        // Jefferson St at +30, Morgan Av at +120, Montrose Av at +210.
        assertEquals(LINE.drop(1), leavingJefferson.ahead(T0 + 61).map { it.platform })
        assertEquals(LINE.drop(1), leavingJefferson.ahead(T0 + 89).map { it.platform })
        assertEquals(LINE.drop(2), leavingJefferson.ahead(T0 + 90).map { it.platform })
        assertEquals(LINE.drop(3), leavingJefferson.ahead(T0 + 180).map { it.platform })
    }

    @Test
    fun aTrainIsSeenFromAPlatformItIsComingTo() {
        val coming = assertIs<Sighting.Coming>(leavingJefferson.seenFrom(MONTROSE, T0 + 10))
        assertEquals(3, coming.stopsAway)
        assertEquals(T0 + 210, coming.due)
        assertEquals(JEFFERSON, coming.next.platform)
        assertFalse(coming.standing)
        assertTrue(coming.sure)

        // Standing at Jefferson St, the stops left to Montrose Av are two.
        val standing = seen(feed(T0, trip(JEFFERSON, T0 - 10), standing = mapOf(ID to JEFFERSON)))
        assertEquals(2, assertIs<Sighting.Coming>(standing.seenFrom(MONTROSE, T0)).stopsAway)
        assertEquals(1, assertIs<Sighting.Coming>(standing.seenFrom(MORGAN, T0)).stopsAway)
        val here = assertIs<Sighting.Coming>(standing.seenFrom(JEFFERSON, T0))
        assertEquals(0, here.stopsAway)
        assertTrue(here.standing)
        // The next stop for a train on its way is one stop away, not none.
        assertEquals(1, assertIs<Sighting.Coming>(leavingJefferson.seenFrom(JEFFERSON, T0)).stopsAway)
    }

    @Test
    fun aTripWithNoTrainHasMadeNoStopsWhateverTheFeedDoesToItsList() {
        // The lettered lines take a stop off an empty trip's list as its timetable time goes by.
        val slot = seen(feed(T0, trip(JEFFERSON, T0 + 30, assigned = false)))
        val walked = slot
            .then(feed(T0 + 60, trip(MORGAN, T0 + 120, assigned = false)))
            .then(feed(T0 + 240, trip(GRAND, T0 + 300, assigned = false)))
        assertFalse(walked.assigned)
        assertEquals(LINE, walked.platforms)
        assertTrue(walked.behind.isEmpty() && walked.dropped.isEmpty())
        assertEquals(0, walked.changed)
        assertEquals(T0 + 240, walked.asOf)
        // From the stop it would set out from it is still only scheduled, minutes after it was to go.
        val scheduled = assertIs<Sighting.Scheduled>(walked.seenFrom(JEFFERSON, T0 + 240))
        assertEquals(T0 + 30, scheduled.due)
        assertEquals(JEFFERSON, scheduled.origin.platform)
        assertIs<Sighting.Scheduled>(walked.seenFrom(MORGAN, T0 + 240))
        // The place the feed gives such a trip is the timetable too, and is not a train standing anywhere.
        val placed = seen(feed(T0, trip(JEFFERSON, T0 - 20, assigned = false), standing = mapOf(ID to MORGAN)))
        assertFalse(placed.standing)
        assertEquals(LINE, placed.platforms)

        // Then the feed gives it a train, listed from the top again and seen at Morgan Av.
        val running = walked.then(feed(T0 + 300, trip(JEFFERSON, T0 + 280), standing = mapOf(ID to MORGAN)))
        assertTrue(running.assigned && running.standing)
        assertEquals(LINE.drop(1), running.platforms)
        // Jefferson St is behind a train that now exists. Nobody saw it leave, so no time is put to that.
        assertEquals(listOf(Train.Stop("${JEFFERSON}N", 0)), running.behind)
        assertEquals(Sighting.Left(0, running.stops.first(), sure = true), running.seenFrom(JEFFERSON, T0 + 300))
        assertEquals(0, running.changed)
        assertEquals(1, assertIs<Sighting.Coming>(running.seenFrom(MONTROSE, T0 + 300)).stopsAway)

        // A list that changes in any other way is the timetable changed.
        val changed = slot.then(feed(T0 + 60, trip(JEFFERSON, T0 + 90, assigned = false, skipping = setOf(MORGAN))))
        assertEquals(LINE - MORGAN, changed.platforms)
        assertEquals(listOf(MORGAN), changed.dropped.map { it.platform })
        assertEquals(T0 + 60, changed.changed)
        assertIs<Sighting.Skipping>(changed.seenFrom(MORGAN, T0 + 60))
        // Whatever an older build filed for it, a trip with no train has left nowhere.
        val filed = slot.copy(stops = slot.stops.drop(1), behind = slot.stops.take(1))
        assertIs<Sighting.Scheduled>(filed.seenFrom(JEFFERSON, T0))
    }

    @Test
    fun aTripFirstGivenItsTrainSomeStopsDownItsListHasLeftTheStopsBeforeIt() {
        // The lettered lines walk an empty trip down its timetable and then own up to a train well into its run.
        val slot = seen(feed(T0, trip(JEFFERSON, T0 + 30, assigned = false)))
        val walked = slot
            .then(feed(T0 + 60, trip(MORGAN, T0 + 120, assigned = false)))
            .then(feed(T0 + 240, trip(GRAND, T0 + 300, assigned = false)))
        // The train is first named standing at Graham Av, four stops down, with nothing before it listed.
        val named = walked.then(feed(T0 + 400, trip(GRAHAM, T0 + 390), standing = mapOf(ID to GRAHAM)))
        assertTrue(named.assigned)
        assertEquals(LINE.drop(4), named.platforms)
        assertEquals(listOf(JEFFERSON, MORGAN, MONTROSE, GRAND), named.behind.map { it.platform })
        assertTrue(named.behind.all { it.time == 0L })
        assertTrue(named.dropped.isEmpty())
        assertEquals(0, named.changed)
        assertFalse(named.lost)
        // A rider who was waiting for it to Grand St is told it has left there, not that it has gone missing.
        val ride = Ride(named, MORGAN, GRAND)
        assertEquals(RideState.Past(0, named.stops.first()), ride.state(T0 + 400))
        assertEquals(Sighting.Left(0, named.stops.first(), sure = true), named.seenFrom(GRAND, T0 + 400))
        // With no time for the stop itself, the ride is over ten minutes after the first time known for a stop after it.
        assertFalse(ride.over(T0 + 390 + Ride.OVER_AFTER))
        assertTrue(ride.over(T0 + 390 + Ride.OVER_AFTER + 1))

        // The first list a train is given can start too far down. A stop nobody saw it leave is given back
        // the first time it is listed again, and the change is dated.
        val back = named.then(feed(T0 + 430, trip(MONTROSE, T0 + 425), standing = mapOf(ID to MONTROSE)))
        assertEquals(LINE.drop(2), back.platforms)
        assertEquals(listOf(JEFFERSON, MORGAN), back.behind.map { it.platform })
        assertEquals(T0 + 430, back.changed)
        assertEquals(RideState.Next(T0 + 425 + 90, Train.Where(back.stops.first(), Train.How.THERE, T0 + 425), sure = true), Ride(back, MORGAN, GRAND).state(T0 + 430))
        // One it was seen to leave still has to be listed twice running: see aStopListedAtTheFrontAgainTwiceRunning.
        val on = named.then(feed(T0 + 460, trip(LORIMER, T0 + 500)))
        assertEquals(T0 + 390, on.left(GRAHAM)!!.time)
        val flapped = on.then(feed(T0 + 490, trip(GRAHAM, T0 + 485), standing = mapOf(ID to GRAHAM)))
        assertEquals(LINE.drop(5), flapped.platforms)
        assertEquals(0, flapped.changed)

        // A stop the trip listed further on that the train does not is one it will not make.
        val skipping = walked.then(feed(T0 + 400, trip(GRAHAM, T0 + 390, skipping = setOf(LORIMER))))
        assertEquals(listOf(LORIMER), skipping.dropped.map { it.platform })
        assertEquals(T0 + 400, skipping.changed)
        assertEquals(listOf(JEFFERSON, MORGAN, MONTROSE, GRAND), skipping.behind.map { it.platform })
        // And one the timetable had already taken off the trip stays off the train: it never stopped there.
        val cut = slot.then(feed(T0 + 60, trip(JEFFERSON, T0 + 90, assigned = false, skipping = setOf(MORGAN))))
        val cutTrain = cut.then(feed(T0 + 120, trip(JEFFERSON, T0 + 100, skipping = setOf(MORGAN))))
        assertTrue(cutTrain.assigned)
        assertEquals(listOf(MORGAN), cutTrain.dropped.map { it.platform })
        assertEquals(T0 + 60, cutTrain.changed)
        assertIs<Sighting.Skipping>(cutTrain.seenFrom(MORGAN, T0 + 120))
        assertEquals(RideState.Dropped(MORGAN, cutTrain.stops.take(2)), Ride(cutTrain, MORGAN, UNION_SQ).state(T0 + 120))
        // Given back by the list the train comes with, it is a stop again.
        val whole = cut.then(feed(T0 + 120, trip(JEFFERSON, T0 + 100)))
        assertTrue(whole.dropped.isEmpty())
        assertEquals(T0 + 120, whole.changed)
        // A train named down the list with a time to leave and none to arrive is not waiting where its run starts:
        // the trip listed stops before that one. Named at the top of the list, it is.
        fun leaving(from: String, at: Long) = trip(from, at).let { it.copy(stops = listOf(it.stops.first().copy(departsOnly = true)) + it.stops.drop(1)) }
        assertFalse(walked.then(feed(T0 + 400, leaving(MONTROSE, T0 + 500))).starting)
        assertTrue(slot.then(feed(T0 + 20, leaving(JEFFERSON, T0 + 30))).starting)
        // A train named with no stops left at all has made every one of them.
        val done = walked.then(feed(T0 + 2000, GtfsRealtime.Trip("L", emptyList(), id = ID)))
        assertTrue(done.ended && done.stops.isEmpty())
        assertEquals(LINE, done.behind.map { it.platform })
    }

    @Test
    fun aTrainWhereItsRunStartsHasNotSetOutUntilTheFeedSeesItMove() {
        // On the numbered lines a train waiting to start has no position, and a time to leave with none to arrive.
        fun waiting(at: Long, leaves: Long, assigned: Boolean = true): GtfsRealtime.Feed {
            val base = trip(JEFFERSON, leaves, assigned = assigned)
            return feed(at, base.copy(stops = listOf(base.stops.first().copy(departsOnly = true)) + base.stops.drop(1)))
        }
        val starting = seen(waiting(T0, T0 + 300))
        assertTrue(starting.starting)
        assertFalse(starting.standing)
        assertEquals(Sighting.Starting(leaves = T0 + 300, sure = true), starting.seenFrom(JEFFERSON, T0))
        // Further down the line it is still counted in, and known not to have left.
        val coming = assertIs<Sighting.Coming>(starting.seenFrom(MONTROSE, T0))
        assertEquals(3, coming.stopsAway)
        assertEquals(Train.How.STARTING, coming.where.how)
        assertEquals(JEFFERSON, coming.next.platform)
        // It goes on starting while the feed says the same, and stops when the feed sees it at the platform or gone from it.
        assertTrue(starting.then(waiting(T0 + 30, T0 + 300)).starting)
        val seenThere = starting.then(feed(T0 + 60, trip(JEFFERSON, T0 + 300), standing = mapOf(ID to JEFFERSON)))
        assertFalse(seenThere.starting)
        assertEquals(0, assertIs<Sighting.Coming>(seenThere.seenFrom(JEFFERSON, T0 + 60)).stopsAway)
        val left = starting.then(feed(T0 + 330, trip(MORGAN, T0 + 400)))
        assertFalse(left.starting)
        assertIs<Sighting.Left>(left.seenFrom(JEFFERSON, T0 + 330))
        // A trip with no train is not starting, and neither is a train with an arrival time at its first stop.
        assertFalse(seen(waiting(T0, T0 + 300, assigned = false)).starting)
        assertFalse(leavingJefferson.starting)
        assertEquals(1, assertIs<Sighting.Coming>(leavingJefferson.seenFrom(JEFFERSON, T0)).stopsAway)
        // With no word for a minute, it is still starting until half a minute before it is to leave.
        assertEquals(Sighting.Starting(T0 + 300, sure = false), starting.seenFrom(JEFFERSON, T0 + 200))
    }

    @Test
    fun aTrainStandingWhereItIsNotYetDueOutStaysThereWhenItsListGrowsOld() {
        // Waiting at the end of the line, twenty minutes before it is to leave.
        val waiting = seen(feed(T0, trip(JEFFERSON, T0 + 1200), standing = mapOf(ID to JEFFERSON)))
        assertTrue(waiting.standingAt(T0 + 61))
        assertEquals(LINE, waiting.ahead(T0 + 61).map { it.platform })
        val here = assertIs<Sighting.Coming>(waiting.seenFrom(JEFFERSON, T0 + 61))
        assertEquals(0, here.stopsAway)
        assertFalse(here.sure)
        assertEquals(Train.How.THERE, here.where.how)
        assertEquals(2, assertIs<Sighting.Coming>(waiting.seenFrom(MONTROSE, T0 + 200)).stopsAway)
        // It is not being held: its time has not come.
        assertNull(assertIs<Sighting.Coming>(waiting.seenFrom(MORGAN, T0 + 30)).heldSince)
        // Late at first, and then given a new time to leave that is still ahead: it is waiting again, and not held.
        val late = seen(feed(T0, trip(JEFFERSON, T0 - 10), standing = mapOf(ID to JEFFERSON)))
        val retimed = late.then(feed(T0 + 300, trip(JEFFERSON, T0 + 900), standing = mapOf(ID to JEFFERSON)))
        assertEquals(T0 - 10, retimed.stoodSince)
        assertNull(assertIs<Sighting.Coming>(retimed.seenFrom(MORGAN, T0 + 300)).heldSince)
        // A train standing at a stop it was due to leave is reckoned on as before, at once.
        val due = seen(feed(T0, trip(JEFFERSON, T0 + 20), standing = mapOf(ID to JEFFERSON)))
        assertFalse(due.standingAt(T0 + 61))
        assertEquals(LINE.drop(1), due.ahead(T0 + 61).map { it.platform })
    }

    @Test
    fun aTrainBehindItsTimesWhenLastHeardOfIsNotCountedOnFromThem() {
        // Held at Jefferson St three minutes past its time, with the stops after it listed for times already gone.
        val held = seen(feed(T0, trip(JEFFERSON, T0 - 200), standing = mapOf(ID to JEFFERSON)))
        (61L..300L step 13).forEach { later ->
            assertEquals(LINE, held.ahead(T0 + later).map { it.platform }, "$later s on")
            val seen = assertIs<Sighting.Coming>(held.seenFrom(MONTROSE, T0 + later))
            assertEquals(2, seen.stopsAway)
            assertFalse(seen.sure)
            assertEquals(Train.Where(held.stops.first(), Train.How.WAS_THERE, T0 - 200), seen.where)
            assertTrue(seen.asLastSeen)
        }
        assertEquals(Sighting.Unknown(T0), held.seenFrom(MONTROSE, T0 + 301))
        // Late for its next stop and moving: the same.
        val late = seen(feed(T0, trip(JEFFERSON, T0 - 5)))
        val was = assertIs<Sighting.Coming>(late.seenFrom(MONTROSE, T0 + 200))
        assertEquals(3, was.stopsAway)
        assertEquals(Train.How.WAS_COMING, was.where.how)
        // Standing a little past its time is an ordinary stop, and is counted on from.
        val dwell = seen(feed(T0, trip(JEFFERSON, T0 - 40), standing = mapOf(ID to JEFFERSON)))
        assertEquals(LINE.drop(2), dwell.ahead(T0 + 61).map { it.platform })
        // Standing past the time of the stop after is not.
        val stuck = seen(feed(T0, trip(JEFFERSON, T0 - 40, every = 30), standing = mapOf(ID to JEFFERSON)))
        assertEquals(LINE, stuck.ahead(T0 + 200).map { it.platform })
    }

    @Test
    fun onTheLetteredLinesATrainIsOnlySaidToBeAtAStopForItsFirstMinuteThere() {
        // Every train on the J is "stopped" in every download, with its stop's time moved on each time.
        fun atJefferson(at: Long, time: Long, slug: String = "gtfs-jz") =
            Train.find(slug, feed(at, trip(JEFFERSON, time), standing = mapOf(ID to JEFFERSON)), ID, catalog)!!
        val arrived = atJefferson(T0, T0 - 10)
        assertEquals(T0 - 10, arrived.stoodSince)
        assertEquals(Train.How.THERE, arrived.whereAt(T0 + 20)!!.how)
        assertEquals(Train.How.THERE, arrived.whereAt(T0 + 50)!!.how)
        assertEquals(Train.Where(arrived.stops.first(), Train.How.REACHED, T0 - 10), arrived.whereAt(T0 + 51))
        // The next download dates the stop later. The train has still been there since it was first seen there.
        val later = arrived.then(feed(T0 + 30, trip(JEFFERSON, T0 + 22), standing = mapOf(ID to JEFFERSON)))
        assertEquals(T0 - 10, later.stoodSince)
        assertEquals(Train.How.REACHED, later.whereAt(T0 + 60)!!.how)
        // It is still counted as at the stop: the count does not move.
        assertEquals(1, assertIs<Sighting.Coming>(later.seenFrom(MORGAN, T0 + 60)).stopsAway)
        // At the next stop it is "at" again.
        val next = later.then(feed(T0 + 90, trip(MORGAN, T0 + 85), standing = mapOf(ID to MORGAN)))
        assertEquals(T0 + 85, next.stoodSince)
        assertEquals(Train.How.THERE, next.whereAt(T0 + 95)!!.how)
        // The same on each of the feeds that call every train stopped: the lettered lines and the G.
        listOf("gtfs-ace", "gtfs-bdfm", "gtfs-g", "gtfs-jz", "gtfs-nqrw").forEach { slug ->
            assertEquals(Train.How.THERE, atJefferson(T0, T0 - 10, slug).whereAt(T0 + 50)!!.how, slug)
            assertEquals(Train.How.REACHED, atJefferson(T0, T0 - 10, slug).whereAt(T0 + 51)!!.how, slug)
        }
        // Where the feed says when a train is stopped and when it is not, it is believed for as long as it says so.
        listOf("gtfs", "gtfs-l", "gtfs-si").forEach { slug ->
            assertEquals(Train.How.THERE, atJefferson(T0, T0 - 10, slug).whereAt(T0 + 55)!!.how, slug)
        }
    }

    @Test
    fun onTheLetteredLinesAHoldGoesByHowLongTheTrainHasBeenAtItsStop() {
        // Four minutes at one stop, the feed dating the stop a few seconds back every time.
        var train = Train.find("gtfs-jz", feed(T0, trip(JEFFERSON, T0 - 10), standing = mapOf(ID to JEFFERSON)), ID, catalog)!!
        val held = (1..10).map { round ->
            val at = T0 + round * 30L
            train = train.then(feed(at, trip(JEFFERSON, at - 12), standing = mapOf(ID to JEFFERSON)))
            assertIs<Sighting.Coming>(train.seenFrom(MORGAN, at)).heldSince
        }
        assertEquals(List(7) { null } + List(3) { T0 - 10 }, held)
        // Held when last heard of, it is not counted on from its times.
        assertEquals(LINE, train.ahead(T0 + 400).map { it.platform })
        assertEquals(Train.How.WAS_THERE, train.whereAt(T0 + 400)!!.how)
    }

    @Test
    fun aHoldIsWhatTheLastWordOfTheTrainSaidAndNeverWhatTheClockHasMadeOfItSince() {
        // On the J, three and a half minutes at Jefferson St and then four, the stop dated a few seconds back each time.
        var train = Train.find("gtfs-jz", feed(T0, trip(JEFFERSON, T0 - 10), standing = mapOf(ID to JEFFERSON)), ID, catalog)!!
        val copies = (1..8).map { round ->
            val at = T0 + round * 30L
            train.then(feed(at, trip(JEFFERSON, at - 12), standing = mapOf(ID to JEFFERSON))).also { train = it }
        }
        val (short, long) = copies.takeLast(2)
        assertEquals(T0 + 210 to T0 + 240, short.asOf to long.asOf)
        // Not yet four minutes there when last heard of: the clock running on does not make it held.
        (0L..300L).forEach { later -> assertFalse(short.whereAt(short.asOf + later)!!.how == Train.How.HELD, "$later s on") }
        // So it is counted on from its times like any train standing at a stop, and was never said to be held first.
        assertEquals(Train.How.REACHED, short.whereAt(short.asOf + 60)!!.how)
        assertEquals(LINE.drop(2), short.ahead(short.asOf + 100).map { it.platform })
        // Four minutes there when last heard of: held while that is news, and left where it was after.
        (0L..300L).forEach { later ->
            val where = long.whereAt(long.asOf + later)!!
            assertEquals(if (later <= 60) Train.How.HELD else Train.How.WAS_THERE, where.how, "$later s on")
            assertEquals(T0 - 10, where.since)
            assertEquals(LINE, long.ahead(long.asOf + later).map { it.platform })
        }
        // On every copy: once the screen has called a train held, nothing but new word of it takes that back.
        (copies + seen(feed(T0, trip(JEFFERSON, T0 - 100), standing = mapOf(ID to JEFFERSON)))).forEach { copy ->
            var held = false
            (0L..300L).forEach { later ->
                val how = copy.whereAt(copy.asOf + later)!!.how
                if (held) {
                    assertTrue(how == Train.How.HELD || how == Train.How.WAS_THERE, "as of ${copy.asOf - T0}, $later s on: $how")
                    assertEquals(copy.stops, copy.ahead(copy.asOf + later))
                }
                held = held || how == Train.How.HELD
            }
        }
        // The numbered lines and the L leave a stop's time where it was, so there two minutes past it is a hold. A train
        // a minute and a half past its time when last heard of is not called held when the clock makes that two.
        val dwell = seen(feed(T0, trip(JEFFERSON, T0 - 90), standing = mapOf(ID to JEFFERSON)))
        assertNull(assertIs<Sighting.Coming>(dwell.seenFrom(MORGAN, T0 + 40)).heldSince)
        assertEquals(Train.How.THERE, dwell.whereAt(T0 + 40)!!.how)
    }

    @Test
    fun aTrainStoodAtOneStopForMinutesIsBeingHeld() {
        val held = seen(feed(T0, trip(JEFFERSON, T0 - 200), standing = mapOf(ID to JEFFERSON)))
        val coming = assertIs<Sighting.Coming>(held.seenFrom(MORGAN, T0))
        assertEquals(T0 - 200, coming.heldSince)
        // Two minutes is not yet a hold.
        val dwell = seen(feed(T0, trip(JEFFERSON, T0 - 100), standing = mapOf(ID to JEFFERSON)))
        assertNull(assertIs<Sighting.Coming>(dwell.seenFrom(MORGAN, T0)).heldSince)
        // Late and still moving is late, not held.
        val late = seen(feed(T0, trip(JEFFERSON, T0 - 200)))
        assertNull(assertIs<Sighting.Coming>(late.seenFrom(MORGAN, T0)).heldSince)
        // Standing at the rider's own platform, there is nothing to warn of.
        assertNull(assertIs<Sighting.Coming>(held.seenFrom(JEFFERSON, T0)).heldSince)
    }

    @Test
    fun aTripWithNoTrainIsScheduledHoweverItsTimesStand() {
        val slot = seen(feed(T0, trip(JEFFERSON, T0 + 600, assigned = false)))
        val scheduled = assertIs<Sighting.Scheduled>(slot.seenFrom(MORGAN, T0))
        assertEquals(T0 + 690, scheduled.due)
        assertEquals(JEFFERSON, scheduled.origin.platform)
        // Its place is not reckoned from its times once the list is old: there is no train to place.
        assertEquals(scheduled, slot.seenFrom(MORGAN, T0 + 200))
        // A slot whose time has gone by with no train is still only a slot. Some never run.
        val phantom = seen(feed(T0, trip(JEFFERSON, T0 - 900, assigned = false)))
        assertIs<Sighting.Scheduled>(phantom.seenFrom(MORGAN, T0))
    }

    @Test
    fun aTrainThatHasGoneByHasLeft() {
        val atMontrose = leavingJefferson.then(feed(T0 + 240, trip(MONTROSE, T0 + 260)))
        val left = assertIs<Sighting.Left>(atMontrose.seenFrom(MORGAN, T0 + 240))
        assertEquals(T0 + 120, left.at)
        assertEquals(MONTROSE, left.next!!.platform)
        assertTrue(left.sure)
        // What it has done stays said, however old the word of it.
        assertIs<Sighting.Left>(atMontrose.seenFrom(MORGAN, T0 + 3000))
    }

    @Test
    fun aTrainFirstSeenPastThePlatformHadLeftBeforeTheFirstLook() {
        val left = assertIs<Sighting.Left>(seen(feed(T0, trip(MONTROSE, T0 + 30))).seenFrom(MORGAN, T0))
        assertEquals(0, left.at)
        assertEquals(MONTROSE, left.next!!.platform)
    }

    @Test
    fun withoutNewWordATrainIsReckonedPastThePlatformAndNotSaidToHaveLeft() {
        // Jefferson St was due at +30, so it counts as passed. Morgan Av, due at +120, does from +90.
        val reckoned = assertIs<Sighting.Coming>(leavingJefferson.seenFrom(MONTROSE, T0 + 80))
        assertFalse(reckoned.sure)
        assertEquals(2, reckoned.stopsAway)
        assertEquals(MORGAN, reckoned.next.platform)
        assertFalse(reckoned.standing)
        val past = assertIs<Sighting.Left>(leavingJefferson.seenFrom(MORGAN, T0 + 100))
        assertFalse(past.sure)
        assertEquals(T0 + 120, past.at)
        assertEquals(Sighting.Unknown(T0), leavingJefferson.seenFrom(MONTROSE, T0 + 301))
    }

    @Test
    fun aTrainThatNoLongerStopsAtThePlatformIsSkippingIt() {
        val skipping = leavingJefferson.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70, skipping = setOf(MORGAN, MONTROSE))))
        val seen = assertIs<Sighting.Skipping>(skipping.seenFrom(MORGAN, T0 + 60))
        assertEquals(listOf(JEFFERSON, GRAND), seen.next.map { it.platform })
        assertIs<Sighting.Coming>(skipping.seenFrom(GRAND, T0 + 60))
    }

    @Test
    fun theTrainsCallingAtAStopAreTheOnesARiderCouldBeOn() {
        val day = Fixtures.weekdayAfternoon
        val feed = day.feeds.getValue("gtfs-l")
        val toUnionSq = Train.callingAt("gtfs-l", feed, "L03N", catalog)
        assertTrue(toUnionSq.size >= 5)
        assertTrue(toUnionSq.all { train -> train.assigned && train.stops.any { it.id == "L03N" } })
        assertEquals(toUnionSq.map { it.stop("L03")!!.time }.sorted(), toUnionSq.map { it.stop("L03")!!.time })
        // The other direction is other trains.
        val fromUnionSq = Train.callingAt("gtfs-l", feed, "L03S", catalog)
        assertTrue(fromUnionSq.isNotEmpty())
        assertTrue(toUnionSq.map { it.id }.intersect(fromUnionSq.map { it.id }.toSet()).isEmpty())
        // Timetable slots list the stop too, and are not trains.
        val listing = feed.trips.count { trip -> trip.stops.any { it.stopId == "L03N" } }
        assertTrue(listing > toUnionSq.size)
        // On shared track it is every line that stops there: the 4, 5 and 6 at Union Sq.
        val numbered = Train.callingAt("gtfs", day.feeds.getValue("gtfs"), "635S", catalog)
        assertEquals(setOf("4", "5", "6"), numbered.map { it.route.take(1) }.toSet())
    }

    @Test
    fun aBoardsTrainsCarryTheTripAndFeedTheyAreFollowedBy() {
        val day = Fixtures.weekdayAfternoon
        val unionSq = Fixtures.station("14 St-Union Sq")
        val board = day.board(unionSq)
        assertTrue(board.arrivals.size > 50)
        board.arrivals.forEach { arrival ->
            val train = Train.find(arrival.feed, day.feeds.getValue(arrival.feed), arrival.trip, catalog)
            assertTrue(train != null && train.place(arrival.stop) >= 0, "${arrival.route} ${arrival.destination}")
            assertEquals(arrival.epochSeconds, train.stop(arrival.stop)!!.time)
            assertEquals(arrival.destination, catalog.stopName(train.lastStop!!.platform))
            assertEquals(arrival.assigned, train.assigned)
        }
        // A row's trains are found by it, soonest first, each at the platform and in the feed the row had it.
        val row = board.groups(day.time).first().rows.first()
        val refs = TrainRef.of(row.arrivals)
        assertEquals(row.arrivals.map { it.trip }, refs.map { it.trip })
        assertEquals(row.arrivals.map { it.stop }, refs.map { it.platform })
        assertEquals(row.arrivals.map { it.feed }, refs.map { it.feed })
        assertTrue(refs.all { it.name == "${row.route} to ${row.destination}" })
        // A feed that gives its trips no ids offers nothing to tap.
        assertTrue(TrainRef.of(row.arrivals.map { it.copy(trip = "") }).isEmpty())
    }

    @Test
    fun theStopsAfterAPlatformAreTheOnesARiderThereCouldRideTo() {
        assertEquals(LINE.drop(2), leavingJefferson.stopsAfter(MORGAN).map { it.platform })
        assertEquals(LINE.drop(1), leavingJefferson.stopsAfter(JEFFERSON).map { it.platform })
        assertTrue(leavingJefferson.stopsAfter(EIGHTH_AV).isEmpty())
        // Once the train is past the platform, or no longer stops at it, they are every stop it has left.
        val past = leavingJefferson.then(feed(T0 + 240, trip(MONTROSE, T0 + 260)))
        assertEquals(LINE.drop(2), past.stopsAfter(MORGAN).map { it.platform })
        val skipping = leavingJefferson.then(feed(T0 + 60, trip(JEFFERSON, T0 + 70, skipping = setOf(MORGAN))))
        assertEquals(LINE - MORGAN, skipping.stopsAfter(MORGAN).map { it.platform })
    }

    @Test
    fun aTapOnAStopStartsARideFromTheRowsPlatformToThatStop() {
        val ref = TrainRef("gtfs-l", ID, MORGAN, "L to 8 Av")
        val ride = ref.rideTo(leavingJefferson, leavingJefferson.stop(UNION_SQ)!!)
        assertEquals(Triple(leavingJefferson, MORGAN, UNION_SQ), Triple(ride.train, ride.from, ride.to))
        // The train is two stops from Morgan Av, and Union Sq is eight stops on from there.
        val waiting = assertIs<RideState.Waiting>(ride.state(T0))
        assertEquals(2, assertIs<Sighting.Coming>(waiting.train).stopsAway)
        assertEquals(8, waiting.stops)
        assertEquals(leavingJefferson.stop(UNION_SQ)!!.time, waiting.arrives)
    }

    @Test
    fun aTrainReadAfreshKnowsHowLongItWasAlreadySeenWhereItStands() {
        val first = Train.find("gtfs-jz", feed(T0, trip(JEFFERSON, T0 - 10), standing = mapOf(ID to JEFFERSON)), ID, catalog)!!
        val again = Train.find("gtfs-jz", feed(T0 + 60, trip(JEFFERSON, T0 + 50), standing = mapOf(ID to JEFFERSON)), ID, catalog)!!
        assertEquals(T0 + 50, again.stoodSince)
        assertEquals(T0 - 10, again.since(first).stoodSince)
        // Not from a train at another stop, and not from one that was not standing.
        val moved = Train.find("gtfs-jz", feed(T0 + 60, trip(MORGAN, T0 + 50), standing = mapOf(ID to MORGAN)), ID, catalog)!!
        assertEquals(T0 + 50, moved.since(first).stoodSince)
        assertEquals(again, again.since(leavingJefferson))
        assertEquals(again, again.since(null))
    }
}
