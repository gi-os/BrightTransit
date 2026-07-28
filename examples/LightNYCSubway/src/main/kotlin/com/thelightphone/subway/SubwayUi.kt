package com.thelightphone.subway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Diamond used for express routes (e.g. 6X, 7X), mirroring the MTA bullets. */
private val DiamondShape = GenericShape { size, _ ->
    moveTo(size.width / 2f, 0f)
    lineTo(size.width, size.height / 2f)
    lineTo(size.width / 2f, size.height)
    lineTo(0f, size.height / 2f)
    close()
}

/**
 * An authentic subway "bullet": a filled circle (local) or diamond (express) with
 * the route glyph reversed out of it. Monochrome — the fill is the theme's content
 * color and the glyph is the background color, so it reads correctly on e-ink in
 * both light and dark themes.
 */
@Composable
fun RouteBadge(route: String, modifier: Modifier = Modifier, diameter: Dp = 30.dp) {
    val colors = LightThemeTokens.colors
    val express = route.length > 1 && route.last() == 'X'
    val glyph = if (express) route.dropLast(1) else route
    Box(
        modifier = modifier
            .size(diameter)
            .clip(if (express) DiamondShape else CircleShape)
            .background(colors.content),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = TextStyle(
                color = colors.background,
                fontSize = (diameter.value * 0.5f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** A row of small bullets for listing the routes that serve a station. */
@Composable
fun RouteBadgeRow(routes: List<String>, modifier: Modifier = Modifier, diameter: Dp = 22.dp) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        routes.forEach { route ->
            RouteBadge(route, diameter = diameter, modifier = Modifier.padding(end = 6.dp))
        }
    }
}

fun minutesLabel(minutes: Int): String = when {
    minutes <= 0 -> "Now"
    minutes == 1 -> "1 min"
    else -> "$minutes min"
}

/**
 * A single upcoming train: [direction arrow] [bullet] .......... [Xm].
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
