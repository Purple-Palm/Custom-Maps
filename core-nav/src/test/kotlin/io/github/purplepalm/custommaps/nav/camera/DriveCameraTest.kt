package io.github.purplepalm.custommaps.nav.camera

import io.github.purplepalm.custommaps.nav.model.DriveCameraSettings
import io.github.purplepalm.custommaps.nav.model.DriveView
import io.github.purplepalm.custommaps.nav.model.DriveViewControl
import io.github.purplepalm.custommaps.nav.model.RecenterControl
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A made-up drive along a straight route from 0 with street turns at [turns] metres, an update of the
 * navigator once a second, and the hold's clock four times a second. Every wish handed to the camera is
 * kept, with when it came.
 */
private class CameraDrive(val turns: List<Double>, settings: DriveCameraSettings, fromPreview: Boolean = true) {
    val camera = DriveCamera(CameraTuning.PHONE, DriveViewTuning.PHONE)
    var n = 0
    var x = 0.0
    var routeId = "r1"
    var offRoute = false
    val wishes = ArrayList<Pair<Double, CameraWish>>()
    val t: Double get() = n * DT

    init {
        camera.setSettings(settings, 0.0)
        camera.onGuidanceStarted(0.0, fromPreview)?.let { wishes += 0.0 to it }
    }

    fun ahead(): List<ViewPoint> = turns.withIndex().filter { it.value > x }.take(2).map { (i, at) ->
        ViewPoint("$routeId/0/$i", ApproachKind.TURN, FocusKind.TURN, false, at - x)
    }

    /** Keeps a wish handed to the camera; never the one it was already asked for. */
    fun record(wish: CameraWish?, before: CameraWish) {
        if (wish == null) return
        check(wish != before) { "$wish asked again at $t" }
        check(wish != CameraWish.LEAVE) { "the camera is never asked to leave the map" }
        wishes += t to wish
    }

    /** One of the driver's actions, its wish kept as the camera gets it. */
    fun act(action: DriveCamera.() -> CameraWish?) {
        val before = camera.wish
        record(camera.action(), before)
    }

    /** Drives at [v] until [untilX] or for [seconds]. */
    fun drive(v: Double, untilX: Double = Double.POSITIVE_INFINITY, seconds: Double = Double.POSITIVE_INFINITY) {
        val end = t + seconds
        while (x < untilX && t < end - 1e-9) {
            if (n % UPDATE_STEPS == 0) act { onProgress(ViewInput(t, routeId, x, offRoute, v, ahead())) }
            if (n % TICK_STEPS == 0) act { onTick(t) }
            x += v * DT
            n += 1
            check(n < 2_000_000) { "the drive does not get to $untilX" }
        }
    }

    fun lastWish() = wishes.last().second

    companion object {
        const val DT = 0.05
        const val UPDATE_STEPS = 20
        const val TICK_STEPS = 5
    }
}

private val smart = DriveCameraSettings(driveView = DriveView.OVERVIEW, recenterSeconds = 10, turnZoom = true)
private val plain = smart.copy(turnZoom = false)
private val normal = smart.copy(driveView = DriveView.NORMAL)

/** The drive camera of one map: the plan, the map the driver holds, the toggle, the look and the settings. */
class DriveCameraTest {

    @Test
    fun `the start's answer is handed on once per guidance, also when it is what the camera does`() {
        // From the preview: the overview in the Overview, the car in Normal; the plain Overview always the overview.
        assertEquals(CameraWish.OVERVIEW, CameraDrive(listOf(3_000.0), smart).wishes.single().second)
        assertEquals(CameraWish.FOLLOW, CameraDrive(listOf(3_000.0), normal).wishes.single().second)
        assertEquals(CameraWish.OVERVIEW, CameraDrive(listOf(3_000.0), plain, fromPreview = false).wishes.single().second)
        // A map made anew during a drive, or a direct Start: the car at once, and its first update decides.
        val recreated = CameraDrive(listOf(3_000.0, 30_000.0), smart, fromPreview = false)
        assertEquals(CameraWish.FOLLOW, recreated.wishes.single().second)
        recreated.drive(13.0, seconds = 0.5)
        assertEquals(CameraWish.OVERVIEW, recreated.lastWish())
        // A start after a panned preview in Normal: FOLLOW, which the camera may already be asked for, is handed on all the same.
        val camera = DriveCamera()
        camera.setSettings(normal, 0.0)
        assertEquals(CameraWish.FOLLOW, camera.wish)
        assertEquals(CameraWish.FOLLOW, camera.onGuidanceStarted(0.0, fromPreview = true))
        // A second render of guidance asks nothing; the next guidance answers again.
        assertNull(camera.onGuidanceStarted(1.0, fromPreview = true))
        camera.onGuidanceEnded(2.0)
        assertEquals(CameraWish.FOLLOW, camera.onGuidanceStarted(3.0, fromPreview = false))
    }

    @Test
    fun `a moved map goes back to the car in Normal and where the plan says in the Overview`() {
        // Normal: the car, 10 s after the last touch.
        val n = CameraDrive(listOf(3_000.0, 30_000.0), normal)
        n.drive(13.0, untilX = 500.0)
        n.camera.onFinger(true, n.t)
        n.camera.onMapTaken(n.t)
        assertEquals(CameraWish.LEAVE, n.camera.wish)
        val lifted = n.t + 1.0
        n.drive(13.0, seconds = 1.0)
        n.camera.onFinger(false, lifted)
        n.drive(13.0, seconds = 9.6)
        assertEquals(CameraWish.LEAVE, n.camera.wish)
        n.drive(13.0, seconds = 0.5)
        assertEquals(CameraWish.FOLLOW, n.lastWish())
        assertEquals(HoldEnd.TIMER, n.camera.takeHoldEnded()!!.ended)
        // The smart Overview between turns 1 km apart, with more than 10 s of overview possible: the overview.
        val between = CameraDrive(listOf(3_000.0, 4_000.0, 30_000.0), smart)
        between.drive(13.0, untilX = 3_100.0)
        assertEquals(CameraWish.FOLLOW, between.camera.wish)
        between.camera.onMapTaken(between.t)
        assertEquals(CameraWish.OVERVIEW, between.camera.onRecenter(between.t))
        // And down again when the next approach opens.
        between.drive(13.0, untilX = 3_700.0)
        assertEquals(CameraWish.FOLLOW, between.lastWish())
        // Near a turn: the car.
        val near = CameraDrive(listOf(3_000.0, 30_000.0), smart)
        near.drive(13.0, untilX = 2_800.0)
        near.camera.onMapTaken(near.t)
        assertEquals(CameraWish.FOLLOW, near.camera.onRecenter(near.t))
        // The plain Overview: the overview.
        val p = CameraDrive(listOf(3_000.0, 30_000.0), plain)
        p.drive(13.0, untilX = 2_800.0)
        p.camera.onMapTaken(p.t)
        assertEquals(CameraWish.OVERVIEW, p.camera.onRecenter(p.t))
        // Without a hold Recenter asks nothing.
        assertNull(p.camera.onRecenter(p.t))
    }

    @Test
    fun `a hold that runs out where the standing overview could not stand 10 s brings the car until the turn`() {
        // The overview stands from the preview; the next turn's approach opens 16 s after the lift at 13 m/s, so at the
        // count's end the overview would stand about 4 s: the car, and the overview again only after that turn.
        val v = 13.0
        val approach = DriveViewRule.approachMeters(ViewPoint("k", ApproachKind.TURN, FocusKind.TURN, false, 9_000.0), v, CameraTuning.PHONE, DriveViewTuning.PHONE)
        val lift = 2_000.0
        val turn = lift + 10.0 * v + approach + (4.0 + CameraTuning.PHONE.longTransitionSeconds) * v
        val d = CameraDrive(listOf(turn, 40_000.0), smart)
        d.drive(v, untilX = lift)
        assertEquals(CameraWish.OVERVIEW, d.camera.wish)
        d.camera.onFinger(true, d.t)
        d.camera.onMapTaken(d.t)
        d.camera.onFinger(false, d.t)
        val taken = d.t
        d.drive(v, seconds = 10.5)
        val ended = d.wishes.last()
        assertEquals(CameraWish.FOLLOW, ended.second, "${d.wishes}")
        assertTrue(ended.first - taken in 9.9..10.3, "ended ${ended.first - taken} s after the lift")
        assertEquals(HoldEnd.TIMER, d.camera.takeHoldEnded()!!.ended)
        // No overview before the turn, and after it the plan's own way out.
        d.drive(v, untilX = turn + 100.0)
        assertEquals(listOf(CameraWish.OVERVIEW, CameraWish.FOLLOW, CameraWish.OVERVIEW), d.wishes.map { it.second })
        assertTrue(d.wishes.last().first > (turn - lift) / v + taken, "the overview after the turn")
        // A tap of Recenter in the same place does the same.
        val r = CameraDrive(listOf(turn, 40_000.0), smart)
        r.drive(v, untilX = lift + 10.0 * v)
        r.camera.onMapTaken(r.t)
        assertEquals(CameraWish.FOLLOW, r.camera.onRecenter(r.t))
    }

    @Test
    fun `the count near a turn is 3 s after the last touch, also in the plain Overview`() {
        for (settings in listOf(smart, plain, normal)) {
            val d = CameraDrive(listOf(3_000.0, 30_000.0), settings)
            d.drive(13.0, untilX = 2_700.0)
            assertTrue(d.camera.near)
            d.camera.onFinger(true, d.t)
            d.camera.onMapTaken(d.t)
            val lifted = d.t
            d.camera.onFinger(false, lifted)
            d.drive(13.0, seconds = 2.9)
            assertEquals(CameraWish.LEAVE, d.camera.wish, "$settings")
            d.drive(13.0, seconds = 0.4)
            assertTrue(d.camera.wish != CameraWish.LEAVE, "$settings: back 3 s after the touch")
        }
    }

    @Test
    fun `the toggle during a hold ends it and switches the drive view`() {
        // Normal held, far from a turn: Overview, the overview at once.
        val far = CameraDrive(listOf(3_000.0, 30_000.0), normal)
        far.drive(13.0, untilX = 500.0)
        far.camera.onMapTaken(far.t)
        val out = far.camera.toggle(far.t)
        assertEquals(ToggleResult(DriveView.OVERVIEW, CameraWish.OVERVIEW, endedHold = true), out)
        assertEquals(HoldEnd.TOGGLE, far.camera.takeHoldEnded()!!.ended)
        // Normal held near a turn: the car, and the overview after the maneuver.
        val near = CameraDrive(listOf(3_000.0, 30_000.0), normal)
        near.drive(13.0, untilX = 2_800.0)
        near.camera.onMapTaken(near.t)
        assertEquals(ToggleResult(DriveView.OVERVIEW, CameraWish.FOLLOW, endedHold = true), near.camera.toggle(near.t))
        near.drive(13.0, untilX = 3_100.0)
        assertEquals(CameraWish.OVERVIEW, near.lastWish())
        // The Overview held: Normal, the car.
        val o = CameraDrive(listOf(3_000.0, 30_000.0), smart)
        o.drive(13.0, untilX = 500.0)
        o.camera.onMapTaken(o.t)
        assertEquals(ToggleResult(DriveView.NORMAL, CameraWish.FOLLOW, endedHold = true), o.camera.toggle(o.t))
        // Without a hold the toggle switches only: back to the Overview, far from a turn, the overview.
        assertEquals(ToggleResult(DriveView.OVERVIEW, CameraWish.OVERVIEW, endedHold = false), o.camera.toggle(o.t))
        // Normal on the open road, the next approach opening 5 s of overview away: the car through that turn, the
        // overview after it, as near a turn.
        val v = 13.0
        val approach = DriveViewRule.approachMeters(ViewPoint("k", ApproachKind.TURN, FocusKind.TURN, false, 9_000.0), v, CameraTuning.PHONE, DriveViewTuning.PHONE)
        val soon = CameraDrive(listOf(3_000.0, 40_000.0), normal, fromPreview = false)
        soon.drive(v, untilX = 3_000.0 - approach - (5.0 + CameraTuning.PHONE.longTransitionSeconds) * v)
        assertEquals(ToggleResult(DriveView.OVERVIEW, null, endedHold = false), soon.camera.toggle(soon.t))
        soon.drive(v, untilX = 3_100.0)
        assertEquals(listOf(CameraWish.FOLLOW, CameraWish.OVERVIEW), soon.wishes.map { it.second })
        assertTrue(soon.wishes.last().first > 3_000.0 / v, "the overview after the turn")
    }

    @Test
    fun `a drive view changed in Settings ends a hold, Zoom in before turns and Recenter by itself keep it`() {
        val d = CameraDrive(listOf(3_000.0, 30_000.0), smart)
        d.drive(13.0, untilX = 500.0)
        d.camera.onMapTaken(d.t)
        d.camera.onFinger(false, d.t)
        assertNull(d.camera.setSettings(smart.copy(turnZoom = false), d.t))
        assertEquals(CameraWish.LEAVE, d.camera.wish)
        // Recenter by itself changed during the hold: the count takes the new value at once.
        assertNull(d.camera.setSettings(smart.copy(turnZoom = false, recenterSeconds = 20), d.t + 1.0))
        assertEquals(RecenterControl(19), d.camera.recenterControl(d.t + 1.0))
        assertNull(d.camera.setSettings(smart.copy(turnZoom = false, recenterSeconds = 0), d.t + 1.0))
        assertEquals(RecenterControl(null), d.camera.recenterControl(d.t + 1.0))
        // The drive view: the hold ends, and the map shows the new one.
        assertEquals(CameraWish.FOLLOW, d.camera.setSettings(normal.copy(recenterSeconds = 0), d.t + 2.0))
        assertEquals(HoldEnd.SETTINGS, d.camera.takeHoldEnded()!!.ended)
        assertNull(d.camera.recenterControl(d.t + 2.0))
    }

    @Test
    fun `a look shows the overview for 10 s where it can stand, and none near a turn`() {
        // In the Overview kept normal between turns 1 km apart: a look, and at its end the overview stays.
        val d = CameraDrive(listOf(3_000.0, 4_000.0, 30_000.0), smart)
        d.drive(13.0, untilX = 3_100.0)
        assertEquals(CameraWish.FOLLOW, d.camera.wish)
        assertEquals(CameraWish.OVERVIEW, d.camera.onLook(d.t))
        assertEquals(RecenterControl(10), d.camera.recenterControl(d.t))
        val before = d.wishes.size
        d.drive(13.0, seconds = 10.5)
        assertEquals(before, d.wishes.size, "the look's end in the Overview with time to stand: no move")
        assertEquals(CameraWish.OVERVIEW, d.camera.wish)
        assertNull(d.camera.recenterControl(d.t))
        // In Normal the look ends with the car.
        val n = CameraDrive(listOf(3_000.0, 30_000.0), normal)
        n.drive(13.0, untilX = 500.0)
        assertEquals(CameraWish.OVERVIEW, n.camera.onLook(n.t))
        n.drive(13.0, seconds = 10.5)
        assertEquals(CameraWish.FOLLOW, n.lastWish())
        // Near a turn, or 5 s before its approach: no look, only the request for routes.
        val near = CameraDrive(listOf(3_000.0, 30_000.0), normal)
        near.drive(13.0, untilX = 2_800.0)
        assertNull(near.camera.onLook(near.t))
        val soon = CameraDrive(listOf(3_000.0, 30_000.0), normal)
        val approach = DriveViewRule.approachMeters(ViewPoint("k", ApproachKind.TURN, FocusKind.TURN, false, 3_000.0), 13.0, CameraTuning.PHONE, DriveViewTuning.PHONE)
        soon.drive(13.0, untilX = 3_000.0 - approach - (5.0 + CameraTuning.PHONE.longTransitionSeconds) * 13.0)
        assertNull(soon.camera.onLook(soon.t))
        // In the plain Overview, and where the overview shows anyway: none.
        val p = CameraDrive(listOf(3_000.0, 30_000.0), plain)
        p.drive(13.0, untilX = 500.0)
        assertNull(p.camera.onLook(p.t))
        // A look running when a point comes near ends at once, with the car.
        val l = CameraDrive(listOf(3_000.0, 30_000.0), normal)
        val opens = 3_000.0 - approach
        l.drive(13.0, untilX = opens - 12.0 * 13.0 - 40.0)
        assertEquals(CameraWish.OVERVIEW, l.camera.onLook(l.t))
        // At a steady speed a look ends before the next approach opens; the car speeds up, and the approach opens under it.
        l.drive(30.0, untilX = l.x + 40.0)
        assertEquals(CameraWish.FOLLOW, l.lastWish())
        assertEquals(HoldEnd.NEAR, l.camera.takeHoldEnded()!!.ended)
        // A gesture during a look turns it into an ordinary hold.
        val g = CameraDrive(listOf(3_000.0, 30_000.0), normal)
        g.drive(13.0, untilX = 500.0)
        g.camera.onLook(g.t)
        g.camera.onMapTaken(g.t + 1.0)
        assertEquals(CameraWish.LEAVE, g.camera.wish)
    }

    @Test
    fun `the toggle's look changes only on a choice, Recenter's only on a whole second of a hold`() {
        val d = CameraDrive(listOf(3_000.0, 4_000.0, 30_000.0), smart)
        val control = d.camera.driveViewControl()
        assertEquals(DriveViewControl(DriveView.OVERVIEW, turnZoom = true), control)
        d.drive(13.0, untilX = 3_300.0)
        assertEquals(control, d.camera.driveViewControl(), "nothing chosen: the same look while the plan moves")
        assertNull(d.camera.recenterControl(d.t))
        d.camera.onMapTaken(d.t)
        val start = d.t
        val seen = ArrayList<RecenterControl?>()
        var s = start
        while (s < start + 10.5) {
            val c = d.camera.recenterControl(s)
            if (seen.lastOrNull() != c) seen += c
            s += 0.25
        }
        assertEquals((10 downTo 0).map { RecenterControl(it) }, seen)
        // Recenter by itself off: Recenter without a count.
        d.camera.setSettings(smart.copy(recenterSeconds = 0), d.t)
        assertEquals(RecenterControl(null), d.camera.recenterControl(d.t))
        assertEquals(DriveViewControl(DriveView.OVERVIEW, turnZoom = true), d.camera.driveViewControl())
        d.camera.toggle(d.t)
        assertEquals(DriveViewControl(DriveView.NORMAL, turnZoom = true), d.camera.driveViewControl())
    }

    @Test
    fun `the end of a hold is told once, with how long it held and how often a touch started its count again`() {
        val d = CameraDrive(listOf(3_000.0, 30_000.0), normal)
        d.drive(13.0, untilX = 500.0)
        val start = d.t
        d.camera.onFinger(true, start)
        d.camera.onMapTaken(start)
        d.camera.onFinger(false, start + 1.0)
        d.camera.onFinger(true, start + 3.0)
        d.camera.onFinger(false, start + 4.0)
        d.drive(13.0, seconds = 16.0)
        val ended = d.camera.takeHoldEnded()
        assertNotNull(ended)
        assertEquals(MapHold.Kind.MAP_MOVED, ended!!.kind)
        assertEquals(HoldEnd.TIMER, ended.ended)
        assertEquals(1, ended.restarts)
        assertTrue(ended.heldSeconds in 14.0..14.3, "held ${ended.heldSeconds}")
        assertFalse(ended.near)
        assertNull(d.camera.takeHoldEnded())
        // Guidance ending drops a hold, told as such.
        d.camera.onMapTaken(d.t)
        d.camera.onGuidanceEnded(d.t + 2.0)
        assertEquals(HoldEnd.GUIDANCE, d.camera.takeHoldEnded()!!.ended)
        assertEquals(CameraWish.FOLLOW, d.camera.wish)
        assertNull(d.camera.recenterControl(d.t + 2.0))
    }

    @Test
    fun `a seek decides afresh from the new place`() {
        val d = CameraDrive(listOf(5_000.0, 30_000.0), smart)
        d.drive(13.0, untilX = 4_900.0)
        assertEquals(CameraWish.FOLLOW, d.camera.wish)
        d.x = 1_000.0
        d.camera.onJump(d.t)
        d.drive(13.0, seconds = 12.0)
        assertEquals(CameraWish.OVERVIEW, d.lastWish())
    }

    @Test
    fun `a hold ends when the map is put away, and back the map shows the plan's view decided afresh`() {
        val d = CameraDrive(listOf(3_000.0, 4_000.0, 30_000.0), smart)
        d.drive(13.0, untilX = 1_000.0)
        assertEquals(CameraWish.OVERVIEW, d.camera.wish)
        d.camera.onMapTaken(d.t)
        assertEquals(CameraWish.LEAVE, d.camera.wish)
        // Away, the hold ends with its reason, and the wish is the plan's again, carried out when the map is back.
        assertEquals(CameraWish.OVERVIEW, d.camera.onAway(d.t + 1.0))
        assertEquals(HoldEnd.AWAY, d.camera.takeHoldEnded()!!.ended)
        assertNull(d.camera.recenterControl(d.t + 1.0))
        // Without a hold, or outside guidance, being put away changes nothing.
        assertNull(d.camera.onAway(d.t + 2.0))
        assertNull(d.camera.takeHoldEnded())
        // Back past a turn it did not see, inside the next one's approach: the plan decides from there as after a
        // seek, and the view comes down at the first update.
        d.x = 3_700.0
        d.camera.onJump(d.t)
        d.drive(13.0, seconds = 1.5)
        assertEquals(CameraWish.FOLLOW, d.lastWish())
        d.camera.onGuidanceEnded(d.t)
        d.camera.onMapTaken(d.t)
        assertNull(d.camera.onAway(d.t))
    }

    @Test
    fun `random drives with the driver's hands never ask the same wish twice in a row`() {
        val random = Random(21)
        repeat(300) {
            val turns = ArrayList<Double>()
            var at = 400.0 + random.nextDouble() * 2_000.0
            repeat(10) {
                turns += at
                at += 200.0 + random.nextDouble() * 3_500.0
            }
            val settings = DriveCameraSettings(
                driveView = if (random.nextBoolean()) DriveView.OVERVIEW else DriveView.NORMAL,
                recenterSeconds = listOf(0, 5, 10, 20)[random.nextInt(4)],
                turnZoom = random.nextInt(4) != 0,
            )
            // Every wish the drive hands on is checked against the one before it (CameraDrive.record).
            val d = CameraDrive(turns, settings, fromPreview = random.nextBoolean())
            while (d.x < turns.last() - 50.0) {
                val v = 2.0 + random.nextDouble() * 30.0
                when (random.nextInt(14)) {
                    0 -> d.act { onFinger(true, d.t); onMapTaken(d.t); null }
                    1 -> d.act { onFinger(false, d.t); null }
                    2 -> d.act { onRecenter(d.t) }
                    3 -> d.act { toggle(d.t).wish }
                    4 -> d.act { onLook(d.t) }
                    5 -> d.act {
                        setSettings(
                            settings.copy(
                                turnZoom = random.nextBoolean(),
                                recenterSeconds = listOf(0, 5, 10)[random.nextInt(3)],
                                driveView = driveViewControl().driveView,
                            ),
                            d.t,
                        )
                    }
                    6 -> d.act { onMapMoved(d.t); null }
                }
                d.drive(v, untilX = minOf(turns.last() - 50.0, d.x + 100.0 + random.nextDouble() * 500.0))
            }
            d.camera.onGuidanceEnded(d.t)
            assertEquals(CameraWish.FOLLOW, d.camera.wish)
        }
    }

    @Test
    fun `the plan's view and a hold are on the camera line, the decision on the drive view line`() {
        val d = CameraDrive(listOf(3_000.0, 30_000.0), smart)
        d.drive(13.0, untilX = 2_800.0)
        val fields = d.camera.debugFields(d.t)
        assertEquals(DriveView.OVERVIEW, fields["view"])
        assertEquals(DriveViewPlanner.Reason.APPROACH, fields["planWhy"])
        assertEquals(ApproachKind.TURN, fields["pointKind"])
        assertEquals(true, fields["near"])
        assertFalse(fields.containsKey("hold"))
        d.camera.onMapTaken(d.t)
        assertEquals(MapHold.Kind.MAP_MOVED, d.camera.debugFields(d.t)["hold"])
        assertEquals(3, d.camera.debugFields(d.t)["holdS"])
        val view = d.camera.driveViewFields()
        assertEquals(CameraWish.LEAVE, view["wish"])
        assertEquals(13.0, view["speed"])
        assertEquals(false, view["silent"])
        assertSame(true, view["turnZoom"])
        // Numbers, kinds and words only.
        for ((key, value) in fields + view) {
            assertTrue(value is Number || value is Boolean || value is Enum<*>, "$key = $value")
        }
    }
}
