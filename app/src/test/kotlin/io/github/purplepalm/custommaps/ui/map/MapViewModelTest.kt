package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.nav.AccessDeniedException
import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.RouteRequestResult
import io.github.purplepalm.custommaps.nav.SearchProvider
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.PlaceSuggestion
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import io.github.purplepalm.custommaps.settings.FakeAppSettings
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val place = Place("Oak Cafe", "1 Oak Street", GeoPoint(0.0100, 0.0200))
    private val suggestion = PlaceSuggestion("s1", "Oak Cafe", "Oak Street", null)

    private lateinit var navigation: FakeNavigationEngine
    private lateinit var search: FakeSearchProvider
    private lateinit var settings: FakeAppSettings

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        navigation = FakeNavigationEngine()
        search = FakeSearchProvider(listOf(suggestion), place)
        settings = FakeAppSettings()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel() = MapViewModel(navigation, search, settings, DebugLog.NONE).also { runCurrent() }

    @Test
    fun `typing searches once after a short pause`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSearchTextChange("bl")
        viewModel.onSearchTextChange("blu")
        viewModel.onSearchTextChange("blue")
        advanceTimeBy(299)
        assertEquals(emptyList<String>(), search.queries)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("blue"), search.queries)
        assertEquals(listOf(suggestion), viewModel.state.value.suggestions)
    }

    @Test
    fun `a single character does not search`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSearchTextChange("b")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), search.queries)
    }

    @Test
    fun `the same text searches again after a failed search`() = runTest(dispatcher) {
        val viewModel = viewModel()
        search.failNext = true
        viewModel.onSearchTextChange("blue")
        advanceTimeBy(301)
        runCurrent()
        assertEquals(UserMessage.SEARCH_FAILED, viewModel.state.value.message)
        viewModel.onMessageShown()

        viewModel.onSearchTextChange("blu")
        viewModel.onSearchTextChange("blue")
        advanceTimeBy(301)
        runCurrent()

        assertEquals(listOf("blue", "blue"), search.queries)
        assertEquals(listOf(suggestion), viewModel.state.value.suggestions)
    }

    @Test
    fun `clearing the search cancels a search still running`() = runTest(dispatcher) {
        val viewModel = viewModel()
        search.suggestGate = CompletableDeferred()
        viewModel.onSearchTextChange("blue")
        advanceTimeBy(301)
        runCurrent()
        viewModel.clearSearch()
        runCurrent()
        search.suggestGate?.complete(Unit)
        runCurrent()
        assertEquals(emptyList<PlaceSuggestion>(), viewModel.state.value.suggestions)
        assertEquals(false, viewModel.state.value.searching)
    }

    @Test
    fun `picking a suggestion resolves the place without searching again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSearchTextChange("blue")
        advanceUntilIdle()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        assertEquals(place, viewModel.state.value.selectedPlace)
        assertEquals("Oak Cafe", viewModel.state.value.searchText)
        assertEquals(listOf("blue"), search.queries)
    }

    @Test
    fun `directions open the route preview`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        viewModel.onDirections()
        advanceUntilIdle()
        assertEquals(listOf(place), navigation.previewed)
        assertEquals(GuidanceState.PREVIEW, viewModel.state.value.guidance)
    }

    @Test
    fun `closing the preview goes back to the place`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        viewModel.onDirections()
        advanceUntilIdle()
        viewModel.onCancelPreview()
        advanceUntilIdle()
        assertEquals(GuidanceState.IDLE, viewModel.state.value.guidance)
        assertEquals(place, viewModel.state.value.selectedPlace)
    }

    @Test
    fun `an unknown position is reported and keeps the place`() = runTest(dispatcher) {
        navigation.previewResult = RouteRequestResult.NoLocation
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        viewModel.onDirections()
        advanceUntilIdle()
        assertEquals(UserMessage.NO_LOCATION, viewModel.state.value.message)
        assertEquals(place, viewModel.state.value.selectedPlace)
    }

    @Test
    fun `a refused token is reported as such for routes`() = runTest(dispatcher) {
        navigation.previewResult = RouteRequestResult.AccessDenied
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        viewModel.onDirections()
        advanceUntilIdle()
        assertEquals(UserMessage.TOKEN_REJECTED, viewModel.state.value.message)
    }

    @Test
    fun `a refused token is reported as such for search`() = runTest(dispatcher) {
        search.refuseToken = true
        val viewModel = viewModel()
        viewModel.onSearchTextChange("blue")
        advanceUntilIdle()
        assertEquals(UserMessage.TOKEN_REJECTED, viewModel.state.value.message)
        viewModel.onMessageShown()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        assertEquals(UserMessage.TOKEN_REJECTED, viewModel.state.value.message)
    }

    @Test
    fun `start follows the simulated drive setting`() = runTest(dispatcher) {
        settings.simulateDrive.value = true
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onStart()
        assertEquals(true, navigation.startedSimulated)
    }

    @Test
    fun `a place found after the driver cleared the search is not shown`() = runTest(dispatcher) {
        val viewModel = viewModel()
        search.resolveGate = CompletableDeferred()
        viewModel.onSuggestionPicked(suggestion)
        runCurrent()
        viewModel.clearSearch()
        search.resolveGate?.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.state.value.selectedPlace)
        assertEquals(false, viewModel.state.value.busy)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `only the latest pick is kept`() = runTest(dispatcher) {
        val other = Place("Pine Cafe", "2 Pine Street", GeoPoint(0.0300, 0.0100))
        val viewModel = viewModel()
        search.resolveGate = CompletableDeferred()
        viewModel.onSuggestionPicked(suggestion)
        runCurrent()
        val slowGate = search.resolveGate
        search.resolveGate = null
        search.place = other
        viewModel.onSuggestionPicked(PlaceSuggestion("s2", "Pine Cafe", "Pine Street", null))
        runCurrent()
        search.place = place
        slowGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(other, viewModel.state.value.selectedPlace)
    }

    @Test
    fun `routes that arrive after the place was closed do not open the preview`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        navigation.previewGate = CompletableDeferred()
        viewModel.onDirections()
        runCurrent()
        viewModel.clearSearch()
        navigation.previewGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(GuidanceState.IDLE, viewModel.state.value.guidance)
        assertEquals(false, viewModel.state.value.busy)
    }

    @Test
    fun `a place picked right after arriving is shown`() = runTest(dispatcher) {
        navigation.guidanceState.value = GuidanceState.ARRIVED
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        assertEquals(GuidanceState.IDLE, viewModel.state.value.guidance)
        assertEquals(place, viewModel.state.value.selectedPlace)
    }

    @Test
    fun `a start the navigator refuses is reported`() = runTest(dispatcher) {
        val viewModel = viewModel()
        navigation.guidanceState.value = GuidanceState.PREVIEW
        runCurrent()
        viewModel.onStart()
        runCurrent()
        navigation.guidanceState.value = GuidanceState.PREVIEW
        runCurrent()
        assertEquals(UserMessage.START_FAILED, viewModel.state.value.message)
    }

    @Test
    fun `closing the preview is not reported as a failed start`() = runTest(dispatcher) {
        val viewModel = viewModel()
        navigation.guidanceState.value = GuidanceState.PREVIEW
        runCurrent()
        viewModel.onCancelPreview()
        runCurrent()
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `guidance progress is handed on without a new screen state`() = runTest(dispatcher) {
        val viewModel = viewModel()
        navigation.guidanceState.value = GuidanceState.ACTIVE
        runCurrent()
        val screen = viewModel.state.value
        assertNull(viewModel.progress.value)
        val progress = RouteProgressSnapshot(
            nextManeuver = Maneuver(ManeuverType.TURN, TurnModifier.LEFT, "Oak Street"),
            distanceToNextManeuver = Distance.meters(300.0),
            lanes = null,
            distanceRemaining = Distance.meters(5_000.0),
            durationRemaining = 10.minutes,
        )
        navigation.routeProgress.value = progress
        runCurrent()
        assertSame(progress, viewModel.progress.value)
        // The very same state: what reads it has nothing to do on a progress update.
        assertSame(screen, viewModel.state.value)
    }

    @Test
    fun `ending guidance is a user cancel and clears the search`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        viewModel.onEnd()
        advanceUntilIdle()
        assertEquals(listOf(StopReason.USER_CANCELLED), navigation.stops)
        assertNull(viewModel.state.value.selectedPlace)
        assertEquals("", viewModel.state.value.searchText)
    }

    @Test
    fun `the pin is on the picked place, through the routes and the drive, and goes when guidance ends`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertNull(viewModel.state.value.pinnedPlace)
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        assertEquals(place, viewModel.state.value.pinnedPlace)
        viewModel.onDirections()
        advanceUntilIdle()
        assertEquals(place, viewModel.state.value.pinnedPlace)
        viewModel.onStart()
        advanceUntilIdle()
        assertEquals(GuidanceState.ACTIVE, viewModel.state.value.guidance)
        assertEquals(place, viewModel.state.value.pinnedPlace)
        viewModel.onEnd()
        advanceUntilIdle()
        assertNull(viewModel.state.value.pinnedPlace)
    }

    @Test
    fun `closing the preview leaves the pin with the place card, and closing the card takes it away`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        viewModel.onDirections()
        advanceUntilIdle()
        viewModel.onCancelPreview()
        advanceUntilIdle()
        assertNull(viewModel.state.value.destination)
        assertEquals(place, viewModel.state.value.pinnedPlace)
        viewModel.clearSearch()
        advanceUntilIdle()
        assertNull(viewModel.state.value.pinnedPlace)
    }

    @Test
    fun `the pin stays at the arrival and goes when the arrival is dismissed`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        viewModel.onDirections()
        advanceUntilIdle()
        viewModel.onStart()
        navigation.guidanceState.value = GuidanceState.ARRIVED
        advanceUntilIdle()
        assertEquals(place, viewModel.state.value.pinnedPlace)
        viewModel.onDismissArrival()
        advanceUntilIdle()
        assertNull(viewModel.state.value.pinnedPlace)
    }

    @Test
    fun `a screen opened during a drive puts the pin on the drive's destination`() = runTest(dispatcher) {
        // The drive went on without a screen. The new screen has picked no place of its own.
        navigation.destination.value = place
        navigation.guidanceState.value = GuidanceState.ACTIVE
        val viewModel = viewModel()

        assertNull(viewModel.state.value.selectedPlace)
        assertEquals(place, viewModel.state.value.pinnedPlace)
    }

    @Test
    fun `the pin stays on the place reached while the next search is typed or cleared`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        viewModel.onDirections()
        advanceUntilIdle()
        viewModel.onStart()
        navigation.guidanceState.value = GuidanceState.ARRIVED
        advanceUntilIdle()

        // The search bar shows at the arrival; typing in it forgets the picked place.
        viewModel.onSearchTextChange("pi")
        runCurrent()
        assertNull(viewModel.state.value.selectedPlace)
        assertEquals(place, viewModel.state.value.pinnedPlace)
        viewModel.clearSearch()
        runCurrent()
        assertNull(viewModel.state.value.selectedPlace)
        assertEquals(place, viewModel.state.value.pinnedPlace)

        viewModel.onDismissArrival()
        advanceUntilIdle()
        assertNull(viewModel.state.value.pinnedPlace)
    }

    @Test
    fun `typing a new search takes the pin off the picked place`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        viewModel.onSearchTextChange("pine")
        runCurrent()
        assertNull(viewModel.state.value.pinnedPlace)
    }

    private class FakeNavigationEngine : NavigationEngine {
        override val guidanceState = MutableStateFlow(GuidanceState.IDLE)
        override val routeOptions = MutableStateFlow<List<RouteOption>>(emptyList())
        override val routeProgress = MutableStateFlow<RouteProgressSnapshot?>(null)
        override val destination = MutableStateFlow<Place?>(null)

        var previewResult: RouteRequestResult = RouteRequestResult.Success
        var previewGate: CompletableDeferred<Unit>? = null
        val previewed = mutableListOf<Place>()
        var startedSimulated: Boolean? = null
        val stops = mutableListOf<StopReason>()

        override suspend fun previewRoutes(destination: Place): RouteRequestResult {
            previewed += destination
            previewGate?.await()
            if (previewResult == RouteRequestResult.Success) {
                guidanceState.value = GuidanceState.PREVIEW
                this.destination.value = destination
            }
            return previewResult
        }

        override fun selectRoute(routeId: String) = Unit

        override fun startGuidance(simulated: Boolean) {
            startedSimulated = simulated
            guidanceState.value = GuidanceState.ACTIVE
        }

        override fun stopGuidance(reason: StopReason) {
            stops += reason
            goIdle()
        }

        override fun clearPreview() = goIdle()

        override fun dismissArrival() = goIdle()

        /** As in the engine: an idle navigator has no destination. */
        private fun goIdle() {
            guidanceState.value = GuidanceState.IDLE
            destination.value = null
        }
    }

    private class FakeSearchProvider(
        private val suggestions: List<PlaceSuggestion>,
        var place: Place?,
    ) : SearchProvider {
        val queries = mutableListOf<String>()
        var resolveGate: CompletableDeferred<Unit>? = null
        var suggestGate: CompletableDeferred<Unit>? = null
        var failNext = false
        var refuseToken = false

        override suspend fun suggest(query: String): List<PlaceSuggestion> {
            queries += query
            suggestGate?.await()
            if (refuseToken) throw AccessDeniedException("Unauthorized")
            if (failNext) {
                failNext = false
                throw IOException("Network is unreachable")
            }
            return suggestions
        }

        override suspend fun resolve(suggestion: PlaceSuggestion): Place? {
            if (refuseToken) throw AccessDeniedException("Unauthorized")
            val result = place
            resolveGate?.await()
            return result
        }
    }
}
