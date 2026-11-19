package com.thelightphone.subway

import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.RideState
import com.thelightphone.subway.data.Sighting
import com.thelightphone.subway.data.Train
import com.thelightphone.subway.ui.RideWords
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The refresh loop behind a train's page and a ride: one feed, fetched while the screen shows. */
class LiveTrainTest {
    private val phone = TestPhone()
    private val morgan = Fixtures.station("Morgan Av")

    /** In the captured feed, the train standing at Jefferson St on its way to 8 Av. */
    private val trip = "061050_L..N"

    private fun LiveTrain.following(slug: String, id: String) = runBlocking { follow(slug, id) }

    private fun LiveTrain.following(train: Train) = runBlocking { follow(train) }

    private fun LiveTrain.train(): Train = assertNotNull(following.value.train)

    private fun slowLink(): CompletableDeferred<ByteArray> =
        CompletableDeferred<ByteArray>().also { gate -> phone.trains = { gate.await() } }

    @Test
    fun aHiddenScreenDownloadsNothing() {
        val live = phone.following()
        live.following("gtfs-l", trip)
        phone.elapse(120)
        assertEquals(0, phone.trainDownloads)
        assertEquals(0, phone.waiting)
        assertEquals(LiveTrain.Following(loading = true), live.following.value)
    }

    @Test
    fun showingFetchesAtOnceAndThenEveryHalfMinute() {
        val live = phone.following()
        live.following("gtfs-l", trip)
        live.resume()
        assertEquals(1, phone.trainDownloads)
        assertEquals(phone.now - 5, live.train().asOf)
        assertEquals("L15N", live.train().stops.first().id)
        assertTrue(live.train().standing)

        phone.elapse(29)
        assertEquals(1, phone.trainDownloads)
        phone.elapse(1)
        assertEquals(2, phone.trainDownloads)
        phone.elapse(60)
        assertEquals(4, phone.trainDownloads)
        assertEquals(phone.now - 5, live.train().asOf)
        // Only the L's feed was ever asked for, and no alerts.
        assertEquals(0, phone.alertDownloads)
    }

    @Test
    fun theClockMovesWhileShowingAndStopsWhenHidden() {
        val live = phone.following()
        live.following("gtfs-l", trip)
        val shown = phone.now
        live.resume()
        phone.elapse(10)
        assertEquals(shown + 10, live.now.value)
        live.pause()
        assertEquals(0, phone.waiting)
        phone.elapse(300)
        assertEquals(shown + 10, live.now.value)
        assertEquals(1, phone.trainDownloads)
        live.resume()
        assertEquals(shown + 310, live.now.value)
        assertEquals(2, phone.trainDownloads)
    }

    @Test
    fun aTrainOpenedFromAStationPageHasItsStopsAtOnceAndCostsNoDownload() {
        val page = phone.screen(morgan)
        page.resume()
        assertEquals(1, phone.trainDownloads)
        val row = assertIs<Board.View.Trains>(page.says()).groups.first().rows.single()
        val next = TrainRef.of(row.arrivals).first()
        assertEquals(trip, next.trip)
        assertEquals("L to 8 Av", next.name)
        page.pause()

        // The rider taps the row a few seconds later.
        phone.elapse(5)
        slowLink()
        val live = phone.following()
        live.following(next.feed, next.trip)
        // Before the screen has asked for anything, the board's copy of the feed has the train.
        assertEquals("L14N", live.train().stops[1].id)
        assertFalse(live.following.value.loading)
        live.resume()
        // That copy is new enough to be the answer too.
        assertEquals(1, phone.trainDownloads)
    }

    @Test
    fun aTrainAndABoardAskingTogetherShareOneDownload() {
        val gate = slowLink()
        val page = phone.screen(morgan)
        val live = phone.following()
        live.following("gtfs-l", trip)
        page.resume()
        live.resume()
        assertEquals(1, phone.trainDownloads)
        assertEquals(LiveTrain.Following(loading = true), live.following.value)
        gate.complete(phone.feedNow())
        assertIs<Board.View.Trains>(page.says())
        assertEquals(trip, live.train().id)
        assertEquals(1, phone.trainDownloads)
    }

    @Test
    fun refreshDownloadsAgainHoweverNewTheCopy() {
        val live = phone.following()
        live.following("gtfs-l", trip)
        live.resume()
        phone.elapse(5)
        live.refreshNow()
        assertEquals(2, phone.trainDownloads)
        assertTrue(live.refreshing.value)
        phone.elapse(1)
        assertFalse(live.refreshing.value)
        assertEquals(phone.now - 6, live.train().asOf)
    }

    @Test
    fun aFailedDownloadChangesNothingAndTheAgeOfTheStopsShows() {
        val live = phone.following()
        live.following("gtfs-l", trip)
        live.resume()
        val before = live.train()
        assertEquals(Train.Grade.LIVE, before.grade(live.now.value))

        phone.offline()
        phone.elapse(30)
        assertEquals(2, phone.trainDownloads)
        assertEquals(before, live.train())
        assertFalse(live.following.value.failed)
        assertEquals(Train.Grade.LIVE, live.train().grade(live.now.value))
        phone.elapse(30)
        assertEquals(Train.Grade.RECKONED, live.train().grade(live.now.value))
        phone.elapse(240)
        assertEquals(Train.Grade.UNKNOWN, live.train().grade(live.now.value))
        assertEquals(before, live.train())

        phone.online()
        phone.elapse(30)
        assertEquals(Train.Grade.LIVE, live.train().grade(live.now.value))
        assertEquals(phone.now - 5, live.train().asOf)
    }

    @Test
    fun noFeedAtAllIsToldApartFromAFeedWithoutTheTrain() {
        val gate = slowLink()
        val live = phone.following()
        live.following("gtfs-l", "099999_L..N")
        live.resume()
        assertEquals(LiveTrain.Following(loading = true), live.following.value)
        gate.complete(phone.feedNow())
        // The feed came, and no such train is in it.
        assertEquals(LiveTrain.Following(), live.following.value)

        val dark = TestPhone()
        dark.offline()
        val unreachable = dark.following()
        unreachable.following("gtfs-l", trip)
        unreachable.resume()
        assertEquals(LiveTrain.Following(failed = true), unreachable.following.value)
        // The next pass that gets through finds it.
        dark.online()
        dark.elapse(30)
        assertEquals(trip, unreachable.train().id)
    }

    @Test
    fun aRideIsPickedUpFromWhatWasKnownOfItsTrain() {
        // Saved a while ago, with the phone since closed: the train as it was then.
        val saved = assertNotNull(Train.find("gtfs-l", Fixtures.lFeed, trip, Fixtures.catalog))
        phone.elapse(200)
        val gate = slowLink()
        val live = phone.following()
        live.following(saved)
        live.resume()
        // Until the download is back the old stops stand, and are known to be old.
        assertEquals(saved, live.train())
        assertFalse(live.following.value.loading)
        assertEquals(Train.Grade.RECKONED, saved.grade(live.now.value))
        gate.complete(phone.feedNow())
        assertEquals(phone.now - 5, live.train().asOf)
        assertEquals(Train.Grade.LIVE, live.train().grade(live.now.value))
    }

    @Test
    fun aRidePickedUpWithNoSignalAndNoCopyOfTheFeedKeepsWhatItKnew() {
        // The tool was closed and opened again in a tunnel: nothing in memory, nothing to download.
        val saved = assertNotNull(Train.find("gtfs-l", Fixtures.lFeed, trip, Fixtures.catalog))
        phone.offline()
        val live = phone.following()
        live.following(saved)
        live.resume()
        assertEquals(1, phone.trainDownloads)
        assertEquals(LiveTrain.Following(saved), live.following.value)
        phone.elapse(90)
        assertEquals(saved, live.train())
    }

    @Test
    fun anEmptyAnswerFromTheMtaIsADownloadThatFailed() {
        // It happens: a reply that says all is well and carries no feed.
        val live = phone.following()
        live.following("gtfs-l", trip)
        live.resume()
        val before = live.train()
        phone.trains = { ByteArray(0) }
        phone.elapse(30)
        assertEquals(2, phone.trainDownloads)
        assertEquals(before, live.train())
    }

    @Test
    fun whenTheRiderSwitchesTrainsTheOldTrainsAnswerIsDropped() {
        val live = phone.following()
        live.following("gtfs-l", trip)
        live.resume()
        phone.elapse(25)
        val gate = slowLink()
        phone.elapse(5)
        assertEquals(2, phone.trainDownloads)

        // The one behind it, due out of Myrtle-Wyckoff Avs in a few minutes.
        val other = assertNotNull(Train.find("gtfs-l", Fixtures.lFeed, "063500_L..N", Fixtures.catalog))
        live.following(other)
        assertEquals("063500_L..N", live.train().id)
        gate.complete(phone.feedNow())
        assertEquals("063500_L..N", live.train().id)
        assertEquals(phone.now - 5, live.train().asOf)
    }

    @Test
    fun aRealTrainIsFollowedThroughTheLoopDownloadByDownload() {
        // The recorded L, a minute a download, with the phone's clock set to the recording's.
        val replay = Fixtures.lSkipStop
        val phone = TestPhone(start = replay.feeds.first().timestamp + 3)
        phone.trains = { replay.bytes[replay.feeds.indexOfLast { it.timestamp <= phone.now }] }
        val live = phone.following()
        live.following("gtfs-l", "086350_L..N")
        live.resume()
        val ride = { Ride(live.train(), from = "L22", to = "L08").state(live.now.value) }

        // Bedford Av is eleven stops off, and then the train is made to skip six.
        val counts = ArrayList<Int>()
        repeat(13) {
            counts += assertIs<RideState.Riding>(ride()).stops
            phone.elapse(60)
        }
        assertEquals(listOf(11, 11, 10, 10, 9, 8, 2, 2, 2, 2, 2, 2, 2), counts)
        assertEquals(replay.feeds[6].timestamp, live.train().changed)
        assertIs<RideState.Next>(ride())
        phone.elapse(120)
        assertIs<RideState.There>(ride())
        phone.elapse(60)
        assertIs<RideState.Past>(ride())
        // Two downloads a minute were made, one of them each minute bringing nothing new.
        assertEquals(33, phone.trainDownloads)
    }

    @Test
    fun aTrainOpenedOnAnotherScreensCopyIsAskedAboutAgainWhenThatCopyIsHalfAMinuteOld() {
        // The MTA's feed is sixteen seconds old as it arrives, as the A C E's often is.
        phone.trains = { TrainFeeds.lFeedLater(phone.now - 16 - Fixtures.lFeed.timestamp) }
        val page = phone.screen(morgan)
        page.resume()
        page.pause()
        assertEquals(1, phone.trainDownloads)
        // The rider taps a row nineteen seconds later. The page's copy is new enough to answer.
        phone.elapse(19)
        val live = phone.following()
        live.following("gtfs-l", trip)
        live.resume()
        assertEquals(1, phone.trainDownloads)
        assertEquals(35, live.now.value - live.train().asOf)
        // The next download takes three seconds to come back.
        val gate = CompletableDeferred<Unit>()
        phone.trains = {
            gate.await()
            TrainFeeds.lFeedLater(phone.now - 16 - Fixtures.lFeed.timestamp)
        }
        // It is asked for when the copy is thirty seconds old, eleven seconds on, and not thirty seconds after the tap.
        (1..10).forEach {
            phone.elapse(1)
            assertEquals(1, phone.trainDownloads)
            assertEquals(Train.Grade.LIVE, live.train().grade(live.now.value))
        }
        phone.elapse(1)
        assertEquals(2, phone.trainDownloads)
        (1..3).forEach {
            phone.elapse(1)
            assertEquals(Train.Grade.LIVE, live.train().grade(live.now.value))
        }
        gate.complete(Unit)
        assertEquals(16, phone.now - live.train().asOf)
        // From its own download on, it is every half minute as ever.
        phone.elapse(29)
        assertEquals(2, phone.trainDownloads)
        phone.elapse(1)
        assertEquals(3, phone.trainDownloads)
        assertEquals(Train.Grade.LIVE, live.train().grade(live.now.value))
    }

    @Test
    fun theWaitForTheNextPassGoesByTheAgeOfTheCopyThatAnsweredThisOne() {
        assertEquals(30_000, LiveLoop.untilStale(null))
        assertEquals(30_000, LiveLoop.untilStale(0))
        assertEquals(11_000, LiveLoop.untilStale(19))
        // A copy older than a pass would reuse was not what answered: a download did, or failed to.
        assertEquals(30_000, LiveLoop.untilStale(20))
        assertEquals(30_000, LiveLoop.untilStale(500))
        // A clock set back gives an age below zero, which is no age to go by.
        assertEquals(30_000, LiveLoop.untilStale(-40))
        // After a download that failed, the next is not hurried.
        val live = phone.following()
        live.following("gtfs-l", trip)
        live.resume()
        phone.offline()
        phone.elapse(30)
        assertEquals(2, phone.trainDownloads)
        phone.elapse(29)
        assertEquals(2, phone.trainDownloads)
        phone.elapse(1)
        assertEquals(3, phone.trainDownloads)
    }

    @Test
    fun aTrainTooOldToSpeakOfIsLoadingWhileItIsAskedAboutAndUnknownOnlyIfThatFails() {
        val live = phone.following()
        live.following("gtfs-l", trip)
        live.resume()
        live.pause()
        val before = live.train()
        // Six minutes in a pocket.
        phone.sleep(360)
        val gate = slowLink()
        live.resume()
        assertTrue(live.following.value.loading)
        assertEquals(before, live.train())
        assertEquals("Loading…", TrainPageWords.instead(live.following.value, live.train().seenFrom("L14", live.now.value)))
        gate.complete(phone.feedNow())
        assertFalse(live.following.value.loading)
        assertEquals(Train.Grade.LIVE, live.train().grade(live.now.value))

        // The same with no signal: once the download has failed, the connection is what to check.
        live.pause()
        phone.sleep(360)
        phone.offline()
        live.resume()
        assertFalse(live.following.value.loading)
        val sighting = live.train().seenFrom("L14", live.now.value)
        assertIs<Sighting.Unknown>(sighting)
        assertEquals(RideWords.instead(sighting), TrainPageWords.instead(live.following.value, sighting))
        // A train only a few minutes old is shown as it stands while it is asked about.
        phone.online()
        phone.elapse(30)
        live.pause()
        phone.sleep(200)
        slowLink()
        live.resume()
        assertFalse(live.following.value.loading)
    }
}
