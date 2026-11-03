package io.github.purplepalm.custommaps.ui.map

import io.github.purplepalm.custommaps.nav.NavigationEngine
import io.github.purplepalm.custommaps.nav.RouteRequestResult
import io.github.purplepalm.custommaps.nav.SearchProvider
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.guidance.StopReason
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.GuidanceState
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.PlaceSuggestion
import io.github.purplepalm.custommaps.nav.model.RouteOption
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.settings.AppSettings
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val place = Place("Blue Bottle Coffee", "1 Ferry Building, San Francisco, CA", GeoPoint(37.7955, -122.3937))
    private val suggestion = PlaceSuggestion("s1", "Blue Bottle Coffee", "Ferry Building", null)

    private lateinit var navigation: FakeNavigationEngine
    private lateinit var search: FakeSearchProvider
    private lateinit var settings: FakeSettings

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        navigation = FakeNavigationEngine()
        search = FakeSearchProvider(listOf(suggestion), place)
        settings = FakeSettings(simulate = false)
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
    fun `picking a suggestion resolves the place without searching again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onSearchTextChange("blue")
        advanceUntilIdle()
        viewModel.onSuggestionPicked(suggestion)
        advanceUntilIdle()
        assertEquals(place, viewModel.state.value.selectedPlace)
        assertEquals("Blue Bottle Coffee", viewModel.state.value.searchText)
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
    fun `start follows the simulated drive setting`() = runTest(dispatcher) {
        settings.simulateDrive.value = true
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onStart()
        assertEquals(true, navigation.startedSimulated)
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

    private class FakeNavigationEngine : NavigationEngine {
        override val guidanceState = MutableStateFlow(GuidanceState.IDLE)
        override val routeOptions = MutableStateFlow<List<RouteOption>>(emptyList())
        override val routeProgress = MutableStateFlow<RouteProgressSnapshot?>(null)
        override val destination = MutableStateFlow<Place?>(null)

        var previewResult: RouteRequestResult = RouteRequestResult.Success
        val previewed = mutableListOf<Place>()
        var startedSimulated: Boolean? = null
        val stops = mutableListOf<StopReason>()

        override suspend fun previewRoutes(destination: Place): RouteRequestResult {
            previewed += destination
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
            guidanceState.value = GuidanceState.IDLE
        }

        override fun clearPreview() {
            guidanceState.value = GuidanceState.IDLE
        }

        override fun dismissArrival() {
            guidanceState.value = GuidanceState.IDLE
        }
    }

    private class FakeSearchProvider(
        private val suggestions: List<PlaceSuggestion>,
        private val place: Place?,
    ) : SearchProvider {
        val queries = mutableListOf<String>()

        override suspend fun suggest(query: String): List<PlaceSuggestion> {
            queries += query
            return suggestions
        }

        override suspend fun resolve(suggestion: PlaceSuggestion): Place? = place
    }

    private class FakeSettings(simulate: Boolean) : AppSettings {
        override val simulateDrive = MutableStateFlow(simulate)

        override suspend fun setSimulateDrive(enabled: Boolean) {
            simulateDrive.value = enabled
        }
    }
}
