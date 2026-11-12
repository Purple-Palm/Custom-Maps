package io.github.purplepalm.custommaps.ui.token

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.token.TokenProblem
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme

/**
 * First launch, or every token deleted: no map can be shown until the driver pastes a Mapbox
 * public token, which becomes the first saved token, Main. [onSaveToken] returns why it was not
 * taken, or null.
 */
@Composable
fun TokenSetupScreen(
    onSaveToken: (String) -> TokenProblem?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.token_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.token_missing_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            TokenEditor(saveLabel = R.string.token_save, onSave = onSaveToken)

            Spacer(Modifier.height(32.dp))
            OutlinedButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.settings_title))
            }
        }
    }
}

@Preview
@Composable
private fun TokenSetupScreenPreview() {
    CustomMapsTheme(Look.NIGHT) {
        TokenSetupScreen(onSaveToken = { null }, onOpenSettings = {})
    }
}
