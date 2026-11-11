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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.token.TokenList
import io.github.purplepalm.custommaps.token.TokenProblem

/**
 * Field for pasting a Mapbox public token, with its Save button. The typed value is kept in plain
 * `remember`, not `rememberSaveable`, so it is never written into the saved instance state.
 * [onSave] returns why the token was not taken, or null when it is being stored.
 */
@Composable
fun TokenEditor(
    @StringRes saveLabel: Int,
    onSave: (String) -> TokenProblem?,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<TokenProblem?>(null) }

    Column(modifier = modifier) {
        TokenField(
            value = input,
            onValueChange = {
                input = it
                problem = null
            },
            error = problem?.let { tokenProblemText(it) },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val refused = onSave(input)
                if (refused == null) input = "" else problem = refused
            },
            enabled = input.isNotBlank(),
        ) {
            Text(stringResource(saveLabel))
        }
    }
}

/**
 * The token itself: hidden as it is typed, with no suggestions and no autocorrect. Keep its value
 * in plain `remember`, never `rememberSaveable`.
 */
@Composable
internal fun TokenField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.token_field_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        isError = error != null,
        supportingText = error?.let { text -> { Text(text) } },
    )
}

/** A token's name, at most [TokenList.MAX_NAME_LENGTH] characters: longer input is not taken, so a token pasted here by mistake stays out. */
@Composable
internal fun TokenNameField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.codePointCount(0, it.length) <= TokenList.MAX_NAME_LENGTH) onValueChange(it) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.token_name_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        isError = error != null,
        supportingText = error?.let { text -> { Text(text) } },
    )
}
