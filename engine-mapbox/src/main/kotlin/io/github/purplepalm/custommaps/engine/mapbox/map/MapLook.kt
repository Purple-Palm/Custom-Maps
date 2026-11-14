package io.github.purplepalm.custommaps.engine.mapbox.map

import com.mapbox.bindgen.Value
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.StylePropertyValueKind
import io.github.purplepalm.custommaps.nav.look.Look

/**
 * Writes a look's column of [MapPalette.keys] on the basemap of Mapbox Standard, one key a call.
 * The style's call that takes many keys at once is never used: it sets every key it is not given
 * back to the style's default, lane details' switch among them. So the port to the map ([Basemap])
 * has no such call.
 *
 * A key the day look gives back to the style is written with no value. That this restores the
 * style's own value is read in Mapbox's code comments, not promised for Android, so each such key
 * is read back once and compared with what the style said when it loaded ([snapshot]); whatever
 * else it reads (the night value, no value, an undefined one), that snapshot is written in its
 * place. Writing it when the reset did work changes nothing. Main thread only.
 */
internal object MapLook {

    /** The name Mapbox Standard has as an import when it is the style itself, as for LaneDetails. */
    const val BASEMAP = "basemap"

    /** The key of the basemap's theme: the one write that clears its tiles. */
    const val THEME = "theme"

    /** What [write] says of the keys given back to the style: each read back as the snapshot had it. */
    const val RESET_NULL = "null"

    /** What [write] says when a key given back to the style read otherwise, and its snapshot was written. */
    const val RESET_SNAPSHOT = "snapshot"

    /** The config of the basemap import, on one map. */
    interface Basemap {
        /** Sets [key] to [value]; returns what went wrong, or null. One key a call. */
        fun write(key: String, value: Value): String?

        /** What [key] holds now. */
        fun read(key: String): Read
    }

    /** One key as read: its value, and the kind of value the style says it is. */
    data class Read(val value: Value?, val kind: Kind)

    /** The style's kinds of a property value, and [FAILED] when the read itself failed. */
    enum class Kind { CONSTANT, EXPRESSION, TRANSITION, UNDEFINED, FAILED }

    /**
     * What one [write] did: [keys] written from the look's column, how many writes the map refused
     * and for which keys, and for the keys given back to the style [reset] ([RESET_NULL] or
     * [RESET_SNAPSHOT]; null when no key was given back, as at night).
     */
    data class Written(val keys: Int, val errors: Int, val failedKeys: List<String>, val reset: String?) {
        companion object {
            val NONE = Written(keys = 0, errors = 0, failedKeys = emptyList(), reset = null)
        }
    }

    /** Every key of the table as the style holds it, read once in a style load before the first write. */
    fun snapshot(basemap: Basemap): Map<String, Read> = MapPalette.keys.associate { it.name to basemap.read(it.name) }

    /**
     * Writes every key of [look]'s column but the theme, and the theme of [theme]'s column, in the
     * table's order; a null look or theme leaves its keys as they are. With [light], the car's lighter
     * map, the 3D objects are written off whatever the look says ([MapPalette.Key.value]); every caller
     * says it, the phone's map false ([LookApplier.light]), so no write can leave it out. By day each
     * key given back to the style is read back and compared with [snapshot].
     */
    fun write(basemap: Basemap, look: Look?, theme: Look?, snapshot: Map<String, Read>, light: Boolean): Written {
        var keys = 0
        var errors = 0
        val failed = LinkedHashSet<String>()
        val restored = ArrayList<String>()
        for (key in MapPalette.keys) {
            val from = (if (key.name == THEME) theme else look) ?: continue
            val value = key.value(from, light)
            keys += 1
            if (basemap.write(key.name, value.toValue()) != null) {
                errors += 1
                failed += key.name
            }
            if (value == LookValue.Restore) restored += key.name
        }
        if (restored.isEmpty()) return Written(keys, errors, failed.toList(), reset = null)
        var reset = RESET_NULL
        for (name in restored) {
            val own = snapshot[name] ?: Read(null, Kind.FAILED)
            if (basemap.read(name) == own) continue
            reset = RESET_SNAPSHOT
            // A style that said nothing for the key has nothing to give back.
            val value = own.value ?: continue
            if (basemap.write(name, value) != null) {
                errors += 1
                failed += name
            }
        }
        return Written(keys, errors, failed.toList(), reset)
    }

    /**
     * The 3D objects alone, as [look] has them with the car's lighter map composed in: off while
     * [light], else the look's own. Returns what went wrong, or null.
     */
    fun writeObjects(basemap: Basemap, look: Look, light: Boolean): String? {
        val key = MapPalette.keys.first { it.name == MapPalette.SHOW_3D_OBJECTS }
        return basemap.write(key.name, key.value(look, light).toValue())
    }

    /** The value a column's entry is written as. */
    fun LookValue.toValue(): Value = when (this) {
        is LookValue.Text -> Value.valueOf(text)
        is LookValue.Flag -> Value.valueOf(on)
        is LookValue.Number -> Value.valueOf(value)
        is LookValue.Colour -> Value.valueOf(hex)
        LookValue.Restore -> Value.nullValue()
    }

    /** The basemap of [mapboxMap]. */
    fun basemapOf(mapboxMap: MapboxMap): Basemap = object : Basemap {
        override fun write(key: String, value: Value): String? = mapboxMap.setStyleImportConfigProperty(BASEMAP, key, value).error

        override fun read(key: String): Read {
            val property = mapboxMap.getStyleImportConfigProperty(BASEMAP, key).value ?: return Read(null, Kind.FAILED)
            val kind = when (property.kind) {
                StylePropertyValueKind.CONSTANT -> Kind.CONSTANT
                StylePropertyValueKind.EXPRESSION -> Kind.EXPRESSION
                StylePropertyValueKind.TRANSITION -> Kind.TRANSITION
                else -> Kind.UNDEFINED
            }
            return Read(property.value, kind)
        }
    }
}
