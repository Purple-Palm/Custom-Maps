package io.github.purplepalm.custommaps.car.routing

import androidx.car.app.navigation.model.LaneDirection
import io.github.purplepalm.custommaps.nav.glyph.GlyphShapes
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.LaneInfo
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The card's lanes as the car's host takes them: the arrows the card draws on each lane, recommended
 * where the card lights them, from the leftmost lane.
 */
class LanesToCarTest {

    @Test
    fun `every arrow has its shape, a U-turn a left one`() {
        val expected = mapOf(
            TurnModifier.U_TURN to LaneDirection.SHAPE_U_TURN_LEFT,
            TurnModifier.SHARP_RIGHT to LaneDirection.SHAPE_SHARP_RIGHT,
            TurnModifier.RIGHT to LaneDirection.SHAPE_NORMAL_RIGHT,
            TurnModifier.SLIGHT_RIGHT to LaneDirection.SHAPE_SLIGHT_RIGHT,
            TurnModifier.STRAIGHT to LaneDirection.SHAPE_STRAIGHT,
            TurnModifier.SLIGHT_LEFT to LaneDirection.SHAPE_SLIGHT_LEFT,
            TurnModifier.LEFT to LaneDirection.SHAPE_NORMAL_LEFT,
            TurnModifier.SHARP_LEFT to LaneDirection.SHAPE_SHARP_LEFT,
        )
        assertEquals(TurnModifier.entries.toSet(), expected.keys)
        for ((turn, shape) in expected) assertEquals(shape, LanesToCar.shape(turn), "$turn")
    }

    @Test
    fun `only the route's arrow of a lane to take is recommended`() {
        val lane = Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_RIGHT)
        assertEquals(
            CarLane(listOf(direction(LaneDirection.SHAPE_STRAIGHT, false), direction(LaneDirection.SHAPE_SLIGHT_RIGHT, true))),
            LanesToCar.lane(lane),
        )
    }

    @Test
    fun `a lane to take whose arrow the route does not name has every arrow recommended`() {
        val lane = Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.LEFT), isRecommended = true)
        assertEquals(
            CarLane(listOf(direction(LaneDirection.SHAPE_STRAIGHT, true), direction(LaneDirection.SHAPE_NORMAL_LEFT, true))),
            LanesToCar.lane(lane),
        )
    }

    @Test
    fun `a lane the card draws as an outline has no arrow recommended, whatever else it says`() {
        val toCome = Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = false, outline = true)
        assertEquals(CarLane(listOf(direction(LaneDirection.SHAPE_SLIGHT_RIGHT, false))), LanesToCar.lane(toCome))
        val odd = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_RIGHT, outline = true)
        assertEquals(CarLane(listOf(direction(LaneDirection.SHAPE_STRAIGHT, false))), LanesToCar.lane(odd))
    }

    @Test
    fun `a lane not to take has no arrow recommended`() {
        val lane = Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.RIGHT), isRecommended = false)
        assertTrue(LanesToCar.lane(lane).directions.none { it.recommended })
    }

    @Test
    fun `a car pool lane is dim, and lit only where the card lights it as the one lane to use`() {
        val dim = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false, designated = true)
        val lit = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true, activeIndication = TurnModifier.STRAIGHT, designated = true)
        assertEquals(CarLane(listOf(direction(LaneDirection.SHAPE_STRAIGHT, false))), LanesToCar.lane(dim))
        assertEquals(CarLane(listOf(direction(LaneDirection.SHAPE_STRAIGHT, true))), LanesToCar.lane(lit))
    }

    @Test
    fun `a lane without arrows has the straight one the card draws`() {
        assertEquals(CarLane(listOf(direction(LaneDirection.SHAPE_STRAIGHT, false))), LanesToCar.lane(Lane(emptySet(), isRecommended = false)))
        val lit = Lane(emptySet(), isRecommended = true, activeIndication = TurnModifier.STRAIGHT)
        assertEquals(CarLane(listOf(direction(LaneDirection.SHAPE_STRAIGHT, true))), LanesToCar.lane(lit))
    }

    @Test
    fun `the route's arrow is there even where the data does not list it on the lane`() {
        val lane = Lane(setOf(TurnModifier.STRAIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_LEFT)
        assertEquals(
            CarLane(listOf(direction(LaneDirection.SHAPE_STRAIGHT, false), direction(LaneDirection.SHAPE_SLIGHT_LEFT, true))),
            LanesToCar.lane(lane),
        )
    }

    @Test
    fun `lanes keep their order from the left, every one with an arrow, and the library builds them`() {
        val lanes = LaneInfo(
            listOf(
                Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false, designated = true),
                Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false),
                Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), isRecommended = true, activeIndication = TurnModifier.SLIGHT_RIGHT),
                Lane(emptySet(), isRecommended = true, activeIndication = TurnModifier.STRAIGHT),
                Lane(setOf(TurnModifier.U_TURN, TurnModifier.LEFT), isRecommended = false),
            ),
        )
        val car = LanesToCar.of(lanes)
        assertEquals(5, car.size)
        assertTrue(car.all { it.directions.isNotEmpty() })
        assertEquals(listOf(false, false, true, true, false), car.map { lane -> lane.directions.any { it.recommended } })
        val built = car.toHost()
        assertEquals(car.size, built.size)
        for ((lane, host) in car.zip(built)) {
            assertEquals(lane.directions.map { it.shape to it.recommended }, host.directions.map { it.shape to it.isRecommended })
        }
    }

    @Test
    fun `the card draws every lane with the host's arrows, the route's ones where the host's are recommended`() {
        // Every lane of none to three of the eight arrows, in every state, the route's arrow named on the lane or not.
        val eight = TurnModifier.entries
        val sets = listOf(emptySet<TurnModifier>()) + eight.indices.flatMap { i ->
            listOf(setOf(eight[i])) + (i + 1 until eight.size).flatMap { j ->
                listOf(setOf(eight[i], eight[j])) + (j + 1 until eight.size).map { k -> setOf(eight[i], eight[j], eight[k]) }
            }
        }
        assertEquals(93, sets.size)
        for (set in sets) {
            for (designated in listOf(false, true)) for (outline in listOf(false, true)) {
                val lanes = listOf(Lane(set, isRecommended = false, designated = designated, outline = outline)) +
                    (listOf<TurnModifier?>(null) + eight).map { Lane(set, isRecommended = true, activeIndication = it, designated = designated, outline = outline) }
                for (lane in lanes) {
                    val host = LanesToCar.lane(lane).directions
                    assertEquals(host.map { it.shape }, GlyphShapes.laneArrows(lane).map(LanesToCar::shape), "$lane")
                    assertEquals(host.filter { it.recommended }.map { it.shape }.toSet(), GlyphShapes.routeArrows(lane).map(LanesToCar::shape).toSet(), "$lane")
                }
            }
        }
    }

    private fun direction(shape: Int, recommended: Boolean) = CarLaneDirection(shape, recommended)
}
