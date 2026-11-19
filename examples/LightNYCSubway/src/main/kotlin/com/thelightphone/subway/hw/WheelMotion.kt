package com.thelightphone.subway.hw

import kotlin.math.abs
import kotlin.math.sign

/**
 * Holds back the first notch after a pause until a second one confirms it.
 *
 * The wheel sits under a thumb and catches stray brushes, so one notch on its own scrolls
 * nothing. It is remembered, and the next notch releases both. From then on every notch
 * counts at once, until the wheel has been still for [idleMs] and the guard comes back.
 *
 * A second and a half is long on purpose. It has to cover a slow, deliberate turn, and
 * being too long costs nothing: someone still turning sends the notch that keeps it open.
 */
class StrayNotchGuard(private val idleMs: Long = 1_500) {
    private var turning = false
    private var held = 0
    private var seen = 0
    private var last = 0L

    /** How many notches to scroll by, now that [notches] arrived at [nowMs]. 0 while the first is held. */
    fun pass(notches: Int, nowMs: Long): Int {
        if (nowMs - last > idleMs) {
            turning = false
            held = 0
            seen = 0
        }
        last = nowMs
        if (turning) return notches

        held += notches
        seen++
        if (seen < CONFIRMED_BY) return 0
        turning = true
        // What was held is let go with the notch that confirmed it, so nothing meant is
        // lost. One up and one down cancel out, and then the later of the two decides.
        return if (held != 0) held else notches.sign
    }

    private companion object {
        const val CONFIRMED_BY = 2
    }
}

/**
 * Turns notches into a glide.
 *
 * The sensor sends a notch about every 35 ms, faster than the screen redraws. Moving the
 * page a whole notch each time gives a stack of jumps with nothing for the eye to follow.
 * So a notch adds to a distance owed, and each frame pays off a share of what is left: one
 * notch glides, and a fast spin is a single sweep that settles just after the thumb stops.
 *
 * [notchPx] is how far one notch moves the page.
 */
class WheelGlide(private val notchPx: Float) {
    private var owed = 0f

    /** True while there is distance left to pay off. */
    val moving: Boolean get() = abs(owed) > HALF_PIXEL

    fun turn(notches: Int) {
        owed += notches * notchPx * DIRECTION
    }

    /**
     * One frame of the glide: pays this frame's share through [scrollBy], which answers
     * with how far the list really went.
     */
    fun frame(scrollBy: (Float) -> Float) {
        val share = owed * SHARE
        // The last pixel is paid whole, or a notch would trail off in steps too small to see.
        val step = if (abs(share) < 1f) owed else share
        owed -= step
        // Less than was asked means the list is at its top or bottom and the rest can never
        // be paid. Kept, it would swallow the first notches of the turn back the other way.
        if (abs(scrollBy(step)) < abs(step) - HALF_PIXEL) stop()
    }

    /** Forgets what is owed. A finger taking over the list ends the glide this way. */
    fun stop() {
        owed = 0f
    }

    private companion object {
        /**
         * 1 means turning the wheel up moves the page up, the way a finger dragging it
         * would. -1 would be the mouse-wheel way round.
         */
        const val DIRECTION = 1

        /**
         * The share of what is owed that one frame pays. At 0.28 about nine tenths of a
         * notch is done in seven frames: quick enough to feel direct, slow enough to follow.
         */
        const val SHARE = 0.28f

        const val HALF_PIXEL = 0.5f
    }
}
