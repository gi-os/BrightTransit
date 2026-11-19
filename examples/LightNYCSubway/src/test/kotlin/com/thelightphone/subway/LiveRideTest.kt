package com.thelightphone.subway

import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.RideState
import com.thelightphone.subway.data.SavedStore
import com.thelightphone.subway.data.Train
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A ride from when its stop is tapped to when it is over: what is written down, what is
 * opened again, and when the screen gives a ride up. The L is the captured one, moved along
 * so that it is always now.
 */
class LiveRideTest {
    private val phone = TestPhone()
    private val store = MemoryStore()
    private val saved = SavedStore(store, CoroutineScope(Dispatchers.Unconfined))

    /** The train standing at Jefferson St on its way to 8 Av, and the one behind it. */
    private val first = "061050_L..N"
    private val second = "063500_L..N"

    private fun train(id: String, at: Long = phone.now): Train =
        assertNotNull(Train.find("gtfs-l", TrainFeeds.later(Fixtures.lFeed, at - 5 - Fixtures.lFeed.timestamp), id, Fixtures.catalog))

    /** A ride from Morgan Av to 14 St-Union Sq, as tapped on the train's page just now. */
    private fun tapped(id: String = first) = Ride(train(id), from = "L14", to = "L03")

    private fun kept(): Ride? = runBlocking { saved.ride.first() }

    private fun slowLink(): CompletableDeferred<ByteArray> =
        CompletableDeferred<ByteArray>().also { gate -> phone.trains = { gate.await() } }

    /** The MTA as it is ten minutes and more after the ride's train was due: that train is long gone from it. */
    private fun withoutTheTrain() {
        phone.trains = { TrainFeeds.bytes(TrainFeeds.later(Fixtures.lFeed, phone.now - 5 - Fixtures.lFeed.timestamp).let { feed -> feed.copy(trips = feed.trips.filter { it.id != first }) }) }
    }

    @Test
    fun aRideIsWrittenDownWhenItStartsAndAsItsTrainMoves() {
        val start = tapped()
        val ride = phone.ride(start, saved)
        assertEquals(start, kept())
        assertEquals(LiveRide.Showing.RIDE, ride.showing.value)
        ride.shown()
        phone.elapse(30)
        // The newer word of the train is what is kept, so a ride opened again starts from it.
        assertEquals(phone.now - 5, ride.ride.value.train.asOf)
        assertEquals(ride.ride.value, kept())
        assertTrue(ride.ride.value.train.asOf > start.train.asOf)
        assertEquals("L14" to "L03", kept()!!.from to kept()!!.to)
    }

    @Test
    fun leavingTheScreenEndsTheRideAndNothingThatComesBackAfterIsWrittenDown() {
        val ride = phone.ride(tapped(), saved)
        ride.shown()
        phone.elapse(25)
        val gate = slowLink()
        phone.elapse(5)
        // The rider backs out while a download is on its way.
        ride.end()
        assertNull(kept())
        gate.complete(phone.feedNow())
        assertNull(kept())
    }

    @Test
    fun theRiderSayingTheyAreOnAnotherTrainMovesTheRideToIt() {
        val ride = phone.ride(tapped(), saved)
        ride.shown()
        runBlocking { ride.switchTo(train(second)) }
        assertEquals(second, ride.ride.value.train.id)
        assertEquals(second, kept()!!.train.id)
        assertEquals("L14" to "L03", kept()!!.from to kept()!!.to)
        // And that is the train followed from then on.
        phone.elapse(30)
        assertEquals(second, ride.live.following.value.train?.id)
        assertEquals(phone.now - 5, kept()!!.train.asOf)
    }

    @Test
    fun whatTheToolOpensOnIsARideThatIsNotOverOrOneStillWorthAskingAbout() {
        fun toResume() = runBlocking { saved.rideToResume(phone.now) }
        assertNull(toResume())
        val start = tapped()
        saved.setRide(start)
        assertEquals(start, toResume())
        // It looks over, twenty minutes past its time at the stop. Its train may only be late: it is opened, to be asked about.
        val due = start.train.stop("L03")!!.time
        phone.sleep(due - phone.now + 1200)
        assertTrue(start.over(phone.now))
        assertEquals(start, toResume())
        assertEquals(start, kept())
        // An hour and more since the last word of the train, it is not worth asking, and is forgotten.
        phone.sleep(3600)
        assertNull(toResume())
        assertNull(kept())
        // What another version wrote and this one cannot read is no ride.
        runBlocking { store.updateData { prefs -> prefs.toMutablePreferences().also { it[androidx.datastore.preferences.core.stringPreferencesKey("ride_in_progress")] = "not a ride" } } }
        assertNull(toResume())
    }

    @Test
    fun aRideIsNotEndedOnWhatWasKnownBeforeThePhoneSlept() {
        val start = tapped()
        val ride = phone.ride(start, saved)
        ride.shown()
        ride.paused()
        // Twenty minutes past the time the train had for the stop, the rider looks again. The train is still short of it.
        val due = start.train.stop("L03")!!.time
        phone.sleep(due - phone.now + 1200)
        assertTrue(ride.ride.value.over(phone.now))
        val gate = slowLink()
        ride.shown()
        // Nothing of the ride is said while its train is asked about, and it is not ended.
        assertEquals(LiveRide.Showing.ASKING, ride.showing.value)
        assertEquals(start, kept())
        gate.complete(phone.feedNow())
        assertEquals(LiveRide.Showing.RIDE, ride.showing.value)
        assertFalse(ride.ride.value.over(phone.now))
        assertIs<RideState.Waiting>(ride.ride.value.state(phone.now))
        assertEquals(ride.ride.value, kept())
    }

    @Test
    fun aRideIsEndedWhenTheAnswerStillHasItOver() {
        val start = tapped()
        val ride = phone.ride(start, saved)
        ride.shown()
        ride.paused()
        phone.sleep(start.train.stop("L03")!!.time - phone.now + 1200)
        withoutTheTrain()
        ride.shown()
        assertEquals(LiveRide.Showing.OVER, ride.showing.value)
        // The screen leaves, and that is what forgets the ride.
        ride.end()
        assertNull(kept())
    }

    @Test
    fun withNoAnswerToBeHadARideIsKeptOnScreenUntilItsLastWordIsAnHourOld() {
        val start = tapped()
        val ride = phone.ride(start, saved)
        ride.shown()
        ride.paused()
        phone.sleep(start.train.stop("L03")!!.time - phone.now + 1200)
        phone.offline()
        ride.shown()
        // In a tunnel, most likely. What is known is said, with how old it is.
        assertEquals(LiveRide.Showing.RIDE, ride.showing.value)
        assertIs<RideState.Unknown>(ride.ride.value.state(phone.now))
        assertEquals(start, kept())
        ride.paused()
        phone.sleep(3600)
        ride.shown()
        assertEquals(LiveRide.Showing.OVER, ride.showing.value)
    }

    @Test
    fun aRideTheToolWasClosedOnIsJudgedTheSameWayWhenItIsOpenedAgain() {
        val start = tapped()
        saved.setRide(start)
        phone.sleep(start.train.stop("L03")!!.time - phone.now + 1200)
        // The train is gone from the feed: the ride is over, and Home is what is left.
        withoutTheTrain()
        val over = phone.ride(start, saved, resumed = true)
        over.shown()
        assertEquals(LiveRide.Showing.OVER, over.showing.value)
        // The train is still listed short of the stop: the ride is shown.
        phone.online()
        phone.elapse(30)
        val late = phone.ride(start, saved, resumed = true)
        late.shown()
        assertEquals(LiveRide.Showing.RIDE, late.showing.value)
        // A ride opened again that does not look over is shown at once, before any answer.
        val soon = tapped()
        slowLink()
        val waiting = phone.ride(soon, saved, resumed = true)
        waiting.shown()
        assertEquals(LiveRide.Showing.RIDE, waiting.showing.value)
    }

    @Test
    fun aRideJustTappedIsNotJudgedAndNeitherIsOneComeBackToFromTheListOfOtherTrains() {
        // A train held so long that its time at the stop went by twenty minutes ago, tapped all the same.
        val held = tapped().let { it.on(it.train.copy(stops = it.train.stops.map { stop -> stop.copy(time = stop.time - 3000) })) }
        slowLink()
        val ride = phone.ride(held, saved)
        ride.shown()
        assertEquals(LiveRide.Showing.RIDE, ride.showing.value)
        // The list of other trains covers the ride and uncovers it. The tool itself never went away.
        ride.hidden()
        phone.elapse(700)
        withoutTheTrain()
        ride.shown()
        assertEquals(LiveRide.Showing.RIDE, ride.showing.value)
        assertEquals(first, kept()!!.train.id)
    }

    @Test
    fun aTrainPickedFromTheListIsKeptHoweverLongAgoTheOldOneWasDue() {
        val start = tapped()
        val ride = phone.ride(start, saved)
        ride.shown()
        // The phone sleeps on the ride, and is woken twenty minutes past the old train's time at the stop.
        ride.paused()
        phone.sleep(start.train.stop("L03")!!.time - phone.now + 1200)
        val gate = slowLink()
        ride.shown()
        assertEquals(LiveRide.Showing.ASKING, ride.showing.value)
        // Before the answer is in, the rider has opened the list and named the train they are on.
        ride.hidden()
        ride.shown()
        runBlocking { ride.switchTo(train(second)) }
        assertEquals(LiveRide.Showing.RIDE, ride.showing.value)
        gate.complete(phone.feedNow())
        assertEquals(LiveRide.Showing.RIDE, ride.showing.value)
        assertEquals(second, kept()!!.train.id)
        assertFalse(ride.ride.value.over(phone.now))
    }
}
