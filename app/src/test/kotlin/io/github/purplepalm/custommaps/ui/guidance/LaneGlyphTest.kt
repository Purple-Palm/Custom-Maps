package io.github.purplepalm.custommaps.ui.guidance

import androidx.compose.ui.graphics.Color
import io.github.purplepalm.custommaps.nav.glyph.GlyphInk
import io.github.purplepalm.custommaps.nav.glyph.GlyphShapes
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.model.Lane
import io.github.purplepalm.custommaps.nav.model.TurnModifier
import io.github.purplepalm.custommaps.ui.theme.LookPalette
import io.github.purplepalm.custommaps.ui.theme.LookTokens
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Lanes to take are marked by shape as well as colour, since by lightness alone laneOn and laneOff
 * differ by 1.91:1 by day and 2.31:1 at night: a lane to take is an amber tile with its arrows on it
 * in the dark ink of icons on amber. A lane kept for car pools or buses never gets the tile, and
 * only a lane still to come needs a layer of its own.
 */
class LaneGlyphTest {

    /** A lane, the inks its parts are drawn in, in the order they first come, and whether it is a lane to take. */
    private class Row(val what: String, val lane: Lane, val inks: List<GlyphInk>, val toTake: Boolean)

    private val rows = listOf(
        Row("a lane to take, lit on its route's arrow", straightOrRight(lit = true), listOf(GlyphInk.TILE, GlyphInk.ON_TILE), toTake = true),
        Row("a lane not to take", straightOrRight(lit = false), listOf(GlyphInk.DIM), toTake = false),
        // The route can be followed from it, but the data names none of its two arrows: all of them on the tile alike.
        Row(
            "a lane to take whose arrow nobody named",
            Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.LEFT), isRecommended = true),
            listOf(GlyphInk.TILE, GlyphInk.ON_TILE),
            toTake = true,
        ),
        Row("a car pool lane, dim", Lane(setOf(TurnModifier.STRAIGHT), isRecommended = false, designated = true), listOf(GlyphInk.DIM), toTake = false),
        // Where it is the only lane the route marks, its side lines stay grey and its arrow is lit, in colour alone.
        Row(
            "a car pool lane lit as the only one",
            Lane(setOf(TurnModifier.STRAIGHT), true, TurnModifier.STRAIGHT, designated = true),
            listOf(GlyphInk.DIM, GlyphInk.LIT),
            toTake = false,
        ),
        // A lane the map does not draw yet: hollow grey arrows, never lit, never a tile.
        Row("a lane still to come", Lane(setOf(TurnModifier.SLIGHT_RIGHT), isRecommended = false, outline = true), listOf(GlyphInk.DIM, GlyphInk.HOLE), toTake = false),
        Row("a lane still to come, marked to take", straightOrRight(lit = true).copy(outline = true), listOf(GlyphInk.DIM, GlyphInk.HOLE), toTake = false),
    )

    @Test
    fun `which inks each lane is drawn in`() {
        assertAll(rows.map { row -> Executable { assertEquals(row.inks, GlyphShapes.lane(row.lane).map { it.ink }.distinct(), row.what) } })
    }

    @Test
    fun `which lanes get a tile, under everything else of the lane`() {
        assertAll(
            rows.map { row ->
                Executable {
                    assertEquals(row.toTake, isLaneToTake(row.lane), row.what)
                    val parts = GlyphShapes.lane(row.lane)
                    assertEquals(row.toTake, parts.any { it.ink == GlyphInk.TILE }, row.what)
                    if (row.toTake) assertEquals(GlyphInk.TILE, parts.first().ink, row.what)
                }
            },
        )
    }

    @Test
    fun `only a lane still to come is drawn on a layer of its own`() {
        assertAll(rows.map { row -> Executable { assertEquals(row.lane.outline, drawnOnLayer(GlyphShapes.lane(row.lane)), row.what) } })
    }

    @Test
    fun `a tile is the amber fill with its arrows in the ink of icons on amber, other lanes the lane tokens, day and night`() {
        for (look in Look.entries) {
            val palette = LookPalette.of(look)
            // The colours the row hands each lane, and the one of them each part of the lane is drawn in.
            val row = LaneColors.of(LookTokens(palette))
            fun drawnIn(ink: GlyphInk) = laneColor(ink, highlight = row.highlight, dim = row.dim, tile = row.tile, onTile = row.onTile)
            assertEquals(Color(palette.accent), drawnIn(GlyphInk.TILE), "$look")
            assertEquals(Color(palette.onAccent), drawnIn(GlyphInk.ON_TILE), "$look")
            assertEquals(Color(palette.laneOn), drawnIn(GlyphInk.LIT), "$look")
            assertEquals(Color(palette.laneOff), drawnIn(GlyphInk.DIM), "$look")
            // A hollow is cut out of the lane's own grey, whatever colour it is drawn with.
            assertEquals(Color(palette.laneOff), drawnIn(GlyphInk.HOLE), "$look")
        }
    }

    private fun straightOrRight(lit: Boolean) =
        Lane(setOf(TurnModifier.STRAIGHT, TurnModifier.SLIGHT_RIGHT), isRecommended = lit, activeIndication = if (lit) TurnModifier.SLIGHT_RIGHT else null)
}
