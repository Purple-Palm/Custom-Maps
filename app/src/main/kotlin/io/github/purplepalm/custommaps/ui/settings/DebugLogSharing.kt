package io.github.purplepalm.custommaps.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.purplepalm.custommaps.R
import java.io.File

/** Where exported log files are put for sharing. Must match res/xml/debug_log_paths.xml. */
const val DEBUG_LOG_EXPORT_DIRECTORY = "debug-log-export"

/** Opens the share sheet for exported debug log files, for example to save them to Google Drive. */
fun Context.shareDebugLog(files: List<File>) {
    val authority = "$packageName.debuglog"
    val uris = ArrayList(files.map { FileProvider.getUriForFile(this, authority, it) })
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    send.type = "text/plain"
    send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.debug_log_share_subject))
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, getString(R.string.debug_log_share)))
}
