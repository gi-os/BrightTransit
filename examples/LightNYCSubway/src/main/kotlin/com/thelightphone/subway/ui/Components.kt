package com.thelightphone.subway.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import com.thelightphone.subway.data.Board
import com.thelightphone.subway.data.Routes
import com.thelightphone.subway.data.ServiceAlert
import com.thelightphone.subway.data.Station
import com.thelightphone.subway.hw.LocalWheelBus
import com.thelightphone.subway.hw.WheelBus
import com.thelightphone.subway.hw.WheelScroll
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The distances the tool's own layouts are built from, in LightOS grid units (the screen is
 * 27 wide). Everything is placed with these, so it keeps its proportions wherever the kit's
 * own bars and text keep theirs.
 */
private object Space {
    /** The page's left edge, as in Light's own tools. */
    const val GUTTER = 1f

    /** Between one station's board and the next. */
    const val BOARD = 1f

    /** Between a bullet and the words beside it, and between two things on one line. */
    const val BESIDE = 0.5f

    /** Between bullets in a row, and under a row of bullets or a note. */
    const val SMALL = 0.4f

    /** Between a bullet and the time it belongs to. */
    const val HAIR = 0.2f

    /** Above and below a line of times. One line of the kit's text is set with no air of its own. */
    const val LINE = 0.1f

    /** Above and below a line that can be tapped, so a thumb finds it between its neighbours. */
    const val TAP = 0.3f
}

/** A bullet comes in two sizes, in grid units: one under a station's name, one beside a time or a last stop. */
enum class Bullet(val units: Float) {
    Title(1.5f),
    Row(1.25f),
}

/**
 * What a screen keeps for as long as it is on the back stack. A screen is put together
 * again from nothing each time the rider comes back to it, so what they should come back
 * to is held here, outside what is drawn: how far the page was scrolled, and how many
 * lines of times each station's board last had.
 */
class Page {
    val scroll = ScrollState(0)

    /** By station id. A board keeps this much room while it has no times, so nothing under it moves. */
    internal val boardLines = HashMap<String, Int>()

    /**
     * The room, in pixels, the block about getting to a station keeps while its answer is
     * asked for again: how tall it last was when it named a train, or 0 once it has said
     * there is none. Null until it has said either.
     */
    internal var wayRoom: Int? = null
}

private val LocalPage = compositionLocalOf { Page() }

/**
 * The frame every screen in the tool is drawn in: a title bar, the page, and one row of
 * buttons along the bottom. All the actions live in that bottom row and nowhere else, which
 * is how the rest of LightOS does it.
 *
 * With [message] set the page is that text, centred, instead of [content]. That is the
 * blank state: nothing saved yet, nothing found, no connection.
 *
 * With [subtitle] the title bar has two lines, the way a train is named: what it is, and
 * where it was asked about. With [still] the page does not scroll. That is for the one
 * screen made to be read at a glance, where anything pushed off the bottom would be missed.
 */
@Composable
fun SubwayScreen(
    title: String,
    wheel: WheelBus,
    page: Page,
    bottom: List<LightBarButton?>,
    onBack: (() -> Unit)? = null,
    message: String? = null,
    refreshing: Boolean = false,
    subtitle: String? = null,
    still: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val themeColors by LightThemeController.colors.collectAsState()
    CompositionLocalProvider(
        LocalWheelBus provides wheel,
        LocalRefreshing provides refreshing,
        LocalPage provides page,
        LocalOpening provides rememberOpening(),
    ) {
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
                    center = if (subtitle == null) LightTopBarCenter.Text(title) else LightTopBarCenter.TwoLineDetail(title, subtitle),
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
                } else if (still) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = Space.GUTTER.gridUnitsAsDp()),
                        content = content,
                    )
                } else {
                    // The kit's scroll view goes on reading the first scroll state it was
                    // handed. A screen that starts a new page gets a new scroll view with
                    // it, or the bar would stay where the old page was left.
                    key(page) {
                        WheelScroll(page.scroll)
                        LightScrollView(
                            scrollState = page.scroll,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = Space.GUTTER.gridUnitsAsDp()),
                            content = content,
                        )
                    }
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
 * Whether the frame something first appears in is the one its surroundings opened in.
 * LightOS has no transitions: a screen appears whole, and so does everything this tool has
 * in hand when a screen opens or comes back. [over] turns true one frame later, and only
 * what arrives after that is eased in. See [FadeIn].
 */
private class Opening {
    var over = false
}

private val LocalOpening = compositionLocalOf { Opening() }

@Composable
private fun rememberOpening(): Opening {
    val opening = remember { Opening() }
    LaunchedEffect(opening) { withFrameNanos { opening.over = true } }
    return opening
}

/**
 * Eases [content] in over a fifth of a second when it arrives after its screen is already
 * up (train times, an alert, search results), so it settles instead of snapping on. It
 * happens once. What is there when a screen opens, or when the rider comes back to it, is
 * drawn at once, and so is anything that arrives together with something already easing in.
 *
 * This follows the phone's own animation setting. With animations off, which is how Light
 * says to set up its emulator, late content appears at once like everything else.
 */
@Composable
fun FadeIn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val late = LocalOpening.current.over
    val alpha = remember { Animatable(if (late) 0f else 1f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, tween(durationMillis = 200)) }
    CompositionLocalProvider(LocalOpening provides rememberOpening()) {
        Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
    }
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

/**
 * A subway bullet: a filled circle, or a diamond for an express, with the line cut out of
 * it in the phone's own typeface. One colour, so it reads in both the dark and the light theme.
 */
@Composable
fun RouteBadge(route: String, size: Bullet, modifier: Modifier = Modifier) {
    val colors = LightThemeTokens.colors
    val glyph = Routes.glyph(route)
    val diameter = size.units.gridUnitsAsDp()
    // "SIR" has to fit where one letter usually sits.
    val letter = with(LocalDensity.current) { (diameter * if (glyph.length >= 3) 0.34f else 0.5f).toSp() }
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
                fontFamily = LightThemeTokens.typography.copy.fontFamily,
                fontSize = letter,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * The lines at a station, in the station's own order, wrapping onto a second row where
 * there are too many for one. A favorite line carries a small star. With [onRouteClick]
 * each bullet can be tapped (the station page uses that to set favorites), and is given
 * more room around it to be tapped in.
 */
@Composable
fun RouteBadgeRow(
    routes: List<String>,
    modifier: Modifier = Modifier,
    favorites: Set<String> = emptySet(),
    onRouteClick: ((String) -> Unit)? = null,
) {
    val between = Space.SMALL.gridUnitsAsDp()
    val tappable = onRouteClick != null
    FlowRow(
        // Half the space between two bullets belongs to each, so the places to tap leave no
        // gaps. The row is moved left by the first bullet's half, to start on the gutter.
        modifier = modifier.offset(x = -between / 2),
        verticalArrangement = Arrangement.spacedBy(if (tappable) 0f.gridUnitsAsDp() else between),
    ) {
        routes.forEach { route ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .let { if (onRouteClick != null) it.lightClickable { onRouteClick(route) } else it }
                    .padding(horizontal = between / 2, vertical = if (tappable) between / 2 else 0f.gridUnitsAsDp()),
            ) {
                RouteBadge(route, Bullet.Title)
                if (route in favorites) LightIcon(icon = LightIcons.STAR, size = STAR_UNITS, contentDescription = "Favorite")
            }
        }
    }
}

/** The star beside a favorite line, in grid units. */
private const val STAR_UNITS = 0.6f

/** "400 ft" up close, "0.3 mi" beyond that. */
fun distanceLabel(meters: Double): String {
    val feet = meters * 3.28084
    return if (feet < 1000) "${(feet / 50).toInt() * 50} ft" else "%.1f mi".format(Locale.US, meters / 1609.34)
}

/**
 * "11:28 AM", with a space no line can break at: a sentence that runs over never leaves
 * "AM." on a line by itself. The time is the phone's own. [zone] is only for saying what a
 * moment reads as somewhere in particular.
 */
fun clockLabel(epochSeconds: Long, zone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("h:mm\u00A0a", Locale.US).apply { timeZone = zone }.format(Date(epochSeconds * 1000))

/**
 * A station as it appears on the home screen and at the top of the nearby list: its name,
 * its lines, and one line for each way out, with the next three times. It is a glance; the
 * station page has every line and where each train ends.
 *
 * With [alerts], the worst of them gets one line under the bullets; the station page has
 * them all in full.
 */
@Composable
fun StationBoard(
    board: Board,
    nowSeconds: Long,
    favorites: Set<String>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    alerts: List<ServiceAlert> = emptyList(),
) {
    val station = board.station
    val lines = LocalPage.current.boardLines
    Column(
        modifier = modifier
            .fillMaxWidth()
            .lightClickable(onClick = onOpen)
            .padding(bottom = Space.BOARD.gridUnitsAsDp()),
    ) {
        Row {
            LightText(
                text = station.name,
                variant = LightTextVariant.Heading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .alignByBaseline(),
            )
            if (trailing != null) {
                Note(
                    text = trailing,
                    modifier = Modifier
                        .padding(start = Space.BESIDE.gridUnitsAsDp())
                        .alignByBaseline(),
                )
            }
        }
        RouteBadgeRow(
            routes = station.routes,
            favorites = favorites,
            modifier = Modifier.padding(bottom = Space.SMALL.gridUnitsAsDp()),
        )
        if (alerts.isNotEmpty()) AlertLine(alerts, Modifier.padding(bottom = Space.HAIR.gridUnitsAsDp()))

        when (val view = board.view(nowSeconds)) {
            // The sentence stands in the room the times had, or will have, so the boards
            // below this one stay where they are while it loads or has nothing to show.
            // The room is held by empty lines, which are as tall as full ones in any typeface.
            is Board.View.Message -> Box {
                Column { repeat(lines[station.id] ?: 2) { GlanceText("") } }
                Note(view.text)
            }
            is Board.View.Trains -> {
                val headings = view.groups.take(HEADINGS_ON_HOME)
                SideEffect { lines[station.id] = headings.size }
                FadeIn {
                    Column {
                        BoardNotes(view)
                        Column(modifier = Modifier.dimWhileRefreshing()) {
                            headings.forEach { group ->
                                key(group.heading) { GlanceLine(group, station, nowSeconds, favorites, view.stale) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The first alert's headline on one line, cut off where the line ends, and "+2" when two
 * more are waiting on the station page. Quiet on purpose: this is a hint to open the station.
 */
@Composable
private fun AlertLine(alerts: List<ServiceAlert>, modifier: Modifier = Modifier) {
    FadeIn(modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            LightText(
                text = alerts.first().headline,
                variant = LightTextVariant.Detail,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (alerts.size > 1) Note("+${alerts.size - 1}", Modifier.padding(start = Space.BESIDE.gridUnitsAsDp()))
        }
    }
}

/** Text on one of a [StationBoard]'s lines, with the little air a single line of the kit's text needs. */
@Composable
private fun GlanceText(text: String, modifier: Modifier = Modifier, lighten: Boolean = false, overflow: TextOverflow = TextOverflow.Clip) {
    LightText(
        text = text,
        variant = LightTextVariant.Paragraph,
        lighten = lighten,
        maxLines = 1,
        overflow = overflow,
        modifier = modifier.padding(vertical = Space.LINE.gridUnitsAsDp()),
    )
}

/**
 * One way out of a station on one line: its heading, and the next three times. At a
 * station with one line the times stand by themselves, because the bullet under the name
 * has already said which line. Anywhere else each run of times follows its line's bullet.
 * When room runs out it is the heading that is cut short, never a time.
 */
@Composable
private fun GlanceLine(group: Board.Group, station: Station, nowSeconds: Long, favorites: Set<String>, stale: Boolean) {
    val trains = group.glance(favorites)
    val minutes = trains.map { it.minutesFrom(nowSeconds) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        GlanceText(
            text = group.heading,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = Space.BESIDE.gridUnitsAsDp()),
        )
        if (trains.all { it.route == station.routes.singleOrNull() }) {
            GlanceText(text = timesLabel(minutes), lighten = stale)
        } else {
            val words = timeWords(minutes)
            var told = 0
            trains.byLine().forEach { run ->
                RouteBadge(
                    route = run.first().route,
                    size = Bullet.Row,
                    modifier = Modifier.padding(
                        start = if (told == 0) 0f.gridUnitsAsDp() else Space.BESIDE.gridUnitsAsDp(),
                        end = Space.HAIR.gridUnitsAsDp(),
                    ),
                )
                GlanceText(text = words.subList(told, told + run.size).joinToString(", "), lighten = stale)
                told += run.size
            }
        }
    }
}

/**
 * One line to one last stop on the station page: its bullet, where it ends, and the times
 * of its next three trains. A long last stop is cut short; the times never are.
 *
 * A time is drawn lighter when it is not a train on the move: every time on a [stale]
 * board, and on a live one the time of a train that is so far only on the timetable.
 *
 * With [onClick] the whole row can be tapped, and is given a little more height to be
 * tapped in.
 */
@Composable
fun TrainRow(
    row: Board.Row,
    heading: String,
    nowSeconds: Long,
    stale: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LightThemeTokens.colors
    val trains = row.named
    val words = timeWords(trains.map { it.minutesFrom(nowSeconds) })
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.lightClickable(onClick = onClick) else it }
            .padding(vertical = (if (onClick != null) Space.TAP else Space.LINE).gridUnitsAsDp()),
    ) {
        RouteBadge(row.route, Bullet.Row, Modifier.align(Alignment.CenterVertically))
        LightText(
            text = row.lastStop(heading),
            variant = LightTextVariant.Paragraph,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.BESIDE.gridUnitsAsDp())
                .alignByBaseline(),
        )
        BasicText(
            text = buildAnnotatedString {
                trains.forEachIndexed { index, train ->
                    withStyle(SpanStyle(color = if (stale || !train.assigned) colors.contentSecondary else colors.content)) {
                        append(words[index])
                        if (index < trains.lastIndex) append(", ")
                    }
                }
            },
            style = LightThemeTokens.typography.copy.onThisScreen(),
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/**
 * A kit text style at the size [LightText] draws it on this screen. For the one thing
 * LightText cannot set, a line in two tones.
 */
@Composable
private fun TextStyle.onThisScreen(): TextStyle = copy(
    fontSize = fontSize.value.designVerticalPxToSp(),
    lineHeight = lineHeight.value.designVerticalPxToSp(),
)

/**
 * One stop on a train's page: its name, and when the train is due there. A long name is
 * cut short; the time never is. The time is drawn lighter when it is not a moving train's:
 * a timetable time, or one from a list that is no longer live. With no [time] the feed
 * gave none worth showing.
 */
@Composable
fun StopRow(name: String, time: String?, lighten: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = Space.TAP.gridUnitsAsDp()),
    ) {
        LightText(
            text = name,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = Space.BESIDE.gridUnitsAsDp())
                .alignByBaseline(),
        )
        if (time != null) {
            LightText(text = time, variant = LightTextVariant.Copy, lighten = lighten, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
    }
}

/**
 * One train on the way from one station to another: its bullet, the line that says when it
 * leaves, the line that says when it gets there, and under them how to know it on the
 * platform. Nothing here is cut short, so a long name takes a second line.
 *
 * With [onClick] the row can be tapped. On a station's page the whole block the row sits in
 * is tapped instead.
 */
@Composable
fun DirectRow(route: String, words: PlanWords.Row, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.lightClickable(onClick = onClick) else it }
            .padding(vertical = Space.TAP.gridUnitsAsDp()),
    ) {
        RouteBadge(route, Bullet.Title, Modifier.padding(top = Space.HAIR.gridUnitsAsDp()))
        Column(modifier = Modifier.padding(start = Space.BESIDE.gridUnitsAsDp())) {
            MainLine(words.leaves, lighten = !words.sure)
            if (words.arrives != null) MainLine(words.arrives, lighten = !words.sure)
            Note(words.detail)
            if (words.warning != null) Note(words.warning)
        }
    }
}

/**
 * A way there with one change, laid out like [DirectRow]: the first train's bullet beside
 * the line that says when it leaves, the second's beside the line that says when it gets
 * there, then where to change, the time there is to do it, and any warning.
 */
@Composable
fun ChangeRow(first: String, second: String, words: PlanWords.Row, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.lightClickable(onClick = onClick) else it }
            .padding(vertical = Space.TAP.gridUnitsAsDp()),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.HAIR.gridUnitsAsDp())) {
            RouteBadge(first, Bullet.Title, Modifier.padding(top = Space.HAIR.gridUnitsAsDp()))
            RouteBadge(second, Bullet.Title)
        }
        Column(modifier = Modifier.padding(start = Space.BESIDE.gridUnitsAsDp())) {
            MainLine(words.leaves, lighten = !words.sure)
            if (words.arrives != null) MainLine(words.arrives, lighten = !words.sure)
            Note(words.detail)
            if (words.more != null) Note(words.more)
            if (words.warning != null) Note(words.warning)
        }
    }
}

/**
 * One of a train's two main lines, in the kit's Copy type. The kit sets the lines of one
 * text further apart than it stacks two texts, so a line that a platform's long name ran
 * over would sit lower than the line after it. Here it is set at the type's own pitch.
 */
@Composable
private fun MainLine(text: String, lighten: Boolean) {
    val colors = LightThemeTokens.colors
    BasicText(
        text = text,
        style = LightThemeTokens.typography.copy.onThisScreen().copy(
            color = if (lighten) colors.contentSecondary else colors.content,
            lineHeight = TextUnit.Unspecified,
        ),
    )
}

/**
 * A station named in a line of small grey text. With [bullets], its lines and borough
 * follow the name: that is for a station that shares its name with another, which the
 * name alone would not pick out.
 */
@Composable
fun StationLabel(text: String, station: Station, bullets: Boolean, modifier: Modifier = Modifier) {
    val between = Space.SMALL.gridUnitsAsDp()
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(between),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Note(text)
        if (bullets) {
            station.routes.forEach { RouteBadge(it, Bullet.Row) }
            Note(station.boroLabel)
        }
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    LightText(text = text, variant = LightTextVariant.Detail, lighten = true, modifier = modifier)
}

/**
 * What has to be read before the times under it: that they are old, or that some of the
 * station's lines are not among them. It goes above the times, where it cannot be scrolled
 * past, so old numbers are never passed off as live and a short list never as the whole one.
 */
@Composable
fun BoardNotes(trains: Board.View.Trains) {
    val gap = Modifier.padding(bottom = Space.SMALL.gridUnitsAsDp())
    if (trains.stale) Note(notLive(trains.asOf), gap)
    trains.missingNote?.let { Note(it, gap) }
}
