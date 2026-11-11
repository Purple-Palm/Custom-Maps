package io.github.purplepalm.custommaps.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** The privacy policy: docs/PRIVACY.md of the public repository, as GitHub shows it. */
internal const val PRIVACY_POLICY_URL = "https://github.com/Purple-Palm/Custom-Maps/blob/main/docs/PRIVACY.md"

/**
 * Runs [start], which opens another app as `startActivity` does, and returns whether it did. A phone
 * without an app for it ([ActivityNotFoundException]), or one that does not let this app start it
 * ([SecurityException]), gets false; nothing else stops.
 */
internal fun opened(start: () -> Unit): Boolean = try {
    start()
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

/** Opens [url] in the browser; false when the phone cannot. */
internal fun Context.openWebPage(url: String): Boolean = opened {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}
