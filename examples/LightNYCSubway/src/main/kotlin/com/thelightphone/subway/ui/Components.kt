package com.thelightphone.subway.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.subway.data.Arrival
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Directions
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.hw.LocalWheelBus
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.WheelScroll
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The frame every screen in the tool is drawn in: a title bar, the page, and one row of
 * buttons along the bottom. All the actions live in that bottom row and nowhere else, which
 * is how the rest of LightOS does it.
 *
 * With [message] set the page is that text, centred, instead of [content]. That is the
 * blank state: nothing saved yet, nothing found, no connection.
 */
@Composable
fun SubwayScreen(
    title: String,
    wheel: WheelBus,
    bottom: List<LightBarButton?>,
    onBack: (() -> Unit)? = null,
    message: String? = null,
    refreshing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val themeColors by LightThemeController.colors.collectAsState()
    CompositionLocalProvider(LocalWheelBus provides wheel, LocalRefreshing provides refreshing) {
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = onBack?.let {
                        LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = it, contentDescription = "Back")
                    },
                    center = LightTopBarCenter.Text(title),
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )
                if (message != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 2f.gridUnitsAsDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        key(message) {
                            FadeIn { LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center) }
                        }
                    }
                } else {
                    val scroll = rememberScrollState()
                    WheelScroll(scroll)
                    LightScrollView(
                        scrollState = scroll,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                        content = content,
                    )
                }
                LightBottomBar(items = bottom)
            }
        }
    }
}

fun searchButton(onClick: () -> Unit) =
    LightBarButton.LightIcon(icon = LightIcons.SEARCH, onClick = onClick, contentDescription = "Search stations")

fun refreshButton(onClick: () -> Unit) =
    LightBarButton.LightIcon(icon = LightIcons.REFRESH, onClick = onClick, contentDescription = "Refresh")

/**
 * The current time in seconds, moving forward on its own so "3 min" becomes "2 min" without
 * a download. Ten seconds is fine-grained enough for a number shown in whole minutes.
 */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis() / 1000) {
    while (true) {
        delay(10_000)
        value = System.currentTimeMillis() / 1000
    }
}

/**
 * Brings [content] in from nothing over a quarter of a second, once, when it first appears.
 *
 * Used where something arrives after the screen is already up (train times, search results,
 * the nearby list), so it settles in instead of snapping on. [order] staggers a list: each
 * item starts a moment after the one above it, up to the seventh.
 *
 * LightOS can turn animations off system-wide; with that set this shows the content at once.
 */
@Composable
fun FadeIn(modifier: Modifier = Modifier, order: Int = 0, content: @Composable () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(durationMillis = 260, delayMillis = minOf(order, 6) * 45))
    }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

/** True while a refresh the person asked for is in flight. Set by [SubwayScreen]. */
val LocalRefreshing = compositionLocalOf { false }

/**
 * Dims train times while they are being fetched again, then brings them back. Without it
 * the refresh button gives no sign it did anything when the times come back unchanged.
 */
@Composable
fun Modifier.dimWhileRefreshing(): Modifier {
    val alpha by animateFloatAsState(if (LocalRefreshing.current) 0.35f else 1f, tween(180), label = "refresh")
    return graphicsLayer { this.alpha = alpha }
}

/** Diamond used for express services, as on the MTA's own bullets. */
private val DiamondShape = GenericShape { size, _ ->
    moveTo(size.width / 2f, 0f)
    lineTo(size.width, size.height / 2f)
    lineTo(size.width / 2f, size.height)
    lineTo(0f, size.height / 2f)
    close()
}

/** A small filled star, drawn rather than typed so it looks the same in any font. */
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
            val angle = -Math.PI / 2 + i * Math.PI / 5
            val x = cx + (r * Math.cos(angle)).toFloat()
            val y = cy + (r * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

/**
 * A subway bullet: a filled circle, or a diamond for an express, with the line cut out of
 * it. One colour, so it reads in both the dark and the light theme.
 */
@Composable
fun RouteBadge(route: String, modifier: Modifier = Modifier, diameter: Dp = 26.dp) {
    val colors = LightThemeTokens.colors
    val glyph = Routes.glyph(route)
    Box(
        modifier = modifier
            .size(diameter)
            .clip(if (Routes.isExpress(route)) DiamondShape else CircleShape)
            .background(colors.content),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = glyph,
            style = TextStyle(
                color = colors.background,
                // "SIR" has to fit where one letter usually sits.
                fontSize = (diameter.value * if (glyph.length >= 3) 0.34f else 0.5f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * The lines at a station. Favorite lines come first and carry a small star. With
 * [onRouteClick] each bullet can be tapped (the station page uses that to set favorites).
 */
@Composable
fun RouteBadgeRow(
    routes: List<String>,
    modifier: Modifier = Modifier,
    diameter: Dp = 22.dp,
    favorites: Set<String> = emptySet(),
    onRouteClick: ((String) -> Unit)? = null,
) {
    val ordered = routes.sortedByDescending { it in favorites }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ordered.forEach { route ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .let { if (onRouteClick != null) it.lightClickable { onRouteClick(route) } else it },
            ) {
                RouteBadge(route, diameter = diameter)
                if (route in favorites) {
                    StarMark(
                        color = LightThemeTokens.colors.contentSecondary,
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
    else -> "$minutes min"
}

/** "400 ft" up close, "0.3 mi" beyond that. */
fun distanceLabel(meters: Double): String {
    val feet = meters * 3.28084
    return if (feet < 1000) "${(feet / 50).toInt() * 50} ft" else "%.1f mi".format(Locale.US, meters / 1609.34)
}

fun clockLabel(epochSeconds: Long): String =
    SimpleDateFormat("h:mm a", Locale.US).format(Date(epochSeconds * 1000))

/**
 * A station as it appears on the home screen and at the top of the nearby list: its name,
 * its lines, and the next trains each way in two columns.
 * Trains on a favorite line carry a small star.
 */
@Composable
fun StationBoard(
    board: Board,
    nowSeconds: Long,
    favorites: Set<String>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val station = board.station
    Column(
        modifier = modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
            .padding(bottom = 1.5f.gridUnitsAsDp()),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            LightText(
                text = station.name,
                variant = LightTextVariant.Heading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                LightText(
                    text = trailing,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(start = 8.dp, bottom = 3.dp),
                )
            }
        }
        RouteBadgeRow(
            routes = station.routes,
            favorites = favorites,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
        )

        val groups = board.groups(nowSeconds)
        when {
            board.failed -> Note("Times unavailable. Check your connection.")
            board.loading && board.arrivals.isEmpty() -> Note("Loading…")
            groups.isEmpty() -> Note("No trains due right now.")
            else -> FadeIn {
                Column(modifier = Modifier.dimWhileRefreshing()) {
                    // Two headings side by side. A transfer station with a third or fourth
                    // heading gets a second row instead of squeezing them under the wrong title.
                    groups.chunked(2).forEachIndexed { row, pair ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (row == 0) 0.dp else 0.5f.gridUnitsAsDp()),
                        ) {
                            DirectionColumn(pair[0], nowSeconds, favorites, Modifier.weight(1f))
                            Spacer(Modifier.width(16.dp))
                            if (pair.size > 1) {
                                DirectionColumn(pair[1], nowSeconds, favorites, Modifier.weight(1f))
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (board.stale) StaleNote(board)
                }
            }
        }
    }
}

@Composable
private fun DirectionColumn(
    group: Board.Group,
    nowSeconds: Long,
    favorites: Set<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        LightText(
            text = group.heading,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )
        group.arrivals.take(4).forEach { arrival ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 0.15f.gridUnitsAsDp()),
            ) {
                RouteBadge(arrival.route, diameter = 20.dp)
                if (Routes.plain(arrival.route) in favorites) FavoriteStar(20.dp)
                Spacer(Modifier.weight(1f))
                LightText(text = minutesLabel(arrival.minutesFrom(nowSeconds)), variant = LightTextVariant.Copy)
            }
        }
    }
}

/** One train on the station page: its line, where it ends, and how long until it comes. */
@Composable
fun ArrivalRow(arrival: Arrival, nowSeconds: Long, favorite: Boolean, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        RouteBadge(arrival.route)
        if (favorite) FavoriteStar(26.dp)
        LightText(
            // Not repeated when the heading above already is the last stop.
            text = if (Directions.shortName(arrival.destination) == arrival.heading || arrival.destination == arrival.heading) "" else arrival.destination,
            variant = LightTextVariant.Copy,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        )
        LightText(text = minutesLabel(arrival.minutesFrom(nowSeconds)), variant = LightTextVariant.Copy)
    }
}

/** The mark beside a train on a favorite line. */
@Composable
private fun FavoriteStar(badge: Dp) {
    StarMark(
        color = LightThemeTokens.colors.contentSecondary,
        size = (badge.value * 0.5f).dp,
        modifier = Modifier.padding(start = 2.dp),
    )
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    LightText(text = text, variant = LightTextVariant.Detail, lighten = true, modifier = modifier)
}

/** Shown under times that could not be refreshed, so old numbers are never passed off as live. */
@Composable
fun StaleNote(board: Board) {
    Note(
        text = "Not live. Last updated ${clockLabel(board.asOf)}.",
        modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
    )
}
