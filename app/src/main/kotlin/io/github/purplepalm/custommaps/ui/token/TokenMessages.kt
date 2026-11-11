package io.github.purplepalm.custommaps.ui.token

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.purplepalm.custommaps.R
import io.github.purplepalm.custommaps.engine.mapbox.AccessTokenCheck
import io.github.purplepalm.custommaps.token.NameProblem
import io.github.purplepalm.custommaps.token.TokenList
import io.github.purplepalm.custommaps.token.TokenProblem

/** What the screen says about [problem]. */
@Composable
internal fun tokenProblemText(problem: TokenProblem): String = when (problem) {
    is TokenProblem.Duplicate -> stringResource(problem.messageRes(), problem.name)
    else -> stringResource(problem.messageRes())
}

/** The text for [this] problem; a duplicate's names the entry it duplicates, and a long name's gives [TokenList.MAX_NAME_LENGTH]. */
@StringRes
internal fun TokenProblem.messageRes(): Int = when (this) {
    is TokenProblem.BadToken -> check.messageRes()
    is TokenProblem.BadName -> when (problem) {
        NameProblem.EMPTY -> R.string.token_error_name_empty
        NameProblem.TOO_LONG -> R.string.token_error_name_long
        NameProblem.TAKEN -> R.string.token_error_name_taken
        NameProblem.LOOKS_LIKE_TOKEN -> R.string.token_error_name_token
    }
    is TokenProblem.Duplicate -> R.string.token_error_duplicate
    TokenProblem.Full -> R.string.token_error_full
    TokenProblem.NotFound -> R.string.token_error_gone
    TokenProblem.DuringTrip -> R.string.token_locked_during_guidance
}

@StringRes
internal fun AccessTokenCheck.messageRes(): Int = when (this) {
    AccessTokenCheck.VALID -> R.string.token_saved
    AccessTokenCheck.EMPTY -> R.string.token_error_empty
    AccessTokenCheck.SECRET_TOKEN -> R.string.token_error_secret
    AccessTokenCheck.NOT_PUBLIC_TOKEN -> R.string.token_error_not_public
    AccessTokenCheck.MALFORMED -> R.string.token_error_malformed
}
