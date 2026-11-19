package com.thelightphone.subway

import com.thelightphone.subway.data.GtfsRealtime
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reader takes whatever a phone on a bad connection hands it: half a download, a page
 * from a Wi-Fi sign-in, bytes that are simply wrong. Whatever it is given, it either reads
 * a feed or says [GtfsRealtime.BadFeed]. Anything else it threw would get past the code
 * that catches a failed download.
 *
 * The random cases come from one fixed seed, so a failure here is the same failure on
 * every run.
 */
class GtfsReaderPropertiesTest {
    private val day = Fixtures.weekdayAfternoon

    /** Three real train feeds: the L alone, and from the snapshot the G and the railway. */
    private val trainFeeds = mapOf(
        "the L" to Fixtures.lFeedBytes,
        "the G" to day.bytes.getValue("gtfs-g"),
        "the railway" to day.bytes.getValue("gtfs-si"),
    )

    /** Null for a refusal. Any other exception is the test failing. */
    private fun read(bytes: ByteArray): GtfsRealtime.Feed? =
        try {
            GtfsRealtime.parse(bytes)
        } catch (e: GtfsRealtime.BadFeed) {
            null
        }

    private fun readAlerts(bytes: ByteArray): GtfsRealtime.AlertFeed? =
        try {
            GtfsRealtime.alerts(bytes, 0, Long.MAX_VALUE)
        } catch (e: GtfsRealtime.BadFeed) {
            null
        }

    @Test
    fun aDownloadCutOffAnywhereIsRefusedOrIsAShorterFeedAndNeverHalfATrain() {
        val readable = trainFeeds.mapValues { (name, bytes) ->
            val whole = GtfsRealtime.parse(bytes)
            (0 until bytes.size).count { length ->
                val cut = read(bytes.copyOf(length)) ?: return@count false
                // What is read is the feed up to some train, every one of them complete.
                assertEquals(whole.timestamp, cut.timestamp, "$name cut at $length")
                assertEquals(whole.trips.take(cut.trips.size), cut.trips, "$name cut at $length")
                true
            }
        }
        // Only a cut that falls exactly between two entities reads at all. The L feed has 37
        // entities after its header, so 37 places to cut it cleanly out of 11,479.
        assertEquals(37, readable["the L"])
        readable.forEach { (name, count) ->
            assertTrue(count in 1..trainFeeds.getValue(name).size / 100, "$name: $count cuts read as a feed")
        }
    }

    @Test
    fun aCutOffAlertsDownloadIsRefusedOrIsAShorterFeed() {
        val bytes = Fixtures.alertBytes
        val whole = GtfsRealtime.alerts(bytes, 0, Long.MAX_VALUE)
        val random = Random(SEED)
        // Every cut in the first four thousand bytes, then three thousand anywhere.
        val lengths = (0 until 4000) + List(3000) { random.nextInt(bytes.size) }
        var read = 0
        lengths.forEach { length ->
            val cut = readAlerts(bytes.copyOf(length)) ?: return@forEach
            read++
            assertEquals(whole.alerts.take(cut.alerts.size), cut.alerts, "cut at $length")
        }
        assertTrue(read in 1..lengths.size / 100, "$read of ${lengths.size} cuts read as a feed")
    }

    @Test
    fun wrongBytesAreReadOrRefusedAndNothingElse() {
        val random = Random(SEED)
        trainFeeds.forEach { (name, bytes) ->
            var read = 0
            var refused = 0
            repeat(4000) {
                if (read(damaged(bytes, random)) == null) refused++ else read++
            }
            // Both happen: damage inside a train's stops breaks the feed, damage inside a
            // field the tool skips does not.
            assertTrue(read > 100 && refused > 100, "$name: $read read, $refused refused")
        }
        var read = 0
        repeat(2000) {
            if (readAlerts(damaged(Fixtures.alertBytes, random)) != null) read++
        }
        assertTrue(read in 100..1900, "alerts: $read of 2000 read")
    }

    @Test
    fun noiseIsRefused() {
        val random = Random(SEED)
        repeat(3000) {
            val noise = ByteArray(1 + random.nextInt(600)).also(random::nextBytes)
            // Bytes at random could spell a feed, only not in three thousand tries.
            assertNull(read(noise))
            assertNull(readAlerts(noise))
        }
    }

    /** One to four bytes changed, or a run of bytes dropped, or a run of bytes repeated. */
    private fun damaged(bytes: ByteArray, random: Random): ByteArray {
        val at = random.nextInt(bytes.size)
        val run = 1 + random.nextInt(64)
        return when (random.nextInt(4)) {
            0 -> bytes.copyOfRange(0, at) + bytes.copyOfRange(minOf(bytes.size, at + run), bytes.size)
            1 -> bytes.copyOfRange(0, at) + bytes.copyOfRange(at, minOf(bytes.size, at + run)) + bytes.copyOfRange(at, bytes.size)
            else -> bytes.copyOf().also { copy ->
                repeat(1 + random.nextInt(4)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
            }
        }
    }

    @Test
    fun aFeedWrittenBackOutReadsTheSame() {
        (day.feeds.values + Fixtures.lFeed).forEach { feed ->
            assertEquals(feed, GtfsRealtime.parse(TrainFeeds.bytes(feed)))
        }
    }

    @Test
    fun fieldsInAnyOrderAmongOnesTheToolDoesNotReadGiveTheSameFeed() {
        val random = Random(SEED)
        (day.feeds.values + Fixtures.lFeed).forEach { feed ->
            val inOrder = TrainFeeds.bytes(feed)
            repeat(3) {
                val mixed = TrainFeeds.bytes(feed, mixed = random)
                assertTrue(!mixed.contentEquals(inOrder))
                assertEquals(feed, GtfsRealtime.parse(mixed))
            }
        }
    }

    private companion object {
        const val SEED = 20261112L
    }
}
