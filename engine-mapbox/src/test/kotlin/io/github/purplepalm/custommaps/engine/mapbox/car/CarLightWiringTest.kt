package io.github.purplepalm.custommaps.engine.mapbox.car

import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Where the car's lighter map while the phone is hot ([io.github.purplepalm.custommaps.nav.car.CarLightRule]) reaches the
 * basemap: the car map hands what the rule says to the scene of the surface there is and of every later one, and waits
 * for the end of a cool phone's two minutes; the scene composes the light state into every look it writes, a switch's and
 * a style load's alike, and writes the 3D objects alone when it changes. The rule is CarLightRuleTest's, the composition
 * MapLookTest's and LookApplierTest's; the scene needs a map and the car map a car's surface, so these statements are
 * pinned by their source ([SourceText]), as the measuring's calls are.
 */
class CarLightWiringTest {

    private val scene = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/map/MapScene.kt").readText()
    private val car = File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/car/CarNavigationMap.kt").readText()

    private fun body(text: String, declaration: String) = SourceText.body(text, declaration).map { it.trim() }

    @Test
    fun `every look the scene writes on the basemap takes the light state with it`() {
        val write = "MapLook.write(basemap, writes.basemap, writes.theme, styleSnapshot, lookApplier.light)"
        assertTrue(write in body(scene, "private fun applyLook(writes: LookApplier.Writes)"), write)
        // The one write of a look in the map's code: a look switch, a style's data and a style load all come through it.
        val main = File("src/main/kotlin")
        val writes = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> SourceText.code(file.readText()).lines().filter { "MapLook.write(" in it }.map { file.name to it.trim() } }
            .toList()
        assertEquals(listOf("MapScene.kt" to write), writes)
        val through = listOf(
            "override fun setLook(request: LookOnMap, onSettled: (idleMs: Long?) -> Unit): LookApplied" to "val written = applyLook(writes)",
            "private fun onStyleData()" to "styleWritten = applyLook(lookApplier.onStyleData())",
            "mapboxMap.loadStyle(Style.STANDARD) { loaded ->" to "applyLook(lookApplier.onStyleLoaded())",
        )
        for ((function, call) in through) assertTrue(SourceText.hasStatement(scene, function, call), "$function: $call")
    }

    @Test
    fun `a change of the light state writes the 3D objects at once, with the look the basemap shows`() {
        val set = "fun setLight(light: Boolean)"
        assertTrue(SourceText.hasStatement(scene, set, "val look = lookApplier.setLight(light) ?: return"))
        assertTrue(SourceText.hasStatement(scene, set, "MapLook.writeObjects(basemap, look, light)?.let { error ->"))
    }

    @Test
    fun `the car map hands the thermal status and the switch to its rule, and each change to the scene with its line`() {
        assertTrue(
            SourceText.hasStatement(
                car,
                "override fun setPhoneThermal(status: ThermalStatus?)",
                "guard(\"setPhoneThermal\") { onLightRule(lightRule.onStatus(status, SystemClock.elapsedRealtime())) }",
            ),
        )
        assertTrue(
            SourceText.hasStatement(
                car,
                "override fun setLightWhenHot(enabled: Boolean)",
                "guard(\"setLightWhenHot\") { onLightRule(lightRule.setEnabled(enabled, SystemClock.elapsedRealtime())) }",
            ),
        )
        val rule = body(car, "private fun onLightRule(changed: Boolean)")
        val changed = rule.indexOf("if (changed) {")
        assertTrue(changed >= 0, "a change")
        assertEquals(listOf("scene?.setLight(lightRule.light)", "log.log(CarLightRule.LINE, lightRule.fields())"), rule.subList(changed + 1, changed + 3))
        // A new surface's scene, before its style's data writes the look.
        assertTrue(SourceText.hasStatement(car, "private fun attach(surface: MapboxCarMapSurface)", "created.setLight(lightRule.light)"))
        // The car's camera line says it every second.
        assertTrue("\"light\" to lightRule.light," in body(car, "override fun cameraFields(): Map<String, Any?>"))
    }

    @Test
    fun `the end of a cool phone's two minutes asks the rule again, once, after the wait`() {
        val function = "private fun onLightRule(changed: Boolean)"
        assertTrue(SourceText.hasStatement(car, function, "val due = lightRule.dueAtMillis"))
        assertTrue(SourceText.hasStatement(car, function, "if (due == CarLightRule.NONE) return"))
        assertTrue(SourceText.hasStatement(car, function, "coolCheck = scope.launch {"))
        val rule = body(car, function)
        val wait = rule.indexOf("delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(0L))")
        val again = rule.indexOf("guard(\"carLight\") { onLightRule(lightRule.onTime(SystemClock.elapsedRealtime())) }")
        assertTrue(wait > rule.indexOf("coolCheck = scope.launch {") && again > wait, "the rule asked again after the wait")
    }
}
