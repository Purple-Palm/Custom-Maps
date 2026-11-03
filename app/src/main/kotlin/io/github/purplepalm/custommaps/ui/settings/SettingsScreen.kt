package io.github.purplepalm.custommaps.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import io.github.purplepalm.custommaps.ui.token.TokenEditor
import io.github.purplepalm.custommaps.ui.token.TokenState

@Composable
fun SettingsScreen(
    tokenState: TokenState,
    onSaveToken: (String) -> AccessTokenCheck,
    onClearToken: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.navigate_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.token_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (tokenState) {
                    is TokenState.Present -> stringResource(R.string.token_saved_as, tokenState.masked)
                    else -> stringResource(R.string.token_not_set)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            TokenEditor(
                saveLabel = if (tokenState is TokenState.Present) R.string.token_replace else R.string.token_save,
                onSave = onSaveToken,
            )
            if (tokenState is TokenState.Present) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClearToken) {
                    Text(stringResource(R.string.token_remove))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.token_storage_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    CustomMapsTheme {
        SettingsScreen(
            tokenState = TokenState.Present("pk.eyJ1…a1b2"),
            onSaveToken = { AccessTokenCheck.VALID },
            onClearToken = {},
            onBack = {},
        )
    }
}
