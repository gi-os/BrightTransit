package com.thelightphone.subway.ui

import com.thelightphone.subway.data.Catalog
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.RideState
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.Sighting
import com.thelightphone.subway.data.Train

/**
 * The words on a train's page and on a ride, kept apart from how they are drawn so they can
 * be read, and tested, as sentences.
 *
 * They say what the MTA said and nothing more, in words a rider on a platform would use:
 * nothing here names where the times come from. A count of stops comes before a time,
 * because on the lettered lines the times run a minute or more late and the count does
 * not. A time is "about", and one that has gone by is not given as when a train is due.
 * What is no longer known is said to be no longer known.
 */
object RideWords {

    /** "L to 8 Av": a train as its front names it. An express is spelled out, since a title has no diamond. */
    fun name(train: Train, catalog: Catalog): String {
        val line = Routes.display(train.route).let { if (Routes.isExpress(it)) "${Routes.glyph(it)} express" else it }
        return "$line ${toward(train, catalog)}"
    }

    /** "to 8 Av": where a train ends, for beside its bullet. */
    fun toward(train: Train, catalog: Catalog): String =
        train.lastStop?.let { "to ${catalog.stopName(it.platform)}" } ?: "train"

    fun stops(count: Int): String = if (count == 1) "1 stop" else "$count stops"

    /** Said of a trip no train has yet, wherever its times are shown. */
    const val TIMETABLE = "These are timetable times."

    /** "in about 6 min", or "now" for anything under half a minute. */
    private fun inAbout(time: Long, now: Long): String {
        val minutes = ((time - now) + 30) / 60
        return if (minutes <= 0) "now" else "in about $minutes min"
    }

    /** True for a time more than a minute gone. A train is not said to be due, or leaving, then. */
    private fun lapsed(time: Long, now: Long): Boolean = time < now - 60

    /** The two lines at the top of a train's page, seen from the platform named [here]. */
    class Top(val head: String, val detail: String, val sure: Boolean = true)

    /**
     * [Sighting.Unknown], [Sighting.Ended] and [Sighting.Lost] leave nothing to list, and
     * are worded by [instead].
     */
    fun top(sighting: Sighting, here: String, now: Long, catalog: Catalog): Top = when (sighting) {
        is Sighting.Scheduled -> Top(scheduled(sighting, now), "${notLeft(sighting.origin, here, now, catalog)} $TIMETABLE")
        is Sighting.Starting -> Top(leaves(sighting, now), if (sighting.sure) "It has not left yet." else "", sighting.sure)
        is Sighting.Coming -> Top(
            head = when {
                sighting.stopsAway > 0 -> "${stops(sighting.stopsAway)} away"
                sighting.where.how == Train.How.REACHED -> "Reached $here about ${clockLabel(sighting.where.since)}"
                sighting.where.how == Train.How.WAS_THERE -> "Last seen standing at $here"
                else -> "At $here now"
            },
            detail = when {
                sighting.stopsAway > 0 -> listOfNotNull(
                    whereabouts(sighting, catalog),
                    if (sighting.heldSince != null) "The times below will slip." else due(sighting, now),
                ).joinToString(" ")
                // The lettered lines cannot say when a train pulls out. Nobody should run for one on this word.
                sighting.where.how == Train.How.REACHED -> "It may have left."
                else -> ""
            },
            sure = sighting.sure,
        )
        is Sighting.Left -> Top(
            head = when {
                sighting.at <= 0 -> "Left $here"
                sighting.sure -> "Left $here about ${clockLabel(sighting.at)}"
                sighting.at > now -> "Due at $here at ${clockLabel(sighting.at)}"
                else -> "Was due at $here at ${clockLabel(sighting.at)}"
            },
            detail = sighting.next?.let { "Next stop ${catalog.stopName(it.platform)}." }.orEmpty(),
            sure = sighting.sure,
        )
        is Sighting.Skipping -> Top("Not stopping at $here", "Its stops have changed. ${nextStops(sighting.next, catalog)}".trim())
        is Sighting.Unknown, Sighting.Ended, is Sighting.Lost -> Top("", "")
    }

    private fun scheduled(sighting: Sighting.Scheduled, now: Long): String = when {
        sighting.due <= 0 -> "On the timetable"
        lapsed(sighting.due, now) -> "Was scheduled for ${clockLabel(sighting.due)}"
        else -> "Scheduled for ${clockLabel(sighting.due)}"
    }

    /** When a train waiting where its run starts is to set out. [from] is that stop's name and a space, where it has to be said. */
    private fun leaves(starting: Sighting.Starting, now: Long, from: String = ""): String =
        if (lapsed(starting.leaves, now)) "Was to leave ${from}at ${clockLabel(starting.leaves)}" else "Leaves ${from}${inAbout(starting.leaves, now)}"

    /**
     * Where a train on its way is, in one sentence, or null when the count has said it
     * already: one stop away is on its way here, and none away is here.
     */
    fun whereabouts(coming: Sighting.Coming, catalog: Catalog): String? {
        val how = coming.where.how
        return when {
            coming.stopsAway == 0 -> null
            // Nothing is said in the present of a train not heard from.
            how == Train.How.STARTING && !coming.sure -> null
            !how.standing && how != Train.How.STARTING && coming.stopsAway == 1 -> null
            else -> at(coming.where, catalog.stopName(coming.where.stop.platform), "Next stop")
        }
    }

    /**
     * Where a train is, in one sentence. [stop] is the name of the stop it is at or coming
     * to, and [next] the words for coming to one, which differ between its page and a ride.
     */
    private fun at(where: Train.Where, stop: String, next: String): String = when (where.how) {
        Train.How.COMING -> "$next $stop."
        Train.How.STARTING -> "It has not left $stop yet."
        Train.How.THERE -> "At $stop now."
        Train.How.REACHED -> "Last seen at $stop."
        Train.How.HELD -> "Standing at $stop since ${clockLabel(where.since)}."
        Train.How.WAS_COMING -> "Its next stop was $stop."
        Train.How.WAS_THERE -> "Last seen standing at $stop."
    }

    /**
     * When a train on its way is due at the platform, or null when there is no time worth
     * giving: the train is there, the time has gone by, or the train was last seen behind
     * its times and they are not to be counted from.
     */
    fun due(coming: Sighting.Coming, now: Long): String? =
        if (coming.due > 0 && coming.stopsAway > 0 && !coming.asLastSeen && !lapsed(coming.due, now)) "Here ${inAbout(coming.due, now)}." else null

    /** Said under a count, or a train's stops, that the MTA has altered since the train was first followed. */
    fun changed(at: Long): String = "Its stops changed at ${clockLabel(at)}."

    /** The sentence that stands where a train's stops would be, when there are none to show. */
    fun instead(sighting: Sighting): String? = when (sighting) {
        is Sighting.Unknown -> "No word on this train since ${clockLabel(sighting.since)}.\n\nCheck your connection."
        Sighting.Ended -> "This train has finished its run."
        is Sighting.Lost -> if (sighting.ranOut) FINISHED_UNSEEN else GONE
        else -> null
    }

    /** A train that went missing with stops and times still ahead of it. A new number is the usual reason at the end of a line. */
    const val GONE = "The MTA has stopped reporting this train.\n\nIt may have been taken out of service, or it may now be shown as another train."

    /** A train that is not there to be found, and by its times, or for all that is known of it, has had time to run. */
    const val FINISHED_UNSEEN = "The MTA has stopped reporting this train.\n\nIt has most likely finished its run."

    /**
     * What is known of a trip no train has yet, which is only that. Until its time to set
     * out it has not left. After that nobody can say: on the lettered lines the train has
     * mostly gone by then, and is named some minutes and some stops later.
     */
    private fun notLeft(origin: Train.Stop, here: String, now: Long, catalog: Catalog): String {
        if (origin.time <= now) return NO_TRAIN_YET
        val from = catalog.stopName(origin.platform)
        return if (from == here) "It has not left yet." else "It has not left $from yet."
    }

    /** Said of a trip whose time to set out has gone by with no train named for it. */
    const val NO_TRAIN_YET = "No train reported yet."

    private fun nextStops(next: List<Train.Stop>, catalog: Catalog): String {
        val names = next.map { catalog.stopName(it.platform) }
        return when (names.size) {
            0 -> ""
            1 -> "Its next stop is ${names[0]}."
            else -> "Its next stops are ${names[0]}, then ${names[1]}."
        }
    }

    /**
     * What the ride screen says: one statement, largest first. [figure] is the one thing
     * drawn at the size of the LightOS clock, a count or a single word. Without one,
     * [headline] is the statement and is drawn large instead. [body] is what else is
     * known, [aside] what is worth checking against the train itself, and [note] says how
     * far the rest can be trusted. With [sure] false the figure is drawn grey.
     */
    class Statement(
        val figure: String? = null,
        val headline: String,
        val body: List<String> = emptyList(),
        val aside: List<String> = emptyList(),
        val note: String? = null,
        val sure: Boolean = true,
    )

    /**
     * A stop's name with its spaces made unbreakable, so a line of the statement never
     * ends in the middle of "14 St-Union Sq". A name too long for one line of the largest
     * type is left to break where it can.
     */
    fun whole(name: String): String = if (name.length <= WHOLE) name.replace(' ', '\u00A0') else name

    private const val WHOLE = 18

    fun statement(ride: Ride, now: Long, catalog: Catalog): Statement {
        val train = ride.train
        fun name(platform: String) = whole(catalog.stopName(platform))
        val to = name(ride.to)
        val from = name(ride.from)
        // The stop before the rider's is a thing to listen for. The one they board at is not.
        val before = train.before(ride.to)?.takeIf { it.platform != ride.from }
        val comesAfter = before?.let { "Yours comes after ${name(it.platform)}." }
        val since = clockLabel(train.asOf)
        // Said under anything that is not being counted, once it is no longer fresh.
        val old = if (train.grade(now) == Train.Grade.LIVE) null else "No word since $since."
        fun note(sure: Boolean, asLastSeen: Boolean) = when {
            sure -> if (train.changed > 0) changed(train.changed) else null
            asLastSeen -> "No word since $since. This is where it was then."
            else -> "No word since $since. Counted from its times then."
        }
        fun asLastSeen(where: Train.Where) = where.how == Train.How.WAS_COMING || where.how == Train.How.WAS_THERE
        // A time is only given for a train that is running to its times, and never once it has gone by.
        fun dueAbout(time: Long, where: Train.Where) =
            if (time > 0 && time >= now - now % 60 && where.how != Train.How.HELD && !asLastSeen(where)) "Due about ${clockLabel(time)}." else null
        return when (val state = ride.state(now)) {
            is RideState.Waiting -> {
                val ahead = "Then ${stops(state.stops)} to $to."
                when (val coming = state.train) {
                    // On the lettered lines a train is only known to have got to the platform.
                    // "Now" in the largest type would go on being said of one that left a minute ago.
                    is Sighting.Coming -> if (coming.stopsAway == 0 && coming.where.how == Train.How.REACHED) {
                        Statement(
                            headline = "Reached $from about ${clockLabel(coming.where.since)}",
                            body = listOf("It may have left.", ahead),
                            aside = listOfNotNull(comesAfter),
                        )
                    } else Statement(
                        figure = if (coming.stopsAway == 0) "Now" else coming.stopsAway.toString(),
                        headline = when (coming.stopsAway) {
                            0 -> "at $from"
                            1 -> "stop away from $from"
                            else -> "stops away from $from"
                        },
                        // A train being held gets no time: whatever the MTA has for it will slip.
                        body = listOfNotNull(if (coming.heldSince == null) due(coming, now) else null, ahead),
                        aside = listOfNotNull(whereabouts(coming, catalog), comesAfter),
                        note = note(coming.sure, coming.asLastSeen),
                        sure = coming.sure,
                    )
                    is Sighting.Starting -> Statement(
                        headline = leaves(coming, now, "$from "),
                        body = listOfNotNull(if (coming.sure) "It has not left yet." else null, ahead),
                        aside = listOfNotNull(comesAfter),
                        note = old,
                    )
                    is Sighting.Scheduled -> Statement(
                        headline = scheduled(coming, now),
                        // Each a sentence of its own: run together they are three lines for a long name, and the last would be cut.
                        body = listOf(notLeft(coming.origin, catalog.stopName(ride.from), now, catalog), TIMETABLE, ahead),
                        aside = listOfNotNull(comesAfter),
                        note = old,
                    )
                    else -> top(coming, from, now, catalog).let { top ->
                        Statement(headline = top.head, body = listOfNotNull(top.detail.ifEmpty { null }, ahead), aside = listOfNotNull(comesAfter), note = old)
                    }
                }
            }
            is RideState.Riding -> Statement(
                figure = state.stops.toString(),
                headline = "stops to $to",
                body = listOfNotNull(dueAbout(state.arrives, state.where)),
                // "Next is 3 Av. Yours comes after 3 Av." says one thing twice.
                aside = listOfNotNull(at(state.where, name(state.next.platform), "Next is"), comesAfter.takeUnless { state.next.id == before?.id }),
                note = note(state.sure, asLastSeen(state.where)),
                sure = state.sure,
            )
            is RideState.Next -> Statement(
                figure = "Next",
                headline = "stop is $to",
                body = listOfNotNull(dueAbout(state.arrives, state.where)),
                aside = listOfNotNull(state.where.takeIf { state.standing }?.let { at(it, name(it.stop.platform), "Next is") }),
                note = note(state.sure, asLastSeen(state.where)),
                sure = state.sure,
            )
            is RideState.There -> Statement(
                figure = "Now",
                headline = "at $to",
                body = listOf(if (state.sure) "This is your stop." else "By its last times, this is your stop."),
                note = note(state.sure, asLastSeen = false),
                sure = state.sure,
            )
            is RideState.Past -> Statement(
                headline = "This train has left $to.",
                body = when {
                    state.sure -> listOfNotNull(state.next?.let { "Its next stop is ${name(it.platform)}." })
                    state.at > 0 -> listOf("It was due there about ${clockLabel(state.at)} and has finished its run.")
                    else -> listOf("It has finished its run.")
                },
            )
            is RideState.Dropped -> Statement(
                headline = "This train no longer stops at ${name(state.platform)}.",
                body = listOfNotNull(nextStops(state.next, catalog).ifEmpty { null }),
                note = old,
            )
            is RideState.Lost -> Statement(
                headline = "The MTA has stopped reporting this train.",
                body = listOfNotNull(
                    "It was last reported at ${clockLabel(state.since)}.",
                    if (state.due > 0) "It was due at $to at ${clockLabel(state.due)}." else null,
                    // Nobody was looking when it went, and its times had all gone by.
                    if (train.ranOut) "It has most likely finished its run." else null,
                ),
                aside = listOfNotNull(comesAfter.takeUnless { train.ranOut }),
            )
            // Only what cannot have gone stale: the name of the stop, and the one before it.
            is RideState.Unknown -> Statement(
                headline = "Get off at $to.",
                body = listOfNotNull(
                    train.before(ride.to)?.let { if (it.platform == ride.from) "It is the next stop." else "It is the stop after ${name(it.platform)}." },
                    if (state.due > 0) "At ${clockLabel(state.since)} the train was due there at ${clockLabel(state.due)}." else null,
                ),
                note = "No word since ${if (state.due > 0) "then" else clockLabel(state.since)}, which is too long ago to count from.",
            )
        }
    }

    /**
     * A train in the list of other trains, told by what a rider on it would hear or see.
     * [asOf] is when the list was made, which is the moment the words are true of.
     */
    fun whereabouts(train: Train, asOf: Long, catalog: Catalog): String {
        val where = train.whereAt(asOf) ?: return ""
        return at(where, catalog.stopName(where.stop.platform), "Next stop").removeSuffix(".")
    }
}
