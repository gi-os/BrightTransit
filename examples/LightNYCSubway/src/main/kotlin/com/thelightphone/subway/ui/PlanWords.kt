package com.thelightphone.subway.ui

import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.Change
import com.thelightphone.subway.data.Direct
import com.thelightphone.subway.data.Plan
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.Sighting
import com.thelightphone.subway.data.Station

/**
 * The words for the way from one station to another, kept apart from how they are drawn so
 * they can be read, and tested, as sentences.
 *
 * Every time here is the MTA's guess or the timetable's, and is worded as one: "about" for
 * a train on the move, and a plain "at" for one that is not yet a train, with a line under
 * it that says the times are the timetable's. How many stops is a count and is said
 * plainly. Nothing is promised.
 *
 * A train's two main lines are kept short enough to sit on one line each beside its
 * bullet, with the longest time there is, wherever the station's own name is all that has
 * to be said. A platform that goes by another name is the one thing that can run them over.
 */
object PlanWords {

    /** Above the way there: where it starts. */
    fun from(station: Station): String = "From ${station.name}"

    /** Under the way there, on the page of the station it ends at: what a tap on it is for. */
    const val TAP = "Tap to follow a train."

    /**
     * Between the parts of a train's small line. Each part is kept whole and the dot stays
     * with the part before it, so where the line runs over it never ends on "1" with
     * "stop" under it, and never starts with a dot.
     */
    private const val BETWEEN = "\u00A0\u00A0·  "

    /** The title of the list of trains: where they are going. */
    fun to(station: Station): String = "To ${station.name}"

    /**
     * One train as the list of ways there words it. [leaves] and [arrives] are its two
     * lines, [arrives] null where the feed gives no time to go by. [detail] is how to know
     * the train on the platform: its sign, where it ends, and how many stops the ride is.
     * [warning] is set for a train that is being held, and for a trip no train has yet.
     * With [sure] false the two lines are drawn lighter: the times are not a moving train's.
     * [more] is a second small line, which only a way with a change has.
     */
    class Row(val leaves: String, val arrives: String?, val detail: String, val warning: String?, val sure: Boolean, val more: String? = null)

    /**
     * In the list, a platform is named only where it goes by another name than its
     * station: the L leaves "8 Av" inside the station called 14 St, and a rider told only
     * "14 St" would listen for the wrong stop.
     *
     * With [here], the words are for the page of the station the train is going to, under
     * a line that has just named the station it leaves from. Every other train on that
     * page is leaving it. So this one "leaves there" and "arrives here", and cannot be
     * taken for one of them.
     */
    fun row(train: Direct, plan: Plan, now: Long, catalog: Catalog, here: Boolean = false): Row {
        val boarding = catalog.stopName(train.from).takeIf { it != plan.from.name }
        val from = when {
            boarding != null -> "$boarding "
            here -> "there "
            else -> ""
        }
        val platform = catalog.stopName(train.to).takeIf { it != plan.to.name }
        val to = when {
            platform != null -> "at $platform "
            here -> "here "
            else -> ""
        }
        val coming = train.seen(now) as? Sighting.Coming
        val held = coming?.heldSince != null
        return Row(
            // The timetable's lines are the moving train's with "at" for "about". Anything longer runs to a second line.
            leaves = if (train.running) leaves(train.leaves, now, from) else "Leaves ${from}at ${clockLabel(train.leaves)}",
            arrives = when {
                train.arrives > 0 && train.running -> "Arrives ${to}about ${clockLabel(train.arrives)}"
                train.arrives > 0 -> "Arrives ${to}at ${clockLabel(train.arrives)}"
                // With no time to give, the line is only worth having for the platform's name.
                platform == null -> null
                else -> "Arrives at $platform"
            },
            detail = listOfNotNull(
                train.heading.takeUnless { train.lastStop == it || train.lastStop.split('-').any { part -> part.trim() == it } },
                "to ${train.lastStop}",
                RideWords.stops(train.stops),
            ).joinToString(BETWEEN) { it.replace(' ', '\u00A0') },
            warning = when {
                !train.running -> RideWords.TIMETABLE
                coming != null && held -> "${RideWords.whereabouts(coming, catalog)} Its times will slip."
                else -> null
            },
            sure = train.running && !held,
        )
    }

    /**
     * A way there with one change, worded like a direct train: when the first train
     * leaves, when the second gets there, and where to change and to what. The first train
     * is always a moving one, so its line says "about"; the second's says "at" while it is
     * only the timetable's. [here] is as in [row].
     *
     * The time to change is the gap between the two trains' times at the station, which
     * is what the rider has to go by on the platform.
     */
    fun change(way: Change, plan: Plan, now: Long, catalog: Catalog, here: Boolean = false): Row {
        val boarding = catalog.stopName(way.first.from).takeIf { it != plan.from.name }
        val from = when {
            boarding != null -> "$boarding "
            here -> "there "
            else -> ""
        }
        val platform = catalog.stopName(way.second.to).takeIf { it != plan.to.name }
        val to = when {
            platform != null -> "at $platform "
            here -> "here "
            else -> ""
        }
        val second = way.second
        val coming = second.seen(now) as? Sighting.Coming
        val held = coming?.heldSince != null
        return Row(
            leaves = leaves(way.leaves, now, from),
            arrives = if (second.running) "Arrives ${to}about ${clockLabel(way.arrives)}" else "Arrives ${to}at ${clockLabel(way.arrives)}",
            // Named as the first train names the stop, which is what the rider listens for: the L
            // calls the stop at 14 St "8 Av". The sentence breaks between its words but never
            // inside the name.
            detail = "Change at ${catalog.stopName(way.first.to).ifEmpty { way.at.name }.replace(' ', '\u00A0')} to the ${lines(way.lines).replace(' ', '\u00A0')}",
            more = listOf("${minutes(way.window)} min to change", RideWords.stops(way.stops)).joinToString(BETWEEN) { it.replace(' ', '\u00A0') },
            warning = when {
                !second.running -> "The second train's times are the timetable's."
                held -> "The second train is being held. Its times will slip."
                else -> null
            },
            sure = way.sure(now),
        )
    }

    /** "4", "4 or 5", "4, 5 or 6". Nothing for no lines, which only a damaged saved ride could have. */
    fun lines(routes: List<String>): String {
        val names = routes.map { Routes.glyph(it) }.distinct()
        return when (names.size) {
            0 -> ""
            1 -> names.single()
            else -> names.dropLast(1).joinToString(", ") + " or " + names.last()
        }
    }

    /** Whole minutes, rounded down: two and a half minutes to change is called two. */
    private fun minutes(seconds: Long): Long = maxOf(0, seconds / 60)

    /** "Leaves in about 6 min", or "Leaves now" for anything under half a minute. [from] is where from and a space, or nothing. */
    private fun leaves(time: Long, now: Long, from: String): String {
        val minutes = ((time - now) + 30) / 60
        return if (minutes <= 0) "Leaves ${from}now" else "Leaves ${from}in about $minutes min"
    }

    /** Under the first train on a station's page, when it is not the only one worth a look. */
    fun others(count: Int): String = if (count == 1) "1 other train to choose from." else "$count other trains to choose from."

    /** Above a list of later trains: the time the train before them was to leave, which is the feed's guess like any other. */
    fun after(time: Long): String = "Trains that leave after about ${clockLabel(time)}."

    /**
     * Said in place of a way there, or beside one, when lines that go there could not be
     * looked at. With no line to name, something could not be looked at all the same.
     */
    fun unchecked(lines: List<String>): String =
        if (lines.isEmpty()) "Could not check just now." else "Could not check the ${lines.joinToString(" ")} just now."

    fun none(plan: Plan, later: Boolean): String =
        "No ${if (later) "later " else ""}direct train from ${plan.from.name} to ${plan.to.name} right now."

    /** Above the ways with a change, when there are direct trains above them too. */
    const val WITH_A_CHANGE = "Sooner with a change"

    /** Under the ways with a change. */
    const val CHANGE_TAP = "Tap one to count down to the change."

    /** On the page of the station found, under the line that names where from: no train runs there direct. */
    const val NONE_HERE = "No direct train right now."

    /**
     * On the search screen, when it was opened from a station's page: what opening a
     * station found will show. It is the one place a rider is told the tool can do this.
     */
    fun searching(from: Station): String = "Open a station to see trains to it from ${from.name}."
}
