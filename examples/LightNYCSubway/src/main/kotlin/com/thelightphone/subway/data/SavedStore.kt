package com.thelightphone.subway.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the tool remembers: which stations are saved (in the order they were saved), which
 * lines are favorites, the ride in progress if there is one, and whether a train's stops
 * have ever been opened. All of it stays on the phone.
 *
 * The first two keys are the ones version 1 wrote under, so what was starred there is saved here.
 */
class SavedStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    val stationIds: Flow<List<String>> = dataStore.data.map { it[STATIONS].parts() }.distinctUntilChanged()

    val favoriteRoutes: Flow<Set<String>> = dataStore.data.map { it[ROUTES].parts().toSet() }.distinctUntilChanged()

    /**
     * The ride in progress, or null. The screens are gone once LightOS closes the tool,
     * and a rider who looks again three stops later should find the ride, not Home. What
     * is kept is the train as last heard of, so there is something to say before the first
     * download is back, or with no signal at all.
     */
    val ride: Flow<Ride?> = dataStore.data.map { prefs -> prefs[RIDE]?.let(Ride::decode) }.distinctUntilChanged()

    /** The ride to keep or, with [None], to forget. Only the latest matters, and they are written in the order given. */
    private val rides = Channel<Any>(Channel.CONFLATED)

    private object None

    init {
        scope.launch {
            for (latest in rides) {
                runCatching {
                    dataStore.edit { prefs -> if (latest is Ride) prefs[RIDE] = latest.encode() else prefs.remove(RIDE) }
                }
            }
        }
    }

    /**
     * Keeps [ride] as the ride in progress, or ends it with null. This does not wait and
     * does not belong to the screen that calls it: the last thing a ride's screen does is
     * end the ride, and that write has to outlive the screen.
     */
    fun setRide(ride: Ride?) {
        rides.trySend(ride ?: None)
    }

    /**
     * The ride to open the tool on at [now], or null. A ride that looks over is still
     * handed back: its train may only be late, and the ride's own screen asks before it
     * gives up. One that looks over and has heard nothing of its train for an hour is not
     * worth asking about, and is forgotten here.
     */
    suspend fun rideToResume(now: Long): Ride? {
        val kept = runCatching { ride.first() }.getOrNull() ?: return null
        if (!kept.forgotten(now)) return kept
        setRide(null)
        return null
    }

    /** True once a train's stops have been opened, which is when the station page stops saying they can be. */
    val openedATrain: Flow<Boolean> = dataStore.data.map { it[OPENED_A_TRAIN] != null }.distinctUntilChanged()

    suspend fun noteTrainOpened() {
        dataStore.edit { it[OPENED_A_TRAIN] = "1" }
    }

    /** Rewrites the saved list in today's ids if an older version wrote it. See [Catalog.canonicalIds]. */
    suspend fun bringUpToDate(catalog: Catalog) {
        dataStore.edit { prefs ->
            val current = prefs[STATIONS].parts()
            val canonical = catalog.canonicalIds(current)
            if (canonical != current) prefs[STATIONS] = canonical.joinToString("|")
        }
    }

    suspend fun toggleStation(id: String) = toggle(STATIONS, id)

    suspend fun toggleRoute(route: String) = toggle(ROUTES, route)

    private suspend fun toggle(key: Preferences.Key<String>, value: String) {
        dataStore.edit { prefs ->
            val current = prefs[key].parts().toMutableList()
            if (!current.remove(value)) current.add(value)
            prefs[key] = current.joinToString("|")
        }
    }

    private fun String?.parts(): List<String> = this?.split("|")?.filter { it.isNotBlank() } ?: emptyList()

    private companion object {
        val STATIONS = stringPreferencesKey("starred_station_ids")
        val ROUTES = stringPreferencesKey("favorite_routes")
        val RIDE = stringPreferencesKey("ride_in_progress")
        val OPENED_A_TRAIN = stringPreferencesKey("opened_a_train")
    }
}
