package io.github.purplepalm.custommaps.ui.guidance

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.nav.replay.ReplayPosition
import io.github.purplepalm.custommaps.ui.theme.LookParameter
import io.github.purplepalm.custommaps.ui.theme.LookTheme
import io.github.purplepalm.custommaps.ui.theme.PreviewOnMap
import kotlinx.coroutines.flow.collectLatest

/**
 * The replay slider of a simulated drive, like a video player's progress bar: a thin track, amber
 * where the drive has played and a quiet grey ahead, and a white dot where the drive is. The row is
 * as tall as a finger needs; the track and the dot are small. One design for both looks: a graphite
 * casing along the track and a graphite ring around the dot carry them over the light map, and at
 * night the dot, the amber and the grey do.
 *
 * The drive's position comes once a second. Between two of them the dot glides, in the draw phase
 * only: a glide draws the slider again on each frame, but composes nothing, and it stops as soon
 * as the dot has reached the newest position. So nothing runs while no new position comes, as
 * during a seek, at the end of the drive or while the app is in the background, and nothing glides
 * while a finger holds the dot. Where the finger lets go, anywhere on the row, the drive goes
 * ([onSeek], with the share of the track from 0 to 1); a tap goes there too. TalkBack reads it as a
 * slider named "Simulated drive position", with the position in percent, and can move it.
 *
 * [position] is read here, not by the caller, so a new position reaches only this slider.
 */
@Composable
fun ReplaySlider(position: State<ReplayPosition?>, onSeek: (Double) -> Unit, modifier: Modifier = Modifier) {
    val seek by rememberUpdatedState(onSeek)
    val glide = remember { DotGlide() }
    // The frame of a glide; read only when the slider is drawn, so a frame draws and composes nothing.
    val frame = remember { mutableLongStateOf(0L) }
    // Where a finger holds the dot, from 0 to 1; null while none does. Read when drawn and touched.
    val held = remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(position) {
        snapshotFlow { position.value }.collectLatest { now ->
            val target = now?.fraction?.toFloat() ?: return@collectLatest
            // A seek shows where it goes at once; so does the first position.
            if (now.seeking || !glide.started || held.value != null) {
                glide.snapTo(target)
                frame.longValue = withFrameNanos { it }
                return@collectLatest
            }
            glide.glideTo(target, withFrameNanos { it })
            do {
                val nanos = withFrameNanos { it }
                frame.longValue = nanos
            } while (!glide.arrived(nanos) && held.value == null)
        }
    }
    // Composed again only when the whole percent changes, for TalkBack.
    val percent by remember(position) { derivedStateOf { position.value?.percent ?: 0 } }
    val name = stringResource(R.string.replay_position)
    val said = stringResource(R.string.replay_position_percent, percent)
    val tokens = LookTheme.tokens
    val colors = SliderColors(played = tokens.accent, ahead = tokens.sliderRest, ink = tokens.mapInk, dot = tokens.sliderDot)
    Box(
        modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .semantics {
                contentDescription = name
                stateDescription = said
                progressBarRangeInfo = ProgressBarRangeInfo(percent / PERCENT, 0f..1f)
                setProgress { value ->
                    seek(value.coerceIn(0f, 1f).toDouble())
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val inset = TRACK_INSET.toPx()
                    val down = awaitFirstDown()
                    held.value = trackFraction(down.position.x, size.width.toFloat(), inset)
                    down.consume()
                    val lifted = drag(down.id) { change ->
                        held.value = trackFraction(change.position.x, size.width.toFloat(), inset)
                        change.consume()
                    }
                    val at = held.value
                    held.value = null
                    // A gesture taken over by another, as by the system, moves nothing.
                    if (lifted && at != null) seek(at.toDouble())
                }
            }
            .drawBehind {
                val holding = held.value
                drawSlider(holding ?: glide.at(frame.longValue), holding != null, colors)
            },
    )
}

/** The slider's colours: the played part, the part ahead, the ink of the casing and the ring, the dot. */
private class SliderColors(val played: Color, val ahead: Color, val ink: Color, val dot: Color)

/** The track from end to end, the played part over it, and the dot at [fraction] of it, larger while held. */
private fun DrawScope.drawSlider(fraction: Float, holding: Boolean, colors: SliderColors) {
    val y = size.height / 2f
    val start = TRACK_INSET.toPx()
    val end = size.width - start
    if (end <= start) return
    val x = start + (end - start) * fraction
    val track = TRACK_WIDTH.toPx()
    // The ink's casing and ring keep the track and the dot plain over any part of the map.
    drawLine(colors.ink, Offset(start, y), Offset(end, y), strokeWidth = track + 2f * CASING.toPx(), cap = StrokeCap.Round)
    drawLine(colors.ahead, Offset(start, y), Offset(end, y), strokeWidth = track, cap = StrokeCap.Round)
    if (x > start) drawLine(colors.played, Offset(start, y), Offset(x, y), strokeWidth = track, cap = StrokeCap.Round)
    val radius = (if (holding) HELD_DOT else DOT).toPx() / 2f
    drawCircle(colors.ink, radius = radius + RING.toPx(), center = Offset(x, y))
    drawCircle(colors.dot, radius = radius, center = Offset(x, y))
}

/** Where on the track [x] pixels from the row's start lie, from 0 to 1, with the track [inset] from each end of a row [width] wide. */
internal fun trackFraction(x: Float, width: Float, inset: Float): Float {
    val length = width - 2f * inset
    if (length <= 0f) return 0f
    return ((x - inset) / length).coerceIn(0f, 1f)
}

/**
 * Where the dot is drawn between two positions of the drive: from where it was drawn when the
 * newest one came, it moves at a steady pace and reaches it in [glideNanos], about when the next one
 * comes, and stays there if none does. Times are those of the frames. Not thread-safe: the slider
 * uses it on the main thread.
 */
internal class DotGlide(private val glideNanos: Long = GLIDE_NANOS) {
    private var from = 0f
    private var to = 0f
    private var start = NO_GLIDE

    /** False until the first position has been shown. */
    var started = false
        private set

    /** Where the dot is drawn on the frame at [nanos]. */
    fun at(nanos: Long): Float {
        if (start == NO_GLIDE) return to
        val share = ((nanos - start).toFloat() / glideNanos).coerceIn(0f, 1f)
        return from + (to - from) * share
    }

    /** A new position, [target]: the dot glides there from where it is drawn on the frame at [nanos]. */
    fun glideTo(target: Float, nanos: Long) {
        from = at(nanos)
        to = target
        start = nanos
        started = true
    }

    /** The dot goes to [target] at once, as for a seek or the first position. */
    fun snapTo(target: Float) {
        from = target
        to = target
        start = NO_GLIDE
        started = true
    }

    /** Whether the dot has reached the newest position on the frame at [nanos]: no more frames are needed. */
    fun arrived(nanos: Long): Boolean = start == NO_GLIDE || nanos - start >= glideNanos

    companion object {
        /** The drive's positions come once a second. */
        const val GLIDE_NANOS = 1_000_000_000L

        private const val NO_GLIDE = Long.MIN_VALUE
    }
}

/** At least as tall as a finger needs. */
private val ROW_HEIGHT = 48.dp

private val TRACK_WIDTH = 4.dp
private val DOT = 12.dp
private val HELD_DOT = 16.dp

/** The casing on each side of the track. */
private val CASING = 1.dp

/** The ring around the dot. */
private val RING = 1.5.dp

/** From each end of the row to the end of the track: the held dot fits, with room to spare. */
private val TRACK_INSET = 12.dp

private const val PERCENT = 100f

/** The slider of a simulated drive 40 % of the way, over the map, on a narrow phone. */
@Preview(name = "replay slider", widthDp = 360)
@Composable
private fun ReplaySliderPreview(@PreviewParameter(LookParameter::class) look: Look) {
    PreviewOnMap(look) {
        ReplaySlider(position = remember { mutableStateOf(ReplayPosition(seconds = 240.0, durationSeconds = 600.0)) }, onSeek = {})
    }
}
