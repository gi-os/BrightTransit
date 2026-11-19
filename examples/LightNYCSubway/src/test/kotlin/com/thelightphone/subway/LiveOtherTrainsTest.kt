package com.thelightphone.subway

import com.thelightphone.subway.ByHand.GRAHAM
import com.thelightphone.subway.ByHand.ID
import com.thelightphone.subway.ByHand.JEFFERSON
import com.thelightphone.subway.ByHand.MORGAN
import com.thelightphone.subway.ByHand.UNION_SQ
import com.thelightphone.subway.ByHand.feed
import com.thelightphone.subway.ByHand.trip
import com.thelightphone.subway.data.GtfsRealtime
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.Train
import com.thelightphone.subway.ui.RideWords
import com.thelightphone.subway.ui.clockLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The refresh loop behind the list of other trains: the feed of the train being followed,
 * fetched while the list shows, and what the list says while that is on its way.
 */
class LiveOtherTrainsTest {
    private val phone = TestPhone()

    /** In the captured feed, the train standing at Jefferson St on its way to 8 Av. */
    private val followed = "061050_L..N"

    /** A ride on it from Morgan Av to 14 St-Union Sq, as it stands now. */
    private fun ride(): Ride {
        val now = TrainFeeds.later(Fixtures.lFeed, phone.now - 5 - Fixtures.lFeed.timestamp)
        return Ride(assertNotNull(Train.find("gtfs-l", now, followed, Fixtures.catalog)), from = "L14", to = "L03")
    }

    private fun LiveOtherTrains.says() = choice.value.view(now.value)

    private fun slowLink(): CompletableDeferred<ByteArray> =
        CompletableDeferred<ByteArray>().also { gate -> phone.trains = { gate.await() } }

    @Test
    fun theListIsFetchedWhileItShowsAndNotWhenItIsCovered() {
        val list = phone.otherTrains(ride())
        assertEquals(OtherTrains.Choice.View.Loading, list.says())
        assertEquals(0, phone.trainDownloads)
        list.resume()
        assertEquals(1, phone.trainDownloads)
        val shown = assertIs<OtherTrains.Choice.View.Trains>(list.says())
        assertTrue(shown.live)
        // The trains on their way to 14 St-Union Sq, the followed one among them.
        assertTrue(shown.trains.size > 1 && shown.trains.any { it.id == followed })
        assertTrue(shown.trains.all { it.place("L03") >= 0 })
        assertEquals(phone.now - 5, list.choice.value.asOf)
        phone.elapse(30)
        assertEquals(2, phone.trainDownloads)
        assertEquals(phone.now - 5, list.choice.value.asOf)
        list.pause()
        phone.elapse(120)
        assertEquals(2, phone.trainDownloads)
        assertEquals(0, phone.waiting)
        // The button fetches again, however new the copy.
        list.resume()
        assertEquals(3, phone.trainDownloads)
        phone.elapse(5)
        list.refreshNow()
        assertEquals(4, phone.trainDownloads)
        assertTrue(list.refreshing.value)
    }

    @Test
    fun aListComeBackToAfterASleepSaysLoadingWhileItIsAskedForAndUnavailableOnlyWhenThatFailed() {
        val list = phone.otherTrains(ride())
        list.resume()
        list.pause()
        assertIs<OtherTrains.Choice.View.Trains>(list.says())
        // Six minutes in a pocket. The list in hand is too old to pick a train from, and nothing has failed.
        phone.sleep(360)
        val gate = slowLink()
        list.resume()
        assertEquals(OtherTrains.Choice.View.Loading, list.says())
        assertEquals("Loading…", OtherTrainWords.instead(list.says(), "14 St-Union Sq"))
        gate.complete(phone.feedNow())
        assertTrue(assertIs<OtherTrains.Choice.View.Trains>(list.says()).live)
        assertEquals(phone.now - 5, list.choice.value.asOf)

        // The same with no signal: once the download has failed, the connection is what to check.
        list.pause()
        phone.sleep(360)
        phone.offline()
        list.resume()
        assertEquals(OtherTrains.Choice.View.Unavailable, list.says())
        assertEquals("Times unavailable. Check your connection.", OtherTrainWords.instead(list.says(), "14 St-Union Sq"))
        // A list only a few minutes old is shown as it stands while it is asked for again.
        phone.online()
        phone.elapse(30)
        list.pause()
        phone.sleep(200)
        slowLink()
        list.resume()
        assertEquals(false, assertIs<OtherTrains.Choice.View.Trains>(list.says()).live)

        // With nothing in memory and no signal, there is no list to show at all.
        val dark = TestPhone()
        dark.offline()
        val none = dark.otherTrains(ride())
        none.resume()
        assertEquals(OtherTrains.Choice(failed = true), none.choice.value)
        assertEquals(OtherTrains.Choice.View.Unavailable, none.says())
    }

    @Test
    fun aListOpenedOnTheRidesOwnCopyIsAskedForAgainWhenThatCopyIsHalfAMinuteOld() {
        // The MTA's feed is sixteen seconds old as it arrives, as the A C E's often is.
        phone.trains = { TrainFeeds.lFeedLater(phone.now - 16 - Fixtures.lFeed.timestamp) }
        // The ride's own screen fetched it, and the rider opens the list nineteen seconds later.
        val screen = phone.following()
        runBlocking { screen.follow("gtfs-l", followed) }
        screen.resume()
        screen.pause()
        assertEquals(1, phone.trainDownloads)
        val ride = Ride(assertNotNull(screen.following.value.train), from = "L14", to = "L03")
        phone.elapse(19)
        val list = phone.otherTrains(ride)
        list.resume()
        // That copy is new enough to answer, and is thirty-five seconds old as the list first shows.
        assertEquals(1, phone.trainDownloads)
        assertEquals(35, phone.now - list.choice.value.asOf)
        // The next download comes when the copy is half a minute old, eleven seconds on, and not half a minute
        // after the list opened: by then its trains would be over a minute old, and marked as not live.
        (1..10).forEach {
            phone.elapse(1)
            assertEquals(1, phone.trainDownloads)
        }
        phone.elapse(1)
        assertEquals(2, phone.trainDownloads)
        assertEquals(16, phone.now - list.choice.value.asOf)
        // So at no tick in its first minute is the list anything but live.
        (1..49).forEach {
            phone.elapse(1)
            assertTrue(assertIs<OtherTrains.Choice.View.Trains>(list.says()).live, "$it s on")
            assertTrue(phone.now - list.choice.value.asOf <= Train.LIVE_FOR, "$it s on")
        }
        // From its own download on, it is every half minute as ever.
        assertEquals(3, phone.trainDownloads)
    }

    @Test
    fun theListRemembersFromOnePassToTheNextHowLongEachTrainHasBeenWhereItIs() {
        // A lettered line: every train "stopped" in every download, and its stop dated a few seconds back each time.
        // The followed train is on its way to Graham Av. Another stands at Jefferson St the whole while.
        val other = "091000_L..N"
        fun jz(): GtfsRealtime.Feed {
            val at = phone.now - 5
            return feed(at, trip(GRAHAM, at + 60), trip(JEFFERSON, at - 10, id = other), standing = mapOf(other to JEFFERSON))
        }
        phone.trains = { TrainFeeds.bytes(jz()) }
        val start = phone.now
        val ride = Ride(assertNotNull(Train.find("gtfs-jz", jz(), ID, Fixtures.catalog)), MORGAN, UNION_SQ)
        val list = phone.otherTrains(ride)
        list.resume()
        fun row() = list.choice.value.trains.orEmpty().single { it.id == other }
        fun said() = RideWords.whereabouts(row(), list.choice.value.asOf, Fixtures.catalog)
        assertEquals(listOf(ID, other), list.choice.value.trains.orEmpty().map { it.id })
        assertEquals(start - 15, row().stoodSince)
        assertEquals("At Jefferson St now", said())
        // A minute on, read afresh twice, it has still been there since the list first had it there.
        phone.elapse(60)
        assertEquals(3, phone.trainDownloads)
        assertEquals(start - 15, row().stoodSince)
        assertEquals("Last seen at Jefferson St", said())
        // Which is the only way a train held at one stop on these lines is ever told as held.
        phone.elapse(210)
        assertEquals(start + 265, list.choice.value.asOf)
        assertEquals("Standing at Jefferson St since ${clockLabel(start - 15)}", said())
    }
}
