package com.thelightphone.subway

import com.thelightphone.subway.data.ArrivalsRepository
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrivalsRepositoryTest {
    private val morgan = Fixtures.station("Morgan Av")
    private var now = Fixtures.lFeed.timestamp
    private var downloads = 0
    private var online = true

    private val repo = ArrivalsRepository(
        download = {
            downloads++
            if (online) Fixtures.lFeedBytes else throw IOException("no signal")
        },
        clock = { now },
    )

    private fun board(force: Boolean = false) = runBlocking { repo.board(morgan, Fixtures.catalog, force) }

    @Test
    fun aFeedIsDownloadedOnceAndSharedWhileFresh() {
        assertTrue(board().arrivals.isNotEmpty())
        now += 10
        board()
        assertEquals(1, downloads)
        now += 15
        board()
        assertEquals(2, downloads)
    }

    @Test
    fun theRefreshButtonAlwaysDownloads() {
        board()
        now += 5
        board(force = true)
        assertEquals(2, downloads)
    }

    @Test
    fun losingSignalKeepsTheLastTimesAndSaysTheyAreOld() {
        assertFalse(board().stale)
        online = false
        now += 60
        val offline = board()
        assertTrue(offline.stale)
        assertFalse(offline.failed)
        assertTrue(offline.arrivals.isNotEmpty())
    }

    @Test
    fun withNothingEverDownloadedTheBoardFails() {
        online = false
        val b = board()
        assertTrue(b.failed)
        assertTrue(b.arrivals.isEmpty())
    }

    @Test
    fun timesTooOldToTrustAreDropped() {
        board()
        online = false
        now += ArrivalsRepository.KEEP_FOR + 1
        assertTrue(board().failed)
    }
}
