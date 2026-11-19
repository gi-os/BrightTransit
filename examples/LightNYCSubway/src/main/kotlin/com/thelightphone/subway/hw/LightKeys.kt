package com.thelightphone.subway.hw

import android.view.KeyEvent
import com.thelightphone.lp3Keyboard.ui.LightDeviceKeys

/** The wheel's two directions. */
enum class LightKey {
    /** Turned towards the top of the phone. */
    WheelUp,

    /** Turned towards the bottom of the phone. */
    WheelDown,
}

/**
 * Tells a turn of the Light Phone III's wheel from every other key.
 *
 * The wheel is not a rotary encoder. It is an optical sensor that sends one key press and
 * release per notch, so a turn reaches the tool as ordinary key events and not as a scroll.
 *
 * LightOS gives the two directions key codes of their own, and the SDK lists them in
 * [LightDeviceKeys], the table it reads to decide which keys go on to LightOS. That table
 * is the first thing checked here. Two more ways in sit behind it, for a build of LightOS
 * whose numbers differ from the SDK's: the names its key layout gives the two keys, looked
 * up on the phone, and last the sensor's own scancodes.
 */
object LightKeys {

    /** The wheel's sensor, as the phone names it. */
    private const val WHEEL_SENSOR = "Pixart pat9126ja"

    /**
     * What the sensor itself sends, whatever key codes are laid over it. A paired keyboard
     * sends the same two numbers for R and T, so they only count from [WHEEL_SENSOR].
     */
    private val byScanCode = mapOf(19 to LightKey.WheelUp, 20 to LightKey.WheelDown)

    /** Where the phone's own name for a key and the SDK's number disagree, the phone wins. */
    private val byKeyCode: Map<Int, LightKey> = buildMap {
        put(LightDeviceKeys.RotaryTurnUp.keyCode, LightKey.WheelUp)
        put(LightDeviceKeys.RotaryTurnDown.keyCode, LightKey.WheelDown)
        named("WHEEL_CCW")?.let { put(it, LightKey.WheelUp) }
        named("WHEEL_CW")?.let { put(it, LightKey.WheelDown) }
    }

    /** The key code LightOS gives a key name, or null on a build that has no such key. */
    private fun named(label: String): Int? =
        runCatching { KeyEvent.keyCodeFromString(label) }.getOrNull()
            ?.takeIf { it != KeyEvent.KEYCODE_UNKNOWN }

    /** Which way the wheel turned, or null if [event] is some other key. */
    fun of(event: KeyEvent): LightKey? = of(event.keyCode, event.scanCode, event.device?.name)

    /** The same answer from the three things it depends on, which a test can supply. */
    fun of(keyCode: Int, scanCode: Int, device: String?): LightKey? =
        byKeyCode[keyCode] ?: byScanCode[scanCode].takeIf { device == WHEEL_SENSOR }
}
