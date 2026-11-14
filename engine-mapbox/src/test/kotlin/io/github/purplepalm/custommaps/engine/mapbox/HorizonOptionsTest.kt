package io.github.purplepalm.custommaps.engine.mapbox

import com.mapbox.navigation.base.options.AlertServiceOptions
import com.mapbox.navigation.base.options.EHorizonOptions
import io.github.purplepalm.custommaps.engine.mapbox.map.SourceText
import io.github.purplepalm.custommaps.nav.lanes.HorizonAhead
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The electronic horizon's options (the own lanes' OL9): what the navigator is given at its setup, once, and that no other
 * place of the engine sets them or makes a navigator anew.
 */
class HorizonOptionsTest {

    @Test
    fun `the horizon reaches 3,500 m along the most probable path only, at most once a second, without enhanced data`() {
        val options = MapboxEngine.horizonOptions()
        val defaults = EHorizonOptions.Builder().build()

        assertEquals(HorizonAhead.HORIZON_AHEAD_M, options.length)
        assertEquals(3_500.0, options.length)
        assertTrue(options.length > defaults.length, "the SDK's default would not reach a drawing window")
        assertEquals(0, options.expansion)
        assertEquals(0, MapboxEngine.HORIZON_EXPANSION)
        assertEquals(1.0, options.minTimeDeltaBetweenUpdates)
        assertEquals(null, defaults.minTimeDeltaBetweenUpdates, "the SDK's default updates at every status")
        // At the SDK's defaults: no enhanced (ADAS) data, the road objects it collects anyway, the branches' length unused.
        assertFalse(options.enableEnhancedDataAlongEH)
        assertEquals(defaults.enableEnhancedDataAlongEH, options.enableEnhancedDataAlongEH)
        assertEquals(AlertServiceOptions.Builder().build(), options.alertServiceOptions)
        assertEquals(defaults.alertServiceOptions, options.alertServiceOptions)
        assertEquals(defaults.branchLength, options.branchLength)
    }

    private val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the options are set once, with the navigator's setup, and nothing else sets them or makes a navigator`() {
        val engine = SourceText.code(File("src/main/kotlin/io/github/purplepalm/custommaps/engine/mapbox/MapboxEngine.kt").readText())
        val setup = engine.substringAfter("MapboxNavigationApp.setup(").substringBefore("return MapboxEngine(")
        assertEquals(1, Regex("""\.eHorizonOptions\(horizonOptions\(\)\)""").findAll(setup).count())
        for (file in sources) {
            val code = SourceText.code(file.readText())
            val sets = Regex("""\.eHorizonOptions\(""").findAll(code).count()
            assertEquals(if (file.name == "MapboxEngine.kt") 1 else 0, sets, file.name)
            assertFalse("MapboxNavigation(" in code.replace("MapboxNavigationApp", ""), "${file.name} makes a navigator")
            // The navigator's own options, not those of its predictive cache.
            val builders = Regex("""(?<![A-Za-z])(NavigationOptions|EHorizonOptions)\.Builder\(""").findAll(code).count()
            assertEquals(if (file.name == "MapboxEngine.kt") 2 else 0, builders, file.name)
        }
    }

    @Test
    fun `only the engine's horizon reader registers and unregisters the horizon's observer`() {
        for (file in sources) {
            val code = SourceText.code(file.readText())
            val registers = Regex("""registerEHorizonObserver\(""").findAll(code).count()
            val unregisters = Regex("""unregisterEHorizonObserver\(""").findAll(code).count()
            val expected = if (file.name == "MapboxNavigationEngine.kt") 1 else 0
            // "unregister..." holds "register..." too.
            assertEquals(expected, unregisters, file.name)
            assertEquals(expected * 2, registers, file.name)
        }
    }
}
