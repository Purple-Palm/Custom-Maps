package io.github.purplepalm.custommaps.ui.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper

/** Whether this is a debug build (the project builds no BuildConfig); only those offer Crash now. */
fun Context.isDebuggable(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * Ends the app with an uncaught exception on the main thread, as a real crash would, so that the
 * debug log's "crash" line and, at the next start, Android's record of how the process ended can be
 * checked on the phone. Thrown from the main looper, outside any click handling.
 */
fun crashNow() {
    Handler(Looper.getMainLooper()).post {
        throw IllegalStateException("Crash now, from Settings: a deliberate crash to test the crash record")
    }
}
