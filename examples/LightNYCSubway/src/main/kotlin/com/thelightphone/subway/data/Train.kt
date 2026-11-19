package com.thelightphone.subway.data

import kotlinx.serialization.Serializable

/**
 * One train, followed from one download to the next.
 *
 * [stops] are the stations it still has to call at, in order, each with the time the feed
 * gives for it (0 for none). Points that are not stations are left out when a trip is read,
 * so a count of these is a count of stops. [asOf] is when the MTA built the feed they came
 * from. [standing] means that feed had the train at the first of them, and [stoodSince] is
 * the earliest time the feed has given for its being there, or 0 while that time is still
 * ahead. [starting] means the first of them is where its run begins and the feed has not
 * seen it move.
 *
 * [behind] are the stops it has left since it was first followed, oldest first. One with no
 * time is a stop nobody saw it leave: the feed first named the train beyond it. [dropped]
 * are stops it listed and then stopped listing without having reached them. [checked] is
 * when the MTA built the latest feed looked in, which is later than [asOf] when the train
 * was not in it. [changed] is when its stops were last found altered under it, or 0.
 * [ended] means the train left the feed at the end of its run. [relisted] is a stop it had
 * left that the last feed put back at the front of its list, or empty. [ranOut] means that
 * when the train was first missed from the feed, every time it had was already past.
 *
 * [assigned] is false while the trip is only the timetable's: a slot a train may fill, or
 * may never. The railway's feed calls every trip assigned, so there a train counts once
 * the feed has a status for it.
 *
 * Nothing here reads a clock. [after] says what one more download changes, and [ahead],
 * [whereAt], [seenFrom] and [Ride.state] say what can be claimed at a given moment, so all
 * of it can be run over recorded feeds.
 */
@Serializable
data class Train(
    val feed: String,
    val id: String,
    val route: String,
    val assigned: Boolean,
    val stops: List<Stop>,
    val asOf: Long,
    val standing: Boolean = false,
    val behind: List<Stop> = emptyList(),
    val dropped: List<Stop> = emptyList(),
    val checked: Long = asOf,
    val changed: Long = 0,
    val ended: Boolean = false,
    val stoodSince: Long = 0,
    val starting: Boolean = false,
    val relisted: String = "",
    val ranOut: Boolean = false,
) {
    /** A stop as the feeds name it ("L14N": platform and direction) and when the train is due there. */
    @Serializable
    data class Stop(val id: String, val time: Long) {
        /** The id the station file knows the stop by. */
        val platform: String get() = id.dropLast(1)
    }

    /** How far the stops in hand can be trusted at some moment. */
    enum class Grade {
        /** As new as the feed gets. */
        LIVE,

        /** Old enough that the train has moved since. Where it is now is worked out from its times. */
        RECKONED,

        /** Too old to say where the train is. */
        UNKNOWN,
    }

    /** How a train stands to the stop it is at or coming to. */
    enum class How(val standing: Boolean) {
        /** On its way there. */
        COMING(false),

        /** Still at the stop its run starts from, as far as the feed has said. */
        STARTING(false),

        /** Standing there. */
        THERE(true),

        /** Reached it a while ago, on a line whose feed cannot say when a train leaves a stop. */
        REACHED(true),

        /** Held there, long enough that its times will slip. */
        HELD(true),

        /** On its way there when last heard of, and not counted on from that. */
        WAS_COMING(false),

        /** Standing there when last heard of, and not counted on from that. */
        WAS_THERE(true),
    }

    /**
     * Where a train is: at or on its way to [stop], and [how]. [since] is when it got
     * there or began to be held, where that is known.
     */
    data class Where(val stop: Stop, val how: How, val since: Long = 0)

    /**
     * A train that left the feed at the end of its run is reckoned from its last times,
     * at once and for good: the L's feed lets go of a train as it leaves its second-to-last
     * stop, and there will be no later word to wait for.
     */
    fun grade(now: Long): Grade {
        val age = now - asOf
        return when {
            ended -> Grade.RECKONED
            age > RECKON_FOR -> Grade.UNKNOWN
            age > LIVE_FOR -> Grade.RECKONED
            else -> Grade.LIVE
        }
    }

    /**
     * True once the train has been out of the feed for longer than one bad download
     * explains. Trains drop out for a minute and come back often enough to wait that long.
     */
    val lost: Boolean get() = !ended && checked - asOf > MISSED_FOR

    /**
     * Since when the train had been held where it stood, as the feed that last had it was
     * built, or null. A train is held once it has stood two minutes past the time the feed
     * has for its stop. The lettered lines move that time on with the clock, so there it
     * goes by how long the feed has had the train at the stop, and takes twice as long:
     * only one stop in a hundred lasts four minutes. A train waiting to set out, its time
     * still ahead, is not held.
     *
     * It is never judged by the clock since. Four minutes on the clock is not four minutes
     * at the stop: the train may have left a second after the feed was built. And a train
     * the screen has called held is then always one that is left where it was once its
     * list is old: see [behindItsTimes].
     */
    private val heldSince: Long?
        get() {
            if (!standing || !assigned) return null
            val due = stops.firstOrNull()?.time ?: return null
            if (due <= 0 || due > asOf) return null
            val since = if (stoodSince > 0) stoodSince else due
            return since.takeIf { asOf - due > HELD_AFTER || asOf - since > HELD_LONG }
        }

    /**
     * True when the train was already behind the times in hand as the feed was built: held
     * at a stop, standing well past its time there, or late for its next. Those times say
     * when it would get somewhere if it left at once, and nothing is counted from them.
     */
    private val behindItsTimes: Boolean
        get() {
            val first = stops.firstOrNull() ?: return false
            if (!standing) return first.time in 1..asOf
            val next = stops.getOrNull(1)?.time ?: 0
            return heldSince != null || (first.time > 0 && asOf - first.time > DWELL) || next in 1..asOf
        }

    /** True when the list is old and the train is left where it was last seen, not counted on from its times. */
    private fun asLastSeen(now: Long): Boolean = assigned && !ended && grade(now) != Grade.LIVE && behindItsTimes

    /**
     * Whether the train is taken to be at its first stop at [now]. Once the list is old, a
     * train that was standing stays there while it is not counted on at all, or until half
     * a minute before the time it is to leave: one waiting at the end of the line has not
     * gone because a download was missed.
     */
    fun standingAt(now: Long): Boolean = standing && stops.isNotEmpty() && when {
        grade(now) == Grade.LIVE -> true
        ended -> false
        behindItsTimes -> true
        else -> stops.first().time - EARLY > now
    }

    /**
     * The stops still ahead at [now]. While the list is live that is the list. After that
     * a stop counts as passed half a minute before its time, so a count made without a
     * signal runs early and never late, and a stop the train was standing at counts as
     * passed with it. A stop with no time cannot be reckoned past.
     *
     * Two trains are left where they were. A trip with no train has no place to reckon.
     * And a train that was behind its times when last heard of is not counted on from
     * them: a count that leapt ahead on times the train had already missed would only
     * have to be taken back.
     */
    fun ahead(now: Long): List<Stop> = when {
        !assigned || grade(now) == Grade.LIVE || asLastSeen(now) || standingAt(now) -> stops
        else -> stops.drop(if (standing) 1 else 0).dropWhile { it.time > 0 && it.time - EARLY <= now }
    }

    /**
     * Where the train is at [now], as far as can be said, or null when it has no stops
     * left to be at. The lettered lines and the G call every train stopped, and move it to
     * a stop when it gets there, so on them a train is only said to be at a stop for the
     * first minute: after that it has reached it, and may have left. Whether it is held
     * there is what its last word said, however the clock has moved on since.
     */
    fun whereAt(now: Long): Where? {
        val next = ahead(now).firstOrNull() ?: return null
        val live = grade(now) == Grade.LIVE
        val there = standingAt(now)
        return when {
            there && asLastSeen(now) -> Where(next, How.WAS_THERE, heldSince ?: stoodSince)
            there && !live -> Where(next, How.THERE, stoodSince)
            there -> heldSince?.let { Where(next, How.HELD, it) }
                ?: Where(next, if (feed in ALWAYS_STOPPED && stoodSince > 0 && now - stoodSince > AT_FOR) How.REACHED else How.THERE, stoodSince)
            starting && next.id == stops.first().id -> Where(next, How.STARTING)
            asLastSeen(now) -> Where(next, How.WAS_COMING)
            else -> Where(next, How.COMING)
        }
    }

    /** Where [platform] comes among the stops still listed, or -1. A trip calls at a platform once. */
    fun place(platform: String): Int = stops.indexOfFirst { it.platform == platform }

    /** [platform] as a stop this train has left, or null. */
    fun left(platform: String): Stop? = behind.lastOrNull { it.platform == platform }

    /** [platform] as a stop this train was going to make and no longer is, or null. */
    fun skips(platform: String): Stop? = dropped.lastOrNull { it.platform == platform }

    /** [platform] as this train last listed it, wherever that was. */
    fun stop(platform: String): Stop? = stops.firstOrNull { it.platform == platform } ?: left(platform) ?: skips(platform)

    /** The last stop the train is known to be making, which is what its front says. */
    val lastStop: Stop? get() = stops.lastOrNull() ?: behind.lastOrNull()

    /** The stop this train makes just before [platform], or null when it was never seen. */
    fun before(platform: String): Stop? {
        val at = place(platform)
        return when {
            at > 0 -> stops[at - 1]
            at == 0 -> behind.lastOrNull()
            else -> behind.indexOfLast { it.platform == platform }.let { behind.getOrNull(it - 1) }
        }
    }

    /**
     * The stops after [platform], which are the ones a rider there could ride to. Once
     * the train is past the platform, or no longer stops at it, they are simply the stops
     * it has left to make.
     */
    fun stopsAfter(platform: String): List<Stop> = stops.drop(place(platform) + 1)

    /** True when [other] is this train under its own id, or under that id with its ending dropped or put back. */
    fun runsAs(other: String): Boolean = sameTrain(id, other)

    /**
     * This train, knowing how long [earlier] had already had it at the stop it stands at.
     * That is for a train read afresh from each download, which would otherwise seem to
     * have just arrived every time.
     */
    fun since(earlier: Train?): Train =
        if (earlier != null && standing && earlier.standing && earlier.stoodSince in 1 until stoodSince && earlier.stops.firstOrNull()?.id == stops.firstOrNull()?.id) {
            copy(stoodSince = earlier.stoodSince)
        } else {
            this
        }

    /**
     * This train as [fresh] has it. A feed no newer than the last one looked in changes
     * nothing, so a download that failed and handed back the old copy can be passed in too.
     *
     * The stops only ever shrink from the front, with these exceptions the feeds are known
     * for, and each is settled here:
     *  - a stop the train has left turns up at the front again. For one download it stays
     *    left: the lettered lines do that. Listed there twice running, the train has not
     *    left it after all, and it is given back with [changed] set. A stop nobody saw it
     *    leave is given back the first time.
     *  - the stops ahead are changed under a moving train (made to skip, cut short, sent
     *    another way). The new list is taken as it is, what fell out of it goes to
     *    [dropped], and [changed] is set.
     *  - the train is not in the feed at all. It keeps what it had, and whether that is a
     *    missed download, the end of its run or the train gone is told by [lost] and [ended].
     *  - the trip has no train. It has made no stops, whatever the feed does to its list:
     *    see [stillNoTrain]. When it is given one, see [givenItsTrain].
     *
     * Whichever it is, a stop the train once listed is never simply forgotten. It is still
     * ahead, or [behind], or [dropped], so a rider bound for it is always told which.
     */
    fun after(fresh: GtfsRealtime.Feed, catalog: Catalog): Train {
        if (fresh.timestamp <= checked) return this
        // Whether a missing train has finished is settled while it is only just missing.
        // Asked again at every later feed, a train lost in the middle of its run would
        // turn into one that finished as the clock came round to its old last time.
        // The same goes for whether its times had run out: that is for a train nobody
        // was looking at when it went, and is read off the first feed it is missed from.
        val trip = find(fresh) ?: return copy(
            checked = fresh.timestamp,
            ended = ended || (!lost && nearItsEnd(minOf(fresh.timestamp, asOf + MISSED_FOR))),
            ranOut = if (checked == asOf) (stops.maxOfOrNull { it.time } ?: 0) in 1..fresh.timestamp else ranOut,
        )
        val seen = of(feed, fresh, trip, catalog)
        if (!assigned) return if (seen.assigned) givenItsTrain(seen) else stillNoTrain(seen)

        val back = seen.stops.firstOrNull()?.takeIf { first -> behind.any { it.id == first.id } }
        val unseen = back != null && behind.first { it.id == back.id }.time <= 0
        if (back != null && (back.id == relisted || unseen) && !ended) {
            fun stillListed(stop: Stop) = seen.stops.any { it.id == stop.id }
            return seen.copy(
                behind = behind.takeWhile { it.id != back.id },
                // The list handed back can be a changed one too. What it no longer has,
                // of the stops from the one given back on, the train will not make.
                dropped = (dropped + behind.dropWhile { it.id != back.id } + stops).filterNot(::stillListed).distinctBy { it.id },
                changed = fresh.timestamp,
            )
        }
        val ahead = seen.stops.dropWhile { stop -> behind.any { it.id == stop.id } }
        fun listed(stop: Stop) = ahead.any { it.id == stop.id }
        // A first stop this train never listed says nothing about the ones it did list:
        // none of them is behind it for that.
        val reached = ahead.firstOrNull()?.let { first -> stops.indexOfFirst { it.id == first.id } } ?: stops.size
        val passed = stops.take(maxOf(reached, 0))
        val expected = stops.drop(maxOf(reached, 0))
        val seenThere = seen.standing && ahead.size == seen.stops.size
        // Once seen standing at a stop the train is taken to be there until the stop
        // leaves its list. The numbered lines' status wavers, and the count must not go back
        // up. A trip the feed has taken its train from again is standing nowhere.
        val stillThere = standing && seen.assigned && reached == 0 && ahead.isNotEmpty()
        return seen.copy(
            stops = ahead,
            standing = seenThere || stillThere,
            stoodSince = when {
                stillThere && stoodSince > 0 -> if (seenThere && seen.stoodSince > 0) minOf(stoodSince, seen.stoodSince) else stoodSince
                seenThere -> seen.stoodSince
                else -> 0
            },
            starting = seen.starting && behind.isEmpty() && reached == 0,
            // A stop cannot have been left later than the feed that no longer lists it.
            behind = behind + passed.map { it.copy(time = minOf(it.time, fresh.timestamp)) },
            dropped = (dropped + expected).filterNot(::listed),
            changed = if (reached < 0 || expected.map { it.id } != ahead.map { it.id }) fresh.timestamp else changed,
            ended = ahead.isEmpty(),
            relisted = back?.id.orEmpty(),
        )
    }

    /**
     * This trip as [seen] has it, now that it has a train. The train is taken as the feed
     * lists it, but the list the trip had is not thrown away. The lettered lines often own
     * up to a train some stops into its run, and the stops the trip listed before the
     * train's first are behind a train that now exists. Nobody saw it leave them, so they
     * are filed with no time. A stop the trip listed further on that the train does not is
     * one it will not make, and so is one the timetable had already taken off the trip.
     */
    private fun givenItsTrain(seen: Train): Train {
        val from = seen.stops.firstOrNull()?.let { first -> stops.indexOfFirst { it.id == first.id } } ?: stops.size
        fun listed(stop: Stop) = seen.stops.any { it.id == stop.id }
        val gone = (dropped + stops.drop(maxOf(from, 0))).filterNot(::listed).distinctBy { it.id }
        return seen.copy(
            behind = stops.take(maxOf(from, 0)).map { it.copy(time = 0) },
            dropped = gone,
            changed = if (from < 0 || gone.map { it.id } != dropped.map { it.id }) seen.asOf else changed,
            starting = seen.starting && from <= 0,
            ended = seen.stops.isEmpty(),
        )
    }

    /**
     * This trip as [seen] has it, still with no train. The lettered lines take a stop off
     * such a trip's list as its timetable time goes by, as if a train had made it, and
     * give the trip its train some minutes later, listed from the top again. So the stops
     * taken off the front are kept and none is counted as left. A list that differs in
     * any other way is the timetable changed, and is taken as it is.
     */
    private fun stillNoTrain(seen: Train): Train {
        val from = seen.stops.firstOrNull()?.let { first -> stops.indexOfFirst { it.id == first.id } } ?: -1
        val trimmed = from >= 0 && stops.drop(from).map { it.id } == seen.stops.map { it.id }
        return if (trimmed) {
            seen.copy(stops = stops.take(from) + seen.stops, dropped = dropped, changed = changed)
        } else {
            seen.copy(
                dropped = (dropped + stops).filterNot { gone -> seen.stops.any { it.id == gone.id } }.distinctBy { it.id },
                changed = seen.asOf,
            )
        }
    }

    /**
     * The trip this train is running as in [fresh]. The end of an id can drop off while
     * the train runs ("085350_1..N15R" becomes "085350_1..N") and come back, so when the
     * whole id is not there the one trip on the line with the same front part is looked
     * at. It is taken only when it is this id with its ending dropped or put back, and
     * still makes one of this train's stops: two trains can set out in the same minute
     * the same way and differ in nothing but the ending. That front part alone can also be
     * shared with a timetable slot, and a train that had its trip is never taken for one.
     */
    private fun find(fresh: GtfsRealtime.Feed): GtfsRealtime.Trip? {
        fresh.trips.firstOrNull { it.id == id }?.let { return it }
        val stem = stem(id)
        val twin = fresh.trips.singleOrNull { it.route == route && stem(it.id) == stem && (it.assigned || !assigned) } ?: return null
        return twin.takeIf { sameTrain(it.id, id) && it.stops.any { stop -> stops.any { mine -> mine.id == stop.stopId } } }
    }

    /**
     * With two stops or three minutes to go, a train missing from the feed has most likely
     * finished. A timetable slot has not: it never ran, and is missing like any other.
     */
    private fun nearItsEnd(at: Long): Boolean =
        assigned && (stops.size <= 2 || stops.last().time.let { it > 0 && it <= at + NEAR_END })

    /**
     * How this train stands at [now] for someone on [platform]: the two lines at the top
     * of its page, and what a rider is told while waiting for it.
     */
    fun seenFrom(platform: String, now: Long): Sighting {
        val grade = grade(now)
        val live = grade == Grade.LIVE
        val gone = left(platform)
        val here = place(platform)
        // Where it stops next is only said while that is news.
        val next = stops.firstOrNull()?.takeIf { live }
        val reckoned = ahead(now)
        return when {
            lost -> Sighting.Lost(ranOut)
            // Out of the feed at the end of its run, and by now through the stops it had
            // left. A last stop with no time is given five minutes, unless it is the very
            // platform being asked about.
            ended && (reckoned.isEmpty() || (now - asOf > RECKON_FOR && reckoned.none { it.platform == platform })) -> Sighting.Ended
            // A trip with no train has left nowhere, whatever was once filed for it.
            assigned && gone != null -> Sighting.Left(gone.time, next, sure = true)
            skips(platform) != null -> Sighting.Skipping(if (live) stops.take(2) else emptyList())
            // Never listed while it was followed: it had left before the first look.
            here < 0 && (assigned || stops.isEmpty()) -> Sighting.Left(0, next, sure = true)
            grade == Grade.UNKNOWN -> Sighting.Unknown(asOf)
            !assigned -> Sighting.Scheduled(if (here >= 0) stops[here].time else 0, stops.first())
            else -> {
                val away = reckoned.indexOfFirst { it.platform == platform }
                val where = whereAt(now)
                when {
                    away < 0 || where == null -> Sighting.Left(stops[here].time, reckoned.firstOrNull(), sure = false)
                    away == 0 && where.how == How.STARTING -> Sighting.Starting(reckoned[away].time, sure = live)
                    else -> Sighting.Coming(
                        stopsAway = away + if (where.how.standing) 0 else 1,
                        due = reckoned[away].time,
                        where = where,
                        sure = live,
                    )
                }
            }
        }
    }

    companion object {
        /** Stops this new are shown as they are. A feed is fetched every half minute and can be a quarter minute old. */
        const val LIVE_FOR = 60L

        /** Past this, where the train is cannot be worked out from its old times either. */
        const val RECKON_FOR = 5 * 60L

        /** How much sooner than its time a stop counts as passed when reckoning. */
        const val EARLY = 30L

        /** How long a train can be out of the feed before it is taken for gone. */
        const val MISSED_FOR = 75L

        /** A train stopped this long past its time at one stop is being held, and the times after it are not to be believed. */
        const val HELD_AFTER = 120L

        /** Where the feed keeps moving that time on, a train at one stop for this long is being held. */
        const val HELD_LONG = 240L

        /** A train still standing this long past its time has overstayed an ordinary stop, and its later times are behind it. */
        private const val DWELL = 60L

        /** How long a train is said to be at a stop on the lines whose feed never says it has left one. */
        const val AT_FOR = 60L

        private const val NEAR_END = 3 * 60L

        private const val RAILWAY = "gtfs-si"

        /**
         * The feeds that call every train stopped on every download: the lettered lines
         * and the G. On them the word says nothing, and where the train is goes by the
         * stop it names.
         */
        private val ALWAYS_STOPPED = setOf("gtfs-ace", "gtfs-bdfm", "gtfs-g", "gtfs-jz", "gtfs-nqrw")

        /**
         * The part of a trip id that stays put: when it set out, its line and which way
         * ("085350_1..N15R" gives "085350_1..N"). What follows names the path, and comes and goes.
         */
        fun stem(id: String): String {
            var end = id.indexOf('.')
            if (end < 0) return id
            while (end < id.length && id[end] == '.') end++
            return if (end < id.length) id.substring(0, end + 1) else id
        }

        /** True when two ids are one train's: the same, or one of them the other with its ending dropped. */
        fun sameTrain(one: String, other: String): Boolean =
            one == other || (stem(one) == stem(other) && (one.startsWith(other) || other.startsWith(one)))

        /** The train running as [id] in the feed fetched as [slug], or null when it is not there. */
        fun find(slug: String, feed: GtfsRealtime.Feed, id: String, catalog: Catalog): Train? =
            feed.trips.firstOrNull { id.isNotEmpty() && it.id == id }?.let { of(slug, feed, it, catalog) }

        fun of(slug: String, feed: GtfsRealtime.Feed, trip: GtfsRealtime.Trip, catalog: Catalog): Train {
            var stops = trip.stops
                .filter { it.stopId.length > 1 && catalog.stop(it.stopId.dropLast(1)) != null }
                .map { Stop(it.stopId, it.time) }
            // The railway's time for a train's last stop stays some forty minutes off until
            // the train is there. No time is better than that one.
            if (slug == RAILWAY && stops.isNotEmpty()) stops = stops.dropLast(1) + stops.last().copy(time = 0)
            val position = feed.positions[trip.id]
            val assigned = trip.assigned && !(slug == RAILWAY && position != null && position.status == null)
            // The place the feed gives a trip with no train is only the timetable walking down its list.
            val named = position?.takeIf { assigned && it.sighting(feed) }?.let { seen -> stops.indexOfFirst { it.id == seen.stopId } } ?: -1
            // The lettered lines can go on listing a stop after the train has reached the
            // one beyond it, and where the train was seen is the newer word. Further down
            // the list than that it is no sighting: those feeds now and then list a run of
            // stops a second apart and name the last of them, with the train still short of the first.
            val at = named.takeIf { it <= 1 } ?: -1
            val first = trip.stops.firstOrNull()
            return Train(
                feed = slug,
                id = trip.id,
                route = trip.route,
                assigned = assigned,
                stops = stops.drop(maxOf(at, 0)),
                asOf = feed.timestamp,
                standing = at >= 0,
                stoodSince = if (at >= 0) stops[at].time.takeIf { it in 1..feed.timestamp } ?: 0 else 0,
                // A first stop with a time to leave and none to arrive is where the run
                // starts, and the train has not moved from it.
                starting = assigned && at < 0 && first != null && first.departsOnly && stops.firstOrNull()?.id == first.stopId,
            )
        }

        /**
         * Whether a position says the train has got to its stop. One dated after its own
         * feed was built is a forecast, which the lettered lines sometimes send.
         *
         * The lettered lines and the G send "stopped" for every train on every download.
         * There the position moves to a stop when the train reaches it, is dated with the
         * list's own time for that stop, and can stay on it until the next stop is
         * reached. So it counts the stops rightly and cannot say the train is still there.
         *
         * On the numbered lines a train whose stops are being changed can be called
         * stopped at a stop minutes before its own times put it there. The sighting is
         * believed all the same: the mistake that makes is early, and doubting it would
         * make the count late for every train held past its time.
         */
        private fun GtfsRealtime.Position.sighting(feed: GtfsRealtime.Feed): Boolean =
            status == GtfsRealtime.Status.STOPPED && timestamp <= feed.timestamp

        /**
         * The trains in [feed] that will call at [stop] ("L03N", so the direction counts),
         * soonest there first. Timetable slots are left out: nobody is riding one.
         */
        fun callingAt(slug: String, feed: GtfsRealtime.Feed, stop: String, catalog: Catalog): List<Train> =
            feed.trips
                .filter { trip -> trip.id.isNotEmpty() && trip.stops.any { it.stopId == stop } }
                .map { of(slug, feed, it, catalog) }
                .filter { train -> train.assigned && train.stops.any { it.id == stop } }
                .sortedBy { train -> train.stops.first { it.id == stop }.time }
    }
}

/**
 * Where a train is, seen from one platform. [Coming.sure] and [Left.sure] are false when
 * the answer was worked out from old times and not read from a live feed.
 */
sealed interface Sighting {
    /** Only on the timetable so far, due at the platform at [due]. [origin] is where it would set out from. */
    data class Scheduled(val due: Long, val origin: Train.Stop) : Sighting

    /**
     * A train at the platform its run starts from, which the feed has not yet seen move.
     * [leaves] is when it is to set out. It is not called "at" the platform: the feed has
     * not said so, and on the numbered lines mostly never does.
     */
    data class Starting(val leaves: Long, val sure: Boolean) : Sighting

    /**
     * On its way. [stopsAway] counts the platform itself, so 1 means it is the next stop
     * and 0 that the train is standing there. [where] is the stop it is at or about to
     * reach and how it stands to it, and [due] when it is due at the platform.
     */
    data class Coming(val stopsAway: Int, val due: Long, val where: Train.Where, val sure: Boolean) : Sighting {
        /** The stop it is at or about to reach. */
        val next: Train.Stop get() = where.stop

        /** True when it is at that stop, as far as is known. */
        val standing: Boolean get() = where.how.standing

        /** Since when it has been held where it is. Standing at the platform itself there is nothing to warn of. */
        val heldSince: Long? get() = where.since.takeIf { where.how == Train.How.HELD && stopsAway > 0 }

        /** True when the count is where the train was last seen, and not worked out from its times. */
        val asLastSeen: Boolean get() = where.how == Train.How.WAS_COMING || where.how == Train.How.WAS_THERE
    }

    /** Past the platform. [at] is when it left, or 0 when that was not seen. [next] is its next stop, if it has one. */
    data class Left(val at: Long, val next: Train.Stop?, val sure: Boolean) : Sighting

    /** No longer stopping at the platform. [next] are the first stops it does make, while that is news. */
    data class Skipping(val next: List<Train.Stop>) : Sighting

    /** No word of the train since [since], which is too long ago to say where it is. */
    data class Unknown(val since: Long) : Sighting

    /** Finished its run. */
    data object Ended : Sighting

    /**
     * Gone from the feed in the middle of its run, as far as was seen. With [ranOut] it
     * was not being watched when it went, and every time it had was past by then: it has
     * most likely finished.
     */
    data class Lost(val ranOut: Boolean = false) : Sighting
}
