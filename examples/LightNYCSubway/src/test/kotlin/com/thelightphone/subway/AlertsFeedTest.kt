package com.thelightphone.subway

import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.GtfsRealtime.Period
import com.thelightphone.subway.data.GtfsRealtime.Selector
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlertsFeedTest {
    private val built = Fixtures.ALERTS_BUILT
    private val bytes = Fixtures.alertBytes
    private val nextHour = GtfsRealtime.alerts(bytes, built, built + 3600)

    private fun GtfsRealtime.AlertFeed.alert(id: String) = alerts.first { it.id == id }

    @Test
    fun onlyAlertsThatApplyWithinTheWindowAreKept() {
        assertEquals(built, nextHour.timestamp)
        assertEquals(14, nextHour.alerts.size)
        // Late night R work that does not start for another three weeks.
        assertTrue(nextHour.alerts.none { it.id == "lmm:planned_work:36463" })
    }

    @Test
    fun aWindowOverAllTimeKeepsEveryAlert() {
        assertEquals(30, Fixtures.everyAlert.alerts.size)
        assertEquals(built, Fixtures.everyAlert.timestamp)
    }

    @Test
    fun plannedWorkComesWithItsStopsItsHoursAndItsKind() {
        val gap = nextHour.alert("lmm:planned_work:35536")
        assertEquals("In Brooklyn, no [L] between Myrtle-Wyckoff Avs and Atlantic Av", gap.header)
        assertEquals("Planned - Part Suspended", gap.type)
        assertEquals(listOf(Period(1794495600, 1794511800)), gap.periods)
        assertEquals(listOf("L17", "L19", "L20", "L21", "L22", "L24").map { Selector("L", it, 20) }, gap.selectors)
    }

    @Test
    fun aDelayNamesLinesAndNoStopsAndHasNoEnd() {
        val delay = nextHour.alert("lmm:alert:275985:26")
        assertEquals(listOf(Selector("B", "", 26), Selector("Q", "", 26)), delay.selectors)
        assertEquals(listOf(Period(1794500746, 0)), delay.periods)
        assertEquals("Delays", delay.type)
    }

    @Test
    fun theHtmlCopyOfAHeadlineIsNeverTheOneRead() {
        // Every alert in the fixture carries an "en-html" copy wrapped in tags.
        Fixtures.everyAlert.alerts.forEach { alert ->
            assertTrue(alert.header.isNotEmpty(), alert.id)
            assertTrue('<' !in alert.header, alert.header)
        }
    }

    @Test
    fun anAlertWithHundredsOfPeriodsKeepsOnlyThoseInTheWindow() {
        val id = "lmm:planned_work:31871"
        assertEquals(245, Fixtures.everyAlert.alert(id).periods.size)
        // None of its 245 nights falls in the hour after the feed was built.
        assertTrue(nextHour.alerts.none { it.id == id })

        val week = 7 * 86400L
        val kept = GtfsRealtime.alerts(bytes, built, built + week).alert(id).periods
        assertEquals(10, kept.size)
        assertTrue(kept.all { it.start <= built + week && it.end > built })
    }

    @Test
    fun aCutOffDownloadIsRejectedNotHalfRead() {
        assertFailsWith<GtfsRealtime.BadFeed> { GtfsRealtime.alerts(bytes.copyOf(bytes.size - 7), built, built + 3600) }
        assertFailsWith<GtfsRealtime.BadFeed> { GtfsRealtime.alerts(bytes.copyOf(bytes.size / 2), built, built + 3600) }
    }

    @Test
    fun aWebPageOrAnErrorBodyIsNotAFeed() {
        val portal = "<html><body>Sign in to Wi-Fi</body></html>".toByteArray()
        val refused = "{\"message\":\"Forbidden\"}".toByteArray()
        assertFailsWith<GtfsRealtime.BadFeed> { GtfsRealtime.alerts(portal, built, built + 3600) }
        assertFailsWith<GtfsRealtime.BadFeed> { GtfsRealtime.alerts(refused, built, built + 3600) }
        assertFailsWith<GtfsRealtime.BadFeed> { GtfsRealtime.alerts(ByteArray(0), built, built + 3600) }
    }

    @Test
    fun aQuietDayIsStillAFeed() {
        val quiet = Proto().message(1) { number(3, built) }.bytes()
        assertEquals(GtfsRealtime.AlertFeed(built, emptyList()), GtfsRealtime.alerts(quiet, built, built + 3600))
    }

    @Test
    fun anAlertWithNoPeriodsAppliesForAsLongAsItIsInTheFeed() {
        val feed = feedOf {
            text(1, "no-hours")
            message(5) {
                message(5) { text(2, "G") }
                message(10) { message(1) { text(1, "[G] trains are delayed"); text(2, "en") } }
            }
        }
        val alert = GtfsRealtime.alerts(feed, built, built + 3600).alerts.single()
        assertEquals(GtfsRealtime.Alert("no-hours", emptyList(), listOf(Selector("G", "", 0)), "[G] trains are delayed", ""), alert)
    }

    @Test
    fun theWindowTakesAPeriodThatStartsOnItsLastSecondAndLeavesOneThatEndsOnItsFirst() {
        fun feed(start: Long, end: Long) = feedOf {
            text(1, "edge")
            message(5) { message(1) { number(1, start); number(2, end) } }
        }
        assertEquals(1, GtfsRealtime.alerts(feed(built + 3600, built + 7200), built, built + 3600).alerts.size)
        assertEquals(0, GtfsRealtime.alerts(feed(built + 3601, built + 7200), built, built + 3600).alerts.size)
        assertEquals(1, GtfsRealtime.alerts(feed(built - 60, built + 1), built, built + 3600).alerts.size)
        assertEquals(0, GtfsRealtime.alerts(feed(built - 60, built), built, built + 3600).alerts.size)
    }

    @Test
    fun plainEnglishIsPreferredAndUntaggedTextIsTheFallback() {
        fun header(vararg translations: Pair<String, String?>) = feedOf {
            text(1, "words")
            message(5) {
                message(10) {
                    translations.forEach { (words, language) ->
                        message(1) { text(1, words); if (language != null) text(2, language) }
                    }
                }
            }
        }.let { GtfsRealtime.alerts(it, built, built + 3600).alerts.single().header }

        assertEquals("plain", header("<p>marked up</p>" to "en-html", "plain" to "en"))
        assertEquals("plain", header("untagged" to null, "plain" to "en", "<p>marked up</p>" to "en-html"))
        assertEquals("untagged", header("<p>marked up</p>" to "en-html", "untagged" to null))
        assertEquals("", header("<p>marked up</p>" to "en-html"))
    }

    private fun feedOf(entity: Proto.() -> Unit): ByteArray =
        Proto().message(1) { number(3, built) }.message(2, entity).bytes()

    /** Just enough of a protobuf writer to build a feed the MTA has not sent. */
    private class Proto {
        private val out = ByteArrayOutputStream()

        fun number(field: Int, value: Long) = apply {
            varint((field shl 3).toLong())
            varint(value)
        }

        fun text(field: Int, value: String) = raw(field, value.toByteArray())

        fun message(field: Int, build: Proto.() -> Unit) = raw(field, Proto().apply(build).bytes())

        fun bytes(): ByteArray = out.toByteArray()

        private fun raw(field: Int, value: ByteArray) = apply {
            varint((field shl 3 or 2).toLong())
            varint(value.size.toLong())
            out.write(value)
        }

        private fun varint(value: Long) {
            var rest = value
            while (rest >= 0x80) {
                out.write((rest and 0x7F or 0x80).toInt())
                rest = rest ushr 7
            }
            out.write(rest.toInt())
        }
    }
}
