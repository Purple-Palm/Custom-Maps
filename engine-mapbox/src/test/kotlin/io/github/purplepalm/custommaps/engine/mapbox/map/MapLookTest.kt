package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.bindgen.Value
import io.github.purplepalm.custommaps.engine.mapbox.map.MapLook.Kind
import io.github.purplepalm.custommaps.engine.mapbox.map.MapLook.Read
import io.github.purplepalm.custommaps.engine.mapbox.map.MapLook.toValue
import io.github.purplepalm.custommaps.nav.look.Look
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapLookTest {

    /** What the style holds for each key as it loads: a made-up value of its own for each. */
    private val own: Map<String, Read> = MapPalette.keys.associate { it.name to Read(Value.valueOf("own ${it.name}"), Kind.CONSTANT) }

    /**
     * The basemap's config, with every write and read recorded. [onRestore] is what a key holds
     * after it was written with no value, from what it held before.
     */
    private class Basemap(own: Map<String, Read>, private val onRestore: (key: String, before: Read) -> Read) : MapLook.Basemap {
        val held = HashMap(own)
        val writes = ArrayList<Pair<String, Value>>()
        val reads = ArrayList<String>()
        var refused = emptySet<String>()

        override fun write(key: String, value: Value): String? {
            writes += key to value
            if (key in refused) return "refused"
            held[key] = if (value.contents == null) onRestore(key, held.getValue(key)) else Read(value, Kind.CONSTANT)
            return null
        }

        override fun read(key: String): Read {
            reads += key
            return held[key] ?: Read(null, Kind.FAILED)
        }
    }

    /** A style that gives a key written with no value its own value back, as Mapbox's code comments say. */
    private fun restoring() = Basemap(own) { key, _ -> own.getValue(key) }

    private val restoredKeys = MapPalette.keys.filter { it.day == LookValue.Restore }.map { it.name }

    @Test
    fun `a look is written in the table's order, one key a call`() {
        val basemap = restoring()
        val written = MapLook.write(basemap, Look.NIGHT, Look.NIGHT, own, light = false)

        assertEquals(MapPalette.column(Look.NIGHT).map { (key, value) -> key to value.toValue() }, basemap.writes)
        assertEquals(MapLook.Written(keys = 16, errors = 0, failedKeys = emptyList(), reset = null), written)
    }

    @Test
    fun `the car's lighter map writes no 3D objects in either look, and every other key as the look has it`() {
        for (look in listOf(Look.NIGHT, Look.DAY)) {
            val light = restoring()
            val written = MapLook.write(light, look, look, own, light = true)
            val full = restoring()
            MapLook.write(full, look, look, own, light = false)
            assertEquals(Value.valueOf(false), light.writes.toMap()[MapPalette.SHOW_3D_OBJECTS], "$look")
            assertEquals(Value.valueOf(true), full.writes.toMap()[MapPalette.SHOW_3D_OBJECTS], "$look")
            assertEquals(
                full.writes.filter { it.first != MapPalette.SHOW_3D_OBJECTS },
                light.writes.filter { it.first != MapPalette.SHOW_3D_OBJECTS },
                "$look",
            )
            assertEquals(16, written.keys)
        }
    }

    @Test
    fun `no look switch turns the 3D objects on while the car's map is light`() {
        val basemap = restoring()
        val off = Read(Value.valueOf(false), Kind.CONSTANT)
        MapLook.write(basemap, Look.NIGHT, Look.NIGHT, own, light = true)
        assertEquals(off, basemap.held[MapPalette.SHOW_3D_OBJECTS])
        MapLook.write(basemap, Look.DAY, Look.DAY, own, light = true)
        assertEquals(off, basemap.held[MapPalette.SHOW_3D_OBJECTS])
        // A theme that waits: the look without its theme, still without them.
        MapLook.write(basemap, Look.NIGHT, null, own, light = true)
        assertEquals(off, basemap.held[MapPalette.SHOW_3D_OBJECTS])
        // The theme alone leaves them as they are.
        MapLook.write(basemap, null, Look.NIGHT, own, light = false)
        assertEquals(off, basemap.held[MapPalette.SHOW_3D_OBJECTS])
        // The full map again: the look's own.
        MapLook.write(basemap, Look.DAY, Look.DAY, own, light = false)
        assertEquals(Read(Value.valueOf(true), Kind.CONSTANT), basemap.held[MapPalette.SHOW_3D_OBJECTS])
    }

    @Test
    fun `the 3D objects alone are one key, off while light and the look's own after`() {
        for (look in listOf(Look.NIGHT, Look.DAY)) {
            val basemap = restoring()
            assertNull(MapLook.writeObjects(basemap, look, light = true))
            assertNull(MapLook.writeObjects(basemap, look, light = false))
            assertEquals(listOf(MapPalette.SHOW_3D_OBJECTS to Value.valueOf(false), MapPalette.SHOW_3D_OBJECTS to Value.valueOf(true)), basemap.writes)
            assertTrue(basemap.reads.isEmpty())
        }
        val refusing = restoring()
        refusing.refused = setOf(MapPalette.SHOW_3D_OBJECTS)
        assertEquals("refused", MapLook.writeObjects(refusing, Look.DAY, light = true))
    }

    @Test
    fun `the port to the map has no call that writes many keys at once`() {
        assertEquals(setOf("read", "write"), MapLook.Basemap::class.java.declaredMethods.map { it.name }.toSet())
    }

    @Test
    fun `nothing is read at night`() {
        val basemap = restoring()
        MapLook.write(basemap, Look.NIGHT, Look.NIGHT, own, light = false)
        assertTrue(basemap.reads.isEmpty())
    }

    @Test
    fun `a write the map refuses is counted with its key, and the other keys are still written`() {
        val basemap = restoring()
        basemap.refused = setOf("colorWater")
        val written = MapLook.write(basemap, Look.NIGHT, Look.NIGHT, own, light = false)

        assertEquals(16, basemap.writes.size)
        assertEquals(1, written.errors)
        assertEquals(listOf("colorWater"), written.failedKeys)
    }

    @Test
    fun `the theme is written from the theme's own column, or left as it is`() {
        val basemap = restoring()
        MapLook.write(basemap, Look.NIGHT, Look.DAY, own, light = false)
        assertEquals(Value.valueOf("default"), basemap.writes.toMap()[MapLook.THEME])
        assertEquals(Value.valueOf("night"), basemap.writes.toMap()["lightPreset"])

        val withoutTheme = restoring()
        val written = MapLook.write(withoutTheme, Look.DAY, null, own, light = false)
        assertEquals(15, written.keys)
        assertTrue(withoutTheme.writes.none { it.first == MapLook.THEME })

        val themeAlone = restoring()
        assertEquals(1, MapLook.write(themeAlone, null, Look.NIGHT, own, light = false).keys)
        assertEquals(listOf(MapLook.THEME to Value.valueOf("monochrome")), themeAlone.writes)
        assertTrue(themeAlone.reads.isEmpty())
    }

    @Test
    fun `by day a key that reads back as the style had it says null and is written once`() {
        val basemap = restoring()
        MapLook.write(basemap, Look.NIGHT, Look.NIGHT, own, light = false)
        basemap.writes.clear()
        val written = MapLook.write(basemap, Look.DAY, Look.DAY, own, light = false)

        assertEquals(MapLook.RESET_NULL, written.reset)
        assertEquals(restoredKeys, basemap.reads)
        assertEquals(16, basemap.writes.size)
        for (key in restoredKeys) assertEquals(own[key], basemap.held[key], key)
    }

    /** Night first, then day with [basemap]: every key given back reads otherwise, and gets the snapshot. */
    private fun assertSnapshotWrittenBack(basemap: Basemap) {
        MapLook.write(basemap, Look.NIGHT, Look.NIGHT, own, light = false)
        basemap.writes.clear()
        val written = MapLook.write(basemap, Look.DAY, Look.DAY, own, light = false)

        assertEquals(MapLook.RESET_SNAPSHOT, written.reset)
        assertEquals(0, written.errors)
        assertEquals(16, written.keys)
        // The column first, then the snapshot of each key given back, in the table's order.
        assertEquals(restoredKeys.map { it to own.getValue(it).value }, basemap.writes.drop(16))
        for (key in restoredKeys) assertEquals(own[key], basemap.held[key], key)
    }

    @Test
    fun `by day a key that still holds the night value gets the snapshot and says so`() {
        assertSnapshotWrittenBack(Basemap(own) { _, before -> before })
    }

    @Test
    fun `by day a key that holds no value gets the snapshot and says so`() {
        assertSnapshotWrittenBack(Basemap(own) { _, _ -> Read(Value.nullValue(), Kind.CONSTANT) })
    }

    @Test
    fun `by day a key that reads as undefined gets the snapshot and says so`() {
        assertSnapshotWrittenBack(Basemap(own) { _, _ -> Read(null, Kind.UNDEFINED) })
    }

    @Test
    fun `a key the style said nothing for when it loaded has nothing to give back`() {
        val silent = own + ("colorWater" to Read(null, Kind.FAILED))
        val basemap = Basemap(silent) { _, before -> before }
        MapLook.write(basemap, Look.NIGHT, Look.NIGHT, silent, light = false)
        basemap.writes.clear()
        val written = MapLook.write(basemap, Look.DAY, Look.DAY, silent, light = false)

        assertEquals(MapLook.RESET_SNAPSHOT, written.reset)
        assertTrue(basemap.writes.drop(16).none { it.first == "colorWater" })
        assertEquals(restoredKeys.size - 1, basemap.writes.size - 16)
    }

    @Test
    fun `the snapshot reads every key of the table once`() {
        val basemap = restoring()
        val snapshot = MapLook.snapshot(basemap)

        assertEquals(MapPalette.keys.map { it.name }, basemap.reads)
        assertEquals(own, snapshot)
        assertTrue(basemap.writes.isEmpty())
    }

    @Test
    fun `nothing to write writes nothing`() {
        val basemap = restoring()
        val written = MapLook.write(basemap, null, null, own, light = false)
        assertEquals(0, written.keys)
        assertNull(written.reset)
        assertTrue(basemap.writes.isEmpty() && basemap.reads.isEmpty())
    }
}
