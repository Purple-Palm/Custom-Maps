package io.github.purplepalm.custommaps.car.routing

import io.github.purplepalm.custommaps.nav.model.Distance
import io.github.purplepalm.custommaps.nav.model.FollowingManeuver
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneEdge
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.LaneTarget
import io.github.purplepalm.custommaps.nav.model.LanesAhead
import io.github.purplepalm.custommaps.nav.model.Maneuver
import io.github.purplepalm.custommaps.nav.model.ManeuverType
import io.github.purplepalm.custommaps.nav.model.Place
import io.github.purplepalm.custommaps.nav.model.RouteProgressSnapshot
import io.github.purplepalm.custommaps.nav.model.RunUpLanes
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Synthetic drives for the car's tests: invented roads and places, times from 2000-01-01 UTC. */
internal object Drives {

    val NOW: ZonedDateTime = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

    val OFFICE = Place("Office", "12 Oak Street", GeoPoint(0.0, 0.0))

    /** The words of the car's resources, as the phone has them. */
    val WORDS = object : CarWords {
        override fun roundaboutExit(number: Int) = "Exit $number"

        override fun arrivalSide(side: ArrivalSide) = when (side) {
            ArrivalSide.LEFT -> "On the left"
            ArrivalSide.RIGHT -> "On the right"
            ArrivalSide.AHEAD -> "Destination"
        }

        override val destination = "Destination"
    }

    fun lane(vararg arrows: TurnModifier, lit: TurnModifier? = null, carPool: Boolean = false) =
        Lane(arrows.toSet(), isRecommended = lit != null, activeIndication = lit, designated = carPool)

    /** The exit's own lanes: two go on, two take the exit. */
    val EXIT_LANES = LaneInfo(
        listOf(
            lane(TurnModifier.STRAIGHT),
            lane(TurnModifier.STRAIGHT),
            lane(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT, lit = TurnModifier.SLIGHT_RIGHT),
            lane(TurnModifier.SLIGHT_RIGHT, lit = TurnModifier.SLIGHT_RIGHT),
        ),
    )

    /** A lane choice before the exit: the three left lanes go on, the two right ones leave. */
    val CHOICE_LANES = LaneInfo(
        listOf(
            lane(TurnModifier.STRAIGHT, lit = TurnModifier.STRAIGHT),
            lane(TurnModifier.STRAIGHT, lit = TurnModifier.STRAIGHT),
            lane(TurnModifier.STRAIGHT, lit = TurnModifier.STRAIGHT),
            lane(TurnModifier.SLIGHT_RIGHT),
            lane(TurnModifier.SLIGHT_RIGHT),
        ),
    )

    /** The run-up's one row: a car pool lane at the left, the right lane lit. */
    val RUN_UP = RunUpLanes(
        LaneInfo(
            listOf(
                lane(TurnModifier.STRAIGHT, carPool = true),
                lane(TurnModifier.STRAIGHT),
                lane(TurnModifier.STRAIGHT),
                lane(TurnModifier.SLIGHT_RIGHT, lit = TurnModifier.SLIGHT_RIGHT),
            ),
        ),
        target = LaneTarget(LaneEdge.RIGHT, 1, 0),
        key = "route/0/3/end",
        odd = null,
    )

    val EXIT = Maneuver(ManeuverType.OFF_RAMP, TurnModifier.SLIGHT_RIGHT, "Exit 24: Oak Street", "Office")
    val PINE = Maneuver(ManeuverType.TURN, TurnModifier.RIGHT, "Pine Street")
    val ELM = Maneuver(ManeuverType.TURN, TurnModifier.LEFT, "Elm Street")

    /** On the freeway towards the exit, [miles] away, with the exit's own lanes. */
    fun towardsExit(miles: Double, step: String = "route/0/3") = RouteProgressSnapshot(
        nextManeuver = EXIT,
        distanceToNextManeuver = Distance.miles(miles),
        lanes = EXIT_LANES,
        distanceRemaining = Distance.miles(miles + 3.0),
        durationRemaining = 12.minutes,
        durationToNextManeuver = (miles * 60).seconds,
        stepKey = step,
        following = FollowingManeuver(PINE, Distance.meters(250.0), 15.seconds),
    )
}
