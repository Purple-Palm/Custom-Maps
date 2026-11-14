package io.github.purplepalm.custommaps.nav.car

/**
 * Whether the car's map is drawn lighter while the phone is hot: without the 3D objects of Mapbox
 * Standard (buildings, landmarks, trees), a heavy part of each frame. A phone that Android holds back
 * for its heat draws fewer frames whatever the frame cap says, and the 3D objects are what the car's
 * map can give up then; the phone's own map keeps them.
 *
 * - **Light** from the moment the phone's thermal status is [ThermalStatus.MODERATE] or worse.
 * - **Full again** once the status has been [ThermalStatus.LIGHT] or better for [COOL_MILLIS] in a
 *   row: a status that comes and goes inside that time changes nothing, and every hot status starts
 *   the count again. No status (none read yet, or before Android 10) counts as cool.
 * - **Never light** while the Debug switch "Lighter car map when hot" is off ([setEnabled]); on again,
 *   the map is light at once if the phone is hot.
 *
 * Its decisions come only on a change of the status, of the switch, or at the moment the count ends
 * ([dueAtMillis], [onTime]): nothing runs per frame. The times are milliseconds on one clock that never
 * goes back (Android's time since boot). Pure: the car map tells it the status, the switch and the
 * time, draws what [light] says and writes a "car_light" line ([fields]) at each change. Main thread
 * only.
 */
class CarLightRule {

    /** Whether the car's map is the lighter one now. */
    var light: Boolean = false
        private set

    /** The phone's thermal status as last told; null while none was read. */
    var status: ThermalStatus? = null
        private set

    /** The Debug switch "Lighter car map when hot": on unless turned off. */
    var enabled: Boolean = true
        private set

    /** Since when the status has been cool, LIGHT or better; [NONE] while it is hot. */
    private var coolSince = NONE

    /**
     * When the lighter map goes back to full if nothing changes before, on the clock of the calls;
     * [NONE] while nothing is due: the map is full, or the phone is hot.
     */
    val dueAtMillis: Long get() = if (light && coolSince != NONE) coolSince + COOL_MILLIS else NONE

    /** The phone's thermal status is [status] at [nowMillis]. Returns whether [light] changed. */
    fun onStatus(status: ThermalStatus?, nowMillis: Long): Boolean {
        this.status = status
        if (status.isHot()) {
            coolSince = NONE
        } else if (coolSince == NONE) {
            coolSince = nowMillis
        }
        return settle(nowMillis)
    }

    /** The time is [nowMillis], as at [dueAtMillis]. Returns whether [light] changed. */
    fun onTime(nowMillis: Long): Boolean = settle(nowMillis)

    /** The Debug switch is [enabled] at [nowMillis]. Returns whether [light] changed. */
    fun setEnabled(enabled: Boolean, nowMillis: Long): Boolean {
        this.enabled = enabled
        return settle(nowMillis)
    }

    /** The "car_light" line's fields: whether the map is the lighter one, and the thermal status by Android's name. */
    fun fields(): Map<String, Any?> = mapOf("on" to light, "thermal" to status?.name)

    private fun settle(nowMillis: Long): Boolean {
        val next = when {
            !enabled -> false
            status.isHot() -> true
            // A lighter map stays until the phone has been cool long enough.
            light -> coolSince == NONE || nowMillis - coolSince < COOL_MILLIS
            else -> false
        }
        if (next == light) return false
        light = next
        return true
    }

    private fun ThermalStatus?.isHot(): Boolean = this?.hot == true

    companion object {
        /** How long the phone must be cool, LIGHT or better, in a row before the car's map is full again. */
        const val COOL_MILLIS = 120_000L

        /** No time. */
        const val NONE = Long.MIN_VALUE

        /** The line written at each change of [light], with `screen` car. */
        const val LINE = "car_light"
    }
}
