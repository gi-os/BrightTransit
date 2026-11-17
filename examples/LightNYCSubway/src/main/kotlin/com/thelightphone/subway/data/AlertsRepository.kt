package com.thelightphone.subway.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Downloads the service alerts and keeps the latest copy.
 *
 * Alerts are kept well away from the train times. They have their own download and their
 * own place to be read from ([current]), so a slow or failed alerts download can never hold
 * up, blank or mark stale a board of trains.
 *
 * The download runs in this repository's own scope and not in the caller's. Going from Home
 * to a station within a few seconds is the normal thing to do, and it cancels everything
 * the first screen started. The alerts feed is the biggest download in the tool; owned by a
 * screen it would be thrown away and begun again on every such move, and on a weak signal
 * might never finish.
 */
class AlertsRepository(
    private val download: suspend (String) -> ByteArray = { MtaHttp.get(it, requestTimeoutMs = TIMEOUT_MS) },
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _current = MutableStateFlow(Alerts.NONE)

    /** The alerts in hand. Replaced whole by each good download, left alone by a failed one. */
    val current: StateFlow<Alerts> = _current

    private val lock = Any()
    private var running: Job? = null
    private var lastAttempt: Long? = null
    private var failures = 0

    /**
     * Starts a download if one is due, and returns at once either way. Safe to call as
     * often as the trains are fetched: most calls do nothing.
     *
     * With [force] (the refresh button) the last try still has to be a minute old before
     * there is another, however often the button is pressed. The Job is returned for tests;
     * null means nothing was due.
     */
    fun refresh(force: Boolean = false): Job? = synchronized(lock) {
        val now = clock()
        val sinceLast = lastAttempt?.let { now - it }
        val inFlight = running?.takeIf { it.isActive }
        if (inFlight != null) {
            if (sinceLast != null && sinceLast in 0..STUCK_AFTER) return inFlight
            // Still going after a minute means the phone slept in the middle of it. It is
            // begun again right away: a try that brought nothing back is not a copy to sit on.
            inFlight.cancel()
        } else {
            val wait = when {
                force -> FORCED_FRESH_FOR
                failures > 0 -> RETRY_AFTER shl (failures - 1).coerceAtMost(MOST_DOUBLINGS)
                else -> FRESH_FOR
            }
            // A last try dated in the future means the clock was set back, and counts as due.
            if (sinceLast != null && sinceLast in 0 until wait) return null
        }
        lastAttempt = now
        scope.launch {
            val fresh = latest()
            synchronized(lock) {
                // A download that was replaced while it ran has no say in what is shown.
                if (isActive) {
                    if (fresh == null) {
                        failures++
                    } else {
                        failures = 0
                        _current.value = fresh
                    }
                }
            }
        }.also { running = it }
    }

    /** The feed as it is now, or null when it could not be had: no signal, an error page, a cut-off body. */
    private suspend fun latest(): Alerts? =
        try {
            val bytes = download(Feeds.ALERTS)
            val now = clock()
            Alerts.from(GtfsRealtime.alerts(bytes, from = now, until = now + Alerts.KEEP_FOR), checkedAt = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }

    companion object {
        /** How long one download lasts when nobody asks for a newer one. */
        const val FRESH_FOR = 180L

        /** The refresh button fetches alerts again only if the last try is at least this old. */
        const val FORCED_FRESH_FOR = 60L

        /**
         * The wait after a failed download, doubled once if the next one fails too. It stops at
         * four minutes because no signal is the normal state between stations: a rider who
         * comes out of a tunnel should not wait much longer for alerts than for trains.
         */
        const val RETRY_AFTER = 120L

        private const val MOST_DOUBLINGS = 1

        /** A download still running after this long is given up on and replaced. */
        const val STUCK_AFTER = 60L

        /** Longer than the trains get: this feed is many times the size of theirs. */
        const val TIMEOUT_MS = 25_000L
    }
}
