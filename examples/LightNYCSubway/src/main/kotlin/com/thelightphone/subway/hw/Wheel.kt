package com.thelightphone.subway.hw

import android.view.KeyEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Carries wheel notches from a screen's key handler to the list it draws.
 *
 * The SDK hands a key to the screen class, but only the page inside it knows what there is
 * to scroll. Each screen owns one of these and gives it to its page. One notch per event,
 * positive for up.
 *
 * Nothing is replayed: a notch that arrives while no list is showing is dropped, not saved
 * up for the next one. The buffer is there because the sensor sends bursts faster than a
 * frame.
 */
class WheelBus {
    private val _notches = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val notches: SharedFlow<Int> = _notches.asSharedFlow()

    fun send(notches: Int) {
        _notches.tryEmit(notches)
    }
}

/**
 * Passes a key event to the bus if it is a turn of the wheel, and says whether it was.
 *
 * A screen calls this from onKeyDown and onKeyUp. The SDK offers every key to the screen
 * on top before it sends anything on to LightOS, and a key the screen claims stops there.
 * So on this tool's screens the wheel scrolls the page, and LightOS does not also read the
 * turn as a change of brightness.
 */
fun WheelBus.dispatch(event: KeyEvent): Boolean =
    take(LightKeys.of(event), pressed = event.action == KeyEvent.ACTION_DOWN)

/**
 * One half of a notch. Both the press and the release are claimed, though only the press
 * scrolls: a release let through would reach LightOS as half a key press.
 */
fun WheelBus.take(key: LightKey?, pressed: Boolean): Boolean {
    if (key == null) return false
    if (pressed) send(if (key == LightKey.WheelUp) 1 else -1)
    return true
}

val LocalWheelBus = staticCompositionLocalOf<WheelBus?> { null }

/**
 * How far one notch moves the page. About six notches to a screenful: far enough that a
 * flick of the wheel gets somewhere, short enough to stop on the station you wanted.
 */
private val NOTCH = 64.dp

/** Lets the wheel scroll [state]. Works for a ScrollState and a LazyListState alike. */
@Composable
fun WheelScroll(state: ScrollableState) {
    val bus = LocalWheelBus.current ?: return
    val notchPx = with(LocalDensity.current) { NOTCH.toPx() }
    // Plain objects, not Compose state. Nothing drawn reads them, and a redraw in the
    // middle of a turn must not start the guard or the glide over.
    val guard = remember { StrayNotchGuard() }
    val glide = remember(notchPx) { WheelGlide(notchPx) }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }

    LaunchedEffect(bus, glide) {
        bus.notches.collect { notches ->
            val released = guard.pass(notches, System.nanoTime() / 1_000_000)
            if (released != 0) {
                glide.turn(released)
                wake.trySend(Unit)
            }
        }
    }

    LaunchedEffect(state, glide) {
        while (true) {
            // Waits here while the wheel is still, so an idle screen costs nothing.
            wake.receive()
            try {
                // One scroll for the whole glide, paid out a frame at a time.
                state.scroll {
                    while (glide.moving) {
                        withFrameNanos { }
                        glide.frame { scrollBy(it) }
                    }
                }
            } catch (interrupted: CancellationException) {
                // A finger on the list takes it over and ends the glide, which is right.
                // The wheel itself carries on unless this screen is the one going away.
                ensureActive()
                glide.stop()
            }
        }
    }
}
