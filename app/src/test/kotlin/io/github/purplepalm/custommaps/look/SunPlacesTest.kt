package io.github.purplepalm.custommaps.look

import io.github.purplepalm.custommaps.nav.look.DriveMoment
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.look.LookCause
import io.github.purplepalm.custommaps.nav.look.LookChoice
import io.github.purplepalm.custommaps.nav.look.LookInput
import io.github.purplepalm.custommaps.nav.look.LookState
import io.github.purplepalm.custommaps.nav.look.LookSwitch
import io.github.purplepalm.custommaps.nav.look.LookTrigger
import io.github.purplepalm.custommaps.nav.look.LookTuning
import io.github.purplepalm.custommaps.nav.look.PlaceSource
import io.github.purplepalm.custommaps.nav.look.SunElevation
import io.github.purplepalm.custommaps.nav.look.SunPlace
import io.github.purplepalm.custommaps.nav.model.GeoPoint
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneRules
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The place the sun is computed for, best first, with synthetic positions near 0, 0 and synthetic
 * time zones. Places are compared as places: a failure prints only their sources.
 */
class SunPlacesTest {

    private val t0 = Instant.parse("2000-01-01T00:00:00Z")
    private val nearZero = GeoPoint(0.04, -0.03)
    private val alsoNearZero = GeoPoint(-0.02, 0.06)

    /** A zone of standard time one hour east, with summer time two hours east from a day in March. */
    private val oneHourEast = ZoneRules.of(
        ZoneOffset.ofHours(1),
        ZoneOffset.ofHours(1),
        emptyList(),
        listOf(ZoneOffsetTransition.of(LocalDateTime.of(2000, 3, 26, 2, 0), ZoneOffset.ofHours(1), ZoneOffset.ofHours(2))),
        emptyList(),
    )

    private fun places(
        fix: () -> GeoPoint? = { null },
        lastKnown: () -> GeoPoint? = { null },
        zone: () -> ZoneRules = { oneHourEast },
        now: Instant = t0,
    ) = SunPlaces(fix, lastKnown, zone) { now }

    @Test
    fun `a fix comes first, then the last known position, then the time zone`() {
        assertEquals(SunPlace.of(nearZero.latitude, nearZero.longitude, PlaceSource.FIX), places(fix = { nearZero }, lastKnown = { alsoNearZero }).current())
        assertEquals(SunPlace.of(alsoNearZero.latitude, alsoNearZero.longitude, PlaceSource.LAST_KNOWN), places(lastKnown = { alsoNearZero }).current())
        assertEquals(SunPlace.ofTimeZone(3_600), places().current())
    }

    @Test
    fun `a source that fails is passed over`() {
        val noAccess = places(fix = { throw IllegalStateException("no navigator") }, lastKnown = { throw SecurityException("no location access") })
        assertEquals(PlaceSource.TIME_ZONE, noAccess.current().source)
        val brokenZone = places(zone = { throw java.time.DateTimeException("no rules") })
        assertEquals(SunPlace.ofTimeZone(0), brokenZone.current())
    }

    @Test
    fun `the time zone stands in by its standard offset, the same in summer`() {
        val summer = Instant.parse("2000-07-01T12:00:00Z")
        assertEquals(ZoneOffset.ofHours(2), oneHourEast.getOffset(summer))
        assertEquals(places(now = t0).current(), places(now = summer).current())
        assertEquals(SunPlace.ofTimeZone(3_600), places(now = summer).current())
        assertEquals(SunPlace.ofTimeZone(0), places(zone = { ZoneOffset.UTC.rules }).current())
    }

    @Test
    fun `values that are no position give none, and no message to carry them`() {
        assertNull(positionOrNull(91.0, 0.0))
        assertNull(positionOrNull(0.0, Double.NaN))
        assertNull(positionOrNull(0.0, 180.5))
        assertEquals(GeoPoint(0.0, 0.0), positionOrNull(0.0, 0.0))
    }

    @Test
    fun `a first fix after the time zone's guess changes the look as a better place`() {
        // A made-up sun that sets towards the poles: the guess takes its darkest latitude.
        val sun = SunElevation { _, latitude, _ -> 5.0 - abs(latitude) / 5.0 }
        var fix: GeoPoint? = null
        val places = places(fix = { fix })
        val tuning = LookTuning()
        val start = LookSwitch.reduce(LookState.NONE, LookInput(t0, LookChoice.AUTO, places.current(), LookTrigger.START, DriveMoment.NONE), tuning, sun)
        assertEquals(Look.NIGHT, start.change?.look)
        assertEquals(PlaceSource.TIME_ZONE, start.change?.source)
        fix = nearZero
        val tick = LookSwitch.reduce(start.state, LookInput(t0.plusSeconds(60), LookChoice.AUTO, places.current(), LookTrigger.PLACE, DriveMoment.NONE), tuning, sun)
        assertEquals(Look.DAY, tick.change?.look)
        assertEquals(LookCause.PLACE, tick.change?.cause)
        assertEquals(PlaceSource.FIX, tick.change?.source)
    }
}
