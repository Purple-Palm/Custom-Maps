package io.github.purplepalm.custommaps.nav.model

/**
 * A non-negative distance. Stored in meters, which is what routing engines report;
 * the UI shows miles and feet.
 */
@JvmInline
value class Distance private constructor(val meters: Double) : Comparable<Distance> {

    init {
        require(meters.isFinite() && meters >= 0.0) { "Distance must be finite and non-negative: $meters" }
    }

    val feet: Double get() = meters / METERS_PER_FOOT

    val miles: Double get() = meters / METERS_PER_MILE

    operator fun plus(other: Distance): Distance = Distance(meters + other.meters)

    override fun compareTo(other: Distance): Int = meters.compareTo(other.meters)

    override fun toString(): String = "Distance(${meters}m)"

    companion object {
        const val METERS_PER_FOOT = 0.3048
        const val METERS_PER_MILE = 1609.344

        val ZERO = Distance(0.0)

        fun meters(value: Double): Distance = Distance(value)

        fun feet(value: Double): Distance = Distance(value * METERS_PER_FOOT)

        fun miles(value: Double): Distance = Distance(value * METERS_PER_MILE)
    }
}
