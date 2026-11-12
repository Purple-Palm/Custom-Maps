package io.github.purplepalm.custommaps.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.nav.look.Look

/**
 * Both looks, for previews: a preview that takes a [Look] through
 * `@PreviewParameter(LookParameter::class)` shows by day and at night.
 */
class LookParameter : PreviewParameterProvider<Look> {
    override val values: Sequence<Look> = sequenceOf(Look.DAY, Look.NIGHT)

    override fun getDisplayName(index: Int): String? = values.elementAtOrNull(index)?.name?.lowercase()
}

/**
 * A preview of what lies over the map, in [look]: the app's theme and a plain stand-in for the
 * map ([LookTokens.previewMapStandIn]), so a card shows its corners and, by day, its shadow.
 */
@Composable
internal fun PreviewOnMap(look: Look, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    CustomMapsTheme(look) {
        Box(
            modifier
                .fillMaxWidth()
                .background(LookTheme.tokens.previewMapStandIn)
                .padding(12.dp),
            content = content,
        )
    }
}
