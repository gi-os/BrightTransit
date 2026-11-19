package com.thelightphone.subway

import com.thelightphone.subway.ui.clockLabel
import com.thelightphone.subway.ui.distanceLabel as worded
import com.thelightphone.sdk.shared.LightServiceMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private typealias Fix = LightServiceMethod.GetCurrentLocation.Response
private typealias Home = LightServiceMethod.GetDefaultLocation.Response

private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND

/** A weekday afternoon, in milliseconds. Any moment would do. */
private const val NOW = 1_794_506_045_000L

/** On the platform at Morgan Av, and upstairs at 14 St-Union Sq. */
private const val MORGAN_LAT = 40.70615
private const val MORGAN_LON = -73.93315
private const val UNION_SQ_LAT = 40.73507
private const val UNION_SQ_LON = -73.99042

private fun fix(ageMs: Long = 20 * SECOND, accuracy: Double? = 15.0, nowMs: Long = NOW) =
    Fix(latitude = MORGAN_LAT, longitude = MORGAN_LON, accuracyMeters = accuracy, timestampMs = nowMs - ageMs)

private val unionSq = Home(latitude = UNION_SQ_LAT, longitude = UNION_SQ_LON)

class WhereaboutsTest {
    private fun judge(fix: Fix?, home: Home? = null) = Whereabouts.from(fix, home, NOW)

    @Test
    fun aNewCloseFixIsWhereTheRiderIs() {
        assertEquals(Whereabouts.Here(MORGAN_LAT, MORGAN_LON), judge(fix()))
    }

    @Test
    fun twoMinutesIsTheOldestFixStillTakenAsHere() {
        assertTrue(judge(fix(ageMs = 2 * MINUTE)) is Whereabouts.Here)
        val later = judge(fix(ageMs = 2 * MINUTE + 1))
        assertEquals(Whereabouts.Earlier(MORGAN_LAT, MORGAN_LON, atSeconds = (NOW - 2 * MINUTE - 1) / 1000, close = true), later)
    }

    @Test
    fun aFixFromBeforeTheRideSaysWhenItIsFrom() {
        val boarded = judge(fix(ageMs = 25 * MINUTE))
        assertEquals("Location from ${clockLabel((NOW - 25 * MINUTE) / 1000)}.", basisNote(boarded))
    }

    @Test
    fun aFixOverAnHourOldIsNotUsed() {
        assertTrue(judge(fix(ageMs = 60 * MINUTE)) is Whereabouts.Earlier)
        assertEquals(Whereabouts.Unknown, judge(fix(ageMs = 60 * MINUTE + 1)))
        val home = Whereabouts.DefaultLocation(UNION_SQ_LAT, UNION_SQ_LON)
        assertEquals(home, judge(fix(ageMs = 3 * 60 * MINUTE), unionSq))
    }

    @Test
    fun aFixTwoHundredMetersWideIsTheWidestTakenAsHere() {
        assertTrue(judge(fix(accuracy = 200.0)) is Whereabouts.Here)
        assertEquals(Whereabouts.Roughly(MORGAN_LAT, MORGAN_LON), judge(fix(accuracy = 200.5)))
        assertEquals(Whereabouts.Roughly(MORGAN_LAT, MORGAN_LON), judge(fix(accuracy = 1_400.0)))
    }

    @Test
    fun aFixThatDoesNotSayHowWideItIsIsOnlyRough() {
        assertEquals(Whereabouts.Roughly(MORGAN_LAT, MORGAN_LON), judge(fix(accuracy = null)))
    }

    @Test
    fun aFixThatDoesNotSayWhenItWasTakenIsNotUsed() {
        val undated = Fix(latitude = MORGAN_LAT, longitude = MORGAN_LON, accuracyMeters = 10.0, timestampMs = null)
        assertEquals(Whereabouts.Unknown, judge(undated))
    }

    @Test
    fun oldMattersMoreThanWide() {
        val at = (NOW - 10 * MINUTE) / 1000
        val oldAndWide = Whereabouts.Earlier(MORGAN_LAT, MORGAN_LON, at, close = false)
        assertEquals(oldAndWide, judge(fix(ageMs = 10 * MINUTE, accuracy = 900.0)))
        // One that does not say how wide it is cannot be taken for a tight one either.
        assertEquals(oldAndWide, judge(fix(ageMs = 10 * MINUTE, accuracy = null)))
        assertEquals(oldAndWide.copy(close = true), judge(fix(ageMs = 10 * MINUTE, accuracy = 200.0)))
    }

    @Test
    fun aFixBothOldAndWideSaysBothThings() {
        val at = (NOW - 10 * MINUTE) / 1000
        assertEquals("Location from ${clockLabel(at)}.", basisNote(Whereabouts.Earlier(MORGAN_LAT, MORGAN_LON, at, close = true)))
        assertEquals("Approximate location from ${clockLabel(at)}.", basisNote(Whereabouts.Earlier(MORGAN_LAT, MORGAN_LON, at, close = false)))
    }

    @Test
    fun aFixDatedInTheFutureCountsAsNew() {
        assertTrue(judge(fix(ageMs = -5 * MINUTE)) is Whereabouts.Here)
    }

    @Test
    fun anyFixWorthUsingComesBeforeTheDefaultLocation() {
        assertTrue(judge(fix(), unionSq) is Whereabouts.Here)
        assertTrue(judge(fix(ageMs = 40 * MINUTE), unionSq) is Whereabouts.Earlier)
        assertTrue(judge(fix(accuracy = 2_000.0), unionSq) is Whereabouts.Roughly)
    }

    @Test
    fun withNoFixTheDefaultLocationIsUsedAndSaidToBe() {
        val home = judge(null, unionSq)
        assertEquals(Whereabouts.DefaultLocation(UNION_SQ_LAT, UNION_SQ_LON), home)
        assertEquals("Using your default location.", basisNote(home))
        // LightOS answers with an empty position when it has none.
        assertEquals(home, judge(Fix(), unionSq))
    }

    @Test
    fun halfAPositionIsNoPosition() {
        assertEquals(Whereabouts.Unknown, judge(null))
        assertEquals(Whereabouts.Unknown, judge(Fix(latitude = MORGAN_LAT, timestampMs = NOW)))
        assertEquals(Whereabouts.Unknown, judge(null, Home(longitude = UNION_SQ_LON)))
    }

    @Test
    fun onlyAListThatIsNotFromHereCarriesANote() {
        assertNull(basisNote(Whereabouts.Here(MORGAN_LAT, MORGAN_LON)))
        assertNull(basisNote(Whereabouts.Unknown))
        assertEquals("Approximate location.", basisNote(Whereabouts.Roughly(MORGAN_LAT, MORGAN_LON)))
    }
}

/** The phone, answering from a script. Each poll takes the next answer; the last one repeats. */
private class FakePhone(
    var allowed: Boolean = true,
    var answers: List<Fix?> = listOf(null),
    var home: Home? = null,
) : PhoneLocation {
    val calls = mutableListOf<String>()
    var polls = 0

    /** Set to hold a poll open, the way a slow LightOS would. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun allowed(): Boolean {
        calls += "allowed"
        return allowed
    }

    override suspend fun startListening() {
        calls += "listen"
    }

    override suspend fun latest(): Fix? {
        calls += "latest"
        gate?.await()
        return answers[minOf(polls++, answers.lastIndex)]
    }

    override suspend fun stopListening() {
        calls += "stop"
    }

    override suspend fun default(): Home? {
        calls += "default"
        return home
    }
}

class NearbySearchTest {
    private var now = NOW
    private val waits = mutableListOf<Long>()
    private val phone = FakePhone()

    /** Waiting only moves the clock, so eight seconds of asking take no time at all. */
    private val search = NearbySearch(
        catalog = { Fixtures.catalog },
        phone = phone,
        clock = { now },
        pause = { ms ->
            waits += ms
            now += ms
        },
    )

    private val state get() = search.state.value

    private fun look() = runBlocking { search.look() }

    private fun locate() = runBlocking { search.locate() }

    @Test
    fun aGoodFixIsTakenAtOnce() {
        phone.answers = listOf(fix())
        assertEquals(Whereabouts.Here(MORGAN_LAT, MORGAN_LON), locate())
        assertEquals(listOf("listen", "latest", "stop"), phone.calls)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun itKeepsAskingUntilAnOldFixIsReplacedByANewOne() {
        val before = fix(ageMs = 30 * MINUTE)
        phone.answers = listOf(before, before, fix(ageMs = 0, nowMs = NOW + 2 * SECOND))
        assertTrue(locate() is Whereabouts.Here)
        assertEquals(3, phone.polls)
        assertEquals(listOf(SECOND, SECOND), waits)
        assertEquals("stop", phone.calls.last())
    }

    @Test
    fun afterEightTriesAnOldFixIsUsedAndDated() {
        phone.answers = listOf(fix(ageMs = 30 * MINUTE))
        phone.home = unionSq
        val settled = locate()
        assertEquals(Whereabouts.Earlier(MORGAN_LAT, MORGAN_LON, atSeconds = (NOW - 30 * MINUTE) / 1000, close = true), settled)
        assertEquals(8, phone.polls)
        assertEquals(List(7) { SECOND }, waits)
        // The phone's own fix, however old, comes before the default location.
        assertFalse("default" in phone.calls)
    }

    @Test
    fun aWideFixIsWaitedOnAndThenUsedAsRough() {
        phone.answers = listOf(fix(accuracy = 1_200.0))
        assertEquals(Whereabouts.Roughly(MORGAN_LAT, MORGAN_LON), locate())
        assertEquals(8, phone.polls)
    }

    @Test
    fun aFixThatWasNewGoesStaleWhileWaitingForABetterOne() {
        // 1:55 old and wide on the first try; over two minutes old by the last.
        phone.answers = listOf(fix(ageMs = 115 * SECOND, accuracy = 800.0))
        assertEquals("Morgan Av", look()?.name)
        assertEquals(Whereabouts.Earlier(MORGAN_LAT, MORGAN_LON, atSeconds = (NOW - 115 * SECOND) / 1000, close = false), state.basis)
        // Wide when the search began and wide when it ended: getting older gave it no distances.
        assertTrue(state.stations.all { it.meters == null })
    }

    @Test
    fun withNoFixTheDefaultLocationIsAskedForOnceListeningHasStopped() {
        phone.home = unionSq
        assertEquals(Whereabouts.DefaultLocation(UNION_SQ_LAT, UNION_SQ_LON), locate())
        assertEquals(listOf("listen") + List(8) { "latest" } + listOf("stop", "default"), phone.calls)
    }

    @Test
    fun withNoFixAndNoDefaultLocationNothingIsKnown() {
        assertEquals(Whereabouts.Unknown, locate())
    }

    @Test
    fun anEmptyAnswerDoesNotWipeOutTheFixBeforeIt() {
        phone.answers = listOf(fix(ageMs = 10 * MINUTE), null, Fix())
        assertTrue(locate() is Whereabouts.Earlier)
    }

    @Test
    fun leavingMidSearchStillStopsLightOsListening() = runBlocking {
        phone.gate = CompletableDeferred()
        val leaving = CoroutineScope(Dispatchers.Unconfined).launch { search.locate() }
        assertEquals(listOf("listen", "latest"), phone.calls)
        leaving.cancelAndJoin()
        assertEquals(listOf("listen", "latest", "stop"), phone.calls)
    }

    @Test
    fun withoutPermissionThePhoneIsNotAskedWhereItIs() {
        phone.allowed = false
        assertNull(look())
        assertEquals(Locating.NEEDS_PERMISSION, state.status)
        assertEquals(listOf("allowed"), phone.calls)
    }

    @Test
    fun aGoodFixListsTheClosestStationsWithHowFarEachIs() {
        phone.answers = listOf(fix())
        assertEquals("Morgan Av", look()?.name)
        assertEquals(Locating.FOUND, state.status)
        assertEquals(8, state.stations.size)
        assertEquals("Morgan Av", state.stations.first().station.name)
        val meters = state.stations.map { it.meters ?: error("no distance") }
        assertTrue(meters.first() < 10.0)
        assertEquals(meters.sorted(), meters)
        assertNull(basisNote(state.basis))
    }

    @Test
    fun theDefaultLocationListsStationsButNoWalkingDistances() {
        phone.home = unionSq
        assertEquals("14 St-Union Sq", look()?.name)
        assertEquals(Locating.FOUND, state.status)
        assertEquals(8, state.stations.size)
        assertTrue(state.stations.all { it.meters == null })
        assertEquals("Using your default location.", basisNote(state.basis))
    }

    @Test
    fun aWideFixListsStationsButNoWalkingDistances() {
        phone.answers = listOf(fix(accuracy = 900.0))
        assertEquals("Morgan Av", look()?.name)
        assertTrue(state.stations.all { it.meters == null })
        assertEquals("Approximate location.", basisNote(state.basis))
    }

    @Test
    fun anOldFixKeepsItsDistancesAndSaysWhenItIsFrom() {
        phone.answers = listOf(fix(ageMs = 12 * MINUTE))
        look()
        assertTrue(state.stations.all { it.meters != null })
        assertEquals("Location from ${clockLabel((NOW - 12 * MINUTE) / 1000)}.", basisNote(state.basis))
    }

    @Test
    fun anOldFixThatWasWideOrDoesNotSayListsStationsButNoWalkingDistances() {
        for (accuracy in listOf(900.0, null)) {
            phone.polls = 0
            phone.answers = listOf(fix(ageMs = 12 * MINUTE, accuracy = accuracy))
            assertEquals("Morgan Av", look()?.name)
            assertEquals(8, state.stations.size)
            assertTrue(state.stations.all { it.meters == null }, "accuracy $accuracy")
            assertEquals("Approximate location from ${clockLabel((NOW - 12 * MINUTE) / 1000)}.", basisNote(state.basis))
        }
    }

    @Test
    fun aDistanceIsWordedOnlyWhereThereIsOne() {
        val walk: Double? = 420.0
        assertEquals(worded(420.0), distanceLabel(walk))
        assertNull(distanceLabel(null))
    }

    @Test
    fun thePageStartsOverOnlyWhenTheNearestStationIsAnotherOne() {
        // The first list, and the same list again after a refresh or a visit to a station page.
        assertFalse(startsOver(scrolledOver = null, nearest = "125"))
        assertFalse(startsOver(scrolledOver = "125", nearest = "125"))
        // The rider has moved: the list is the one around another station.
        assertTrue(startsOver(scrolledOver = "125", nearest = "602"))
        // While there is no list, the page it was on is left alone.
        assertFalse(startsOver(scrolledOver = "125", nearest = null))
    }

    @Test
    fun withNothingToGoOnTheScreenSaysItCannotFindTheRider() {
        assertNull(look())
        assertEquals(Locating.NOT_FOUND, state.status)
        assertTrue(state.stations.isEmpty())
    }

    @Test
    fun aListOnScreenStaysUpWhileTheRiderIsLookedForAgain() = runBlocking {
        phone.answers = listOf(fix())
        search.look()
        val listed = state.stations

        phone.gate = CompletableDeferred()
        val again = CoroutineScope(Dispatchers.Unconfined).launch { search.look() }
        assertEquals(Locating.FOUND, state.status)
        assertEquals(listed, state.stations)

        phone.gate?.complete(Unit)
        again.join()
        assertEquals(Locating.FOUND, state.status)
    }

    @Test
    fun onceLocationIsAllowedTheScreenGoesBackToSearching() = runBlocking {
        assertEquals(Locating.SEARCHING, state.status)
        phone.allowed = false
        search.look()
        assertEquals(Locating.NEEDS_PERMISSION, state.status)

        phone.allowed = true
        phone.gate = CompletableDeferred()
        val allowed = CoroutineScope(Dispatchers.Unconfined).launch { search.look() }
        assertEquals(Locating.SEARCHING, state.status)
        allowed.cancelAndJoin()
    }

    @Test
    fun aSearchCutShortStopsListeningBeforeTheNextOneStarts() = runBlocking {
        val unconfined = CoroutineScope(Dispatchers.Unconfined)
        phone.answers = listOf(fix())
        phone.gate = CompletableDeferred()
        val first = unconfined.launch { search.look() }
        val second = unconfined.launch { search.look() }
        // The second waits its turn: nothing of it has reached the phone yet.
        assertEquals(listOf("allowed", "listen", "latest"), phone.calls)

        phone.gate = null
        first.cancelAndJoin()
        second.join()
        assertEquals(
            listOf("allowed", "listen", "latest", "stop", "allowed", "listen", "latest", "stop"),
            phone.calls,
        )
        assertEquals(Locating.FOUND, state.status)
    }
}
