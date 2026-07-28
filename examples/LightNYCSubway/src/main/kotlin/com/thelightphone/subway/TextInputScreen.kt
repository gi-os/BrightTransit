package com.thelightphone.subway

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Full-screen Light keyboard editor. Returns the typed text on submit, or null if
 * the user backs out. Reused by [SearchScreen].
 */
class TextInputScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialValue: String = "",
) : SimpleLightScreen<String?>(sealedActivity) {

    @Composable
    override fun Content() {
        val textState = rememberTextFieldState(initialValue)
        // NOTE: we deliberately do NOT use rememberKeyboardOptions() here. It fetches
        // keyboard settings from the LightOS server, and some server builds return a
        // payload missing the required `swipeEnabled` field, which makes the SDK's
        // serializer throw MissingFieldException and crashes the editor. The built-in
        // defaults type fine and work identically for a search box.
        val keyboardOptionsFlow = remember { MutableStateFlow(defaultKeyboardOptions()) }
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = title,
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { result -> goBack(result.toString()) },
                onBack = { goBack(null) },
                submitIcon = LightIcons.SEARCH,
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
