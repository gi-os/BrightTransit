package com.thelightphone.subway

import com.thelightphone.subway.data.Change
import com.thelightphone.subway.data.Plan
import com.thelightphone.subway.data.Planner
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.ui.PlanWords
import com.thelightphone.subway.ui.clockLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ways from one station to another with one change of train, asked of every train in the
 * system on a weekday at midday.
 *
 * Each list below was worked out a second time, in Python, from the same recorded bytes and
 * the rules in words alone (tool/scripts/change_trains.py), and the two agree. A way is
 * written as change_trains.py prints it: the first train's line in the feed, its trip, when it
 * leaves, the station changed at and when it gets there | the second train's line, trip, when
 * it leaves and when it gets there, the MTA's time to change, and the lines that run from the
 * second train's platform. The lines are named as the tool names them ("S", where the feed
 * says "GS").
 */
class ChangesTest {
    private val day = Fixtures.weekdayMidday
    private val catalog = Fixtures.catalog
    private val now = day.time

    private val newYork = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }

    private fun clock(time: Long) = newYork.format(Date(time * 1000))

    private fun station(id: String): Station = catalog.station(id)!!

    private fun plan(from: String, to: String, without: Set<String> = emptySet(), at: Long = now): Plan {
        val a = station(from)
        val b = station(to)
        return Planner.plan(a, b, catalog, Planner.slugs(a, b).filter { it !in without }.associateWith { day.feeds.getValue(it) }, at)
    }

    private fun Change.said() =
        "${first.train.route} ${first.train.id} ${clock(leaves)} ${at.name} ${clock(first.arrives)} | " +
            "${second.train.route} ${second.train.id} ${clock(second.leaves)} ${clock(arrives)} walk=$walk lines=${lines.joinToString(",")}"

    private fun Plan.offered(at: Long = now, after: Plan.After? = null) = changes(at, after).map { it.said() }

    @Test
    fun withNoDirectTrainTheWaysWithAChangeThatGetThereSoonestAreOffered() {
        // Jay St to Grand Central: the C to Fulton St for the 4, or the F to Broadway-Lafayette St for the 6.
        assertEquals(
            listOf(
                "C 065600_C..N04R 11:21:56 Fulton St 11:26:56 | 4 067200_4..N06R 11:31:02 11:41:02 walk=180 lines=4",
                "F 063550_F..N54R 11:20:39 Broadway-Lafayette St 11:28:49 | 6 069000_6..N03R 11:34:30 11:43:30 walk=180 lines=6",
            ),
            plan("636", "610").offered(),
        )
        // Bedford Av to Times Sq: the L to Union Sq, then whichever of the Broadway lines comes.
        assertEquals(
            listOf("L 064850_L..N 11:22:45 14 St-Union Sq 11:29:15 | W 068150_N..N72R 11:33:47 11:40:17 walk=180 lines=W,N,Q,R"),
            plan("120", "611").offered(),
        )
        assertEquals(
            listOf("7 068100_7..S 11:21:00 Jackson Hts-Roosevelt Av 11:32:10 | E 068100_E..S71R 11:37:00 11:46:30 walk=180 lines=E,F"),
            plan("447", "612").offered(),
        )
        assertEquals(
            listOf("6 064450_6..S03R 11:22:27 14 St-Union Sq 11:28:27 | L 069050_L..S 11:33:15 11:46:15 walk=180 lines=L"),
            plan("610", "125").offered(),
        )
        assertEquals(
            listOf("4 066250_4..N06R 11:22:57 Grand Central-42 St 11:32:57 | 7 069600_7..N 11:41:10 12:17:40 walk=180 lines=7"),
            plan("628", "447").offered(),
        )
    }

    @Test
    fun theTimeToChangeIsTheMtasWhereItListsOne() {
        // At 14 St the A C E and the L are listed a minute and a half apart.
        assertEquals(
            listOf("A 065500_A..S58R 11:20:44 14 St 11:24:49 | L 069050_L..S 11:30:30 11:39:15 walk=90 lines=L"),
            plan("611", "120").offered(),
        )
        // The 42 St shuttle, from the 3 at Times Sq.
        assertEquals(
            listOf("3 066850_3..S06R 11:22:17 Times Sq-42 St 11:29:17 | GS 069550_GS.S01R 11:35:30 11:37:00 walk=180 lines=S"),
            plan("310", "610").offered(),
        )
        // Every way found leaves the MTA's time to change and a minute over, through a change the MTA lists.
        for ((from, to) in listOf("636" to "610", "120" to "611", "611" to "120", "623" to "606", "635" to "413")) {
            val plan = plan(from, to)
            assertTrue(plan.changes.isNotEmpty(), "$from $to")
            for (way in plan.changes) {
                assertEquals(way.at.change(way.first.to, way.second.from), way.walk, way.said())
                assertTrue(way.window >= way.walk + Planner.SPARE, way.said())
            }
        }
    }

    @Test
    fun theFirstTrainIsAlwaysAMovingTrainAndTheSecondNeverDoublesBack() {
        for ((from, to) in listOf("636" to "610", "125" to "611", "120" to "611", "606" to "120", "610" to "125", "635" to "413", "602" to "278")) {
            val plan = plan(from, to)
            val start = plan.from.platforms.map { it.stop }.toSet()
            for (way in plan.changes) {
                assertTrue(way.first.running && !way.first.held(now), way.said())
                assertTrue(way.first.key != way.second.key, way.said())
                val second = way.second.train.stops
                val on = second.indexOfFirst { it.platform == way.second.from }
                val off = second.indexOfFirst { it.platform == way.second.to }
                assertTrue(second.subList(on + 1, off).none { it.platform in start }, way.said())
                // Neither train goes to where the rider is going before the change, nor back through where they started.
                assertTrue(way.first.train.stops.subList(way.first.train.place(way.first.from) + 1, way.first.train.place(way.first.to)).none { it.platform in start || it.platform in plan.to.platforms.map { p -> p.stop } }, way.said())
            }
        }
    }

    @Test
    fun aTrainBeingHeldOnItsWayIsNeverTheFirstOfAChange() {
        // Grand Army Plaza to Sutphin Blvd: the 3 due first has stood at Nostrand Av for over three minutes, so its times will
        // slip, and a change timed off them would not hold. The 2 behind it makes the same F at 14 St.
        val plan = plan("340", "257")
        assertTrue(plan.changes.none { it.first.train.id == "066350_3..N01R" })
        assertEquals(
            listOf("2 066500_2..N14R 11:24:43 14 St 11:47:43 | F 065966_F..N54R 11:57:14 12:33:14 walk=300 lines=F"),
            plan.offered(),
        )
    }

    @Test
    fun aWayThatGetsThereWellAfterAnotherIsNotOfferedHoweverSoonItLeaves() {
        val plan = plan("125", "611")
        // Morgan Av to Times Sq: the L the other way to Broadway Junction leaves a minute sooner, and gets there half an hour later.
        // The R and W from Union Sq are named nowhere: they would get there more than five minutes after the N.
        val wrongWay = plan.changes.single { it.first.train.id == "066650_L..S" && it.at.name == "Broadway Junction" }
        assertEquals("L 066650_L..S 11:21:57 Broadway Junction 11:32:57 | A 065800_A..N55R 11:45:21 12:15:51 walk=180 lines=A,C", wrongWay.said())
        assertEquals(
            listOf("L 066050_L..N 11:22:55 14 St-Union Sq 11:36:25 | N 065400_N..N33R 11:40:33 11:46:03 walk=180 lines=N,Q"),
            plan.offered(),
        )
    }

    @Test
    fun aChangeIsOnlyOfferedWhenItBeatsEveryDirectTrainByFiveMinutes() {
        // Astoria to Atlantic Av: the N runs all the way. Nothing with a change gets there five minutes sooner.
        val astoria = plan("1", "617")
        assertTrue(astoria.view(now) is Plan.View.Trains)
        assertEquals(emptyList(), astoria.offered())
        // Canal St to Court Sq: the E runs direct, and the ways with a change it beats are found but not offered.
        val canal = plan("169", "606")
        val direct = (canal.view(now) as Plan.View.Trains).choices.filter { it.arrives > 0 }.minOf { it.arrives }
        assertTrue(canal.changes.isNotEmpty())
        assertTrue(canal.changes.all { it.arrives + Plan.WORTH > direct || it.gone(now) || it.first.held(now) })
        assertEquals(emptyList(), canal.offered())
        // Canal St on the 6 to Court Sq: no train runs direct, so the way with a change is offered on its own.
        assertTrue(plan("623", "606").view(now) is Plan.View.None)
        assertEquals(
            listOf("R 063900_R..N93R 11:22:02 34 St-Herald Sq 11:32:17 | F 063550_F..N54R 11:37:04 11:47:19 walk=180 lines=F"),
            plan("623", "606").offered(),
        )
        // Flushing to Times Sq: the 7 runs all the way, and gets there at noon. Off the same 7 at Jackson Hts, the E is
        // there six minutes sooner, so it is offered beside the direct trains.
        val flushing = plan("447", "611")
        assertEquals("12:00:10", clock((flushing.view(now) as Plan.View.Trains).choices.filter { it.arrives > 0 }.minOf { it.arrives }))
        assertEquals(
            listOf("7 068100_7..S 11:21:00 Jackson Hts-Roosevelt Av 11:32:10 | E 068100_E..S71R 11:37:00 11:54:00 walk=180 lines=E"),
            flushing.offered(),
        )
        // With direct trains to go by and no time there for any of them, nothing says a change is sooner: none is offered.
        val untimed = flushing.copy(trains = flushing.trains.map { it.copy(arrives = 0) })
        assertTrue(untimed.view(now) is Plan.View.Trains)
        assertEquals(emptyList(), untimed.offered())
    }

    @Test
    fun aWayIsOnlyOfferedWhileItsFirstTrainIsStillToComeAndItsFeedsAreFresh() {
        val plan = plan("636", "610")
        val first = plan.changes(now).first()
        // A minute after the first train left, it is gone, and the next way is offered.
        val later = first.leaves + 31
        assertTrue(plan.changes(later).none { it.key == first.key })
        // The list of later ways leaves out the ways passed over.
        val after = Plan.After(first.leaves, setOf(first.first.key))
        assertTrue(plan.changes(now, after).all { it.leaves > first.leaves && it.first.key != first.first.key })
        // From feeds too old to plan from, nothing is offered.
        assertEquals(emptyList(), plan.changes(now + Planner.FRESH_FOR + 1))
        // A feed that could not be had is not looked in: from the lettered lines' feeds alone, no 4, 5 or 6 can be the second train.
        assertTrue(plan("636", "610", without = setOf("gtfs")).changes.none { it.second.train.feed == "gtfs" })
    }

    @Test
    fun aWayWithAChangeIsWordedAsTheRiderWillDoIt() {
        val plan = plan("636", "610")
        val way = plan.changes(now).first()
        val words = PlanWords.change(way, plan, now, catalog)
        assertEquals("Leaves in about 1 min", words.leaves)
        // The clock is the phone's, so the time is written as the phone writes it.
        assertEquals("Arrives about ${clockLabel(way.arrives)}", words.arrives)
        assertEquals("11:41:02", clock(way.arrives))
        assertEquals("Change at Fulton St to the 4", words.detail.replace('\u00A0', ' '))
        assertEquals("4 min to change  ·  5 stops", words.more?.replace('\u00A0', ' '))
        assertEquals(null, words.warning)
        assertTrue(words.sure)
        // A second train that is only the timetable's is said to be, and its time is given with "at".
        val slot = plan("125", "292").changes(now).single()
        val slotWords = PlanWords.change(slot, plan("125", "292"), now, catalog)
        assertEquals("Arrives at ${clockLabel(slot.arrives)}", slotWords.arrives)
        assertEquals("11:48:00", clock(slot.arrives))
        assertEquals("The second train's times are the timetable's.", slotWords.warning)
        assertTrue(!slotWords.sure)
    }
}
