package com.thelightphone.subway.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A rider following one train to one stop.
 *
 * [from] is the platform they asked at and [to] the platform they get off at, both as the
 * station file names them ("L14", "L03"). [train] is the train they said they are taking,
 * as last heard of. The tool never works out which train that is: the rider says, and can
 * say again.
 *
 * [then] is set when the rider changes trains at the end of this ride: see [Change].
 *
 * This is all a ride is, so it can be written down when the tool is closed and picked up
 * when it is opened again. See [SavedStore.ride].
 */
@Serializable
data class Ride(val train: Train, val from: String, val to: String, val then: Onward? = null) {

    /**
     * What can be said to the rider at [now]. It is worked out from the train's last known
     * stops and how old they are, and from nothing else, so it promises nothing the feed
     * did not say:
     *
     *  - what the train has done stays said: once it has left the rider's stop it has left.
     *  - a stop the feed has taken away is reported, not counted down to.
     *  - a trip with no train is never counted as moving, and has left nowhere.
     *  - from stops more than a minute old the count is reckoned, runs early, and says so.
     *    When word comes back it can be put right by a stop, rarely two. A train that was
     *    behind its times when last heard of is not reckoned at all: the count stays where
     *    it was.
     *  - from stops more than five minutes old there is no count at all.
     */
    fun state(now: Long): RideState {
        val gone = train.left(to).takeIf { train.assigned }
        val due = train.stop(to)?.time ?: 0
        val grade = train.grade(now)
        val live = grade == Train.Grade.LIVE
        // Where it stops next is only said while that is news.
        fun next(count: Int) = if (live) train.stops.take(count) else emptyList()
        return when {
            gone != null -> RideState.Past(gone.time, next(1).firstOrNull())
            train.lost -> RideState.Lost(train.asOf, due)
            train.skips(to) != null -> RideState.Dropped(to, next(2))
            train.skips(from) != null -> RideState.Dropped(from, next(2))
            // In none of its lists: the train was past the stop when it was first heard of.
            // A lost train was caught above, so this is not one the MTA has stopped reporting.
            train.place(to) < 0 -> if (train.assigned) RideState.Past(0, next(1).firstOrNull()) else RideState.Lost(train.asOf, due)
            grade == Train.Grade.UNKNOWN -> RideState.Unknown(train.asOf, due)
            // A train that left the feed at the end of its run is reckoned for good, but
            // only into its last stops. Five minutes on, a stop it has yet to be reckoned
            // into is as unknown as on any other train.
            train.ended && now - train.asOf > Train.RECKON_FOR && train.ahead(now).any { it.platform == to } -> RideState.Unknown(train.asOf, due)
            else -> counted(now, live)
        }
    }

    private fun counted(now: Long, live: Boolean): RideState {
        val ahead = train.ahead(now)
        val mine = ahead.indexOfFirst { it.platform == to }
        if (mine < 0) return reckonedIn(ahead)
        val boarding = ahead.indexOfFirst { it.platform == from }
        val arrives = ahead[mine].time
        if (boarding >= 0) {
            return RideState.Waiting(train.seenFrom(from, now), stops = mine - boarding, arrives = arrives)
        }
        val where = train.whereAt(now)
        if (!train.assigned || where == null) {
            return RideState.Waiting(Sighting.Scheduled(0, ahead.first()), stops = mine + 1, arrives = arrives)
        }
        val stops = mine + if (where.how.standing) 0 else 1
        return when (stops) {
            0 -> RideState.There(arrives, sure = live)
            1 -> RideState.Next(arrives, where, sure = live)
            else -> RideState.Riding(stops, arrives, where, sure = live)
        }
    }

    /**
     * The rider's stop is no longer among the stops reckoned [ahead]: by its old times the
     * train is there, or has been. A train that has not been seen to leave is not said to
     * have left, with one exception. A train the feed let go of at the end of its run is
     * not standing at a stop short of its last one, so once it has been gone longer than a
     * missed download, or is reckoned into the stop after, it has left.
     */
    private fun reckonedIn(ahead: List<Train.Stop>): RideState {
        val at = train.place(to)
        val stop = train.stops[at]
        val after = train.stops.getOrNull(at + 1)
        val gone = train.ended && after != null && (train.checked - train.asOf > Train.MISSED_FOR || ahead.none { it.id == after.id })
        return if (gone) RideState.Past(stop.time, next = null, sure = false) else RideState.There(stop.time, sure = false)
    }

    /**
     * True once the ride is not worth coming back to: ten minutes after the train was due
     * at the rider's stop, however it ended. While the last word of the train still had
     * the stop ahead of it, the ten minutes run from that word if it is the later: a train
     * held short of the stop is not over because the time first given for it has gone by.
     * Where the feed gave the stop no time, the latest time it gave for any stop is gone by.
     * A stop the train left unseen has no time either, and goes by the first time known
     * for a stop after it: the train had surely gone by then.
     */
    fun over(now: Long): Boolean {
        val due = train.stop(to)?.time ?: 0
        val listed = train.behind + train.stops
        val last = when {
            due > 0 -> due
            // A stop in none of its lists was left before the train was first heard of: every stop it has listed comes after.
            train.place(to) < 0 && train.skips(to) == null -> listed.drop(listed.indexOfFirst { it.platform == to } + 1).firstOrNull { it.time > 0 }?.time
            else -> null
        } ?: listed.maxOfOrNull { it.time } ?: 0
        val from = when {
            last <= 0 -> train.asOf
            train.place(to) >= 0 && !train.ended -> maxOf(last, train.asOf)
            else -> last
        }
        return now - from > OVER_AFTER
    }

    /**
     * True when a ride that looks over is too old to be worth asking about again: nothing
     * has been heard of its train for an hour.
     */
    fun forgotten(now: Long): Boolean = over(now) && now - train.checked > FORGET_AFTER

    /** The same ride on [other], for a rider who says that is the train they are on. */
    fun on(other: Train): Ride = copy(train = other)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val OVER_AFTER = 10 * 60L

        const val FORGET_AFTER = 60 * 60L

        private val json = Json { ignoreUnknownKeys = true }

        /** Null for anything that is not a ride this version can read. */
        fun decode(text: String): Ride? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * What the ride screen says. [sure] is false where the answer was reckoned from old times:
 * it is then drawn grey, with the time of the last word from the train under it.
 */
sealed interface RideState {
    /**
     * The train has not reached the rider's platform. [train] says where it is from there,
     * and is always [Sighting.Scheduled], [Sighting.Starting] or [Sighting.Coming]. [stops]
     * is how many stops the ride will be and [arrives] when the feed has the train at the
     * rider's stop.
     */
    data class Waiting(val train: Sighting, val stops: Int, val arrives: Long) : RideState

    /** Two stops or more to go. [where] is the stop the train is at or about to reach, and how it stands to it. */
    data class Riding(val stops: Int, val arrives: Long, val where: Train.Where, val sure: Boolean) : RideState {
        val next: Train.Stop get() = where.stop

        val standing: Boolean get() = where.how.standing
    }

    /** The next stop is the rider's. [where] is that stop, or the one before it while the train is in that station. */
    data class Next(val arrives: Long, val where: Train.Where, val sure: Boolean) : RideState {
        val standing: Boolean get() = where.how.standing
    }

    /** The train is at the rider's stop, or by its old times ought to be. */
    data class There(val due: Long, val sure: Boolean) : RideState

    /**
     * The train has left the rider's stop, at about [at]. [next] is where it stops next,
     * while that is known and current. With [sure] false it was not seen to leave: the
     * feed let go of it at the end of its run, and [at] is when it was due at the stop.
     */
    data class Past(val at: Long, val next: Train.Stop?, val sure: Boolean = true) : RideState

    /** The train no longer stops at [platform], which is the rider's stop or the one they are waiting at. [next] are its next stops, while that is news. */
    data class Dropped(val platform: String, val next: List<Train.Stop>) : RideState

    /** The train went out of the feed in the middle of its run. It was last in it at [since]. */
    data class Lost(val since: Long, val due: Long) : RideState

    /** No word of the train since [since], too long ago to count from. [due] is when it was due at the rider's stop. */
    data class Unknown(val since: Long, val due: Long) : RideState
}

/**
 * Where a rider goes on to after a ride that ends in a change: from the station [at], from
 * its platform [platform], to the station [to], on any of [lines]. All are as the station
 * file names them. The tool does not pick the second train ahead of time: the rider is
 * shown the trains there when they get there.
 */
@Serializable
data class Onward(val at: String, val to: String, val platform: String, val lines: List<String>)
