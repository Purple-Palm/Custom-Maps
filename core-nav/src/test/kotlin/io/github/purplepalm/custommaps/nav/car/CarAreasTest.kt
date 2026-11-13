package io.github.purplepalm.custommaps.nav.car

import io.github.purplepalm.custommaps.nav.ViewportPadding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The car map's padding and the place of the logo, on invented surfaces: 800 by 480 pixels at one
 * pixel a dp, and 1280 by 720 at two, with made-up areas such as a strip of 96 px on each side.
 */
class CarAreasTest {

    private val width = 800
    private val height = 480

    /** Free of the host's strips on both sides and of a band at the top and the bottom. */
    private val stable = CarArea(left = 96, top = 24, right = 704, bottom = 456)

    /** Only the action strip on the right covers the map at this moment. */
    private val visible = CarArea(left = 0, top = 0, right = 704, bottom = 480)

    @Test
    fun `insets are the area's distances from each edge`() {
        assertEquals(CarInsets(96, 24, 96, 24), CarAreas.insets(width, height, stable))
        assertEquals(CarInsets(0, 0, 96, 0), CarAreas.insets(width, height, visible))
    }

    @Test
    fun `an unknown area has no insets, and one past the edges is cut at them`() {
        assertNull(CarAreas.insets(width, height, null))
        // An empty rectangle is how the host says it does not know the area.
        assertNull(CarAreas.insets(width, height, CarArea(0, 0, 0, 0)))
        assertNull(CarAreas.insets(width, height, CarArea(100, 50, 100, 400)))
        // Wholly outside the surface.
        assertNull(CarAreas.insets(width, height, CarArea(900, 0, 1000, 100)))
        assertEquals(CarInsets.NONE, CarAreas.insets(width, height, CarArea(-10, -10, 900, 500)))
        // No surface yet.
        assertNull(CarAreas.insets(0, 0, stable))
    }

    @Test
    fun `following frames the stable area with the car a third up, the overview the visible area`() {
        val paddings = CarAreas.paddings(width, height, density = 1f, stable = stable, visible = visible)
        // 5 dp around the stable area; at the bottom its inset of 24 plus a third of its 432 px.
        assertEquals(ViewportPadding(left = 101, top = 29, right = 101, bottom = 24 + 144), paddings.following)
        // 5 dp around the visible area, whose only inset is the strip on the right.
        assertEquals(ViewportPadding(left = 5, top = 5, right = 101, bottom = 5), paddings.overview)
    }

    @Test
    fun `the margin is 5 dp at the car screen's density`() {
        val big = CarAreas.paddings(1280, 720, density = 2f, stable = CarArea(0, 0, 1280, 720), visible = null)
        assertEquals(ViewportPadding(left = 10, top = 10, right = 10, bottom = 240), big.following)
        assertEquals(ViewportPadding(left = 10, top = 10, right = 10, bottom = 10), big.overview)
        assertEquals(5.0, CarAreas.MARGIN_DP)
        assertEquals(1.0 / 3.0, CarAreas.FOLLOWING_BOTTOM_SHARE)
    }

    @Test
    fun `the strip coming and going moves the overview only`() {
        val shown = CarAreas.paddings(width, height, 1f, stable, visible)
        val hidden = CarAreas.paddings(width, height, 1f, stable, CarArea(0, 0, width, height))
        assertEquals(shown.following, hidden.following)
        assertEquals(ViewportPadding(5, 5, 5, 5), hidden.overview)
    }

    @Test
    fun `the visible area stands in for a stable one that has not come, and the whole surface for both`() {
        val noStable = CarAreas.paddings(width, height, 1f, stable = null, visible = visible)
        assertEquals(ViewportPadding(left = 5, top = 5, right = 101, bottom = 160), noStable.following)
        val none = CarAreas.paddings(width, height, 1f, stable = CarArea(0, 0, 0, 0), visible = null)
        assertEquals(ViewportPadding(left = 5, top = 5, right = 5, bottom = 160), none.following)
        assertEquals(ViewportPadding(left = 5, top = 5, right = 5, bottom = 5), none.overview)
        // A stable area and no visible one: the overview frames the stable area.
        val noVisible = CarAreas.paddings(width, height, 1f, stable = stable, visible = null)
        assertEquals(ViewportPadding(left = 101, top = 29, right = 101, bottom = 29), noVisible.overview)
    }

    @Test
    fun `a padding never takes more than three quarters of the surface`() {
        // A tiny area in the middle, as no host should report: the two sides share what they may take.
        val tiny = CarArea(390, 230, 410, 250)
        val paddings = CarAreas.paddings(width, height, 1f, tiny, tiny)
        for (padding in listOf(paddings.following, paddings.overview)) {
            assertTrue(padding.left + padding.right <= width * 3 / 4, "$padding")
            assertTrue(padding.top + padding.bottom <= height * 3 / 4, "$padding")
        }
        // In proportion: the left and right insets were equal, and stay so.
        assertEquals(paddings.following.left, paddings.following.right)
        assertEquals(300, paddings.following.left)
        assertEquals(0.25, CarAreas.MIN_FREE_SHARE)
    }

    @Test
    fun `the logo sits in the bottom left corner of the stable area, the attribution beside it`() {
        val place = CarAreas.ornaments(width, height, 1f, stable, visible, logoWidthPx = 85, logoHeightPx = 21, attributionHeightPx = 15)
        assertEquals(OrnamentPlace(logoX = 104, logoBottom = 32, attributionX = 104 + 85 + 4, attributionBottom = 35), place)
        // At two pixels a dp the margins double; the logo's own size is what the library drew.
        val big = CarAreas.ornaments(1280, 720, 2f, CarArea(0, 0, 1280, 720), null, 170, 42, 30)
        assertEquals(OrnamentPlace(logoX = 16, logoBottom = 16, attributionX = 16 + 170 + 8, attributionBottom = 22), big)
    }

    @Test
    fun `the logo does not move when the strips come and go`() {
        val shown = CarAreas.ornaments(width, height, 1f, stable, visible, 85, 21, 15)
        val hidden = CarAreas.ornaments(width, height, 1f, stable, CarArea(0, 0, width, height), 85, 21, 15)
        assertEquals(shown, hidden)
        // Until a stable area comes, the visible one places it; without either, the surface.
        assertEquals(8, CarAreas.ornaments(width, height, 1f, null, visible, 85, 21, 15).logoX)
        assertEquals(8, CarAreas.ornaments(width, height, 1f, null, null, 85, 21, 15).logoBottom)
    }

    @Test
    fun `dp become whole pixels`() {
        assertEquals(5, CarAreas.px(5.0, 1f))
        assertEquals(8, CarAreas.px(5.0, 1.5f))
        assertEquals(13, CarAreas.px(5.0, 2.625f))
    }
}
