package com.thelightphone.subway

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** "L", "6", "Q" rendered big and bold as the route bullet. */
@Composable
fun RouteBadge(route: String, modifier: Modifier = Modifier) {
    LightText(
        text = route,
        variant = LightTextVariant.Heading,
        modifier = modifier,
    )
}

fun minutesLabel(minutes: Int): String = when {
    minutes <= 0 -> "Now"
    minutes == 1 -> "1 min"
    else -> "$minutes min"
}

/**
 * A single upcoming train: [direction arrow] [route] .......... [Xm].
 */
@Composable
fun ArrivalRow(arrival: Arrival, nowSeconds: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.25f.gridUnitsAsDp()),
    ) {
        LightIcon(
            icon = if (arrival.direction == Direction.NORTH) LightIcons.UP else LightIcons.DOWN,
        )
        Spacer(Modifier.width(12.dp))
        RouteBadge(arrival.route)
        Spacer(Modifier.weight(1f))
        LightText(
            text = minutesLabel(arrival.minutesFrom(nowSeconds)),
            variant = LightTextVariant.Copy,
        )
    }
}
