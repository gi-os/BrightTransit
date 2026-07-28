package com.thelightphone.subway

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.thelightphone.sdk.ui.lightClickable

/** Diamond used for express routes (e.g. 6X, 7X), mirroring the MTA bullets. */
private val DiamondShape = GenericShape { size, _ ->
    moveTo(size.width / 2f, 0f)
    lineTo(size.width, size.height / 2f)
    lineTo(size.width / 2f, size.height)
    lineTo(0f, size.height / 2f)
    close()
}

/** A small filled 5-point star, drawn (not a glyph) so it renders on any font. */
@Composable
fun StarMark(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val outer = this.size.minDimension / 2f
        val inner = outer * 0.45f
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val angle = (-Math.PI / 2 + i * Math.PI / 5)
            val x = cx + (r * Math.cos(angle)).toFloat()
            val y = cy + (r * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

/**
 * An authentic subway "bullet": a filled circle (local) or diamond (express) with
 * the route glyph reversed out of it. Monochrome — fill is the theme content color,
 * glyph is the background color, so it reads on e-ink in light or dark themes.
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

/**
 * A row of station-line bullets. Favorited routes get a faint star beside them.
 * If [onRouteClick] is set, each bullet is tappable (used to toggle favorites on
 * the station page).
 */
@Composable
fun RouteBadgeRow(
    routes: List<String>,
    modifier: Modifier = Modifier,
    diameter: Dp = 22.dp,
    favorites: Set<String> = emptySet(),
    onRouteClick: ((String) -> Unit)? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        routes.forEach { route ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .let { if (onRouteClick != null) it.lightClickable { onRouteClick(route) } else it },
            ) {
                RouteBadge(route, diameter = diameter)
                if (route in favorites) {
                    StarMark(
                        color = LightThemeTokens.colors.content.copy(alpha = 0.55f),
                        size = (diameter.value * 0.5f).dp,
                        modifier = Modifier.padding(start = 1.dp),
                    )
                }
            }
        }
    }
}

fun minutesLabel(minutes: Int): String = when {
    minutes <= 0 -> "Now"
    minutes == 1 -> "1 min"
    else -> "$minutes min"
}

/** Human distance label, e.g. "0.3 mi" or "400 ft". */
fun distanceLabel(meters: Double): String {
    val feet = meters * 3.28084
    return if (feet < 1000) "${(feet / 10).toInt() * 10} ft"
    else "%.1f mi".format(meters / 1609.34)
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
