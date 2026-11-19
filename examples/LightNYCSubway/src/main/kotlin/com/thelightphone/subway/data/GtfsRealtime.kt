package com.thelightphone.subway.data

/**
 * A small GTFS-realtime (protobuf) reader with no dependencies.
 *
 * The Light SDK keeps third-party libraries to a short list, so instead of protobuf-java
 * this reads only the fields the tool uses and skips the rest by wire type:
 *
 *   FeedMessage      header            = 1
 *                    entity            = 2  (repeated)
 *   FeedHeader       timestamp         = 3  (unix seconds the MTA built the feed)
 *   FeedEntity       id                = 1
 *                    trip_update       = 3
 *                    vehicle           = 4
 *                    alert             = 5
 *   TripUpdate       trip              = 1
 *                    stop_time_update  = 2  (repeated, in the order the train runs)
 *   TripDescriptor   trip_id           = 1  ("085350_1..N15R")
 *                    route_id          = 5
 *                    MTA extension     = 1001, holding is_assigned = 2 (a train has the trip)
 *   VehiclePosition  trip              = 1
 *                    current_status    = 4  (0 coming in, 1 stopped, 2 on its way; often left out)
 *                    timestamp         = 5  (unix seconds the train was last seen)
 *                    stop_id           = 7  (the stop the status is about)
 *   StopTimeUpdate   arrival           = 2
 *                    departure         = 3
 *                    stop_id           = 4  ("L14N": parent stop + direction)
 *   StopTimeEvent    time              = 2  (unix seconds)
 *   Alert            active_period     = 1  (repeated)
 *                    informed_entity   = 5  (repeated)
 *                    header_text       = 10
 *                    MTA extension     = 1001, holding alert_type = 3 ("Delays")
 *   TimeRange        start             = 1  (unix seconds)
 *                    end               = 2  (missing or 0: no end set)
 *   EntitySelector   route_id          = 2
 *                    stop_id           = 5  (parent stop, no direction letter)
 *                    MTA extension     = 1001, holding sort_order = 1 ("MTASBWY:L:20")
 *   TranslatedString translation       = 1  (repeated)
 *   Translation      text              = 1
 *                    language          = 2  ("en" or "en-html")
 *
 * A trip keeps its stops together and in order. That is what lets the tool say where each
 * train ends up. A train's trip and its position are separate entities in the same feed,
 * tied together by the trip id.
 *
 * Trains and alerts come in separate feeds, so there is a reader for each: [parse] and [alerts].
 */
object GtfsRealtime {

    /**
     * One stop still to come, and when the train is due there. [departsOnly] marks a stop the
     * feed gives a time to leave and no time to arrive: on the numbered lines and the L that
     * is the stop a train's run starts at, for as long as the train has not moved.
     */
    data class StopTime(val stopId: String, val time: Long, val departsOnly: Boolean = false)

    /**
     * One train's stops still to come. [assigned] is the MTA's word that a train has been
     * given the trip and is on its way or about to be. Without it the trip is only the
     * timetable's: a time that can slip, or a train that never runs. A feed with none of the
     * MTA's extensions cannot say either way, and its trains are taken as running.
     */
    data class Trip(val route: String, val stops: List<StopTime>, val assigned: Boolean = true, val id: String = "")

    enum class Status { INCOMING, STOPPED, MOVING }

    /**
     * Where the feed last had a train: at or on its way to [stopId], as of [timestamp].
     * [status] is null when the feed does not say. What a position is worth differs from
     * feed to feed, and is sorted out where it is used: see [Train].
     */
    data class Position(val stopId: String, val status: Status?, val timestamp: Long)

    /** [positions] are by trip id. Not every trip has one. */
    data class Feed(val timestamp: Long, val trips: List<Trip>, val positions: Map<String, Position> = emptyMap())

    /** When an alert applies, in unix seconds. An [end] of 0 means no end has been set. */
    data class Period(val start: Long, val end: Long)

    /**
     * One place an alert is about. [route] is spelled as the feed spells it ("GS", "6X", "SI").
     * An empty [stop] means the whole line. [sort] is the MTA's own ranking of how much the
     * alert matters, higher for worse, and 0 when the feed gives none.
     */
    data class Selector(val route: String, val stop: String, val sort: Int)

    /** [header] is the MTA's plain-text headline untouched, "[L]" tokens and line breaks included. */
    data class Alert(
        val id: String,
        val periods: List<Period>,
        val selectors: List<Selector>,
        val header: String,
        val type: String,
    )

    data class AlertFeed(val timestamp: Long, val alerts: List<Alert>)

    /** Thrown for bytes that are not a readable feed (a captive portal page, a cut-off download). */
    class BadFeed(message: String) : Exception(message)

    private class Reader(val b: ByteArray, var p: Int, val end: Int) {
        fun hasMore() = p < end

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (p >= end || shift > 63) throw BadFeed("varint runs past the end")
                val c = b[p++].toInt() and 0xFF
                result = result or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return result
                shift += 7
            }
        }

        /** Reads a length prefix and returns where that value ends. */
        fun lenEnd(): Int {
            val n = varint()
            if (n < 0 || n > end - p) throw BadFeed("length runs past the end")
            return p + n.toInt()
        }

        fun string(): String {
            val e = lenEnd()
            val s = String(b, p, e - p, Charsets.UTF_8)
            p = e
            return s
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> p += 8
                5 -> p += 4
                2 -> p = lenEnd()
                else -> throw BadFeed("wire type $wire")
            }
            if (p > end) throw BadFeed("field runs past the end")
        }

        /** Calls [each] with (field number, wire type) until this message is used up. */
        inline fun fields(each: (Int, Int) -> Unit) {
            while (hasMore()) {
                val tag = varint().toInt()
                each(tag ushr 3, tag and 7)
            }
        }

        fun sub(): Reader {
            val e = lenEnd()
            return Reader(b, p, e).also { p = e }
        }

        /** A second reader over what this one has left, for going through the same bytes twice. */
        fun again() = Reader(b, p, end)

        /** Everything this reader has left, as text. */
        fun text() = String(b, p, end - p, Charsets.UTF_8)
    }

    fun parse(data: ByteArray): Feed {
        var timestamp = 0L
        val trips = ArrayList<Trip>()
        val positions = HashMap<String, Position>()
        try {
            val r = Reader(data, 0, data.size)
            r.fields { field, wire ->
                when {
                    field == 1 && wire == 2 -> timestamp = header(r.sub())
                    field == 2 && wire == 2 -> entity(r.sub(), trips, positions)
                    else -> r.skip(wire)
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            throw BadFeed("truncated")
        }
        if (timestamp == 0L && trips.isEmpty()) throw BadFeed("not a feed")
        return Feed(timestamp, trips, positions)
    }

    private fun header(r: Reader): Long {
        var timestamp = 0L
        r.fields { field, wire ->
            if (field == 3 && wire == 0) timestamp = r.varint() else r.skip(wire)
        }
        return timestamp
    }

    private fun entity(r: Reader, trips: MutableList<Trip>, positions: MutableMap<String, Position>) {
        r.fields { field, wire ->
            when {
                field == 3 && wire == 2 -> tripUpdate(r.sub())?.let { trips.add(it) }
                field == 4 && wire == 2 -> vehicle(r.sub(), positions)
                else -> r.skip(wire)
            }
        }
    }

    private fun tripUpdate(r: Reader): Trip? {
        var id = ""
        var route = ""
        var assigned = true
        val stops = ArrayList<StopTime>()
        r.fields { field, wire ->
            when {
                field == 1 && wire == 2 -> {
                    val trip = r.sub()
                    trip.fields { f, w ->
                        when {
                            f == 1 && w == 2 -> id = trip.string()
                            f == 5 && w == 2 -> route = trip.string()
                            f == 1001 && w == 2 -> assigned = isAssigned(trip.sub())
                            else -> trip.skip(w)
                        }
                    }
                }
                field == 2 && wire == 2 -> stopTime(r.sub())?.let { stops.add(it) }
                else -> r.skip(wire)
            }
        }
        return if (route.isEmpty() || stops.isEmpty()) null else Trip(route, stops, assigned, id)
    }

    /** A position that names no trip or no stop says nothing the tool can use, and is passed over. */
    private fun vehicle(r: Reader, positions: MutableMap<String, Position>) {
        var id = ""
        var stopId = ""
        var status: Status? = null
        var timestamp = 0L
        r.fields { field, wire ->
            when {
                field == 1 && wire == 2 -> {
                    val trip = r.sub()
                    trip.fields { f, w -> if (f == 1 && w == 2) id = trip.string() else trip.skip(w) }
                }
                field == 4 && wire == 0 -> status = Status.entries.getOrNull(r.varint().toInt())
                field == 5 && wire == 0 -> timestamp = r.varint()
                field == 7 && wire == 2 -> stopId = r.string()
                else -> r.skip(wire)
            }
        }
        if (id.isNotEmpty() && stopId.isNotEmpty()) positions[id] = Position(stopId, status, timestamp)
    }

    /** For a trip no train has yet, the L's feed sends a zero and the others leave the field out. */
    private fun isAssigned(r: Reader): Boolean {
        var assigned = false
        r.fields { field, wire ->
            if (field == 2 && wire == 0) assigned = r.varint() != 0L else r.skip(wire)
        }
        return assigned
    }

    private fun stopTime(r: Reader): StopTime? {
        var stopId = ""
        var arrival = 0L
        var departure = 0L
        r.fields { field, wire ->
            when {
                field == 4 && wire == 2 -> stopId = r.string()
                field == 2 && wire == 2 -> arrival = eventTime(r.sub())
                field == 3 && wire == 2 -> departure = eventTime(r.sub())
                else -> r.skip(wire)
            }
        }
        val time = if (arrival > 0L) arrival else departure
        return if (stopId.isEmpty() || time <= 0L) null else StopTime(stopId, time, departsOnly = arrival <= 0L)
    }

    private fun eventTime(r: Reader): Long {
        var time = 0L
        r.fields { field, wire ->
            if (field == 2 && wire == 0) time = r.varint() else r.skip(wire)
        }
        return time
    }

    /**
     * Reads the service alerts feed, keeping only the alerts that apply at some point between
     * [from] and [until] (unix seconds).
     *
     * The feed is about half a megabyte and nearly all of it is planned work for the weeks
     * ahead, so each alert is gone through twice. The first pass reads nothing but its
     * periods, which are plain numbers. Only an alert with a period in reach gets the second
     * pass, where its text is decoded. An alert with no periods at all is kept: GTFS says it
     * applies for as long as it is in the feed.
     */
    fun alerts(data: ByteArray, from: Long, until: Long): AlertFeed {
        var timestamp = 0L
        val alerts = ArrayList<Alert>()
        try {
            val r = Reader(data, 0, data.size)
            r.fields { field, wire ->
                when {
                    field == 1 && wire == 2 -> timestamp = header(r.sub())
                    field == 2 && wire == 2 -> alertEntity(r.sub(), from, until)?.let { alerts.add(it) }
                    else -> r.skip(wire)
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            throw BadFeed("truncated")
        }
        // A day with no alerts at all is still a feed, so the header is the only proof of one.
        if (timestamp == 0L) throw BadFeed("not a feed")
        return AlertFeed(timestamp, alerts)
    }

    private fun alertEntity(r: Reader, from: Long, until: Long): Alert? {
        var id: Reader? = null
        var body: Reader? = null
        r.fields { field, wire ->
            when {
                field == 1 && wire == 2 -> id = r.sub()
                field == 5 && wire == 2 -> body = r.sub()
                else -> r.skip(wire)
            }
        }
        val alert = body ?: return null
        val periods = periodsInReach(alert.again(), from, until) ?: return null

        val selectors = ArrayList<Selector>()
        var header = ""
        var type = ""
        alert.fields { field, wire ->
            when {
                field == 5 && wire == 2 -> selectors.add(selector(alert.sub()))
                field == 10 && wire == 2 -> header = english(alert.sub())
                field == 1001 && wire == 2 -> type = alertType(alert.sub())
                else -> alert.skip(wire)
            }
        }
        return Alert(id?.text() ?: "", periods, selectors, header, type)
    }

    /** Null for an alert that has periods, none of which reaches into [from]..[until]. */
    private fun periodsInReach(r: Reader, from: Long, until: Long): List<Period>? {
        var hasPeriods = false
        var kept: ArrayList<Period>? = null
        r.fields { field, wire ->
            if (field == 1 && wire == 2) {
                hasPeriods = true
                val range = r.sub()
                var start = 0L
                var end = 0L
                range.fields { f, w ->
                    when {
                        f == 1 && w == 0 -> start = range.varint()
                        f == 2 && w == 0 -> end = range.varint()
                        else -> range.skip(w)
                    }
                }
                if (start <= until && (end == 0L || end > from)) {
                    val list = kept ?: ArrayList<Period>().also { kept = it }
                    list.add(Period(start, end))
                }
            } else {
                r.skip(wire)
            }
        }
        return if (hasPeriods) kept else emptyList()
    }

    private fun selector(r: Reader): Selector {
        var route = ""
        var stop = ""
        var sort = 0
        r.fields { field, wire ->
            when {
                field == 2 && wire == 2 -> route = r.string()
                field == 5 && wire == 2 -> stop = r.string()
                field == 1001 && wire == 2 -> sort = sortOrder(r.sub())
                else -> r.skip(wire)
            }
        }
        return Selector(route, stop, sort)
    }

    /** The number at the end of "MTASBWY:L:20". */
    private fun sortOrder(r: Reader): Int {
        var sort = 0
        r.fields { field, wire ->
            if (field == 1 && wire == 2) sort = r.string().substringAfterLast(':').toIntOrNull() ?: 0 else r.skip(wire)
        }
        return sort
    }

    private fun alertType(r: Reader): String {
        var type = ""
        r.fields { field, wire ->
            if (field == 3 && wire == 2) type = r.string() else r.skip(wire)
        }
        return type
    }

    /**
     * The plain English copy of a text. The MTA sends every text twice, as "en" and as
     * "en-html", and the HTML copy is the longer one. Where each text sits is noted first and
     * only the one wanted is decoded, so the HTML never becomes a String. A feed that tags no
     * language at all gets its first text used.
     */
    private fun english(r: Reader): String {
        var english: Reader? = null
        var untagged: Reader? = null
        r.fields { field, wire ->
            if (field == 1 && wire == 2) {
                val translation = r.sub()
                var text: Reader? = null
                var language = ""
                translation.fields { f, w ->
                    when {
                        f == 1 && w == 2 -> text = translation.sub()
                        f == 2 && w == 2 -> language = translation.string()
                        else -> translation.skip(w)
                    }
                }
                when (language) {
                    "en" -> if (english == null) english = text
                    "" -> if (untagged == null) untagged = text
                }
            } else {
                r.skip(wire)
            }
        }
        return (english ?: untagged)?.text() ?: ""
    }
}
