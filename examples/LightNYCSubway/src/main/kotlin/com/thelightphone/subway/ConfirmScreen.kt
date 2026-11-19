package com.thelightphone.subway

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.dispatch
import com.thelightphone.subway.ui.Action
import com.thelightphone.subway.ui.Page
import com.thelightphone.subway.ui.SubwayScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/** The words for asking before something is taken away. */
internal object ConfirmWords {
    const val REMOVE = "REMOVE"
    const val KEEP = "KEEP"

    fun unsave(station: String): String = "Remove $station from your saved stations?"
}

/**
 * Asks [question] before something is taken away, and hands back true only for [yes].
 * KEEP, the arrow and the phone's own back all leave things as they were.
 */
class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val question: String,
    private val yes: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    private val wheel = WheelBus()
    private val page = Page()

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        wheel.dispatch(event) || super.onKeyUp(keyCode, event)

    @Composable
    override fun Content() {
        SubwayScreen(
            title = "",
            wheel = wheel,
            page = page,
            onBack = { goBack(false) },
            bottom = emptyList(),
            message = question,
            actions = listOf(Action(yes) { goBack(true) }, Action(ConfirmWords.KEEP) { goBack(false) }),
        )
    }
}
