package com.thelightphone.subway.ui

import com.thelightphone.subway.data.Arrival
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Routes

/**
 * How many of the next trains a line of times names: the one to catch, and enough after it
 * to tell how long missing that one would cost.
 */
const val TIMES_SHOWN = 3

/**
 * How many headings a station gets on Home. Only Times Sq-42 St and Grand Central-42 St
 * have more, and their station pages list them all.
 */
const val HEADINGS_ON_HOME = 4

/**
 * The minutes to some trains, one word each, with "min" said once: "2", "9", "17 min". The
 * unit goes with the last number. A train at the platform is "Now" and takes none, so the
 * words can be "Now", "6 min", or "Now" by itself. On Home a favorite line is named first
 * whenever it comes, and there the last number is not always the last word: "8 min", "Now".
 */
fun timeWords(minutes: List<Int>): List<String> {
    val unitAt = minutes.indexOfLast { it > 0 }
    return minutes.mapIndexed { index, minute ->
        when {
            minute <= 0 -> "Now"
            index == unitAt -> "$minute min"
            else -> minute.toString()
        }
    }
}

/**
 * Said above anything that is no longer a live picture, with the time it was one. [what]
 * is what is old: a board's times, or the trains in a list.
 */
fun notLive(asOf: Long, what: String = "Times"): String = "Not live. $what as of ${clockLabel(asOf)}."

/** The same as one phrase: "2, 9, 17 min". */
fun timesLabel(minutes: List<Int>): String = timeWords(minutes).joinToString(", ")

/**
 * The trains a heading's one line on Home names: the next three, whichever lines they are
 * on. A favorite line is the exception. Its next train is always one of the three and is
 * named first, also when other trains are due before it.
 */
fun Board.Group.glance(favorites: Set<String>): List<Arrival> {
    val favorite = arrivals.filter { Routes.plain(it.route) in favorites }.distinctBy { Routes.plain(it.route) }
    return (favorite + (arrivals - favorite.toSet())).take(TIMES_SHOWN)
}

/**
 * Trains cut into runs of one line each, in the order given: N N Q is the N's two times
 * and then the Q's one. A run is drawn as one bullet and the times behind it.
 */
fun List<Arrival>.byLine(): List<List<Arrival>> {
    val runs = ArrayList<MutableList<Arrival>>()
    for (train in this) {
        val run = runs.lastOrNull()
        if (run != null && run.last().route == train.route) run += train else runs += mutableListOf(train)
    }
    return runs
}

/**
 * A heading's rows as the station page lists them: favorite lines first. The rest stay in
 * the board's own order, so setting a favorite moves that line's rows and nothing else.
 */
fun Board.Group.rowsWith(favorites: Set<String>): List<Board.Row> =
    rows.sortedByDescending { Routes.plain(it.route) in favorites }

/**
 * A row's last stop as the row says it: nothing when the heading above it has already said
 * it. "Astoria" is said for Astoria-Ditmars Blvd and "Hudson Yards" for 34 St-Hudson Yards,
 * but not for Astoria Blvd, which is a different stop.
 */
fun Board.Row.lastStop(heading: String): String =
    if (destination == heading || destination.split('-').any { it.trim() == heading }) "" else destination

/** The trains a row gives a time for. */
val Board.Row.named: List<Arrival> get() = arrivals.take(TIMES_SHOWN)

/**
 * Whether the station page has a time for a train that is so far only on the timetable.
 * Such a time is drawn lighter and the page says why. Old times are all drawn lighter
 * already and have their own note, so there the question does not come up.
 */
fun Board.View.Trains.namesScheduledTrains(): Boolean =
    !stale && groups.any { group -> group.rows.any { row -> row.named.any { !it.assigned } } }
