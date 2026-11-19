package com.thelightphone.subway

import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.RideState
import com.thelightphone.subway.data.Sighting
import com.thelightphone.subway.data.Train
import com.thelightphone.subway.ui.RideWords
import com.thelightphone.subway.ui.clockLabel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real trains followed across real downloads: a minute apart on the afternoon of Thursday
 * 12 November 2026, and half a minute apart on the morning of Monday 16 November. Each
 * ride is told as the rider's screen would have told it: one word and a number for every
 * download, with "?" where the count was reckoned from old times and "*" where the
 * train's stops had just been changed under it.
 */
class ReplayTest {
    private val catalog = Fixtures.catalog
    private val l = Fixtures.lSkipStop
    private val one = Fixtures.oneCutShort

    private fun RideState.brief(): String = when (this) {
        is RideState.Waiting -> when (val train = train) {
            is Sighting.Coming -> "waiting ${train.stopsAway}${if (train.sure) "" else "?"}"
            is Sighting.Starting -> "starting"
            else -> "scheduled"
        }
        is RideState.Riding -> "riding $stops${if (sure) "" else "?"}"
        is RideState.Next -> "next${if (sure) "" else "?"}"
        is RideState.There -> "there${if (sure) "" else "?"}"
        is RideState.Past -> "past"
        is RideState.Dropped -> "dropped ${catalog.stopName(platform)}"
        is RideState.Lost -> "lost"
        is RideState.Unknown -> "unknown"
    }

    /** The train [id] as each download of [replay] leaves it, the first included. */
    private fun followed(replay: Replay, slug: String, id: String): List<Train> {
        var train = assertNotNull(Train.find(slug, replay.feeds.first(), id, catalog))
        return listOf(train) + replay.feeds.drop(1).map { feed -> train.after(feed, catalog).also { train = it } }
    }

    /** What a rider from [from] to [to] on that train was told, looking as each download came in. */
    private fun told(replay: Replay, slug: String, id: String, from: String, to: String): List<String> =
        followed(replay, slug, id).zip(replay.feeds) { train, feed ->
            Ride(train, from, to).state(feed.timestamp).brief() + if (train.changed == feed.timestamp) "*" else ""
        }

    /** A sentence as it reads, with ordinary spaces: on the screen a short stop name and a clock time are kept from breaking. */
    private fun plain(sentence: String) = sentence.replace('\u00A0', ' ')

    /** A platform by the name on its own signs, which is how the tool names a stop: the L's "Lorimer St" is in the station called Metropolitan Av. */
    private fun platform(name: String, line: String) =
        catalog.stations.flatMap { it.platforms }.first { line in it.routes && catalog.stopName(it.stop) == name }.stop

    @Test
    fun theReplaysAreWhatTheScriptSaysTheyAre() {
        assertEquals(17, l.feeds.size)
        assertEquals(1794512597L, l.feeds.first().timestamp)
        // A minute apart, give or take the few seconds the MTA takes to build a feed.
        assertTrue(l.feeds.zipWithNext { a, b -> b.timestamp - a.timestamp }.all { it in 55..65 })
        // The whole feed, positions and all: every train on the move has one and no timetable slot does.
        l.feeds.forEach { feed ->
            assertTrue(feed.trips.size in 20..30 && feed.trips.all { it.route == "L" && it.id.isNotEmpty() })
            assertEquals(feed.trips.filter { it.assigned }.map { it.id }.toSet(), feed.positions.keys)
        }
        assertEquals(22, one.feeds.size)
        assertTrue(one.feeds.all { feed -> feed.trips.size <= 1 && feed.trips.all { it.id.startsWith("085350_1..N") } })
        // All of them together are small enough to sit beside the other fixtures.
        assertTrue(File("src/test/resources/replays").listFiles()!!.sumOf { it.length() } < 60_000)
        // The single trains cut from Monday's recording: half a minute a download, and nothing in them but the one trip.
        mapOf(
            Fixtures.qSlotThenTrain to 28, Fixtures.bStopGivenBack to 18, Fixtures.jEveryStop to 27, Fixtures.bOverTheBridge to 22,
            Fixtures.nNamedFarAhead to 20, Fixtures.lGoneMidRun to 46, Fixtures.fiveHeld to 42, Fixtures.sevenHeldThenOff to 9,
            Fixtures.shuttleStarting to 17, Fixtures.jHeld to 14, Fixtures.fourAtItsTerminal to 2,
            Fixtures.gGivenBackSkipping to 9, Fixtures.mTrainNamedLate to 20, Fixtures.shuttleNamedTooFarDown to 21, Fixtures.eHeld to 28,
        ).forEach { (replay, downloads) ->
            assertEquals(downloads, replay.feeds.size)
            assertTrue(replay.feeds.zipWithNext { a, b -> b.timestamp - a.timestamp }.all { it in 14..46 })
            assertTrue(replay.feeds.all { it.trips.size <= 1 })
        }
        // And the two trains that shared all of an id but its ending.
        assertEquals(12, Fixtures.fourNamesakes.feeds.size)
        assertEquals(setOf("064700_4..S01R", "064700_4..S06R"), Fixtures.fourNamesakes.feeds.first().trips.mapTo(HashSet()) { it.id })
    }

    @Test
    fun aTrainIsCountedInToARidersPlatformAndThenStopsStoppingThere() {
        // The L from Canarsie, seen from Morgan Av by a rider bound for Union Sq. At 2:49 PM,
        // standing at Myrtle-Wyckoff Avs three stops away, it was made to skip six stops.
        val told = told(l, "gtfs-l", "086350_L..N", platform("Morgan Av", "L"), platform("14 St-Union Sq", "L"))
        assertEquals(
            listOf("waiting 6", "waiting 6", "waiting 5", "waiting 5", "waiting 4", "waiting 3", "dropped Morgan Av*") + List(10) { "dropped Morgan Av" },
            told,
        )
        val after = followed(l, "gtfs-l", "086350_L..N")[6]
        assertEquals(1794512957L, after.changed)
        assertEquals(
            listOf("DeKalb Av", "Jefferson St", "Morgan Av", "Montrose Av", "Grand St", "Graham Av"),
            after.dropped.map { catalog.stopName(it.platform) },
        )
        assertEquals(listOf("Myrtle-Wyckoff Avs", "Lorimer St"), assertIs<Sighting.Skipping>(after.seenFrom("L14", l.feeds[6].timestamp)).next.map { catalog.stopName(it.platform) })
    }

    @Test
    fun aRiderAboardThatTrainIsToldTheirStopIsGoneOrThatItIsSuddenlyNext() {
        val broadwayJunction = platform("Broadway Junction", "L")
        // For Graham Av, one of the six.
        assertEquals(
            listOf("riding 9", "riding 9", "riding 8", "riding 8", "riding 7", "riding 6", "dropped Graham Av*") + List(10) { "dropped Graham Av" },
            told(l, "gtfs-l", "086350_L..N", broadwayJunction, platform("Graham Av", "L")),
        )
        // For Lorimer St, the first stop after them: seven stops became one, and stayed one for
        // the seven minutes the train ran through the stations between.
        assertEquals(
            listOf("riding 10", "riding 10", "riding 9", "riding 9", "riding 8", "riding 7", "next*") + List(6) { "next" } + listOf("there", "past", "past", "past"),
            told(l, "gtfs-l", "086350_L..N", broadwayJunction, platform("Lorimer St", "L")),
        )
        // For Bedford Av, the stop after that.
        assertEquals(
            listOf("riding 11", "riding 11", "riding 10", "riding 10", "riding 9", "riding 8", "riding 2*") + List(6) { "riding 2" } + listOf("next", "next", "there", "past"),
            told(l, "gtfs-l", "086350_L..N", broadwayJunction, platform("Bedford Av", "L")),
        )
    }

    @Test
    fun whileItSkippedStopsTheTrainWasPlacedByItsListAndNotByThePassingMarkers() {
        // Running through Jefferson St, Grand St and Graham Av the feed had it "stopped" at
        // each, a stop that was no longer in its list. It was never taken to be standing.
        val passing = followed(l, "gtfs-l", "086350_L..N").slice(listOf(8, 11, 12))
        assertEquals(listOf("L15N", "L12N", "L11N"), listOf(8, 11, 12).map { l.feeds[it].positions.getValue("086350_L..N").stopId })
        passing.forEach { train ->
            assertFalse(train.standing)
            assertEquals("Lorimer St", catalog.stopName(train.stops.first().platform))
        }
    }

    @Test
    fun aTrainHeldAtOneStopIsSaidToBeStandingThere() {
        // The train ahead of the one that skipped. It stood at Graham Av from 2:43:48 PM for five minutes.
        val trains = followed(l, "gtfs-l", "087500_L..N")
        val bedford = platform("Bedford Av", "L")
        val held = trains.take(7).zip(l.feeds) { train, feed -> assertIs<Sighting.Coming>(train.seenFrom(bedford, feed.timestamp)).heldSince }
        // Not yet there, then there under two minutes, then held, then on to Lorimer St.
        assertEquals(listOf(null, null, null, 1794512628L, 1794512628L, 1794512628L, null), held)
        assertEquals(
            listOf("waiting 3", "waiting 2", "waiting 2", "waiting 2", "waiting 2", "waiting 2", "waiting 1", "waiting 1", "waiting 0", "riding 3", "riding 3", "riding 2", "riding 2", "next", "there", "there", "past"),
            told(l, "gtfs-l", "087500_L..N", bedford, platform("14 St-Union Sq", "L")),
        )
    }

    @Test
    fun aTrainIsRiddenToItsLastStopThoughTheFeedLetsGoOfItOneStopEarly() {
        // A short run from Myrtle-Wyckoff Avs, ridden from 1 Av to 8 Av. The feed dropped it as it
        // left 6 Av, two and a half minutes before it was due at 8 Av.
        val told = told(l, "gtfs-l", "086700_L..N", platform("1 Av", "L"), platform("8 Av", "L"))
        assertEquals(listOf("riding 3", "riding 2", "next", "next?", "next?") + List(12) { "there?" }, told)
        val trains = followed(l, "gtfs-l", "086700_L..N")
        assertFalse(trains[2].ended)
        assertTrue(trains.drop(3).all { it.ended && !it.lost })
        // Due at 8 Av at 2:48:10 PM, and worth coming back to for ten minutes after that.
        val ride = Ride(trains.last(), "L06", "L01")
        assertEquals(1794512890L, ride.train.stop("L01")!!.time)
        assertEquals(listOf(false, false, true), listOf(13, 14, 15).map { ride.over(l.feeds[it].timestamp) })
    }

    @Test
    fun aTimetableSlotIsOnlyScheduledUntilTheFeedGivesItATrain() {
        // A short run from East 105 St. For six downloads it had no train, then one standing at its first stop.
        val trains = followed(l, "gtfs-l", "089000_L..N")
        assertEquals(List(6) { false } + List(11) { true }, trains.map { it.assigned })
        assertEquals(setOf("089000_L..N"), trains.map { it.id }.toSet())
        assertEquals(
            List(6) { "scheduled" } + listOf("waiting 0", "waiting 0", "riding 5", "riding 4", "riding 3", "riding 2", "riding 2", "next", "next", "there", "past"),
            told(l, "gtfs-l", "089000_L..N", platform("East 105 St", "L"), platform("Broadway Junction", "L")),
        )
    }

    @Test
    fun aTrainStandingAtItsFirstStopIsATrainAndIsRiddenFromThere() {
        // The train the study rode end to end, from Canarsie-Rockaway Pkwy.
        assertEquals(
            listOf("waiting 0", "riding 5", "riding 5", "riding 4", "riding 4", "riding 3", "riding 3", "riding 2", "next", "next", "there") + List(6) { "past" },
            told(l, "gtfs-l", "088350_L..N", platform("Canarsie-Rockaway Pkwy", "L"), platform("Broadway Junction", "L")),
        )
    }

    @Test
    fun onEveryTrainInTheReplayTheCountOnlyGoesUpWhenItsStopsWereChanged() {
        var followedTrains = 0
        var steps = 0
        l.feeds.forEachIndexed { start, first ->
            first.trips.filter { it.assigned }.forEach { trip ->
                var train = Train.of("gtfs-l", first, trip, catalog)
                val goal = train.stops.last().platform
                var last = Int.MAX_VALUE
                followedTrains++
                l.feeds.drop(start + 1).forEach { feed ->
                    val before = train
                    train = train.after(feed, catalog)
                    val place = train.place(goal)
                    if (train.changed != before.changed) {
                        last = Int.MAX_VALUE
                    } else if (place >= 0 && !train.ended) {
                        val count = place + if (train.standing) 0 else 1
                        assertTrue(count <= last, "${trip.id} at ${feed.timestamp}: $last then $count")
                        last = count
                        steps++
                    }
                    assertFalse(train.lost, "${trip.id} at ${feed.timestamp}")
                }
            }
        }
        assertTrue(followedTrains > 150 && steps > 1500, "$followedTrains trains, $steps steps")
    }

    @Test
    fun aTrainIsFollowedThroughALostIdEndingAndTwoMissedDownloads() {
        val trains = followed(one, "gtfs", "085350_1..N15R")
        // The id as it was for seven downloads, and as it was after.
        assertEquals(List(7) { "085350_1..N15R" } + List(15) { "085350_1..N" }, trains.map { it.id })
        // It was missing from the tenth and the fourteenth download, and back in the next each time.
        assertEquals(listOf(9, 13), one.feeds.indices.filter { one.feeds[it].trips.isEmpty() })
        assertEquals(listOf(9, 13), trains.indices.filter { trains[it].asOf != one.feeds[it].timestamp })
        assertTrue(trains.none { it.lost || it.ended })
        // Missing, it kept the stops it had.
        assertEquals(trains[8].stops, trains[9].stops)
        assertEquals(trains[12].stops, trains[13].stops)
    }

    @Test
    fun thatTrainsStopsWereChangedAndARiderForOneOfThemWasTold() {
        val uptown = platform("125 St", "1")
        // At 3:00 PM, with the lost ending, it lost 207 St and 215 St.
        assertEquals(
            listOf("riding 7", "riding 7", "riding 6", "riding 6", "riding 5", "riding 5", "riding 4", "dropped 207 St*") + List(14) { "dropped 207 St" },
            told(one, "gtfs", "085350_1..N15R", uptown, platform("207 St", "1")),
        )
        val changed = followed(one, "gtfs", "085350_1..N15R")[7]
        assertEquals(1794513616L, changed.changed)
        assertEquals(listOf("207 St", "215 St"), changed.dropped.map { catalog.stopName(it.platform) })
        assertEquals(listOf("168 St-Washington Hts", "181 St", "191 St"), changed.behind.takeLast(3).map { catalog.stopName(it.platform) })
        assertNull(changed.left("108"))
    }

    @Test
    fun aRiderToItsLastStopSawTheCountOnlyEverGoDown() {
        // Twelve stops became five when two were dropped and three were passed in one minute.
        // The "?" is a download the train was missing from: the count stood, reckoned from the last one.
        assertEquals(
            listOf(
                "riding 12", "riding 12", "riding 11", "riding 11", "riding 10", "riding 10", "riding 9", "riding 5*", "riding 4", "riding 4?",
                "riding 4", "riding 4", "riding 3", "riding 3", "riding 3", "riding 3", "riding 2", "riding 2", "next", "next", "next", "next",
            ),
            told(one, "gtfs", "085350_1..N15R", platform("125 St", "1"), platform("Van Cortlandt Park-242 St", "1")),
        )
    }

    @Test
    fun theToolGoesByWhereTheFeedSawTheTrainEvenWhenTheFeedWasEarly() {
        // At 3:01 PM the feed had the train stopped at Dyckman St while its own times put it
        // there three minutes later, and it stayed listed until 3:05. A sighting is believed
        // over a time: the mistake this makes is telling the rider to look up too soon, and
        // the other way round would be telling them too late.
        val feed = one.feeds[8]
        assertEquals(GtfsRealtime.Position("109N", GtfsRealtime.Status.STOPPED, feed.timestamp - 11), feed.positions.values.single())
        assertEquals(feed.timestamp + 169, feed.trips.single().stops.first().time)
        assertEquals(
            listOf("riding 6", "riding 6", "riding 5", "riding 5", "riding 4", "riding 4", "riding 3", "next*", "there", "there?", "there", "there") + List(10) { "past" },
            told(one, "gtfs", "085350_1..N15R", platform("125 St", "1"), platform("Dyckman St", "1")),
        )
    }

    /** How the train stood to [platform] as each download came in: "2" for two stops away, "0" at it, and a word for anything else. */
    private fun seen(replay: Replay, slug: String, id: String, platform: String): List<String> =
        followed(replay, slug, id).zip(replay.feeds) { train, feed ->
            when (val sighting = train.seenFrom(platform, feed.timestamp)) {
                is Sighting.Scheduled -> "scheduled"
                is Sighting.Starting -> "starting"
                is Sighting.Coming -> "${sighting.stopsAway}${if (sighting.where.how == Train.How.REACHED) "+" else ""}"
                is Sighting.Left -> "left"
                is Sighting.Skipping -> "skipping"
                is Sighting.Unknown -> "unknown"
                Sighting.Ended -> "ended"
                is Sighting.Lost -> "lost"
            } + if (train.changed == feed.timestamp) "*" else ""
        }

    @Test
    fun aTripWithNoTrainIsNotCountedAsMovingWhileTheFeedWalksItDownItsTimetable() {
        // The 11:20 Q from Coney Island had no train until 11:26:51. The feed took Coney
        // Island off its list at 11:21:20, W 8 St at 11:22:51, Ocean Pkwy at 11:24:21 and
        // Brighton Beach at 11:26:21, and then listed all four again with a train at W 8 St.
        val q = Fixtures.qSlotThenTrain
        val trains = followed(q, "gtfs-nqrw", "068050_Q..N36R")
        assertEquals(List(15) { false } + List(13) { true }, trains.map { it.assigned })
        assertEquals(listOf(29, 29, 29, 29, 28, 28, 28, 27, 27, 27, 26, 26, 26, 26, 25), q.feeds.take(15).map { it.trips.single().stops.size })
        // Nothing was filed as left on the timetable's say-so, and the trip kept the stops it started with.
        trains.take(15).forEach { train ->
            assertTrue(train.behind.isEmpty() && train.dropped.isEmpty())
            assertEquals(29, train.stops.size)
            assertFalse(train.standing)
        }
        val coneyIsland = platform("Coney Island-Stillwell Av", "Q")
        val brightonBeach = platform("Brighton Beach", "Q")
        // From Coney Island it is scheduled until it is a train, and by then the train has gone.
        assertEquals(List(15) { "scheduled" } + List(13) { "left" }, seen(q, "gtfs-nqrw", "068050_Q..N36R", coneyIsland))
        // From Brighton Beach it is never said to have left before a train got there.
        assertEquals(
            List(15) { "scheduled" } + listOf("2", "2", "2+", "2+", "1", "1", "1+", "0", "0", "0+", "0+", "left", "left"),
            seen(q, "gtfs-nqrw", "068050_Q..N36R", brightonBeach),
        )
        // A ride to Kings Hwy counts the stops the train really has to make. Filed as left, four of them would be missing.
        assertEquals(
            List(15) { "scheduled" } + List(4) { "riding 6" } + List(3) { "riding 5" } + List(4) { "riding 4" } + List(2) { "riding 3" },
            told(q, "gtfs-nqrw", "068050_Q..N36R", coneyIsland, platform("Kings Hwy", "Q")),
        )
        // And a ride to Brighton Beach is not over before the train has set out.
        assertEquals(
            List(15) { "scheduled" } + List(4) { "riding 2" } + List(3) { "next" } + List(4) { "there" } + List(2) { "past" },
            told(q, "gtfs-nqrw", "068050_Q..N36R", coneyIsland, brightonBeach),
        )
    }

    @Test
    fun aStopTheFeedTookOffTooSoonIsGivenBackWhenItIsListedAgainTwice() {
        // The B at Kings Hwy: off its list at 11:46:22, back on it from 11:49:22 with the train standing there.
        val kingsHwy = platform("Kings Hwy", "B")
        assertEquals(
            listOf("1", "1", "1", "1", "0", "left", "left", "left", "left", "left", "left", "left", "0*", "0", "0+", "left", "left", "left"),
            seen(Fixtures.bStopGivenBack, "gtfs-bdfm", "069500_B..N65R", kingsHwy),
        )
        val trains = followed(Fixtures.bStopGivenBack, "gtfs-bdfm", "069500_B..N65R")
        // Listed again once, it stays left. That one download is how the lettered lines' flaps look.
        assertEquals("D35N", trains[11].relisted)
        assertEquals("D35", trains[11].behind.last().platform)
        // Given back, and then left for good a minute and a half later.
        assertTrue(trains[12].behind.none { it.platform == "D35" })
        assertEquals(Fixtures.bStopGivenBack.feeds[12].timestamp, trains[12].changed)
        assertEquals("D35", trains[15].behind.last().platform)
    }

    @Test
    fun onTheLetteredLinesTheCountStepsDownAsTheTrainReachesEachStop() {
        val j = Fixtures.jEveryStop
        val id = "065700_J..S13R"
        // Every download has it "stopped", at the stop its list opens with or the one after.
        assertTrue(j.feeds.all { it.positions.getValue(id).status == GtfsRealtime.Status.STOPPED })
        val told = told(j, "gtfs-jz", id, platform("Crescent St", "J"), platform("Broadway Junction", "J"))
        assertEquals(
            listOf("waiting 1") + List(5) { "waiting 0" } + List(3) { "riding 4" } + List(3) { "riding 3" } + List(3) { "riding 2" } +
                List(6) { "next" } + List(4) { "there" } + List(2) { "past" },
            told,
        )
        val named = j.feeds.map { catalog.stopName(it.positions.getValue(id).stopId.dropLast(1)) }
        // "Next stop is Broadway Junction" first shows when the feed has the train at Alabama Av, the stop before it.
        assertEquals("Alabama Av", named[told.indexOf("next")])
        assertEquals("Van Siclen Av", named[told.indexOf("next") - 1])
        // "Now at Broadway Junction" first shows when the feed has it there.
        assertEquals("Broadway Junction", named[told.indexOf("there")])
        assertEquals("Alabama Av", named[told.indexOf("there") - 1])
        // It is only said to be at a stop for its first minute there. After that it was last seen there.
        val trains = followed(j, "gtfs-jz", id)
        val how = trains.zip(j.feeds) { train, feed -> train.whereAt(feed.timestamp)!!.how }
        assertEquals(
            listOf(Train.How.COMING) + listOf("TTRRR", "TTR", "TTR", "TTR", "TTRRRR", "TTRR", "TT").joinToString("").map { if (it == 'T') Train.How.THERE else Train.How.REACHED },
            how,
        )
        trains.zip(j.feeds).forEach { (train, feed) ->
            val where = train.whereAt(feed.timestamp)!!
            if (where.how == Train.How.THERE) assertTrue(feed.timestamp - train.stoodSince <= Train.AT_FOR)
        }
        // Seen from Alabama Av: at it for a minute, then "reached", and "left" only once the feed has it at the next stop.
        assertEquals(
            listOf("5", "4", "4", "4+", "4+", "4+", "3", "3", "3+", "2", "2", "2+", "1", "1", "1+", "0", "0", "0+", "0+", "0+", "0+") + List(6) { "left" },
            seen(j, "gtfs-jz", id, platform("Alabama Av", "J")),
        )
    }

    @Test
    fun aTrainNamedAtAStopItHasLeftIsBetweenStops() {
        // The B over the Manhattan Bridge: five minutes named as stopped at Grand St, which was no longer in its list.
        val b = Fixtures.bOverTheBridge
        val id = "062750_B..S65R"
        assertEquals(
            List(3) { "waiting 0" } + listOf("riding 2") + List(14) { "next" } + List(4) { "there" },
            told(b, "gtfs-bdfm", id, platform("Broadway-Lafayette St", "B"), platform("DeKalb Av", "B")),
        )
        val trains = followed(b, "gtfs-bdfm", id)
        (7..17).forEach { at ->
            assertEquals("D22S", b.feeds[at].positions.getValue(id).stopId)
            assertFalse(trains[at].standing)
            assertEquals("DeKalb Av", catalog.stopName(trains[at].stops.first().platform))
        }
    }

    @Test
    fun aPlaceFourStopsDownTheListIsNotBelieved() {
        // The N at 11:45:52: Fort Hamilton Pkwy, 8 Av, 59 St, 36 St and Atlantic Av listed
        // within five seconds, and "stopped at Atlantic Av". It was still short of Fort Hamilton Pkwy.
        val n = Fixtures.nNamedFarAhead
        val id = "068800_N..N33R"
        val feed = n.feeds[5]
        assertEquals(listOf("N03N", "N02N", "R41N", "R36N", "R31N"), feed.trips.single().stops.take(5).map { it.stopId })
        assertEquals(5L, feed.trips.single().stops.let { it[4].time - it[0].time })
        assertEquals(GtfsRealtime.Position("R31N", GtfsRealtime.Status.STOPPED, feed.trips.single().stops[4].time), feed.positions.getValue(id))
        val train = followed(n, "gtfs-nqrw", id)[5]
        assertFalse(train.standing)
        assertEquals("N03N", train.stops.first().id)
        val told = told(n, "gtfs-nqrw", id, platform("New Utrecht Av", "N"), platform("Atlantic Av-Barclays Ctr", "N"))
        // Five stops, then four: never "Now at Atlantic Av" with the train on the Sea Beach line.
        assertEquals(List(2) { "waiting 1" } + List(4) { "riding 5" } + List(5) { "riding 4" }, told.take(11))
        // Then the feed itself dropped the four stops for one download, and listed them again in the next two.
        assertEquals(listOf("past", "past", "riding 3*", "riding 3", "riding 3"), told.subList(11, 16))
        assertTrue(told.none { it.startsWith("there") })
    }

    @Test
    fun aTrainGoneFromTheFeedInTheMiddleOfItsRunStaysLost() {
        // The L that was late for Myrtle-Wyckoff Avs, in two downloads and then in none. Its
        // old times had it at 14 St-Union Sq at 11:28 and at 8 Av at 11:32.
        val l = Fixtures.lGoneMidRun
        val id = "064450_L..N"
        val trains = followed(l, "gtfs-l", id)
        assertEquals(1794845482L, trains.last().asOf)
        assertEquals(listOf(true, true) + List(44) { false }, l.feeds.map { it.trips.isNotEmpty() })
        assertTrue(trains.none { it.ended })
        assertEquals(List(4) { false } + List(42) { true }, trains.map { it.lost })
        val morgan = platform("Morgan Av", "L")
        val unionSq = platform("14 St-Union Sq", "L")
        assertEquals(List(4) { "waiting 4" } + List(42) { "lost" }, told(l, "gtfs-l", id, morgan, unionSq))
        // It was behind its times when last heard of. In the minute before it is called lost, the count stays where it was.
        val last = trains[3]
        (61L..89L step 4).forEach { later ->
            val waiting = assertIs<RideState.Waiting>(Ride(last, morgan, unionSq).state(last.asOf + later))
            val coming = assertIs<Sighting.Coming>(waiting.train)
            assertEquals(4, coming.stopsAway)
            assertFalse(coming.sure)
            assertTrue(coming.asLastSeen)
            // Its time at Morgan Av is still two minutes off. Only its being left where it was keeps "Here in about" back.
            val now = last.asOf + later
            assertTrue(coming.due > now + 60)
            assertEquals("Its next stop was Myrtle-Wyckoff Avs.", RideWords.top(coming, "Morgan Av", now, catalog).detail)
            val statement = RideWords.statement(Ride(last, morgan, unionSq), now, catalog)
            assertEquals(listOf("Then 8 stops to 14 St-Union Sq."), statement.body.map(::plain))
            assertEquals(listOf("Its next stop was Myrtle-Wyckoff Avs.", "Yours comes after 3 Av."), statement.aside.map(::plain))
        }
        // The clock comes round to its old last time, and it has not "finished its run".
        val at1132 = l.feeds.indexOfFirst { it.timestamp >= trains.last().stops.last().time }
        assertTrue(at1132 in 40..44)
        assertEquals(Sighting.Lost(ranOut = false), trains.last().seenFrom(platform("8 Av", "L"), l.feeds.last().timestamp))
        assertEquals(Train.Grade.UNKNOWN, trains.last().grade(l.feeds.last().timestamp))
    }

    @Test
    fun aboardATrainHeldForThirteenMinutesTheRiderIsToldSoAndGivenNoTimeThatHasGoneBy() {
        // The 5 at 149 St-Hostos from 11:33:39, with Jackson Av listed for 11:38:09 the whole time.
        val five = Fixtures.fiveHeld
        val id = "064050_5..N46R"
        val from = platform("125 St", "5")
        val jackson = platform("Jackson Av", "5")
        assertEquals(
            List(3) { "riding 3" } + List(29) { "riding 2" } + List(8) { "next" } + List(2) { "there" },
            told(five, "gtfs", id, from, jackson),
        )
        val trains = followed(five, "gtfs", id)
        val held = trains.zip(five.feeds) { train, feed -> (Ride(train, from, jackson).state(feed.timestamp) as? RideState.Riding)?.where?.takeIf { it.how == Train.How.HELD }?.since }
        // Standing two minutes past its time there, it is held, and stays held until it moves.
        assertEquals(List(7) { null } + List(23) { 1794846819L } + List(12) { null }, held)
        // No statement gives "Due about" a time that has gone by.
        trains.zip(five.feeds).forEach { (train, feed) ->
            listOf(jackson, platform("3 Av-149 St", "5")).forEach { to ->
                val statement = RideWords.statement(Ride(train, from, to), feed.timestamp, catalog)
                val due = statement.body.firstOrNull { it.startsWith("Due about") }
                val arrives = train.stop(to)!!.time
                if (due != null) assertTrue(arrives >= feed.timestamp - 59, "$due at ${feed.timestamp}")
            }
        }
        // The ride is not over while the train is still heard of short of the stop, though its time went by ten minutes ago.
        val stuck = trains[29]
        assertEquals(1794846969L, stuck.stop(platform("3 Av-149 St", "5"))!!.time)
        assertTrue(five.feeds[29].timestamp - 1794846969L > Ride.OVER_AFTER)
        assertFalse(Ride(stuck, from, platform("3 Av-149 St", "5")).over(five.feeds[29].timestamp))
        // And in the list of other trains it is still there to be picked, by a rider following another.
        val other = Ride(stuck.copy(id = "000000_5..N"), from, platform("3 Av-149 St", "5"))
        assertEquals(listOf(id), OtherTrains.of(other, five.feeds[29], catalog, five.feeds[29].timestamp).map { it.id })
    }

    @Test
    fun aTrainHeldWhenLastHeardOfIsNotCountedInOnTimesItHadAlreadyMissed() {
        // The 7 held at Mets-Willets Point, with every stop to 82 St listed for a time gone by.
        val seven = Fixtures.sevenHeldThenOff
        val train = followed(seven, "gtfs", "066600_7..S")[0]
        val from = platform("Mets-Willets Point", "7")
        val to = platform("69 St", "7")
        val ride = Ride(train, from, to)
        assertEquals("waiting 0", ride.state(train.asOf).brief())
        // Three minutes with no download: the count does not move, and is never "next" or "there".
        (61L..180L step 7).forEach { later ->
            val waiting = assertIs<RideState.Waiting>(ride.state(train.asOf + later))
            val coming = assertIs<Sighting.Coming>(waiting.train)
            assertEquals(0 to 7, coming.stopsAway to waiting.stops)
            assertEquals(Train.How.WAS_THERE, coming.where.how)
            assertFalse(coming.sure)
        }
        // The download that gets through has it on its way, seven stops off. Nothing has to be taken back.
        val answer = seven.feeds[6]
        assertEquals("riding 7", Ride(train.after(answer, catalog), from, to).state(answer.timestamp).brief())
    }

    @Test
    fun aTrainIsNotTakenForAnotherThatSharesAllOfItsIdButTheEnding() {
        val pair = Fixtures.fourNamesakes
        val trains = followed(pair, "gtfs", "064700_4..S01R")
        // It left the feed at the end of its run, with the other still in it. It ended, under its own id.
        assertEquals(setOf("064700_4..S01R"), trains.mapTo(HashSet()) { it.id })
        assertEquals(List(8) { false } + List(4) { true }, trains.map { it.ended })
        assertTrue(trains.all { it.dropped.isEmpty() })
        assertTrue(pair.feeds.drop(8).all { feed -> feed.trips.map { it.id } == listOf("064700_4..S06R") })
        assertEquals(
            List(4) { "waiting 0" } + List(2) { "next" } + List(2) { "there" } + List(4) { "there?" },
            told(pair, "gtfs", "064700_4..S01R", platform("Van Siclen Av", "3"), platform("New Lots Av", "3")),
        )
        assertEquals("ended", seen(pair, "gtfs", "064700_4..S01R", platform("New Lots Av", "3")).last())
    }

    @Test
    fun aTrainWaitingWhereItsRunStartsIsNotAStopAwayAndLeavesWhenItsTimeComes() {
        // The 42 St shuttle at Grand Central: a train from the third download, and no position until it left.
        val shuttle = Fixtures.shuttleStarting
        val id = "067700_GS.N04R"
        val grandCentral = platform("Grand Central-42 St", "S")
        assertEquals(
            List(2) { "scheduled" } + List(11) { "starting" } + List(2) { "next" } + listOf("there", "there?"),
            told(shuttle, "gtfs", id, grandCentral, platform("Times Sq-42 St", "S")),
        )
        assertEquals(
            List(2) { "scheduled" } + List(11) { "starting" } + List(3) { "left" } + listOf("ended"),
            seen(shuttle, "gtfs", id, grandCentral),
        )
        assertTrue(shuttle.feeds.subList(2, 13).all { it.positions.isEmpty() })
    }

    @Test
    fun onTheLetteredLinesAHeldTrainIsToldByHowLongItHasBeenAtItsStop() {
        // The J at Delancey St-Essex St from 11:46:09 for five minutes, its time there moved on in every download.
        val j = Fixtures.jHeld
        val id = "070000_J..N41R"
        val times = j.feeds.map { it.trips.single().stops.first() }
        assertEquals(List(11) { "M18N" }, times.subList(1, 12).map { it.stopId })
        assertEquals(11, times.subList(1, 12).map { it.time }.distinct().size)
        val marcy = platform("Marcy Av", "J")
        val held = followed(j, "gtfs-jz", id).zip(j.feeds) { train, feed -> (train.seenFrom(marcy, feed.timestamp) as Sighting.Coming).heldSince }
        // Four minutes at one stop is a hold. It has been there since the feed first had it there.
        assertEquals(List(9) { null } + List(3) { times[1].time } + List(2) { null }, held)
        assertEquals(1794847569L, times[1].time)
        // Marcy Av stayed listed for 11:52:20 while it stood there, and only slipped once it had left.
        val marcyDue = j.feeds.map { feed -> feed.trips.single().stops.first { it.stopId == "M16N" }.time }
        assertEquals(setOf(1794847940L), marcyDue.subList(1, 12).toSet())
        assertTrue(marcyDue.last() > 1794847940L + 50)
    }

    @Test
    fun aTrainHeldOnALetteredLineIsToldSoWhileItsTimesSlipAndIsLeftThereWhenWordStops() {
        // The E at Sutphin Blvd from 11:27:43 to 11:39:50, one stop short of Jamaica Center, where its run ends.
        val e = Fixtures.eHeld
        val id = "064450_E..N66R"
        val vanWyck = platform("Jamaica-Van Wyck", "E")
        val jamaicaCenter = platform("Jamaica Center-Parsons/Archer", "E")
        val trains = followed(e, "gtfs-ace", id)
        assertEquals(
            listOf("waiting 0", "riding 2") + List(25) { "next" } + listOf("there"),
            told(e, "gtfs-ace", id, vanWyck, jamaicaCenter),
        )
        // The feed moved Jamaica Center's time on by nearly eleven minutes while the train stood.
        val due = e.feeds.map { feed -> feed.trips.single().stops.last().time }
        assertEquals(1794846593L to 1794847237L, due[2] to due[26])
        val held = trains.zip(e.feeds) { train, feed -> (Ride(train, vanWyck, jamaicaCenter).state(feed.timestamp) as? RideState.Next)?.where?.takeIf { it.how == Train.How.HELD }?.since }
        // Four minutes after the feed first had it there, it is held, and stays so until it moves.
        assertEquals(1794846463L, trains[2].stoodSince)
        assertEquals(List(10) { null } + List(17) { 1794846463L } + listOf(null), held)
        // Held, the rider is given no time for a stop the train is not moving towards.
        val said = RideWords.statement(Ride(trains[10], vanWyck, jamaicaCenter), e.feeds[10].timestamp, catalog)
        assertEquals("Next" to "stop is Jamaica Center-Parsons/Archer", said.figure to said.headline)
        assertEquals(emptyList(), said.body)
        assertEquals(listOf("Standing at Sutphin Blvd-Archer Av-JFK Airport since ${clockLabel(1794846463L)}."), said.aside)

        // The download at 11:30:57 is the last to get through, three and a quarter minutes into the stay. The clock
        // running on to four minutes does not make the train held: it was not, by the last word of it. So the
        // screen never says "Standing at" and then counts from the times that would have disowned.
        val before = trains[8]
        assertEquals(1794846657L, before.asOf)
        (0L..300L).forEach { later ->
            val now = before.asOf + later
            assertFalse(before.whereAt(now)?.how == Train.How.HELD, "$later s on")
            val statement = RideWords.statement(Ride(before, vanWyck, jamaicaCenter), now, catalog)
            assertTrue((statement.body + statement.aside).none { it.startsWith("Standing at") }, "$later s on")
        }
        // The download at 11:32:57 is the last to get through, with the train held. It is left where it was: the
        // count stays at "next" for the five minutes anything is said, and never becomes "now" by its times.
        val after = trains[12]
        assertEquals(1794846777L, after.asOf)
        (0L..300L).forEach { later ->
            val now = after.asOf + later
            val next = assertIs<RideState.Next>(Ride(after, vanWyck, jamaicaCenter).state(now), "$later s on")
            assertEquals(if (later <= 60) Train.How.HELD else Train.How.WAS_THERE, next.where.how, "$later s on")
            assertEquals(later <= 60, next.sure)
        }
        val grey = RideWords.statement(Ride(after, vanWyck, jamaicaCenter), after.asOf + 120, catalog)
        assertEquals(listOf("Last seen standing at Sutphin Blvd-Archer Av-JFK Airport."), grey.aside)
        assertEquals("No word since ${clockLabel(after.asOf)}. This is where it was then.", grey.note)
    }

    @Test
    fun onEveryTrainInEveryReplayATrainCalledHeldIsNeverCountedOnWithoutNewWordOfIt() {
        // Whatever the last download to get through, and however long the screen then sits: once it has said
        // "Standing at ... since", only new word of the train moves the count or gives it a time again.
        val replays = mapOf(
            "gtfs-l" to listOf(l, Fixtures.lGoneMidRun),
            "gtfs" to listOf(one, Fixtures.fiveHeld, Fixtures.sevenHeldThenOff, Fixtures.fourNamesakes, Fixtures.shuttleStarting, Fixtures.fourAtItsTerminal),
            "gtfs-bdfm" to listOf(Fixtures.shuttleNoTrain, Fixtures.bStopGivenBack, Fixtures.bOverTheBridge, Fixtures.mTrainNamedLate),
            "gtfs-nqrw" to listOf(Fixtures.qSlotThenTrain, Fixtures.nNamedFarAhead),
            "gtfs-jz" to listOf(Fixtures.jEveryStop, Fixtures.jHeld),
            "gtfs-ace" to listOf(Fixtures.shuttleNamedTooFarDown, Fixtures.eHeld),
            "gtfs-g" to listOf(Fixtures.gGivenBackSkipping),
        )
        var looks = 0
        var heldLooks = 0
        replays.forEach { (slug, cut) ->
            cut.forEach { replay ->
                val started = HashSet<String>()
                replay.feeds.forEachIndexed { start, first ->
                    first.trips.filter { it.id.isNotEmpty() && started.add(it.id) }.forEach { trip ->
                        var train = Train.of(slug, first, trip, catalog)
                        replay.feeds.drop(start).forEach { feed ->
                            train = train.after(feed, catalog)
                            if (train.asOf == feed.timestamp && train.stops.isNotEmpty()) {
                                looks++
                                var held = false
                                for (later in 0L..300L) {
                                    val now = train.asOf + later
                                    val how = (train.whereAt(now) ?: break).how
                                    if (held) {
                                        assertTrue(how == Train.How.HELD || how == Train.How.WAS_THERE, "${trip.id} as of ${train.asOf}, $later s on: $how")
                                        assertEquals(train.stops, train.ahead(now), "${trip.id} as of ${train.asOf}, $later s on")
                                    }
                                    held = held || how == Train.How.HELD
                                }
                                if (held) heldLooks++
                            }
                        }
                    }
                }
            }
        }
        assertTrue(looks > 700 && heldLooks > 40, "$looks looks, $heldLooks of them at a train being held")
    }

    @Test
    fun aListHandedBackWithStopsTakenOutOfItTellsARiderForOneOfThemItNoLongerStopsThere() {
        // The G at Bedford-Nostrand Avs: off its list at 11:41:51, first on it again at 11:42:21, and first once
        // more at 11:42:51 with the four stops after it gone. It ran from there to Metropolitan Av.
        val g = Fixtures.gGivenBackSkipping
        val id = "067100_G..N14R"
        val classon = platform("Classon Av", "G")
        val broadway = platform("Broadway", "G")
        val metropolitan = platform("Metropolitan Av", "G")
        assertEquals(
            listOf("G34N", "G34N", "G33N", "G32N", "G33N", "G33N", "G29N", "G29N", "G29N"),
            g.feeds.map { it.trips.single().stops.first().stopId },
        )
        assertEquals(listOf("G33N", "G29N", "G26N", "G24N", "G22N"), g.feeds[5].trips.single().stops.map { it.stopId })
        // Aboard from Classon Av for Broadway. The train is in every one of these downloads, and is never called lost.
        assertEquals(
            listOf("waiting 0", "waiting 0", "riding 3", "riding 3", "riding 3", "dropped Broadway*", "dropped Broadway", "dropped Broadway", "dropped Broadway"),
            told(g, "gtfs-g", id, classon, broadway),
        )
        val trains = followed(g, "gtfs-g", id)
        assertTrue(trains.none { it.lost || it.ended })
        val changed = trains[5]
        assertEquals(listOf("Myrtle-Willoughby Avs", "Flushing Av", "Broadway", "Nassau Av"), changed.dropped.map { catalog.stopName(it.platform) })
        assertEquals(listOf("Classon Av"), changed.behind.map { catalog.stopName(it.platform) })
        assertEquals(g.feeds[5].timestamp, changed.changed)
        val statement = RideWords.statement(Ride(changed, classon, broadway), g.feeds[5].timestamp, catalog)
        assertEquals("This train no longer stops at Broadway.", statement.headline)
        assertEquals(listOf("Its next stops are Bedford-Nostrand Avs, then Metropolitan Av."), statement.body)
        val top = RideWords.top(changed.seenFrom(broadway, g.feeds[5].timestamp), "Broadway", g.feeds[5].timestamp, catalog)
        assertEquals("Not stopping at Broadway" to "Its stops have changed. Its next stops are Bedford-Nostrand Avs, then Metropolitan Av.", top.head to top.detail)
        // Waiting at Broadway for Metropolitan Av, the rider is told the same, and is not counted as aboard.
        assertEquals(
            listOf("waiting 4", "waiting 4", "waiting 3", "waiting 3", "waiting 3") + listOf("dropped Broadway*") + List(3) { "dropped Broadway" },
            told(g, "gtfs-g", id, broadway, metropolitan),
        )
        // And for a rider aboard to Metropolitan Av, four stops became one, with the change dated.
        assertEquals(
            listOf("waiting 0", "waiting 0", "riding 4", "riding 4", "riding 4", "next*", "next", "next", "next"),
            told(g, "gtfs-g", id, classon, metropolitan),
        )
    }

    @Test
    fun aTrainFirstNamedPastTheRidersStopHasLeftItAndIsNotCalledLost() {
        // The 11:37:30 M from Forest Hills-71 Av. The feed walked the empty trip down its timetable for seven
        // minutes, and at 11:44:36 named its train: standing at Elmhurst Av, five stops down, a minute early.
        val m = Fixtures.mTrainNamedLate
        val id = "069750_M..S24R"
        val forestHills = platform("Forest Hills-71 Av", "M")
        val woodhaven = platform("Woodhaven Blvd", "M")
        val trains = followed(m, "gtfs-bdfm", id)
        assertEquals(List(15) { false } + List(5) { true }, trains.map { it.assigned })
        assertEquals("G13S", m.feeds[15].trips.single().stops.first().stopId)
        // Aboard, or waiting, for Woodhaven Blvd: it has left there. It is in every download from the one that first named it.
        assertEquals(List(15) { "scheduled" } + List(5) { "past" }, told(m, "gtfs-bdfm", id, forestHills, woodhaven))
        assertEquals(List(15) { "scheduled" } + List(5) { "left" }, seen(m, "gtfs-bdfm", id, woodhaven))
        assertTrue(trains.none { it.lost })
        val named = trains[15]
        assertEquals(listOf("Forest Hills-71 Av", "67 Av", "63 Dr-Rego Park", "Woodhaven Blvd", "Grand Av-Newtown"), named.behind.map { catalog.stopName(it.platform) })
        assertTrue(named.behind.all { it.time == 0L })
        assertTrue(named.dropped.isEmpty())
        val statement = RideWords.statement(Ride(named, forestHills, woodhaven), m.feeds[15].timestamp, catalog)
        assertEquals("This train has left Woodhaven Blvd." to listOf("Its next stop is Elmhurst Av."), plain(statement.headline) to statement.body.map(::plain))
        // Nobody saw it leave, so the ride goes by when it was at Elmhurst Av: over ten minutes after that, not an hour on at the end of its run.
        val elmhurst = named.stops.first().time
        assertEquals(1794847465L, elmhurst)
        assertTrue(named.stops.last().time > elmhurst + 3000)
        assertFalse(Ride(named, forestHills, woodhaven).over(elmhurst + Ride.OVER_AFTER))
        assertTrue(Ride(named, forestHills, woodhaven).over(elmhurst + Ride.OVER_AFTER + 1))

        // While it had no train, "It has not left yet" was only said until its time to set out. Past that the train
        // was in fact running, and the screen said only what was known: that no train had been reported.
        val setsOut = 1794847050L
        val sixtySeventh = platform("67 Av", "M")
        trains.take(15).zip(m.feeds).forEach { (trip, feed) ->
            val now = feed.timestamp
            val body = RideWords.statement(Ride(trip, forestHills, woodhaven), now, catalog).body
            val page = RideWords.top(trip.seenFrom(sixtySeventh, now), "67 Av", now, catalog).detail
            if (now < setsOut) {
                assertEquals("It has not left yet.", body.first())
                assertEquals("It has not left Forest Hills-71 Av yet. These are timetable times.", page)
            } else {
                assertEquals("No train reported yet.", body.first())
                assertEquals("No train reported yet. These are timetable times.", page)
            }
        }
        assertEquals(List(2) { true } + List(13) { false }, m.feeds.take(15).map { it.timestamp < setsOut })
    }

    @Test
    fun aStopNobodySawTheTrainLeaveIsGivenBackTheFirstTimeItIsListedAgain() {
        // The 11:42 Rockaway Park shuttle. Its train was first named at 11:48:43 with a list starting at Beach 90 St,
        // and half a minute later was listed from Beach 105 St, two stops back, standing there.
        val h = Fixtures.shuttleNamedTooFarDown
        val id = "070200_H..N21R"
        val rockawayPark = platform("Rockaway Park-Beach 116 St", "S")
        val beach98 = platform("Beach 98 St", "S")
        val trains = followed(h, "gtfs-ace", id)
        assertEquals(List(15) { false } + List(6) { true }, trains.map { it.assigned })
        assertEquals(listOf("H12N", "H14N"), listOf(15, 16).map { h.feeds[it].trips.single().stops.first().stopId })
        // For one download the feed had the train past Beach 98 St, and the rider was told so. Then the count came back, dated.
        assertEquals(
            List(15) { "scheduled" } + listOf("past", "next*", "next", "next", "there", "there"),
            told(h, "gtfs-ace", id, rockawayPark, beach98),
        )
        assertTrue(trains.none { it.lost })
        assertEquals(listOf("H15N", "H14N", "H13N"), trains[15].behind.map { it.id })
        assertTrue(trains[15].behind.all { it.time == 0L })
        assertEquals(listOf("H15N"), trains[16].behind.map { it.id })
        assertEquals(listOf("H14N", "H13N", "H12N", "H04N"), trains[16].stops.map { it.id })
    }

    @Test
    fun aTrainWaitingToSetOutIsStillThereWhenADownloadIsMissed() {
        // The 4 standing at Crown Hts-Utica Av at 11:34:50, not due out until 11:54.
        val feed = Fixtures.fourAtItsTerminal.feeds[1]
        val train = assertNotNull(Train.find("gtfs", feed, "071400_4..N06R", catalog))
        assertTrue(train.standing)
        assertEquals(1794848040L, train.stops.first().time)
        val utica = platform("Crown Hts-Utica Av", "4")
        val ride = Ride(train, utica, platform("Atlantic Av-Barclays Ctr", "4"))
        (0L..300L step 20).forEach { later ->
            val now = feed.timestamp + later
            val here = assertIs<Sighting.Coming>(train.seenFrom(utica, now), "$later s on")
            assertEquals(0, here.stopsAway)
            assertEquals(later <= 60, here.sure)
            assertNull(here.heldSince)
            val waiting = assertIs<RideState.Waiting>(ride.state(now))
            assertEquals(2, waiting.stops)
        }
    }

    @Test
    fun withDownloadsWithheldTheGreyCountIsNeverFarFromTheOneThatComesBack() {
        // Every train in the L's recording, at every download, with one download and then
        // three in a row lost: the count just before word comes back, and the count it brings.
        var cases = 0
        var asLastSeen = 0
        val rises = HashMap<Int, Int>()
        fun count(train: Train, now: Long, goal: String): Int? =
            train.ahead(now).indexOfFirst { it.platform == goal }.takeIf { it >= 0 }?.let { it + if (train.standingAt(now)) 0 else 1 }
        listOf(2, 4).forEach { gap ->
            l.feeds.forEachIndexed { start, first ->
                first.trips.filter { it.assigned }.forEach { trip ->
                    var train = Train.of("gtfs-l", first, trip, catalog)
                    val goal = train.stops.last().platform
                    for (at in start + 1 until l.feeds.size - gap) {
                        train = train.after(l.feeds[at], catalog)
                        if (train.ended || train.lost || train.place(goal) < 0) break
                        val answer = l.feeds[at + gap]
                        val back = train.after(answer, catalog)
                        if (back.changed != train.changed || back.asOf != answer.timestamp) continue
                        val grey = count(train, answer.timestamp, goal) ?: continue
                        val live = count(back, answer.timestamp, goal) ?: continue
                        cases++
                        rises[live - grey] = (rises[live - grey] ?: 0) + 1
                        // A train that was behind its times is where it was last seen, and the count that comes back is never above that.
                        if (train.whereAt(answer.timestamp)!!.how in setOf(Train.How.WAS_COMING, Train.How.WAS_THERE)) {
                            asLastSeen++
                            assertEquals(count(train, train.asOf, goal), grey, trip.id)
                            assertTrue(live <= grey, "${trip.id} at ${train.asOf}: $grey then $live")
                        }
                        // For the rest it runs early, and is put right by a stop or, rarely, two.
                        assertTrue(live - grey <= 2, "${trip.id} at ${train.asOf}: $grey then $live")
                    }
                }
            }
        }
        assertTrue(cases > 2000 && asLastSeen > 30, "$cases cases, $asLastSeen of them left where they were")
        // Most of the time nothing has to be taken back. This is what "runs early" costs, and it is said plainly.
        assertTrue(rises.filterKeys { it > 0 }.values.sum() * 4 < cases, rises.toString())
    }
}
