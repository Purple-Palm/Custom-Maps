package io.github.purplepalm.custommaps.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit

/**
 * Settings of one feature kept together in the settings file, each under a key of its own, as the
 * other settings are: read as one value [G], and changed in one write that touches only the keys whose
 * stored value changed. A missing or unknown value reads as the group's default and is never written
 * unless it changes, so a later default still reaches a phone that never chose.
 *
 * @param read the group as [Preferences] hold it, every missing or unknown value its default
 * @param fields every key of the group, with the stored form of its value in [G]
 */
internal class PreferenceGroup<G>(
    private val read: (Preferences) -> G,
    private val fields: List<Field<G, *>>,
) {
    /** One key of the group and the value [stored] holds for it, as the file keeps it. */
    class Field<G, V : Any>(val key: Preferences.Key<V>, val stored: (G) -> V)

    /** The names of the group's keys, in the fields' order. */
    val keyNames: List<String> get() = fields.map { it.key.name }

    /** The group as [preferences] hold it. */
    fun of(preferences: Preferences): G = read(preferences)

    /**
     * Applies [change] to the group as [preferences] hold it and writes the keys whose stored value is
     * not what it was. Returns each key written with the value it now holds, in the fields' order:
     * one entry for each changed key, none for a change that changes nothing.
     */
    fun change(preferences: MutablePreferences, change: (G) -> G): List<Pair<String, Any>> {
        val before = read(preferences)
        val after = change(before)
        val written = ArrayList<Pair<String, Any>>()
        for (field in fields) field.write(preferences, before, after)?.let(written::add)
        return written
    }

    /**
     * Applies [transform] in one write to [dataStore] and then tells [onChanged] of each key it wrote, once
     * each, with the value as stored: the `settings` line stays one for each key.
     */
    suspend fun update(dataStore: DataStore<Preferences>, transform: (G) -> G, onChanged: (key: String, value: Any) -> Unit) {
        var written: List<Pair<String, Any>> = emptyList()
        dataStore.edit { written = change(it, transform) }
        for ((key, value) in written) onChanged(key, value)
    }

    private fun <V : Any> Field<G, V>.write(preferences: MutablePreferences, before: G, after: G): Pair<String, Any>? {
        val value = stored(after)
        if (value == stored(before)) return null
        preferences[key] = value
        return key.name to value
    }
}
