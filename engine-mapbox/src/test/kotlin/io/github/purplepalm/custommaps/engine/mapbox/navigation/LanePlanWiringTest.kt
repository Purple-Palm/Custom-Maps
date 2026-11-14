package io.github.purplepalm.custommaps.engine.mapbox.navigation

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the engine hands the lane plan of a route to the card and the run-up: the plan's one lookup for each progress update,
 * the card's run-up row read from the plan in the snapshot, and the seam and the ramp the lane choices' latch asks the plan
 * for. The engine needs the navigator and its routes, so these statements are pinned by its source ([SourceText]), as the
 * map's measuring calls are; what they do is CardPlanRowTest's, LanePlansTest's and LaneChoicesTest's.
 */
class LanePlanWiringTest {

    private val engine = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/navigation/MapboxNavigationEngine.kt").readText()

    private val observer = "private val routeProgressObserver = RouteProgressObserver { progress ->"

    @Test
    fun `every progress update moves the run-up and looks the plan up before the snapshot reads the card's row`() {
        val run = "if (active) laneChoices.runUp(progress, lastMatch?.enhancedLocation?.speed, nowSeconds())"
        val lookUp = "if (active) lookUpPlan(progress)"
        assertTrue(SourceText.hasStatement(engine, observer, run), run)
        assertTrue(SourceText.hasStatement(engine, observer, lookUp), lookUp)
        val body = SourceText.body(engine, observer).map { it.trim() }
        val snapshot = body.indexOf("next != null -> snapshotOf(progress, next)")
        assertTrue(snapshot >= 0, "the snapshot is made of the update")
        assertTrue(body.indexOf(run) in 0 until body.indexOf(lookUp), "the run-up moves first")
        assertTrue(body.indexOf(lookUp) < snapshot, "the plan is looked up before the snapshot")
    }

    @Test
    fun `the snapshot's run-up row is the lane choices' row of the update read from the plan`() {
        assertTrue(
            SourceText.hasStatement(
                engine,
                "private fun snapshotOf(progress: RouteProgress, next: MapboxManeuver): RouteProgressSnapshot",
                "val runUp = runUpRowOf(progress, laneChoices.runUp(progress, speed, nowSeconds()))",
            ),
        )
        val row = SourceText.body(engine, "private fun runUpRowOf(progress: RouteProgress, rule: RunUpLanes?): RunUpLanes?").map { it.trim() }
        for (line in listOf(
            "val held = lanePlanHeld?.takeIf { it.routeId == progress.navigationRoute.id }",
            "val coming = held == null && lanePlans.answerFor(progress.navigationRoute.id) == null",
            "cardPlanRow.rowFrom(rule, held, lanePlanStation, lanePlanAtCar, coming)",
        )) {
            assertTrue(line in row, line)
        }
        // The lookup keeps what the row reads: the plan held for the update's route, the car's station and the plan there.
        val lookUp = SourceText.body(engine, "private fun lookUpPlan(progress: RouteProgress)").map { it.trim() }
        for (line in listOf("lanePlanHeld = held", "lanePlanStation = car", "lanePlanAtCar = held.at(car)")) assertTrue(line in lookUp, line)
    }

    @Test
    fun `the snapshot's own lanes at a street maneuver are the SDK's read from the plan of the update's route`() {
        val snapshot = SourceText.body(engine, "private fun snapshotOf(progress: RouteProgress, next: MapboxManeuver): RouteProgressSnapshot").map { it.trim() }
        for (line in listOf("val lanes = streetRowOf(progress, told)", "if (lanes === told.lanes) told else told.copy(lanes = lanes)")) {
            assertTrue(line in snapshot, line)
        }
        val row = SourceText.body(engine, "private fun streetRowOf(progress: RouteProgress, snapshot: RouteProgressSnapshot): LaneInfo?").map { it.trim() }
        assertTrue(
            "cardStreetRow.rowFrom(sdk, lanePlanHeld?.takeIf { it.routeId == progress.navigationRoute.id }, snapshot.stepKey)" in row,
            "$row",
        )
        // Every drive starts the row over.
        val forget = SourceText.body(engine, "private fun forgetLaneAgree()").map { it.trim() }
        assertTrue("cardStreetRow.reset()" in forget && "cardStreetFailed = false" in forget)
    }

    @Test
    fun `the lane choices' latch asks the plan of the update's route for the seam and the ramp`() {
        val arguments = SourceText.body(engine, "internal val laneChoices = LaneChoices(").map { it.trim() }
        for (line in listOf(
            "rampOf = { routeId, exitKey -> lanePlans.planFor(routeId)?.rampAfter(exitKey) },",
            "seamOf = { routeId, exitKey -> lanePlans.planFor(routeId)?.seamBefore(exitKey) },",
        )) {
            assertTrue(line in arguments, line)
        }
    }
}
