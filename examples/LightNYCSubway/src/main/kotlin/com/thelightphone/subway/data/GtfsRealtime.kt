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
 *   FeedEntity       trip_update       = 3
 *   TripUpdate       trip              = 1
 *                    stop_time_update  = 2  (repeated, in the order the train runs)
 *   TripDescriptor   route_id          = 5
 *   StopTimeUpdate   arrival           = 2
 *                    departure         = 3
 *                    stop_id           = 4  ("L14N": parent stop + direction)
 *   StopTimeEvent    time              = 2  (unix seconds)
 *
 * Unlike the first version, a trip keeps its stops together and in order. That is what lets
 * the tool say where each train ends up.
 */
object GtfsRealtime {

    data class StopTime(val stopId: String, val time: Long)

    data class Trip(val route: String, val stops: List<StopTime>)

    data class Feed(val timestamp: Long, val trips: List<Trip>)

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
    }

    fun parse(data: ByteArray): Feed {
        var timestamp = 0L
        val trips = ArrayList<Trip>()
        try {
            val r = Reader(data, 0, data.size)
            r.fields { field, wire ->
                when {
                    field == 1 && wire == 2 -> timestamp = header(r.sub())
                    field == 2 && wire == 2 -> entity(r.sub())?.let { trips.add(it) }
                    else -> r.skip(wire)
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            throw BadFeed("truncated")
        }
        if (timestamp == 0L && trips.isEmpty()) throw BadFeed("not a feed")
        return Feed(timestamp, trips)
    }

    private fun header(r: Reader): Long {
        var timestamp = 0L
        r.fields { field, wire ->
            if (field == 3 && wire == 0) timestamp = r.varint() else r.skip(wire)
        }
        return timestamp
    }

    private fun entity(r: Reader): Trip? {
        var trip: Trip? = null
        r.fields { field, wire ->
            if (field == 3 && wire == 2) trip = tripUpdate(r.sub()) else r.skip(wire)
        }
        return trip
    }

    private fun tripUpdate(r: Reader): Trip? {
        var route = ""
        val stops = ArrayList<StopTime>()
        r.fields { field, wire ->
            when {
                field == 1 && wire == 2 -> route = routeId(r.sub())
                field == 2 && wire == 2 -> stopTime(r.sub())?.let { stops.add(it) }
                else -> r.skip(wire)
            }
        }
        return if (route.isEmpty() || stops.isEmpty()) null else Trip(route, stops)
    }

    private fun routeId(r: Reader): String {
        var route = ""
        r.fields { field, wire ->
            if (field == 5 && wire == 2) route = r.string() else r.skip(wire)
        }
        return route
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
        return if (stopId.isEmpty() || time <= 0L) null else StopTime(stopId, time)
    }

    private fun eventTime(r: Reader): Long {
        var time = 0L
        r.fields { field, wire ->
            if (field == 2 && wire == 0) time = r.varint() else r.skip(wire)
        }
        return time
    }
}
