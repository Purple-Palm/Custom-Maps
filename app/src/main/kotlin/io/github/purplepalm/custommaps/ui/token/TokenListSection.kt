package io.github.purplepalm.custommaps.ui.token

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.nav.look.Look
import io.github.purplepalm.custommaps.token.TokenProblem
import io.github.purplepalm.custommaps.ui.theme.CustomMapsTheme
import io.github.purplepalm.custommaps.ui.theme.LookParameter
import io.github.purplepalm.custommaps.ui.theme.LookTheme

/**
 * The saved Mapbox tokens, one line each, with the active one marked, and Add below them while
 * fewer than five are saved. A tap on another token asks before it becomes the active one, since
 * the map reloads; each token's menu renames or deletes it. While guidance runs ([TokenState.Ready.locked])
 * the switch, Add and deleting the active token are off, and a line says why.
 *
 * One composable with everything the token page needs, its dialogs included, so it can move as a
 * whole. The callbacks are [TokenViewModel]'s, which checks every change again.
 */
@Composable
fun TokenListSection(
    state: TokenState,
    onAdd: (name: String?, token: String) -> TokenProblem?,
    onRename: (id: String, name: String) -> TokenProblem?,
    onUse: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state as? TokenState.Ready
    var usingId by remember { mutableStateOf<String?>(null) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (ready != null) {
            if (ready.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.token_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LookTheme.tokens.onCardMuted,
                )
            }
            Column(Modifier.selectableGroup()) {
                ready.rows.forEach { row ->
                    TokenLine(
                        row = row,
                        locked = ready.locked,
                        onUse = { if (row.mayUse) usingId = row.id },
                        onRename = { renamingId = row.id },
                        onDelete = { deletingId = row.id },
                    )
                }
            }
            // Gone at five; off, but in its place, while guidance runs.
            if (!ready.full) {
                TextButton(onClick = { adding = true }, enabled = ready.mayAdd) {
                    Text(stringResource(R.string.token_add))
                }
            }
            if (ready.locked) {
                Text(
                    text = stringResource(R.string.token_locked_during_guidance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LookTheme.tokens.onCard,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.token_list_note),
            style = MaterialTheme.typography.bodySmall,
            color = LookTheme.tokens.onCardMuted,
        )
        Text(
            text = stringResource(R.string.token_storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = LookTheme.tokens.onCardMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (ready == null) return
    ready.rows.firstOrNull { it.id == usingId }?.let { row ->
        ConfirmDialog(
            title = stringResource(R.string.token_use_title, row.name),
            message = stringResource(R.string.token_use_message),
            confirm = stringResource(R.string.token_use_confirm),
            // A trip that began while the dialog was open turns it off; the view model refuses too.
            enabled = row.mayUse,
            onConfirm = { onUse(row.id) },
            onDismiss = { usingId = null },
        )
    }
    ready.rows.firstOrNull { it.id == deletingId }?.let { row ->
        val next = ready.rows.firstOrNull { it.id != row.id }
        ConfirmDialog(
            title = stringResource(R.string.token_delete_title, row.name),
            message = when {
                !row.active -> stringResource(R.string.token_delete_message)
                next == null -> stringResource(R.string.token_delete_last_message)
                else -> stringResource(R.string.token_delete_active_message, next.name)
            },
            confirm = stringResource(R.string.token_delete),
            enabled = row.mayDelete,
            onConfirm = { onDelete(row.id) },
            onDismiss = { deletingId = null },
        )
    }
    ready.rows.firstOrNull { it.id == renamingId }?.let { row ->
        RenameDialog(row = row, onRename = onRename, onDismiss = { renamingId = null })
    }
    if (adding) {
        AddTokenDialog(defaultName = ready.defaultName, mayAdd = ready.mayAdd, onAdd = onAdd, onDismiss = { adding = false })
    }
}

/**
 * One saved token on one line: its name, cut short when it does not fit, the masked token and, on
 * the active one, Active. The whole line is the button that switches to it; the menu at its end
 * renames or deletes it.
 */
@Composable
private fun TokenLine(row: TokenRow, locked: Boolean, onUse: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = row.active, enabled = !locked, role = Role.RadioButton, onClick = onUse),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (row.active) FontWeight.SemiBold else null,
            color = LookTheme.tokens.onCard,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.masked,
            style = MaterialTheme.typography.bodySmall,
            color = LookTheme.tokens.onCardMuted,
            maxLines = 1,
        )
        if (row.active) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.token_list_active),
                style = MaterialTheme.typography.labelMedium,
                color = LookTheme.tokens.accentInk,
                maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more),
                    contentDescription = stringResource(R.string.token_menu, row.name),
                    tint = LookTheme.tokens.onCardMuted,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.token_rename)) },
                    onClick = {
                        menu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.token_delete)) },
                    enabled = row.mayDelete,
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                enabled = enabled,
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RenameDialog(row: TokenRow, onRename: (id: String, name: String) -> TokenProblem?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(row.name) }
    var problem by remember { mutableStateOf<TokenProblem?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.token_rename_title, row.name)) },
        text = {
            TokenNameField(
                value = name,
                onValueChange = {
                    name = it
                    problem = null
                },
                error = problem?.let { tokenProblemText(it) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val refused = onRename(row.id, name)
                    if (refused == null) onDismiss() else problem = refused
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A name, offered as [defaultName], and the token. Both are kept in plain `remember`. */
@Composable
private fun AddTokenDialog(
    defaultName: String,
    mayAdd: Boolean,
    onAdd: (name: String?, token: String) -> TokenProblem?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var token by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<TokenProblem?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.token_add_title)) },
        text = {
            Column {
                TokenNameField(
                    value = name,
                    onValueChange = {
                        name = it
                        problem = null
                    },
                    error = (problem as? TokenProblem.BadName)?.let { tokenProblemText(it) },
                )
                Spacer(Modifier.height(8.dp))
                TokenField(
                    value = token,
                    onValueChange = {
                        token = it
                        problem = null
                    },
                    error = problem?.takeIf { it !is TokenProblem.BadName }?.let { tokenProblemText(it) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val refused = onAdd(name, token)
                    if (refused == null) onDismiss() else problem = refused
                },
                enabled = mayAdd && token.isNotBlank(),
            ) { Text(stringResource(R.string.token_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Masked the way [io.github.purplepalm.custommaps.engine.mapbox.MapboxAccessTokens.mask] masks clearly fake tokens. */
private fun previewRow(n: Int, name: String, active: Boolean, locked: Boolean = false) =
    TokenRow(id = "t$n", name = name, masked = "pk.dGVz…2ln$n", active = active, mayUse = !active && !locked, mayDelete = !(active && locked))

@Preview(widthDp = 360)
@Composable
private fun TokenListSectionPreview(@PreviewParameter(LookParameter::class) look: Look) {
    CustomMapsTheme(look) {
        Surface {
            TokenListSection(
                state = TokenState.Ready(
                    rows = listOf(
                        previewRow(1, "Main", active = true),
                        previewRow(2, "Test", active = false),
                        previewRow(3, "Spare for long drives", active = false),
                    ),
                    locked = false,
                    full = false,
                    defaultName = "Token 2",
                ),
                onAdd = { _, _ -> null },
                onRename = { _, _ -> null },
                onUse = {},
                onDelete = {},
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Preview(widthDp = 360, fontScale = 1.3f)
@Composable
private fun TokenListSectionFullDuringTripPreview(@PreviewParameter(LookParameter::class) look: Look) {
    CustomMapsTheme(look) {
        Surface {
            TokenListSection(
                state = TokenState.Ready(
                    rows = listOf(
                        previewRow(1, "Main", active = false, locked = true),
                        previewRow(2, "Spare for the long trips", active = true, locked = true),
                        previewRow(3, "Test", active = false, locked = true),
                        previewRow(4, "Spare", active = false, locked = true),
                        previewRow(5, "Token 5", active = false, locked = true),
                    ),
                    locked = true,
                    full = true,
                    defaultName = "Token 6",
                ),
                onAdd = { _, _ -> null },
                onRename = { _, _ -> null },
                onUse = {},
                onDelete = {},
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun TokenListSectionEmptyPreview(@PreviewParameter(LookParameter::class) look: Look) {
    CustomMapsTheme(look) {
        Surface {
            TokenListSection(
                state = TokenState.Ready(rows = emptyList(), locked = false, full = false, defaultName = "Main"),
                onAdd = { _, _ -> null },
                onRename = { _, _ -> null },
                onUse = {},
                onDelete = {},
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
