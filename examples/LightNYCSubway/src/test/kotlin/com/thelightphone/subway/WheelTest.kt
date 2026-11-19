package com.thelightphone.subway

import com.thelightphone.subway.hw.LightKey
import com.thelightphone.subway.hw.LightKeys
import com.thelightphone.subway.hw.StrayNotchGuard
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.WheelGlide
import com.thelightphone.subway.hw.take
import com.thelightphone.lp3Keyboard.ui.LightDeviceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrayNotchGuardTest {
    private val guard = StrayNotchGuard()

    /** Any moment well after the phone started. */
    private val start = 500_000L

    @Test
    fun oneStrayNotchScrollsNothing() {
        assertEquals(0, guard.pass(1, start))
    }

    @Test
    fun theSecondNotchLetsBothGo() {
        guard.pass(1, start)
        assertEquals(2, guard.pass(1, start + 40))
        guard.pass(-1, start + 5_000)
        assertEquals(-2, guard.pass(-1, start + 5_040))
    }

    @Test
    fun onceTurningEveryNotchCountsAtOnce() {
        guard.pass(1, start)
        guard.pass(1, start + 40)
        assertEquals(1, guard.pass(1, start + 80))
        assertEquals(-1, guard.pass(-1, start + 120))
    }

    @Test
    fun aSlowDeliberateTurnStaysLive() {
        guard.pass(1, start)
        guard.pass(1, start + 1_500)
        assertEquals(1, guard.pass(1, start + 3_000))
    }

    @Test
    fun afterAPauseTheGuardComesBack() {
        guard.pass(1, start)
        guard.pass(1, start + 40)
        assertEquals(0, guard.pass(1, start + 40 + 1_501))
        assertEquals(2, guard.pass(1, start + 40 + 1_541))
    }

    @Test
    fun aStrayNotchIsForgottenByTheNextPause() {
        guard.pass(1, start)
        // Two brushes a few seconds apart are two strays, not one turn.
        assertEquals(0, guard.pass(1, start + 4_000))
        assertEquals(0, guard.pass(-1, start + 9_000))
    }

    @Test
    fun oneUpAndOneDownGoTheWayOfTheLaterOne() {
        guard.pass(1, start)
        assertEquals(-1, guard.pass(-1, start + 40))
    }
}

class WheelGlideTest {
    private val notch = 192f
    private val glide = WheelGlide(notchPx = notch)

    /** A list scrolled to [at], with [room] more to go before it ends. Remembers each frame's step. */
    private class Page(var at: Float = 0f, val room: Float = 100_000f) {
        val steps = mutableListOf<Float>()

        fun scrollBy(px: Float): Float {
            steps += px
            val to = (at + px).coerceIn(0f, room)
            return (to - at).also { at = to }
        }
    }

    private fun run(page: Page, frames: Int = 1_000) {
        var left = frames
        while (glide.moving && left-- > 0) glide.frame { page.scrollBy(it) }
    }

    @Test
    fun nothingMovesUntilTheWheelTurns() {
        assertFalse(glide.moving)
    }

    @Test
    fun aNotchMovesThePageOneNotchInTheEnd() {
        val page = Page()
        glide.turn(1)
        run(page)
        assertEquals(notch, page.at, 0.5f)
        assertFalse(glide.moving)
    }

    @Test
    fun eachFramePaysAShareOfWhatIsLeft() {
        val page = Page()
        glide.turn(1)
        run(page, frames = 3)
        assertEquals(notch * 0.28f, page.steps[0], 0.01f)
        assertEquals(notch * 0.72f * 0.28f, page.steps[1], 0.01f)
        assertEquals(notch * 0.72f * 0.72f * 0.28f, page.steps[2], 0.01f)
    }

    @Test
    fun aboutNineTenthsOfANotchIsDoneInSevenFrames() {
        val page = Page()
        glide.turn(1)
        run(page, frames = 7)
        assertEquals(0.9f, page.at / notch, 0.005f)
        assertTrue(glide.moving)
    }

    @Test
    fun aFastSpinIsOneSweepThatSlowsToAStop() {
        val page = Page()
        repeat(6) { glide.turn(1) }
        run(page)
        assertEquals(6 * notch, page.at, 0.5f)
        // Each frame moves less than the one before, up to the last, which settles the rest.
        val slowing = page.steps.dropLast(1)
        assertEquals(slowing.sortedDescending(), slowing)
    }

    @Test
    fun notchesThatArriveMidGlideJoinIt() {
        val page = Page()
        glide.turn(1)
        run(page, frames = 2)
        glide.turn(1)
        run(page)
        assertEquals(2 * notch, page.at, 0.5f)
    }

    @Test
    fun theLastPixelIsPaidWholeSoAGlideAlwaysEnds() {
        val page = Page()
        glide.turn(1)
        run(page)
        // Every step but the last is at least a pixel; the last settles the rest.
        assertTrue(page.steps.dropLast(1).all { abs(it) >= 1f })
        assertTrue(page.steps.size < 40)
    }

    @Test
    fun wheelUpMovesDownThePageAndWheelDownBackUp() {
        val page = Page(at = 1_000f)
        glide.turn(1)
        run(page)
        assertTrue(page.at > 1_000f)
        glide.turn(-2)
        run(page)
        assertEquals(1_000f - notch, page.at, 0.5f)
    }

    @Test
    fun atTheEndOfTheListWhatIsStillOwedIsDropped() {
        val page = Page(at = 0f, room = 30f)
        repeat(5) { glide.turn(1) }
        run(page)
        assertEquals(30f, page.at)
        assertFalse(glide.moving)
        // Had it been kept, the first notches back up would have paid it off and moved nothing.
        glide.turn(-1)
        glide.frame { page.scrollBy(it) }
        assertTrue(page.at < 30f)
    }

    @Test
    fun aFingerTakingOverEndsTheGlide() {
        val page = Page()
        repeat(3) { glide.turn(1) }
        run(page, frames = 2)
        glide.stop()
        assertFalse(glide.moving)
    }
}

class WheelKeysTest {
    private val sensor = "Pixart pat9126ja"

    @Test
    fun theSdksOwnKeyCodesAreTheWheel() {
        val up = LightDeviceKeys.RotaryTurnUp.keyCode
        val down = LightDeviceKeys.RotaryTurnDown.keyCode
        assertEquals(LightKey.WheelUp, LightKeys.of(up, scanCode = 0, device = null))
        assertEquals(LightKey.WheelDown, LightKeys.of(down, scanCode = 0, device = null))
    }

    @Test
    fun theSensorsScancodesAreTheWheelWhateverTheKeyCode() {
        assertEquals(LightKey.WheelUp, LightKeys.of(keyCode = 0, scanCode = 19, device = sensor))
        assertEquals(LightKey.WheelDown, LightKeys.of(keyCode = 0, scanCode = 20, device = sensor))
    }

    @Test
    fun aPairedKeyboardsRAndTAreNotTheWheel() {
        // KEYCODE_R and KEYCODE_T, with the same scancodes the wheel's sensor uses.
        assertNull(LightKeys.of(keyCode = 46, scanCode = 19, device = "Bluetooth Keyboard"))
        assertNull(LightKeys.of(keyCode = 48, scanCode = 20, device = null))
    }

    @Test
    fun theOtherHardwareKeysAreLeftToLightOs() {
        for (key in listOf(LightDeviceKeys.VolumeUp, LightDeviceKeys.VolumeDown, LightDeviceKeys.RotaryButtonPress)) {
            assertNull(LightKeys.of(key.keyCode, scanCode = 0, device = sensor), key.name)
        }
    }

    private fun WheelBus.heard(): List<Int> {
        val heard = mutableListOf<Int>()
        CoroutineScope(Dispatchers.Unconfined).launch { notches.collect { heard += it } }
        return heard
    }

    @Test
    fun aNotchScrollsOnThePressAndBothHalvesAreClaimed() {
        val bus = WheelBus()
        val heard = bus.heard()
        assertTrue(bus.take(LightKey.WheelUp, pressed = true))
        assertTrue(bus.take(LightKey.WheelUp, pressed = false))
        assertTrue(bus.take(LightKey.WheelDown, pressed = true))
        assertTrue(bus.take(LightKey.WheelDown, pressed = false))
        assertEquals(listOf(1, -1), heard)
    }

    @Test
    fun anyOtherKeyIsLeftAlone() {
        val bus = WheelBus()
        val heard = bus.heard()
        assertFalse(bus.take(null, pressed = true))
        assertTrue(heard.isEmpty())
    }

    @Test
    fun aNotchWithNoListShowingIsNotSavedUp() {
        val bus = WheelBus()
        bus.take(LightKey.WheelUp, pressed = true)
        assertTrue(bus.heard().isEmpty())
    }
}
