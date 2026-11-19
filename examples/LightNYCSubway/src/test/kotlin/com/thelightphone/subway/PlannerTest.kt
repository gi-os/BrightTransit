package com.thelightphone.subway

import com.thelightphone.subway.data.Direct
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Plan
import com.thelightphone.subway.data.Planner
import com.thelightphone.subway.data.RideState
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.Sighting
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.data.Train
import com.thelightphone.subway.ui.PlanWords
import com.thelightphone.subway.ui.clockLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which trains run direct from one station to another, asked of every train in the system
 * on a weekday afternoon.
 *
 * Each list of trains below was worked out a second time, in Python, from the same recorded
 * bytes and from the rules in words alone, and the two agree. A train is written as its
 * line in the feed, its trip, when it leaves, when it gets there ("--" for no time), how
 * many stops the ride is, and "slot" when no train has the trip yet.
 */
class PlannerTest {
    private val day = Fixtures.weekdayAfternoon
    private val catalog = Fixtures.catalog
    private val now = day.time

    private val newYork = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }

    private fun clock(time: Long) = newYork.format(Date(time * 1000))

    private fun at(clock: String): Long = now - now % 60 + (0L..3600).first { clock(now - now % 60 + it) == clock }

    private fun plan(from: Station, to: Station, without: Set<String> = emptySet(), at: Long = now): Plan =
        Planner.plan(from, to, catalog, Planner.slugs(from, to).filter { it !in without }.associateWith { day.feeds.getValue(it) }, at)

    private fun Direct.said() =
        "${train.route} ${train.id} ${clock(leaves)} ${if (arrives > 0) clock(arrives) else "--"} $stops${if (running) "" else " slot"}"

    private fun Plan.all(): List<String> = trains.map { it.said() }

    private fun Plan.offered(at: Long = now, after: Plan.After? = null): List<String> =
        (view(at, after) as? Plan.View.Trains)?.choices.orEmpty().map { it.said() }

    private fun Plan.lines(): Set<String> = trains.mapTo(HashSet()) { it.train.route }

    private val unionSq = Fixtures.station("14 St-Union Sq")
    private val grandCentral = Fixtures.station("Grand Central-42 St")
    private val timesSq = Fixtures.station("Times Sq-42 St")
    private val morgan = Fixtures.station("Morgan Av")
    private val jay = Fixtures.station("Jay St-MetroTech")
    private val stGeorge = Fixtures.station("St George")

    @Test
    fun theExpressInThreeMinutesBeatsTheLocalInTwoAndBothAreOffered() {
        val plan = plan(Fixtures.station("96 St", "1"), timesSq)
        assertEquals(
            listOf(
                "2 091550_2..S17R 16:03:15 16:10:45 2",
                "1 093350_1..S15R 16:02:17 16:13:47 7",
            ),
            plan.offered(),
        )
        // Every train that goes there is found, the three lines together, soonest there first.
        assertEquals(31, plan.trains.size)
        assertEquals(setOf("1", "2", "3"), plan.lines())
        assertEquals(
            listOf(
                "2 091550_2..S17R 16:03:15 16:10:45 2",
                "3 095250_3..S06R 16:05:04 16:12:34 2",
                "1 093350_1..S15R 16:02:17 16:13:47 7",
                "1 093850_1..S15R 16:04:46 16:16:16 7",
            ),
            plan.all().take(4),
        )
    }

    @Test
    fun aTrainThatLeavesLaterAndGetsThereLaterIsNoChoice() {
        val plan = plan(unionSq, grandCentral)
        assertEquals(setOf("4", "5", "6", "6X"), plan.lines())
        assertEquals(listOf("4 093600_4..N06R 16:03:00 16:07:00 1"), plan.offered())
    }

    @Test
    fun theTrainsAfterTheFirstToGoAreAStepAway() {
        val plan = plan(unionSq, grandCentral)
        val first = assertIs<Plan.View.Trains>(plan.view(now))
        // The step after passes over the 4 at 4:03, by name as well as by its time.
        val later = assertNotNull(first.later)
        assertEquals(at("16:03:00"), later.time)
        assertEquals(setOf("093600_4..N06R"), later.passed.mapTo(HashSet()) { it.second })
        // Without it, the next express is first and the local that leaves just before it is offered too.
        assertEquals(
            listOf(
                "4 093950_4..N06R 16:06:45 16:10:45 1",
                "6 095600_6..N03R 16:06:20 16:12:20 4",
            ),
            plan.offered(after = later),
        )
        // The time alone does as much, for as long as no train's time has moved.
        assertEquals(plan.offered(after = later), plan.offered(after = Plan.After(at("16:03:00"))))
        // Each step drops the train that leaves first, until none is left.
        var after: Plan.After? = null
        var steps = 0
        val seen = ArrayList<Long>()
        while (true) {
            val view = plan.view(now, after) as? Plan.View.Trains ?: break
            val passed = after
            if (passed != null) assertTrue(view.choices.all { it.leaves > passed.time && it.key !in passed.passed })
            seen += view.choices.minOf { it.leaves }
            after = view.later ?: break
            assertEquals(plan.trains.filter { it.leaves <= seen.last() }.mapTo(HashSet()) { it.key }, after.passed)
            steps++
        }
        assertEquals(seen.sorted(), seen)
        assertEquals(plan.trains.map { it.leaves }.distinct().size, steps + 1)
        assertEquals(Plan.View.None, plan.view(now, Plan.After(plan.trains.maxOf { it.leaves })))
    }

    @Test
    fun anExpressAndALocalLeavingInTheSameSecondAreBothOffered() {
        val plan = plan(Fixtures.station("125 St", "A"), Fixtures.station("59 St-Columbus Circle"))
        assertEquals(setOf("A", "B", "C", "D"), plan.lines())
        assertEquals(
            listOf(
                "A 094450_A..S20X002 16:00:17 16:07:14 1",
                "B 095700_B..S66R 16:00:17 16:12:33 8",
            ),
            plan.offered(),
        )
    }

    @Test
    fun linesThatRideInThreeFeedsAreWeighedTogether() {
        val plan = plan(Fixtures.station("Jackson Hts-Roosevelt Av"), Fixtures.station("Queens Plaza"))
        assertEquals(setOf("gtfs-ace", "gtfs-bdfm", "gtfs-nqrw"), plan.trains.mapTo(HashSet()) { it.train.feed })
        assertEquals(setOf("E", "F", "FX", "R"), plan.lines())
        assertEquals(listOf("E 094250_E..S71R 16:00:17 16:04:23 1"), plan.offered())
    }

    @Test
    fun twoPlatformsOfTheFirstStationCompete() {
        val plan = plan(Fixtures.station("Canal St", "N"), timesSq)
        assertEquals(setOf("Q01", "R23"), plan.trains.mapTo(HashSet()) { it.from })
        assertEquals(
            listOf(
                "Q 091150_Q..N36R 16:00:07 16:08:17 3",
                "W 095100_N..N72R 16:00:07 16:11:44 7",
            ),
            plan.offered(),
        )
        assertEquals(listOf("Q01", "R23"), (plan.view(now) as Plan.View.Trains).choices.map { it.from })
    }

    @Test
    fun twoPlatformsOfTheSecondStationCompete() {
        val plan = plan(Fixtures.station("Atlantic Av-Barclays Ctr"), Fixtures.station("Canal St", "N"))
        assertEquals(setOf("Q01", "R23"), plan.trains.mapTo(HashSet()) { it.to })
        // The N over the bridge in four minutes is there before the R that is leaving through the tunnel now.
        assertEquals(
            listOf(
                "N 093000_N..N16R 16:03:51 16:14:51 1",
                "R 094000_R..N93R 16:00:07 16:19:17 8",
            ),
            plan.offered(),
        )
    }

    @Test
    fun aStretchOfStopsListedASecondApartGivesNoArrivalTime() {
        // This N is listed at Atlantic Av at 4:08:35 and at each of its next eight stops a second after the one before.
        val toCanal = plan(Fixtures.station("Atlantic Av-Barclays Ctr"), Fixtures.station("Canal St", "N"))
        assertEquals("N 094000_N..N35R 16:08:35 -- 1", toCanal.all().single { "094000_N..N35R" in it })
        val onward = plan(Fixtures.station("Canal St", "N"), timesSq)
        assertEquals("N 094000_N..N35R 16:08:36 -- 3", onward.all().single { "094000_N..N35R" in it })
        // With no time to go by it is last of them all, and a train that leaves before it puts it out.
        assertEquals(1, toCanal.trains.count { it.arrives == 0L })
        assertEquals("094000_N..N35R", toCanal.trains.last().train.id)
        assertFalse(toCanal.offered().any { "094000_N..N35R" in it })
    }

    @Test
    fun aLineThatDoesNotStopThereIsNotOffered() {
        val plan = plan(unionSq, Fixtures.station("23 St-Baruch College"))
        assertEquals(setOf("6", "6X"), plan.lines())
        assertEquals(12, plan.trains.size)
        assertEquals(listOf("6 095600_6..N03R 16:06:20 16:07:50 1"), plan.offered())
    }

    @Test
    fun aSlotNothingFilledIsNotOffered() {
        // Two 6 trips were to set out from Brooklyn Bridge at 3:53 and 3:57 and no train took
        // them. The feed still lists them through Union Sq at 4:01 and 4:05, ahead of the next
        // real 6, and the station's own page still shows them.
        val ghosts = setOf("095350_6..N03R", "095750_6..N02R")
        val listed = day.board(unionSq).arrivals.filter { it.trip in ghosts }
        assertEquals(listOf("16:01:30", "16:05:30"), listed.map { clock(it.epochSeconds) })
        assertTrue(listed.none { it.assigned })

        val plan = plan(unionSq, Fixtures.station("23 St-Baruch College"))
        assertTrue(plan.trains.none { it.train.id in ghosts })
        assertEquals("6 095600_6..N03R 16:06:20 16:07:50 1", plan.all().first())
        // A slot that has not come up yet is offered, as a slot.
        assertEquals("6 096200_6..N03R 16:10:00 16:12:00 1 slot", plan.all()[1])
        // The same goes the other way: a 6 due out of Pelham Bay Park at 3:20 that never left.
        val downtown = plan(Fixtures.station("77 St", "6"), Fixtures.station("59 St", "6"))
        assertTrue(day.board(Fixtures.station("77 St", "6")).arrivals.any { it.trip == "092000_6..S01R" })
        assertTrue(downtown.trains.none { it.train.id == "092000_6..S01R" })
    }

    /** When a trip's id says it sets out: hundredths of a minute past midnight in New York, on the snapshot's day. */
    private fun idTime(trip: GtfsRealtime.Trip): Long? {
        val hundredths = trip.id.substringBefore('_').toLongOrNull() ?: return null
        val midnight = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
            timeInMillis = now * 1000
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return midnight.timeInMillis / 1000 + hundredths * 6 / 10
    }

    @Test
    fun onTheLetteredLinesATripPastItsTimeToSetOutIsStillOfferedAsScheduledWhileItsStopIsListed() {
        // The Rockaway Park shuttle due out at 3:57:30 had no train three minutes later. The
        // feed had taken its first two stops off the list all the same, as if it had run.
        // On the lettered lines that is nearly always a train the feed gives the trip a
        // minute or two late, and the station's own page lists it. So does the way there.
        val shuttle = day.feeds.getValue("gtfs-ace").trips.single { it.id == "095750_H..N21R" }
        assertFalse(shuttle.assigned)
        assertEquals(listOf("H13N" to at("16:01:30"), "H12N" to at("16:03:00")), shuttle.stops.take(2).map { it.stopId to it.time })
        assertEquals(at("16:01:30") - 240, Planner.setsOut(shuttle))
        val beach98 = Fixtures.station("Beach 98 St")
        assertTrue(day.board(beach98).arrivals.any { it.trip == shuttle.id })
        val toBroadChannel = plan(beach98, Fixtures.station("Broad Channel"))
        assertEquals(listOf("H 095750_H..N21R 16:01:30 16:07:45 2 slot"), toBroadChannel.offered())
        assertEquals("H 097050_H..N21R 16:14:30 16:20:45 2 slot", toBroadChannel.all()[1])
        // It goes when the board lets go of it: half a minute past its time at the stop.
        assertEquals(listOf("H 097050_H..N21R 16:14:30 16:20:45 2 slot"), toBroadChannel.offered(at = at("16:01:30") + 31))
        // A G due out of Church Av at 3:58:30 was the same. It is offered, and does not put out the F that was running.
        val g = day.feeds.getValue("gtfs-g").trips.single { it.id == "095850_G..N14R" }
        assertEquals(Triple(false, "F26N", at("16:00:30")), Triple(g.assigned, g.stops.first().stopId, g.stops.first().time))
        assertEquals(at("16:00:30") - 120, Planner.setsOut(g))
        val toSeventhAv = plan(Fixtures.station("Fort Hamilton Pkwy", "G"), Fixtures.station("7 Av", "G"))
        assertEquals(
            listOf(
                "G 095850_G..N14R 16:00:30 16:04:30 2 slot",
                "F 094600_F..N54R 16:06:13 16:10:13 2",
            ),
            toSeventhAv.offered(),
        )
    }

    @Test
    fun aShuttleWithNoTrainIsOfferedAtEachStationUntilItsTimeThereHasPassed() {
        val downloads = Fixtures.shuttleNoTrain.feeds
        val trips = downloads.map { it.trips.single() }
        val out = trips.first().stops.first().time
        assertEquals(listOf(40L, 10L, -20L), downloads.take(3).map { out - it.timestamp })
        // No train until the last download, and a first stop that moves down the line as the times pass.
        assertEquals(List(10) { false } + true, trips.map { it.assigned })
        assertEquals(
            listOf("S01S", "S01S", "S01S", "S03S", "S03S", "S03S", "S03S", "S04S", "S04S", "S04S", "D26S"),
            trips.map { it.stops.first().stopId },
        )
        // Going by that first stop, the trip would be past its time, then ahead of it again, three times over.
        assertEquals(
            listOf(true, true, false, true, true, true, false, true, true, false, true),
            downloads.zip(trips) { feed, trip -> trip.stops.first().time >= feed.timestamp },
        )
        // Whatever the list starts with, the trip set out when its id says.
        trips.forEach { assertEquals(out, Planner.setsOut(it)) }

        val prospectPark = Fixtures.station("Prospect Park")
        fun offered(from: Station) = downloads.map { feed ->
            val plan = Planner.plan(from, prospectPark, catalog, mapOf("gtfs-bdfm" to feed), feed.timestamp)
            (plan.view(feed.timestamp) as? Plan.View.Trains)?.choices.orEmpty().map { it.train.id }
        }
        // The feed gave this one its train four and a half minutes late, when it had all but
        // finished. It ran all the same, and the page of each station listed it until it
        // was half a minute past its time there. So it is offered, as a slot, for as long.
        fun downloads(count: Int) = List(count) { listOf("067150_FS.S01R") } + List(11 - count) { emptyList() }
        assertEquals(listOf(-40L, -10L, 20L, 50L), downloads.take(4).map { it.timestamp - out })
        assertEquals(downloads(3), offered(Fixtures.station("Franklin Av", "S")))
        // Park Pl two minutes down the line, and Botanic Garden a minute and a half after that.
        assertEquals(downloads(7), offered(Fixtures.station("Park Pl", "S")))
        // The shuttle's Botanic Garden platform is inside the station called Franklin Av-Medgar Evers College.
        assertEquals(downloads(10), offered(Fixtures.station("Franklin Av-Medgar Evers College")))
    }

    @Test
    fun theTimeATripSetsOutIsReadFromItsIdTheSameInEveryFeed() {
        var slots = 0
        day.feeds.values.forEach { feed ->
            feed.trips.filterNot { it.assigned }.forEach { trip ->
                assertEquals(idTime(trip), Planner.setsOut(trip), trip.id)
                slots++
            }
        }
        assertEquals(364, slots)
        // All but those two list the stop they set out from first, at the time in the id or, on the L, a quarter minute before it.
        val moved = day.feeds.values.flatMap { it.trips }.filter { !it.assigned && it.stops.first().time - Planner.setsOut(it) !in -15L..1L }
        assertEquals(setOf("095750_H..N21R", "095850_G..N14R"), moved.mapTo(HashSet()) { it.id })
    }

    @Test
    fun aTrainThatTurnsBackBeforeTheStationIsNotOffered() {
        // The 6 local ends at Parkchester. Only the express goes on to Pelham Bay Park.
        val plan = plan(unionSq, Fixtures.station("Pelham Bay Park"))
        assertEquals(setOf("6X"), plan.lines())
        assertEquals(listOf("6X 096250_6..N02R 16:10:30 16:59:00 23"), plan.offered())
        assertEquals(
            listOf(
                "6X 096250_6..N02R 16:10:30 16:59:00 23",
                "6X 096600_6..N02R 16:14:00 17:03:00 23 slot",
                "6X 097400_6..N02R 16:22:00 17:11:00 23 slot",
                "6X 098150_6..N02R 16:29:30 17:18:30 23 slot",
                "6X 098900_6..N02R 16:37:00 17:26:00 23 slot",
            ),
            plan.all(),
        )
    }

    @Test
    fun aLineSkippingTheStationOneWayIsOnlyOfferedTheOtherWay() {
        // The 7 to Flushing was not stopping at 52 St that afternoon. The 7 to Manhattan was.
        val queensboro = Fixtures.station("Queensboro Plaza")
        val fiftySecond = Fixtures.station("52 St")
        assertEquals(Plan.View.None, plan(queensboro, fiftySecond).view(now))
        assertEquals(emptyList(), plan(queensboro, fiftySecond).trains)
        val back = plan(fiftySecond, queensboro)
        assertEquals(15, back.trains.size)
        assertEquals(listOf("7 094650_7..S 16:03:35 16:09:35 4"), back.offered())
    }

    @Test
    fun aLocalAndItsExpressThatBothStopThereAreBothFound() {
        val plan = plan(grandCentral, Fixtures.station("61 St-Woodside"))
        assertEquals(setOf("7", "7X"), plan.lines())
        assertEquals(listOf("7 095650_7..N 16:01:33 16:18:53 8"), plan.offered())
        assertEquals("7X 096050_7X..N 16:03:12 16:20:32 8", plan.all()[1])
    }

    @Test
    fun aBranchWithNoTrainsHasNoWayThere() {
        // The A was not running to Lefferts Blvd. Nothing is made up from the line map: there is none.
        assertEquals(Plan.View.None, plan(jay, Fixtures.station("Ozone Park-Lefferts Blvd")).view(now))
        // Of everything at Jay St, only the A trains bound for the Rockaways go to Howard Beach.
        val howardBeach = plan(jay, Fixtures.station("Howard Beach-JFK Airport"))
        assertEquals(setOf("A"), howardBeach.lines())
        assertEquals(19, howardBeach.trains.size)
        assertEquals(listOf("A 092100_A..S58R 16:04:13 16:33:13 11"), howardBeach.offered())
        // Some of them end there, and are offered all the same.
        assertTrue(howardBeach.trains.any { it.lastStop == "Howard Beach-JFK Airport" })
    }

    @Test
    fun whereNoTrainRunsDirectThereIsNothingToSay() {
        val plan = plan(morgan, timesSq)
        assertEquals(emptyList(), plan.trains)
        assertEquals(Plan.View.None, plan.view(now))
        // No line stops at both, so a feed that could not be had leaves no line to name.
        // But "no direct train" is not said without it: a train can run between two
        // stations that share no line in the station file, and that feed is where it would be.
        assertEquals(Plan.View.Unchecked(emptyList()), plan(morgan, timesSq, without = setOf("gtfs-l", "gtfs")).view(now))
        assertEquals(Plan.View.Unchecked(emptyList()), plan(morgan, timesSq, without = setOf("gtfs-nqrw")).view(now))
        // Old feeds are feeds not had.
        assertEquals(Plan.View.Unchecked(emptyList()), plan.view(now + Planner.FRESH_FOR + 1))
    }

    @Test
    fun aTrainBetweenStationsThatShareNoLineIsNotSaidNotToRunWhenItsFeedCouldNotBeHad() {
        // W trains were running from Kings Hwy on the N to Jay St-MetroTech, which lists the A C F R.
        val kingsHwy = Fixtures.station("Kings Hwy", "N")
        assertTrue(Planner.shared(kingsHwy, jay).isEmpty())
        val whole = plan(kingsHwy, jay)
        assertEquals(listOf("W 099200_N..N70R"), whole.trains.map { "${it.train.route} ${it.train.id}" })
        assertIs<Plan.View.Trains>(whole.view(now))
        // The next download of the feed it rides in fails. There is no train to offer, and no saying there is none.
        val without = plan(kingsHwy, jay, without = setOf("gtfs-nqrw"))
        assertEquals(emptyList(), without.trains)
        assertEquals(Plan.View.Unchecked(emptyList()), without.view(now))
        // With every feed in hand and the train gone, it is said.
        assertEquals(Plan.View.None, whole.view(now, Plan.After(whole.trains.maxOf { it.leaves })))
    }

    @Test
    fun theOtherDirectionIsNeverOffered() {
        val toManhattan = plan(morgan, unionSq)
        assertEquals(12, toManhattan.trains.size)
        assertTrue(toManhattan.trains.all { it.train.id.endsWith("_L..N") && it.heading == "Manhattan" })
        assertEquals(listOf("L 094300_L..N 16:02:31 16:16:01 8"), toManhattan.offered())

        val toBrooklyn = plan(unionSq, morgan)
        assertEquals(8, toBrooklyn.trains.size)
        assertTrue(toBrooklyn.trains.all { it.train.id.endsWith("_L..S") && it.heading == "Brooklyn" })
        assertEquals(listOf("L 095750_L..S 16:00:38 16:14:13 8"), toBrooklyn.offered())
    }

    @Test
    fun aShuttleThatIsOnlyEverOnTheTimetableIsOfferedAsThat() {
        val plan = plan(Fixtures.station("Franklin Av", "S"), Fixtures.station("Prospect Park"))
        assertEquals(6, plan.trains.size)
        assertTrue(plan.trains.none { it.running })
        assertEquals(listOf("FS 096150_FS.S01R 16:01:30 16:08:00 3 slot"), plan.offered())
        // It is looked for in two feeds and rides in one. Having only the one it rides in leaves nothing unchecked.
        val oneFeed = plan(Fixtures.station("Franklin Av", "S"), Fixtures.station("Prospect Park"), without = setOf("gtfs-ace"))
        assertEquals(plan.view(now), oneFeed.view(now))
        val other = plan(Fixtures.station("Franklin Av", "S"), Fixtures.station("Prospect Park"), without = setOf("gtfs-bdfm"))
        assertEquals(Plan.View.Unchecked(listOf("S")), other.view(now))
    }

    @Test
    fun aTrainOnlyOnTheTimetableDoesNotPutARunningOneOut() {
        // The Rockaway Park shuttle's next four trips have no train yet. The first A that
        // will make the trip is more than an hour off, and is the only train there is.
        val plan = plan(Fixtures.station("Broad Channel"), Fixtures.station("Rockaway Park-Beach 116 St"))
        assertEquals(
            listOf(
                "H 096500_H..S21R 16:06:30 16:15:30 4 slot",
                "A 096150_A..S87R 17:12:43 17:22:13 4",
            ),
            plan.offered(),
        )
        // The shuttle turns round at a siding that is no station. It is not counted as a stop.
        assertEquals(listOf("H19S", "H04S"), day.feeds.getValue("gtfs-ace").trips.first { it.id == "096500_H..S21R" }.stops.take(2).map { it.stopId })
        assertEquals("H04", plan.trains.first().from)
    }

    @Test
    fun theShuttleAndTheSevenCompeteAcross42St() {
        val plan = plan(grandCentral, timesSq)
        assertEquals(setOf("GS", "7"), plan.lines())
        assertEquals(
            listOf(
                "GS 096100_GS.N04R 16:01:00 16:02:30 1",
                "7 092950_7..S 16:00:51 16:03:51 2",
            ),
            plan.offered(),
        )
        val choices = (plan.view(now) as Plan.View.Trains).choices
        assertEquals(listOf("901" to "902", "723" to "725"), choices.map { it.from to it.to })
        assertEquals(listOf("S", "7"), choices.map { it.route })
    }

    @Test
    fun twoStationsWithOneNameAreToldApartByTheirPlatforms() {
        val sixthAv = Fixtures.station("14 St", "F")
        val eighthAv = Fixtures.station("14 St", "A")
        assertTrue(catalog.hasNamesake(sixthAv) && catalog.hasNamesake(eighthAv))
        assertFalse(catalog.hasNamesake(morgan))
        val plan = plan(sixthAv, eighthAv)
        assertEquals(listOf("L 092850_L..N 16:07:28 16:09:58 1"), plan.offered())
        val train = plan.trains.first()
        assertEquals("6 Av" to "8 Av", catalog.stopName(train.from) to catalog.stopName(train.to))
        // And nothing runs the other way on that track: 8 Av is the end of the line.
        assertEquals(15, plan.trains.size)
        assertTrue(plan(eighthAv, sixthAv).trains.all { it.train.id.endsWith("_L..S") })
    }

    @Test
    fun aRideEndsAtThePlatformTheTrainStopsAtWhateverTheStationIsCalled() {
        val plan = plan(morgan, Fixtures.station("14 St", "A"))
        assertEquals(listOf("L 094300_L..N 16:02:31 16:20:01 10"), plan.offered())
        assertEquals("L01", plan.trains.first().to)
        assertEquals("8 Av", catalog.stopName(plan.trains.first().to))
    }

    @Test
    fun theJAndTheMCompeteThoughTheFeedsLetterThemOppositeWays() {
        val plan = plan(Fixtures.station("Myrtle Av", "J"), Fixtures.station("Delancey St-Essex St"))
        assertEquals(setOf("J", "M"), plan.lines())
        assertEquals(listOf("J 092900_J..S12R 16:04:13 16:16:43 5"), plan.offered())
        assertEquals("M 095650_M..N24R 16:13:47 16:26:17 5", plan.all()[2])
        // One platform and one way out of it, whatever the letters say.
        assertEquals(setOf("Manhattan"), plan.trains.mapTo(HashSet()) { it.heading })
        assertEquals(setOf("M11"), plan.trains.mapTo(HashSet()) { it.from })
    }

    @Test
    fun aReroutedTrainIsOfferedWhereItIsReallyGoing() {
        // An F was running up the G line that afternoon. It is in the feed, it stops at
        // Nassau Av and then at Court Sq, and it gets there before the next G.
        val plan = plan(Fixtures.station("Nassau Av"), Fixtures.station("Court Sq"))
        assertEquals(listOf("F 089250_F..N 16:08:19 16:14:19 3"), plan.offered())
        assertEquals("G 093500_G..N14R 16:10:43 16:16:43 3", plan.all()[1])
        assertTrue(plan.trains.first().running)
    }

    @Test
    fun aLineTheStationDoesNotListIsOfferedWhenItsTrainsGoThere() {
        // Some N trains were ending at 96 St on the Q's line.
        val plan = plan(timesSq, Fixtures.station("96 St", "Q"))
        assertEquals(setOf("Q", "N"), plan.lines())
        assertEquals(listOf("Q 090350_Q..N36R 16:01:14 16:13:44 5"), plan.offered())
        assertEquals("N 093000_N..N16R 16:24:36 16:37:06 5", plan.all()[4])
    }

    @Test
    fun theRailwaysLastStopHasNoArrivalTime() {
        // This snapshot holds no positions, so every railway trip reads here as a train. With
        // them, a trip that has not set out is a slot: see PlannerPositionsTest.
        val inbound = plan(Fixtures.station("Tottenville"), stGeorge)
        assertEquals(
            listOf(
                "SI 097900_SI.N03R 16:19:00 -- 20",
                "SI 099900_SI.N03R 16:39:00 -- 20",
                "SI 101400_SI.N03R 16:54:00 -- 20",
            ),
            inbound.all(),
        )
        // With no times to go by, the first to leave is taken to be the first there.
        assertEquals(listOf("SI 097900_SI.N03R 16:19:00 -- 20"), inbound.offered())

        val outbound = plan(stGeorge, Fixtures.station("Tottenville"))
        assertEquals(listOf("SS 096100_SI.S07R 16:01:00 -- 9"), outbound.offered())
        assertEquals("SI 096300_SI.S03R 16:03:00 -- 20", outbound.all()[1])
    }

    @Test
    fun theRailwaysExpressIsWeighedAgainstItsLocal() {
        // As above: with no positions in this snapshot, none of these trips reads as a slot.
        // To Great Kills the express makes one stop and the local twelve.
        val greatKills = plan(stGeorge, Fixtures.station("Great Kills"))
        assertEquals(listOf("SS 096100_SI.S07R 16:01:00 16:17:00 1"), greatKills.offered())
        assertEquals("SI 096300_SI.S03R 16:03:00 16:28:00 12", greatKills.all()[1])
        // A local that ends at Great Kills is offered there, with no time for its last stop.
        assertEquals("SI 101300_SI.S02R 16:53:00 -- 12", greatKills.all().last())
        // The express does not stop at Grasmere.
        val grasmere = plan(stGeorge, Fixtures.station("Grasmere"))
        assertEquals(setOf("SI"), grasmere.lines())
        assertEquals(listOf("SI 096300_SI.S03R 16:03:00 16:13:00 4"), grasmere.offered())
        // And the local that ends at Great Kills does not go on to Huguenot.
        val huguenot = plan(stGeorge, Fixtures.station("Huguenot"))
        assertEquals(5, huguenot.trains.size)
        assertTrue(huguenot.trains.none { it.train.id == "101300_SI.S02R" })
        assertEquals(listOf("SS 096100_SI.S07R 16:01:00 16:23:00 4"), huguenot.offered())
    }

    @Test
    fun aTrainIsNamedAsThePageOfTheStationItLeavesFromNamesIt() {
        val board = day.board(unionSq)
        plan(unionSq, grandCentral).trains.forEach { train ->
            val listed = board.arrivals.single { it.trip == train.train.id && it.feed == train.train.feed }
            assertEquals(listed.heading, train.heading)
            assertEquals(listed.destination, train.lastStop)
            assertEquals(listed.route, train.route)
            assertEquals(listed.epochSeconds, train.leaves)
            assertEquals(listed.stop, train.from)
        }
        val first = plan(unionSq, grandCentral).trains.first()
        assertEquals(Triple("4", "Uptown", "Woodlawn"), Triple(first.route, first.heading, first.lastStop))
    }

    @Test
    fun aFeedThatCouldNotBeHadNamesItsLinesAndTheRestStandsAsFarAsItGoes() {
        val west4 = Fixtures.station("W 4 St-Wash Sq")
        val whole = plan(jay, west4)
        assertEquals(setOf("A", "C", "F"), whole.lines())
        assertEquals(emptyList(), assertIs<Plan.View.Trains>(whole.view(now)).unchecked)
        assertEquals(listOf("A 090500_A..N55R 16:00:12 16:10:25 5"), whole.offered())

        val noF = plan(jay, west4, without = setOf("gtfs-bdfm"))
        assertEquals(setOf("A", "C"), noF.lines())
        assertEquals(listOf("F"), (noF.view(now) as Plan.View.Trains).unchecked)
        assertEquals(listOf("F"), noF.unchecked(now))

        val noAC = plan(jay, west4, without = setOf("gtfs-ace"))
        assertEquals(setOf("F"), noAC.lines())
        assertEquals(listOf("F 090500_F..N 16:00:17 16:11:10 6"), noAC.offered())
        assertEquals(listOf("A", "C"), (noAC.view(now) as Plan.View.Trains).unchecked)

        val neither = plan(jay, west4, without = setOf("gtfs-ace", "gtfs-bdfm"))
        assertEquals(Plan.View.Unchecked(listOf("A", "C", "F")), neither.view(now))
        // The R's feed is fetched for Jay St, and no R goes to W 4 St. Losing it changes nothing.
        assertEquals(whole.view(now), plan(jay, west4, without = setOf("gtfs-nqrw")).view(now))
    }

    @Test
    fun aFeedTooOldToPlanFromIsAsGoodAsNotHad() {
        // The L's feed was built seven seconds before the last of that afternoon's.
        val built = day.feeds.getValue("gtfs-l").timestamp
        val plan = plan(morgan, unionSq)
        assertEquals(mapOf("gtfs" to day.feeds.getValue("gtfs").timestamp, "gtfs-l" to built, "gtfs-nqrw" to day.feeds.getValue("gtfs-nqrw").timestamp), plan.built)
        assertIs<Plan.View.Trains>(plan.view(built + Planner.FRESH_FOR))
        assertEquals(Plan.View.Unchecked(listOf("L")), plan.view(built + Planner.FRESH_FOR + 1))
        // Made from feeds already that old, it has no trains in it at all.
        val late = plan(morgan, unionSq, at = built + Planner.FRESH_FOR + 1)
        assertEquals(emptyList(), late.trains)
        assertFalse("gtfs-l" in late.built)
        assertEquals(Plan.View.Unchecked(listOf("L")), late.view(built + Planner.FRESH_FOR + 1))
    }

    @Test
    fun aTrainIsNoLongerOfferedOnceItHasLeftThePlatform() {
        val plan = plan(unionSq, morgan)
        val leaves = at("16:00:38")
        assertEquals(listOf("L 095750_L..S 16:00:38 16:14:13 8"), plan.offered(at = leaves + 30))
        assertEquals(listOf("L 096250_L..S 16:05:45 16:19:15 8"), plan.offered(at = leaves + 31))
    }

    /** Every station as a place to start, to a few stations on a line it shares and one anywhere. */
    private fun manyPairs(): List<Pair<Station, Station>> {
        val random = Random(20261112)
        return catalog.stations.flatMap { from ->
            val sameLine = catalog.stations.filter { it.id != from.id && it.routes.any(from.routes::contains) }.shuffled(random).take(4)
            val anywhere = catalog.stations.filter { it.id != from.id }.shuffled(random).take(1)
            (sameLine + anywhere).distinct().map { from to it }
        }
    }

    @Test
    fun everyTrainInAPlanIsATripInTheFeedThatStopsAtTheFirstStationAndLaterAtTheSecond() {
        var found = 0
        manyPairs().forEach { (from, to) ->
            val plan = plan(from, to)
            plan.trains.forEach { train ->
                val trip = day.feeds.getValue(train.train.feed).trips.single { it.id == train.train.id }
                val on = trip.stops.indexOfFirst { it.stopId.dropLast(1) == train.from && it.time == train.leaves }
                assertTrue(on >= 0, "$train boards where its trip stops")
                val off = trip.stops.indexOfLast { it.stopId.dropLast(1) == train.to }
                assertTrue(off > on, "$train gets off after it gets on")
                assertTrue(from.platforms.any { it.stop == train.from } && to.platforms.any { it.stop == train.to })
                assertTrue(train.leaves >= now - 30, "$train has not left")
                assertTrue(train.arrives == 0L || (train.arrives == trip.stops[off].time && train.arrives > train.leaves))
                assertEquals(trip.stops.subList(on + 1, off + 1).count { catalog.stop(it.stopId.dropLast(1)) != null }, train.stops)
                assertTrue(train.stops >= 1)
                assertTrue(
                    train.running || train.train.feed !in keepsAnEmptyTrip || (idTime(trip) ?: trip.stops.first().time) >= now,
                    "$train is a slot whose time has not come",
                )
                found++
            }
            assertEquals(plan.trains.sortedWith(compareBy({ if (it.arrives > 0) it.arrives else Long.MAX_VALUE }, { it.leaves })).map { it.train.id }, plan.trains.map { it.train.id })
        }
        assertTrue(found > 10_000, "only $found trains were looked at")
    }

    @Test
    fun tappingATrainInTheListStartsTheRideTheRowDescribed() {
        var rides = 0
        manyPairs().filterIndexed { index, _ -> index % 7 == 0 }.forEach { (from, to) ->
            val view = plan(from, to).view(now) as? Plan.View.Trains ?: return@forEach
            view.choices.forEach { choice ->
                val ride = choice.ride()
                assertEquals(Triple(choice.train, choice.from, choice.to), Triple(ride.train, ride.from, ride.to))
                // On at the platform the row left from, off at the one it arrived at, and as many stops as it said.
                val waiting = assertIs<RideState.Waiting>(ride.state(now), "${from.name} to ${to.name}: $choice")
                assertEquals(choice.stops, waiting.stops)
                assertEquals(choice.train.stop(choice.to)!!.time, waiting.arrives)
                if (choice.arrives > 0) assertEquals(choice.arrives, waiting.arrives)
                assertEquals(choice.running, waiting.train !is Sighting.Scheduled)
                rides++
            }
        }
        assertTrue(rides > 200, "only $rides rides were started")
    }

    @Test
    fun noTrainRunsFromAStationToItself() {
        // No trip stops twice inside one station, however many platforms it has.
        catalog.stations.forEach { assertEquals(emptyList(), plan(it, it).trains, it.name) }
    }

    /** The feeds that go on listing a trip no train took long after it was to set out. In the others such a trip is offered. */
    private val keepsAnEmptyTrip = setOf("gtfs", "gtfs-l", "gtfs-si")

    /** Each trip's stops that are stations, as platform and time. Worked out once: there are 820 trips. */
    private val stationStops: Map<String, List<Pair<GtfsRealtime.Trip, List<Pair<String, Long>>>>> by lazy {
        day.feeds.mapValues { (_, feed) ->
            feed.trips.map { trip -> trip to trip.stops.filter { catalog.stop(it.stopId.dropLast(1)) != null }.map { it.stopId.dropLast(1) to it.time } }
        }
    }

    /**
     * The same question asked the slow way, of the trips as the reader hands them over:
     * each trip that stops at the first station, not more than half a minute ago, and
     * later at the second. When a slot sets out is worked out from New York's midnight.
     * This snapshot holds no positions, so nothing here goes by where a train was seen:
     * that is asked of the midday snapshot, in [PlannerPositionsTest].
     */
    private fun byHand(from: Station, to: Station): Set<Pair<String, String>> {
        val starts = from.platforms.mapTo(HashSet()) { it.stop }
        val ends = to.platforms.mapTo(HashSet()) { it.stop }
        class Found(val slug: String, val trip: GtfsRealtime.Trip, val on: Pair<String, Long>, val last: String)
        val found = ArrayList<Found>()
        for (slug in Planner.slugs(from, to)) for ((trip, stations) in stationStops.getValue(slug)) {
            if (!trip.assigned && slug in keepsAnEmptyTrip && (idTime(trip) ?: trip.stops.first().time) < now) continue
            val on = stations.indexOfFirst { it.first in starts && it.second >= now - 30 }
            if (on < 0 || (on + 1 until stations.size).none { stations[it].first in ends }) continue
            found += Found(slug, trip, stations[on], trip.stops.last { catalog.stop(it.stopId.dropLast(1)) != null }.stopId)
        }
        // One train listed under two trips is one train, as on a station's page: the trip with a train is the one kept.
        return found.sortedBy { !it.trip.assigned }
            .distinctBy { listOf(Routes.display(it.trip.route), it.on, it.last) }
            .mapTo(HashSet()) { it.slug to it.trip.id }
    }

    @Test
    fun aPlanHasEveryTripThatGoesThereAndNoOther() {
        var withTrains = 0
        manyPairs().forEach { (from, to) ->
            val plan = plan(from, to)
            assertEquals(byHand(from, to), plan.trains.mapTo(HashSet()) { it.train.feed to it.train.id }, "${from.name} to ${to.name}")
            if (plan.trains.isNotEmpty()) withTrains++
        }
        assertTrue(withTrains > 1_000, "only $withTrains pairs had a train between them")
    }

    @Test
    fun noTrainOfferedIsBeatenAndEveryTrainLeftOutIs() {
        var lists = 0
        var untimedFirst = 0
        manyPairs().forEach { (from, to) ->
            val plan = plan(from, to)
            val view = plan.view(now) as? Plan.View.Trains ?: return@forEach
            fun beats(other: Direct, train: Direct) = (other.running || !train.running) &&
                other.leaves < train.leaves && (train.arrives == 0L || (other.arrives > 0 && other.arrives < train.arrives))
            assertTrue(view.choices.size in 1..Plan.MOST)
            view.choices.forEach { train -> assertTrue(plan.trains.none { beats(it, train) }, "${from.name} to ${to.name}: $train") }
            if (view.choices.size < Plan.MOST) {
                (plan.trains - view.choices.toSet()).forEach { train -> assertNotNull(plan.trains.firstOrNull { beats(it, train) }, "${from.name} to ${to.name}: $train") }
            }
            // The first is the soonest there of all of them that have a time, or the first to leave where none has.
            // The one exception is a train with no arrival time that leaves before a train of its own line
            // from its own platform: it will be there before that one, and is listed just ahead of it.
            val timed = plan.trains.filter { it.arrives > 0 }
            val first = view.choices.first()
            if (first.arrives > 0) {
                assertEquals(timed.minOf { it.arrives }, first.arrives)
            } else if (timed.isNotEmpty()) {
                val soonest = timed.minBy { it.arrives }
                val behind = view.choices.filter { it.arrives > 0 }.minByOrNull { it.arrives }
                assertNotNull(behind, "${from.name} to ${to.name}: an untimed train is first with a timed one to go by")
                assertEquals(soonest.arrives, behind.arrives)
                assertTrue(first.train.route == behind.train.route && first.from == behind.from && first.leaves < behind.leaves, "${from.name} to ${to.name}")
                untimedFirst++
            }
            lists++
        }
        assertTrue(lists > 1_000)
        assertTrue(untimedFirst > 0, "no list in the snapshot opened with an untimed train")
    }
}

/**
 * The planner's rules one at a time, on trains made by hand: the 6 calling at every stop
 * from Astor Pl to 51 St and the 4 running express from Brooklyn Bridge, by way of
 * 14 St-Union Sq and Grand Central-42 St.
 */
class PlannerRulesTest {
    private val catalog = Fixtures.catalog
    private val unionSq = Fixtures.station("14 St-Union Sq")
    private val grandCentral = Fixtures.station("Grand Central-42 St")

    private fun trip(route: String, id: String, vararg stops: Pair<String, Long>, assigned: Boolean = true) =
        GtfsRealtime.Trip(route, stops.map { GtfsRealtime.StopTime(it.first, it.second) }, assigned, id)

    /** A 4 that leaves Union Sq at [leaves] and is at Grand Central at [arrives]. */
    private fun express(id: String, leaves: Long, arrives: Long, assigned: Boolean = true) =
        trip("4", id, "640N" to leaves - 240, "635N" to leaves, "631N" to arrives, "629N" to arrives + 180, assigned = assigned)

    /** A 6 that leaves Union Sq at [leaves] and takes six minutes over the four stops to Grand Central. */
    private fun local(id: String, leaves: Long, assigned: Boolean = true) = trip(
        "6", id,
        "636N" to leaves - 90, "635N" to leaves, "634N" to leaves + 90, "633N" to leaves + 180,
        "632N" to leaves + 270, "631N" to leaves + 360, "630N" to leaves + 450,
        assigned = assigned,
    )

    private fun feed(vararg trips: GtfsRealtime.Trip, standing: Map<String, String> = emptyMap()) = GtfsRealtime.Feed(
        timestamp = T0,
        trips = trips.toList(),
        positions = standing.mapValues { GtfsRealtime.Position(it.value, GtfsRealtime.Status.STOPPED, T0 - 5) },
    )

    private fun plan(feed: GtfsRealtime.Feed, at: Long = T0, from: Station = unionSq, to: Station = grandCentral, slug: String = "gtfs") =
        Planner.plan(from, to, catalog, mapOf(slug to feed), at)

    private fun Plan.offered(at: Long = T0): List<String> = (view(at) as? Plan.View.Trains)?.choices.orEmpty().map { it.train.id }

    @Test
    fun theOneThatGetsThereFirstIsFirstAndOnlyATrainThatLeavesSoonerJoinsIt() {
        val plan = plan(feed(local("local now", T0 + 60), express("express in four", T0 + 240, T0 + 400), local("next local", T0 + 300), express("next express", T0 + 600, T0 + 760)))
        // The local leaves in a minute and takes six. The express leaves in four and is there twenty seconds before it.
        assertEquals(listOf("express in four", "local now", "next local", "next express"), plan.trains.map { it.train.id })
        assertEquals(listOf("express in four", "local now"), plan.offered())
        // A local slow enough to be passed is still offered, after the express that passes it.
        val passed = plan(feed(local("local now", T0 + 60), express("express in two", T0 + 120, T0 + 280)))
        assertEquals(listOf("express in two", "local now"), passed.offered())
        // One that leaves first and is there first leaves nothing else to choose.
        assertEquals(listOf("express now"), plan(feed(express("express now", T0 + 30, T0 + 200), local("local in two", T0 + 120))).offered())
    }

    @Test
    fun noListOfWaysThereIsLongerThanFour() {
        // Five trains, each leaving sooner than the one before and getting there later.
        val trains = (0..4).map { express("train $it", leaves = T0 + 600 - it * 100, arrives = T0 + 700 + it * 100) }
        val plan = plan(feed(*trains.toTypedArray()))
        assertEquals(listOf("train 0", "train 1", "train 2", "train 3"), plan.offered())
    }

    @Test
    fun aTrainBeingHeldDoesNotPutAMovingOneOut() {
        val moving = local("moving", T0 + 160)
        val ahead = express("ahead", T0 + 100, T0 + 340)
        // On its times alone the express leaves first and is there first.
        assertEquals(listOf("ahead"), plan(feed(ahead, moving)).offered())
        // Standing at Brooklyn Bridge more than two minutes past its time there, its times will slip. Both are offered.
        val held = plan(feed(ahead, moving, standing = mapOf("ahead" to "640N")))
        assertTrue(held.trains.first { it.train.id == "ahead" }.held(T0))
        assertEquals(listOf("ahead", "moving"), held.offered())
        // A moving train can still put a held one out.
        val sooner = express("sooner", T0 + 50, T0 + 300)
        assertEquals(listOf("sooner"), plan(feed(ahead, sooner, standing = mapOf("ahead" to "640N"))).offered())
    }

    @Test
    fun aTrainPassedOverStaysPassedOverWhenItsTimeSlips() {
        fun planned(first: Long, second: Long) = plan(feed(local("first", first), local("second", second), local("third", T0 + 900)))
        fun Plan.offered(at: Long, after: Plan.After?) = (view(at, after) as? Plan.View.Trains)?.choices.orEmpty().map { it.train.id }
        val asked = planned(T0 + 120, T0 + 400)
        val later = assertNotNull(assertIs<Plan.View.Trains>(asked.view(T0)).later)
        assertEquals(Plan.After(T0 + 120, setOf("gtfs" to "first")), later)
        assertEquals(listOf("second"), asked.offered(T0, later))
        // The next download has the first train twenty seconds later. It is still the one the rider said they would not make.
        assertEquals(listOf("second"), planned(T0 + 140, T0 + 400).offered(T0 + 30, later))
        // Going by its time alone would bring it back.
        assertEquals(listOf("first"), planned(T0 + 140, T0 + 400).offered(T0 + 30, Plan.After(T0 + 120)))
        // A train that was not passed over is not hidden for coming sooner than it was going to.
        assertEquals(listOf("second"), planned(T0 + 140, T0 + 130).offered(T0 + 30, later))
        // One more step passes over both, and the step after that has nothing.
        val last = assertNotNull(assertIs<Plan.View.Trains>(planned(T0 + 140, T0 + 400).view(T0 + 30, later)).later)
        assertEquals(Plan.After(T0 + 400, setOf("gtfs" to "first", "gtfs" to "second")), last)
        assertEquals(listOf("third"), planned(T0 + 500, T0 + 520).offered(T0 + 60, last))
        assertNull(assertIs<Plan.View.Trains>(planned(T0 + 500, T0 + 520).view(T0 + 60, last)).later)
    }

    @Test
    fun aTrainTheFeedHasSeenBeyondTheStationIsNotOffered() {
        // The lettered lines can go on listing a stop after the train has been seen at the next one.
        val gone = trip("6", "gone", "635N" to T0 + 10, "634N" to T0 + 100, "633N" to T0 + 190, "632N" to T0 + 280, "631N" to T0 + 370)
        assertEquals(listOf("gone"), plan(feed(gone)).offered())
        assertEquals(emptyList(), plan(feed(gone, standing = mapOf("gone" to "634N"))).trains)
        // Seen standing at the station itself, it is there to be boarded.
        assertEquals(listOf("gone"), plan(feed(gone, standing = mapOf("gone" to "635N"))).offered())
        assertEquals(listOf("gone"), plan(feed(local("gone", T0 + 10), standing = mapOf("gone" to "635N"))).offered())
        // A place two stops or more down the list is no sighting, and takes no train off the list.
        assertEquals(listOf("gone"), plan(feed(gone, standing = mapOf("gone" to "633N"))).offered())
    }

    @Test
    fun aTrainWithNoArrivalTimeIsListedAheadOfTheTrainBehindItOnItsOwnLine() {
        // A 6 whose stops to Grand Central are listed a second apart, and the 6 behind it with real times.
        val untimed = trip("6", "untimed", "635N" to T0 + 120, "634N" to T0 + 121, "633N" to T0 + 122, "632N" to T0 + 123, "631N" to T0 + 124)
        val behind = local("behind", T0 + 400)
        val plan = plan(feed(behind, untimed))
        assertEquals(0, plan.trains.first { it.train.id == "untimed" }.arrives)
        // It leaves first on the same track, so it is there first, and is the one the page names.
        assertEquals(listOf("untimed", "behind"), plan.offered())
        // The same ahead of a trip that is only on the timetable.
        assertEquals(listOf("untimed", "slot"), plan(feed(local("slot", T0 + 400, assigned = false), untimed)).offered())
        // An express of another line that leaves later can still be there sooner. It keeps its place.
        val express = express("express", T0 + 300, T0 + 460)
        assertEquals(listOf("express", "untimed"), plan(feed(express, untimed)).offered())
        // The express puts the local behind it out, and the untimed train still goes by that local's time.
        assertEquals(listOf("express", "untimed"), plan(feed(express, behind, untimed)).offered())
        // With nothing behind it to go by, it is last.
        assertEquals(listOf("untimed"), plan(feed(untimed)).offered())
    }

    @Test
    fun aSlotIsOfferedUntilItsTimeToSetOutAndNotAfter() {
        val slot = express("slot", T0 + 240, T0 + 400, assigned = false)
        val setsOut = slot.stops.first().time
        assertEquals(T0, setsOut)
        assertEquals(listOf("slot"), plan(feed(slot), at = setsOut).trains.map { it.train.id })
        assertFalse(plan(feed(slot), at = setsOut).trains.single().running)
        assertEquals(emptyList(), plan(feed(slot), at = setsOut + 1).trains)
        // A train that has the trip is offered however long ago it set out.
        assertEquals(listOf("train"), plan(feed(express("train", T0 + 240, T0 + 400)), at = T0 + 60).trains.map { it.train.id })
    }

    @Test
    fun aSlotsTimeToSetOutIsTheOneInItsIdWhereverItsListOfStopsNowStarts() {
        // Ten seconds after T0 it is 11:13:30 AM in New York, and a 4 due out of Brooklyn Bridge then is "067350_4..N".
        val out = T0 + 10
        fun slot(vararg stops: Pair<String, Long>, id: String = "067350_4..N") = trip("4", id, *stops, assigned = false)
        val whole = slot("640N" to out, "635N" to out + 240, "631N" to out + 400)
        assertEquals(out, Planner.setsOut(whole))
        // With its first stop taken off the list, as the lettered lines do, it still set out when it did.
        val trimmed = slot("635N" to out + 240, "631N" to out + 400)
        assertEquals(out, Planner.setsOut(trimmed))
        assertEquals(listOf("067350_4..N"), plan(feed(whole), at = out).trains.map { it.train.id })
        assertEquals(listOf("067350_4..N"), plan(feed(trimmed), at = out).trains.map { it.train.id })
        assertEquals(emptyList(), plan(feed(whole), at = out + 1).trains)
        assertEquals(emptyList(), plan(feed(trimmed), at = out + 1).trains)
        // Once a train has the trip it is offered again, however much of its list is gone.
        assertEquals(listOf("067350_4..N"), plan(feed(trimmed.copy(assigned = true)), at = out + 60).trains.map { it.train.id })
        // The L lists its first stop a quarter minute before the time in its id. That is not a stop taken off.
        assertEquals(out, Planner.setsOut(slot("640N" to out - 15, "635N" to out + 240)))
        // A third of a minute in an id is cut to a whole second.
        assertEquals(T0 - 1, Planner.setsOut(slot("640N" to T0, "635N" to T0 + 240, id = "067333_4..N")))
        // The hour is the first stop's own, so the date and the time zone are never asked for.
        assertEquals(out + 7 * 3600, Planner.setsOut(slot("640N" to out + 7 * 3600, "635N" to out + 7 * 3600 + 240)))
        assertEquals(out + 7 * 3600, Planner.setsOut(slot("635N" to out + 7 * 3600 + 240)))
        // Leaving such a trip out is for the feeds that go on listing one no train took: the numbered lines, the L
        // and the railway. On the lettered lines and the G it is most likely running, and is offered as scheduled.
        val morgan = Fixtures.station("Morgan Av")
        val onTheL = trip("L", "067350_L..N", "L14N" to out, "L08N" to out + 400, "L03N" to out + 700, "L01N" to out + 900, assigned = false)
        fun offered(slug: String, at: Long) = plan(feed(onTheL), at, morgan, unionSq, slug).trains.map { it.train.id to it.running }
        assertEquals(out, Planner.setsOut(onTheL))
        assertEquals(listOf("067350_L..N" to false), offered("gtfs-l", out))
        assertEquals(emptyList(), offered("gtfs-l", out + 1))
        assertEquals(emptyList(), offered("gtfs", out + 1))
        listOf("gtfs-ace", "gtfs-bdfm", "gtfs-g", "gtfs-jz", "gtfs-nqrw").forEach { slug ->
            assertEquals(listOf("067350_L..N" to false), offered(slug, out + 1), slug)
        }
        // An id that opens with no time leaves the first stop's time to go by.
        listOf("slot", "", "4..N", "_4..N", "06x350_4..N").forEach { id ->
            assertEquals(out + 240, Planner.setsOut(slot("635N" to out + 240, "631N" to out + 400, id = id)), id)
        }
    }

    @Test
    fun oneTrainListedUnderTwoTripsIsOfferedOnceAsTheTripThatHasATrain() {
        val plan = plan(feed(express("on the timetable", T0 + 300, T0 + 460, assigned = false), express("the train", T0 + 300, T0 + 460)))
        assertEquals(listOf("the train"), plan.trains.map { it.train.id })
    }

    @Test
    fun aTripWithNoIdCannotBeFollowedAndIsNotOffered() {
        assertEquals(emptyList(), plan(feed(express("", T0 + 300, T0 + 460))).trains)
    }

    @Test
    fun onTheRailwayATripIsATrainOnlyOnceTheFeedHasAStatusForIt() {
        val stGeorge = Fixtures.station("St George")
        val stapleton = Fixtures.station("Stapleton")
        val trip = trip("SI", "to Tottenville", "S31S" to T0 + 300, "S30S" to T0 + 480, "S29S" to T0 + 600, "S28S" to T0 + 720)
        // A feed built as it is planned from, so that nothing here is left out for being old.
        fun planned(position: GtfsRealtime.Position?, at: Long = T0) =
            plan(GtfsRealtime.Feed(at, listOf(trip), listOfNotNull(position?.let { trip.id to it }).toMap()), at, stGeorge, stapleton, "gtfs-si")
        // The railway's feed calls every trip assigned. One it gives a place but no status has not started.
        val waiting = planned(GtfsRealtime.Position("S31S", null, T0 - 5)).trains.single()
        assertFalse(waiting.running)
        assertEquals(Triple(T0 + 300, T0 + 600, 2), Triple(waiting.leaves, waiting.arrives, waiting.stops))
        assertTrue(planned(GtfsRealtime.Position("S31S", GtfsRealtime.Status.STOPPED, T0 - 5)).trains.single().running)
        assertTrue(planned(null).trains.single().running)
        // And like any slot on a feed that keeps an empty trip listed, it is offered until its time to set out
        // and left out once that has passed with no train.
        assertFalse(planned(GtfsRealtime.Position("S31S", null, T0 - 5), at = T0 + 300).trains.single().running)
        val late = planned(GtfsRealtime.Position("S31S", null, T0 - 5), at = T0 + 301)
        assertEquals(emptyList(), late.trains)
        // The feed was read, and was not too old: it is the rule that left the trip out.
        assertEquals(mapOf("gtfs-si" to T0 + 301), late.built)
        // A train a second past its time is still there to be boarded.
        assertTrue(planned(null, at = T0 + 301).trains.single().running)
        assertTrue(planned(GtfsRealtime.Position("S31S", GtfsRealtime.Status.STOPPED, T0 + 290), at = T0 + 301).trains.single().running)
    }

    @Test
    fun twoStopsListedTooCloseTogetherTakeTheArrivalTimeWithThem() {
        fun arrives(vararg stops: Pair<String, Long>) = plan(feed(trip("6", "x", *stops))).trains.single().arrives
        assertEquals(T0 + 460, arrives("635N" to T0 + 100, "634N" to T0 + 190, "633N" to T0 + 280, "632N" to T0 + 370, "631N" to T0 + 460))
        // Twenty seconds between stops is believed. Less is the feed having nothing to say.
        assertEquals(T0 + 180, arrives("635N" to T0 + 100, "634N" to T0 + 120, "633N" to T0 + 140, "632N" to T0 + 160, "631N" to T0 + 180))
        assertEquals(0, arrives("635N" to T0 + 100, "634N" to T0 + 190, "633N" to T0 + 209, "632N" to T0 + 370, "631N" to T0 + 460))
        assertEquals(0, arrives("635N" to T0 + 100, "634N" to T0 + 190, "633N" to T0 + 180, "632N" to T0 + 370, "631N" to T0 + 460))
        // Only the stops on the way count. What the train does after Grand Central is its own business.
        assertEquals(T0 + 460, arrives("635N" to T0 + 100, "634N" to T0 + 190, "633N" to T0 + 280, "632N" to T0 + 370, "631N" to T0 + 460, "630N" to T0 + 461))
    }

    private companion object {
        const val T0 = 1_794_500_000L
    }
}

/**
 * The rules that go by where the feed last saw a train, asked of every train in the
 * system on a Monday midday. That snapshot kept the feeds' positions. The afternoon's did
 * not, and everything above it would pass with these rules taken out.
 *
 * As above, the lists were worked out a second time by scripts/direct_trains.py.
 */
class PlannerPositionsTest {
    private val day = Fixtures.weekdayMidday
    private val catalog = Fixtures.catalog
    private val now = day.time

    private val newYork = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }

    private fun clock(time: Long) = newYork.format(Date(time * 1000))

    private fun plan(from: Station, to: Station): Plan =
        Planner.plan(from, to, catalog, Planner.slugs(from, to).associateWith { day.feeds.getValue(it) }, now)

    private fun Direct.said() =
        "${train.route} ${train.id} ${clock(leaves)} ${if (arrives > 0) clock(arrives) else "--"} $stops${if (running) "" else " slot"}"

    private fun Plan.offered(): List<String> = (view(now) as? Plan.View.Trains)?.choices.orEmpty().map { it.said() }

    @Test
    fun theSnapshotHoldsWhereEveryTrainWas() {
        assertEquals("11:20:57", clock(now))
        assertEquals(766, day.feeds.values.sumOf { it.trips.size })
        assertEquals(683, day.feeds.values.sumOf { it.positions.size })
        // The lettered lines and the G call every one of their trains stopped. The G has fewer out.
        listOf("gtfs-ace", "gtfs-bdfm", "gtfs-jz", "gtfs-nqrw", "gtfs-g").forEach { slug ->
            val feed = day.feeds.getValue(slug)
            val trains = feed.trips.filter { it.assigned }
            assertTrue(trains.size > (if (slug == "gtfs-g") 5 else 10) && trains.all { feed.positions[it.id]?.status == GtfsRealtime.Status.STOPPED }, slug)
        }
        // The numbered lines say which are and which are not.
        assertEquals(
            setOf(GtfsRealtime.Status.STOPPED, GtfsRealtime.Status.INCOMING, null),
            day.feeds.getValue("gtfs").positions.values.mapTo(HashSet()) { it.status },
        )
    }

    @Test
    fun onTheRailwayATripThatHasNotSetOutIsScheduledAndWordedSo() {
        val inbound = plan(Fixtures.station("Tottenville"), Fixtures.station("St George"))
        assertEquals(
            listOf("SI 069100_SI..N 11:31:00 -- 20 slot", "SI 072100_SI..N 12:01:00 -- 20 slot"),
            inbound.trains.map { it.said() },
        )
        assertEquals(listOf("SI 069100_SI..N 11:31:00 -- 20 slot"), inbound.offered())
        val row = PlanWords.row(inbound.trains.first(), inbound, now, catalog)
        assertEquals("Leaves at ${clockLabel(inbound.trains.first().leaves)}", row.leaves)
        assertEquals("These are timetable times.", row.warning)
        assertFalse(row.sure)
        // The trips already on their way are trains.
        val feed = day.feeds.getValue("gtfs-si")
        val running = feed.trips.filter { feed.positions[it.id]?.status != null }
        assertTrue(running.isNotEmpty() && running.all { Train.of("gtfs-si", feed, it, catalog).assigned })
        assertTrue(feed.trips.filter { feed.positions.getValue(it.id).status == null }.none { Train.of("gtfs-si", feed, it, catalog).assigned })
    }

    /** A trip's stops that are stations, less the first when the feed has seen the train at the second. */
    private fun stationStops(slug: String, trip: GtfsRealtime.Trip): List<GtfsRealtime.StopTime> {
        val feed = day.feeds.getValue(slug)
        val stops = trip.stops.filter { catalog.stop(it.stopId.dropLast(1)) != null }
        val seen = feed.positions[trip.id]?.takeIf { it.status == GtfsRealtime.Status.STOPPED && it.timestamp <= feed.timestamp }
        return if (isTrain(slug, trip) && seen != null && stops.getOrNull(1)?.stopId == seen.stopId) stops.drop(1) else stops
    }

    private fun isTrain(slug: String, trip: GtfsRealtime.Trip): Boolean =
        trip.assigned && !(slug == "gtfs-si" && day.feeds.getValue(slug).positions[trip.id]?.let { it.status == null } == true)

    /** When a trip's id says it sets out: hundredths of a minute past midnight in New York. */
    private fun idTime(trip: GtfsRealtime.Trip): Long? {
        val hundredths = trip.id.substringBefore('_').toLongOrNull() ?: return null
        val midnight = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
            timeInMillis = now * 1000
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return midnight.timeInMillis / 1000 + hundredths * 6 / 10
    }

    /** The same question asked the slow way, with the position rules followed from their wording. */
    private fun byHand(from: Station, to: Station): Set<Pair<String, String>> {
        val starts = from.platforms.mapTo(HashSet()) { it.stop }
        val ends = to.platforms.mapTo(HashSet()) { it.stop }
        class Found(val slug: String, val trip: GtfsRealtime.Trip, val train: Boolean, val on: GtfsRealtime.StopTime, val last: String)
        val found = ArrayList<Found>()
        for (slug in Planner.slugs(from, to)) for (trip in day.feeds.getValue(slug).trips) {
            val train = isTrain(slug, trip)
            if (!train && slug in setOf("gtfs", "gtfs-l", "gtfs-si") && (idTime(trip) ?: trip.stops.first().time) < now) continue
            val stops = stationStops(slug, trip)
            val on = stops.indexOfFirst { it.stopId.dropLast(1) in starts && it.time >= now - 30 }
            if (on < 0 || (on + 1 until stops.size).none { stops[it].stopId.dropLast(1) in ends }) continue
            found += Found(slug, trip, train, stops[on], trip.stops.last { catalog.stop(it.stopId.dropLast(1)) != null }.stopId)
        }
        return found.sortedBy { !it.trip.assigned }
            .distinctBy { listOf(Routes.display(it.trip.route), it.on.stopId.dropLast(1), it.on.time, it.last) }
            .mapTo(HashSet()) { it.slug to it.trip.id }
    }

    private fun manyPairs(): List<Pair<Station, Station>> {
        val random = Random(20261116)
        return catalog.stations.flatMap { from ->
            val sameLine = catalog.stations.filter { it.id != from.id && it.routes.any(from.routes::contains) }.shuffled(random).take(3)
            sameLine.map { from to it }
        }
    }

    @Test
    fun aPlanHasEveryTripThatGoesThereAndNoOtherWithTheTrainsPlacedWhereTheyWereSeen() {
        var withTrains = 0
        var seenBeyond = 0
        var held = 0
        manyPairs().forEach { (from, to) ->
            val plan = plan(from, to)
            assertEquals(byHand(from, to), plan.trains.mapTo(HashSet()) { it.train.feed to it.train.id }, "${from.name} to ${to.name}")
            if (plan.trains.isNotEmpty()) withTrains++
            held += plan.trains.count { it.held(now) }
            // A train the station's own page lists, and the plan leaves out because it was seen at the stop after.
            val starts = from.platforms.mapTo(HashSet()) { it.stop }
            seenBeyond += Planner.slugs(from, to).sumOf { slug ->
                day.feeds.getValue(slug).trips.count { trip ->
                    val all = trip.stops.filter { catalog.stop(it.stopId.dropLast(1)) != null }
                    all.size > stationStops(slug, trip).size && all.first().stopId.dropLast(1) in starts && all.first().time >= now - 30
                }
            }
        }
        assertTrue(withTrains > 700, "only $withTrains pairs had a train between them")
        // The snapshot has the cases the rules are for, or it would prove nothing about them.
        assertTrue(seenBeyond > 20, "$seenBeyond trains seen beyond a station still listing them")
        assertTrue(held >= 5, "$held trains held")
    }

    @Test
    fun aTrainSeenAtTheStopAfterAStationIsNotOfferedThere() {
        // One named case of each rule, found in the snapshot and stated here.
        val feed = day.feeds.getValue("gtfs-nqrw")
        val beyond = feed.trips.first { trip ->
            val stops = trip.stops.filter { catalog.stop(it.stopId.dropLast(1)) != null }
            trip.assigned && stops.size > 3 && stationStops("gtfs-nqrw", trip).size < stops.size && stops.first().time >= now - 30
        }
        val stops = beyond.stops.filter { catalog.stop(it.stopId.dropLast(1)) != null }
        val from = catalog.stop(stops[0].stopId.dropLast(1))!!.first
        val to = catalog.stop(stops[2].stopId.dropLast(1))!!.first
        // The station's page still lists it. The way there does not: it has been seen at the stop after.
        assertTrue(day.board(from).arrivals.any { it.trip == beyond.id })
        assertTrue(plan(from, to).trains.none { it.train.id == beyond.id })
        assertEquals(stops[1].stopId, feed.positions.getValue(beyond.id).stopId)
        // And one held: stopped at its stop more than two minutes past its time there, with a ride ahead of it.
        // The times after a held train stand still, so the first stop it can be boarded at is some way down its list.
        val numbered = day.feeds.getValue("gtfs")
        fun boardable(train: Train) = train.stops.indexOfFirst { it.time >= now - 30 }
        val held = numbered.trips.filter { it.assigned }.map { Train.of("gtfs", numbered, it, catalog) }
            .first { it.standing && now - it.stops.first().time > Train.HELD_AFTER && boardable(it) in 1 until it.stops.size - 1 }
        assertEquals("066350_3..N01R", held.id)
        val next = catalog.stop(held.stops[boardable(held)].platform)!!.first
        val after = catalog.stop(held.stops[boardable(held) + 1].platform)!!.first
        val direct = plan(next, after).trains.first { it.train.id == held.id }
        assertTrue(direct.held(now))
        val row = PlanWords.row(direct, plan(next, after), now, catalog)
        assertFalse(row.sure)
        assertTrue(row.warning!!.startsWith("Standing at ${catalog.stopName(held.stops.first().platform)} since ") && row.warning!!.endsWith(" Its times will slip."), row.warning)
    }
}
