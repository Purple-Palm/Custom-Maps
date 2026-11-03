package io.github.purplepalm.custommaps.ui.token

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck

/**
 * Field for pasting a Mapbox public token. The typed value is kept in plain `remember`, not
 * `rememberSaveable`, so it is never written into the saved instance state.
 */
@Composable
fun TokenEditor(
    @StringRes saveLabel: Int,
    onSave: (String) -> AccessTokenCheck,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var rejection by remember { mutableStateOf<AccessTokenCheck?>(null) }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                rejection = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.token_field_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            isError = rejection != null,
            supportingText = rejection?.let { check -> { Text(stringResource(check.messageRes())) } },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val check = onSave(input)
                if (check == AccessTokenCheck.VALID) input = "" else rejection = check
            },
            enabled = input.isNotBlank(),
        ) {
            Text(stringResource(saveLabel))
        }
    }
}

@StringRes
private fun AccessTokenCheck.messageRes(): Int = when (this) {
    AccessTokenCheck.VALID -> R.string.token_saved
    AccessTokenCheck.EMPTY -> R.string.token_error_empty
    AccessTokenCheck.SECRET_TOKEN -> R.string.token_error_secret
    AccessTokenCheck.NOT_PUBLIC_TOKEN -> R.string.token_error_not_public
    AccessTokenCheck.MALFORMED -> R.string.token_error_malformed
}
