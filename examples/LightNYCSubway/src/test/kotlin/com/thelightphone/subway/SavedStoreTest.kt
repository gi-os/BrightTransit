package com.thelightphone.subway

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.subway.ByHand.JEFFERSON
import com.thelightphone.subway.ByHand.MORGAN
import com.thelightphone.subway.ByHand.T0
import com.thelightphone.subway.ByHand.UNION_SQ
import com.thelightphone.subway.ByHand.feed
import com.thelightphone.subway.ByHand.seen
import com.thelightphone.subway.ByHand.trip
import com.thelightphone.subway.data.Ride
import com.thelightphone.subway.data.SavedStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ride in progress, kept so it outlives the tool being closed. */
class SavedStoreTest {
    private val phone = MemoryStore()
    private val saved = SavedStore(phone, CoroutineScope(Dispatchers.Unconfined))

    private val toUnionSq = Ride(seen(feed(T0, trip(JEFFERSON, T0 + 30))), MORGAN, UNION_SQ)

    private fun ride(): Ride? = runBlocking { saved.ride.first() }

    @Test
    fun aRideIsKeptAndEnded() {
        assertNull(ride())
        saved.setRide(toUnionSq)
        assertEquals(toUnionSq, ride())
        val later = toUnionSq.on(toUnionSq.train.after(feed(T0 + 60, trip(MORGAN, T0 + 110)), Fixtures.catalog))
        saved.setRide(later)
        assertEquals(later, ride())
        saved.setRide(null)
        assertNull(ride())
    }

    @Test
    fun theLastThingAskedForIsWhatIsLeftWrittenHoweverSlowTheStore() {
        val gate = CompletableDeferred<Unit>()
        phone.hold = gate
        // The screen writes the ride, then the rider backs out before the first write has landed.
        saved.setRide(toUnionSq)
        saved.setRide(toUnionSq.on(toUnionSq.train.copy(asOf = T0 + 30)))
        saved.setRide(null)
        assertEquals(0, phone.writes)
        gate.complete(Unit)
        assertNull(ride())
        // The write in between was never needed, and was not made.
        assertEquals(2, phone.writes)
    }

    @Test
    fun aRideDoesNotDisturbWhatElseIsSaved() {
        runBlocking {
            saved.toggleStation("625")
            saved.toggleRoute("L")
        }
        saved.setRide(toUnionSq)
        saved.setRide(null)
        runBlocking {
            assertEquals(listOf("625"), saved.stationIds.first())
            assertEquals(setOf("L"), saved.favoriteRoutes.first())
        }
    }

    @Test
    fun whatAnotherVersionWroteAndThisOneCannotReadIsNoRide() {
        runBlocking { phone.edit { it[stringPreferencesKey("ride_in_progress")] = "not a ride" } }
        assertNull(ride())
        // It is written over by the next ride like anything else.
        saved.setRide(toUnionSq)
        assertEquals(toUnionSq, ride())
    }

    @Test
    fun openingATrainIsRememberedOnce() {
        runBlocking {
            assertFalse(saved.openedATrain.first())
            saved.noteTrainOpened()
            assertTrue(saved.openedATrain.first())
            saved.noteTrainOpened()
            assertTrue(saved.openedATrain.first())
        }
    }
}
