package com.thelightphone.subway

import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Direction
import com.thelightphone.subway.data.Directions
import com.thelightphone.subway.data.Feeds
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.Station
import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the tool says at each of the 445 stations, written down and pinned.
 *
 * Every station's board is built from one recorded moment of the whole system (see
 * [Snapshot]) and set beside src/test/resources/golden/weekday-afternoon.txt: the station
 * and its lines, each heading in the order it is shown, and under it a row for each line
 * and last stop. That file is meant to be read from top to bottom by someone who rides
 * these trains. A change to a name, an order or a row anywhere in the system is a line in
 * its diff.
 *
 * When a change is meant, run this class once with UPDATE_GOLDEN set in the environment.
 * That rewrites the file; read its diff before keeping it. Gradle does not take a new
 * environment variable as a reason to run tests again, hence --rerun:
 *
 *     UPDATE_GOLDEN=1 ./gradlew <the test task> --tests '*EveryStationTest' --rerun
 *
 * The other tests here hold for every station whatever the file says.
 */
class EveryStationTest {
    private val day = Fixtures.weekdayAfternoon
    private val catalog = Fixtures.catalog

    @Test
    fun everyStationSaysWhatTheGoldenFileSays() {
        assertEquals(445, boards.size)
        val now = boards.map { (station, board) -> block(station, board) }
        if (System.getenv("UPDATE_GOLDEN") != null) {
            GOLDEN.writeText(now.joinToString("\n") { it.joinToString("\n", postfix = "\n") })
            println("Rewrote ${GOLDEN.path}. Read its diff.")
            return
        }
        val golden = GOLDEN.readText().trimEnd().split("\n\n").map { it.lines() }
        val changed = golden.zip(now).filter { (was, isNow) -> was != isNow }
        if (golden.size == now.size && changed.isEmpty()) return
        fail(
            buildString {
                appendLine("${changed.size} of ${now.size} stations no longer read as ${GOLDEN.path} has them (it has ${golden.size}).")
                changed.take(5).forEach { (was, isNow) ->
                    appendLine("\nThe file:")
                    was.forEach { appendLine(it) }
                    appendLine("Now:")
                    isNow.forEach { appendLine(it) }
                }
                appendLine("\nIf this is meant, see how to rewrite the file at the top of EveryStationTest.")
            },
        )
    }

    private fun block(station: Station, board: Board): List<String> = buildList {
        add("${station.name} (${station.routes.joinToString(" ")})")
        val groups = board.groups(day.time)
        if (groups.isEmpty()) add("  no trains")
        groups.forEach { group ->
            add("  ${group.heading}")
            group.rows.forEach { add("    ${it.route.padEnd(3)} ${it.destination}") }
        }
    }

    @Test
    fun noDirectionIsACompassWordAndEveryTrainHasALastStop() {
        val vague = setOf("northbound", "southbound", "eastbound", "westbound", "inbound", "outbound", "last stop", "")
        boards.forEach { (station, board) ->
            board.arrivals.forEach { train ->
                assertTrue(train.heading.lowercase() !in vague, "${station.name}: a ${train.route} headed \"${train.heading}\"")
                assertTrue(train.destination.isNotBlank(), "${station.name}: a ${train.route} with no last stop")
            }
        }
    }

    @Test
    fun headingsAndRowsStayPutWhenTheTrainsComeInAnotherOrder() {
        val random = Random(20261112)
        boards.forEach { (station, board) ->
            val asRecorded = board.groups(day.time)
            repeat(5) {
                val times = board.arrivals.map { it.epochSeconds }.shuffled(random)
                val shuffled = board.copy(arrivals = board.arrivals.zip(times) { train, time -> train.copy(epochSeconds = time) })
                val groups = shuffled.groups(day.time)
                assertEquals(asRecorded.map { it.heading }, groups.map { it.heading }, station.name)
                // Under a heading no row moves either: rows follow the station's order of
                // lines, and a line's rows go by the name of the last stop.
                assertEquals(
                    asRecorded.map { group -> group.rows.map { it.route to it.destination } },
                    groups.map { group -> group.rows.map { it.route to it.destination } },
                    station.name,
                )
            }
        }
    }

    @Test
    fun noHeadingMixesTheTwoSidesOfOnePlatform() {
        boards.forEach { (station, board) ->
            board.groups(day.time).forEach { group ->
                group.arrivals.groupBy { it.stop }.forEach { (stop, trains) ->
                    val sides = trains.map { it.direction }.toSet()
                    assertEquals(1, sides.size, "${station.name}: \"${group.heading}\" holds trains both ways out of $stop")
                }
            }
        }
    }

    /**
     * The check above trusts [Directions.side] to say which trains leave a platform the same
     * way. This one tests that against the tracks: trains leaving opposite sides of a
     * platform never have the same stop next. Read by the letter in the feed alone, the M
     * and the J fail it at every stop from Myrtle Av to Chambers St.
     */
    @Test
    fun trainsOnOppositeSidesOfAPlatformNeverHaveTheSameNextStop() {
        val next = HashMap<Pair<String, Direction>, MutableSet<String>>()
        day.feeds.values.forEach { feed ->
            feed.trips.forEach { trip ->
                val stops = trip.stops.filter { catalog.stop(it.stopId.dropLast(1)) != null }
                stops.zipWithNext { here, after ->
                    val stop = here.stopId.dropLast(1)
                    val side = Directions.side(trip.route, stop, here.stopId.last())!!
                    next.getOrPut(stop to side) { HashSet() } += after.stopId.dropLast(1)
                }
            }
        }
        assertTrue(next.size > 900, "both sides of nearly every platform had a train")
        next.keys.filter { it.second == Direction.NORTH }.forEach { (stop, _) ->
            val both = next.getValue(stop to Direction.NORTH) intersect next[stop to Direction.SOUTH].orEmpty()
            assertTrue(both.isEmpty(), "$stop: trains from both sides go on to $both")
        }
    }

    @Test
    fun noStationHasMoreThanFourHeadingsButTheTwoEndsOfThe42StShuttle() {
        // Each has Uptown and Downtown, the 7 both ways, and the shuttle to the other one:
        // five signs for five different trains, and none of them could carry another's.
        val five = mapOf(
            "Times Sq-42 St" to listOf("Uptown", "Queens", "Downtown", "Hudson Yards", "Grand Central"),
            "Grand Central-42 St" to listOf("Uptown", "Queens", "Times Sq", "Downtown", "Hudson Yards"),
        )
        boards.forEach { (station, board) ->
            val headings = board.groups(day.time).map { it.heading }
            assertEquals(headings.distinct(), headings, station.name)
            if (station.name in five) {
                assertEquals(five[station.name], headings)
            } else {
                assertTrue(headings.size <= 4, "${station.name} has ${headings.size} headings: $headings")
            }
        }
        // Nearly every station is the two directions a rider expects.
        assertTrue(boards.count { (_, board) -> board.groups(day.time).size <= 2 } > 400)
    }

    @Test
    fun everyLineAStationListsThatHasATrainComingHasARow() {
        var lines = 0
        boards.forEach { (station, board) ->
            val rows = board.groups(day.time).flatMap { it.rows }.map { Routes.plain(it.route) }.toSet()
            // Looked for in all eight feeds, so a line riding in a feed the station does not
            // fetch would fail here and not pass unnoticed.
            val coming = day.feeds.values.flatMap { due(station, it) }.map { Routes.plain(Routes.display(it.route)) }.toSet()
            station.routes.filter { it in coming }.forEach { line ->
                lines++
                assertTrue(line in rows, "${station.name}: the $line has a train coming and no row")
            }
        }
        assertTrue(lines > 700, "nearly every line at every station had a train coming, and $lines were checked")
    }

    /**
     * Not a pass or fail: a measurement. A train rerouted onto another line's tracks still
     * rides in its own line's feed, and a station only fetches the feeds of the lines it
     * lists (see [Feeds.slugsFor]), so it never sees that train. This prints how many there
     * were, which is what it would take to justify fetching more feeds per station.
     */
    @Test
    fun countsTheTrainsRidingInAFeedTheirStationDoesNotFetch() {
        val missed = ArrayList<String>()
        var listed = 0
        boards.forEach { (station, board) ->
            listed += board.arrivals.size
            val fetched = Feeds.slugsFor(station)
            day.feeds.filterKeys { it !in fetched }.forEach { (slug, feed) ->
                due(station, feed).forEach { missed += "${Routes.display(it.route)} at ${station.name} (${station.routes.joinToString(" ")}), in $slug" }
            }
        }
        println("Trains at a station's platforms in a feed that station does not fetch: ${missed.size}, beside $listed it does list.")
        missed.groupingBy { it }.eachCount().forEach { (what, count) -> println("  $count x $what") }
    }

    /** The trips in [feed] that call at [station] and go on to another station, found without [Directions.arrivals]. */
    private fun due(station: Station, feed: GtfsRealtime.Feed): List<GtfsRealtime.Trip> {
        val here = station.platforms.map { it.stop }.toSet()
        return feed.trips.filter { trip ->
            val stations = trip.stops.filter { catalog.stop(it.stopId.dropLast(1)) != null }
            stations.dropLast(1).any { it.stopId.dropLast(1) in here && it.time >= day.time - 30 }
        }
    }

    private companion object {
        val GOLDEN = File("src/test/resources/golden/weekday-afternoon.txt")

        /** Built once for all the tests here. */
        val boards: Map<Station, Board> by lazy {
            Fixtures.catalog.stations.associateWith { Fixtures.weekdayAfternoon.board(it) }
        }
    }
}
