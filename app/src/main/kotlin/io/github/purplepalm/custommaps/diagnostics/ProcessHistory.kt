package io.github.purplepalm.custommaps.diagnostics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import io.github.purplepalm.custommaps.nav.debug.AnrTrace
import io.github.purplepalm.custommaps.nav.debug.DebugLog
import io.github.purplepalm.custommaps.nav.debug.ExitKey
import io.github.purplepalm.custommaps.nav.debug.LoggedExits
import io.github.purplepalm.custommaps.nav.debug.StateSummary
import io.github.purplepalm.custommaps.nav.debug.error
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * What the debug log cannot record by itself: how earlier processes ended (an uncaught exception,
 * a crash in native code, an ANR, the system or the user killing it) and why this one started.
 * Android keeps both. [logOnStart] reads them once per process; call it off the main thread.
 */
internal class ProcessHistory(private val context: Context, private val debugLog: DebugLog) {

    private val activityManager: ActivityManager? = context.getSystemService(ActivityManager::class.java)

    /** Not backed up: on another phone the records it points to do not exist. */
    private val exitsLogged = LoggedExits(File(context.noBackupFilesDir, EXITS_LOGGED_FILE))

    fun logOnStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) logEarlierExits()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) logThisStart()
    }

    /**
     * Android keeps [summary] (from [StateSummary.encode], so at most 128 bytes) with this process
     * and hands it back with the process's exit record, so the next start can tell what the app was
     * doing when it died. A call into the system: call it off the main thread, and only when the
     * state changes.
     */
    fun setStateSummary(summary: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            activityManager?.setProcessStateSummary(summary)
        } catch (e: RuntimeException) {
            // Refused; the previous summary stays.
            debugLog.error("process_state_summary", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun logEarlierExits() {
        val exits = try {
            activityManager?.getHistoricalProcessExitReasons(null, 0, MAX_EXITS).orEmpty()
        } catch (e: RuntimeException) {
            debugLog.log("process_exits_unavailable", mapOf("error" to e.toString()))
            return
        }
        exitsLogged.unseen(exits) { it.key() }.forEach { debugLog.log("process_exit", it.debugFields()) }
        exitsLogged.markLogged(exits.map { it.key() })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.key() = ExitKey(pid, timestamp)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun logThisStart() {
        val start = try {
            activityManager?.getHistoricalProcessStartReasons(1)?.firstOrNull()
        } catch (e: RuntimeException) {
            null
        }
        if (start == null || start.pid != Process.myPid()) return
        debugLog.log(
            "process_start",
            mapOf(
                "reason" to startReasonName(start.reason),
                "startType" to startTypeName(start.startType),
                // True when the user (or a cleaner app) force-stopped the app before this start.
                "wasForceStopped" to start.wasForceStopped(),
                "component" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    startComponentName(start.startComponent)
                } else {
                    null
                },
            ).filterValues { it != null },
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.debugFields(): Map<String, Any?> = mapOf(
        "diedAt" to Instant.ofEpochMilli(timestamp).toString(),
        "reason" to exitReasonName(reason),
        // Free text from the system, such as "remove task" or the crash's exception.
        "description" to description,
        // The exit code, or the signal number when the process was killed or crashed natively.
        "status" to status,
        "importance" to importanceName(importance),
        "pid" to pid,
        "process" to processName,
        "pssKb" to pss,
        "rssKb" to rss,
        // What the app was doing, as this app last told Android (StateSummary).
        "summary" to processStateSummary?.let(StateSummary::decode),
        "anr" to if (reason == ApplicationExitInfo.REASON_ANR) anrSummary() else null,
    )

    /** A native crash's tombstone is binary (protobuf) and is left out; its signal is in "status". */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.anrSummary(): Map<String, Any?>? =
        try {
            traceInputStream?.bufferedReader()?.useLines { AnrTrace.summarize(it) }
        } catch (e: IOException) {
            mapOf("error" to e.toString())
        }

    private companion object {
        const val EXITS_LOGGED_FILE = "debug-log-exits-logged"

        /** Android keeps a short ring buffer per app; the first start after an update logs all of it. */
        const val MAX_EXITS = 16

        // The constants are compiled in; phones that predate a reason never report it.
        @SuppressLint("InlinedApi")
        fun exitReasonName(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
            else -> "REASON_$reason"
        }

        @Suppress("DEPRECATION")
        fun importanceName(importance: Int): String = when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "CANT_SAVE_STATE"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
            else -> "IMPORTANCE_$importance"
        }

        @SuppressLint("InlinedApi")
        fun startReasonName(reason: Int): String = when (reason) {
            ApplicationStartInfo.START_REASON_LAUNCHER -> "LAUNCHER"
            ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS -> "LAUNCHER_RECENTS"
            ApplicationStartInfo.START_REASON_START_ACTIVITY -> "START_ACTIVITY"
            ApplicationStartInfo.START_REASON_SERVICE -> "SERVICE"
            ApplicationStartInfo.START_REASON_BROADCAST -> "BROADCAST"
            ApplicationStartInfo.START_REASON_CONTENT_PROVIDER -> "CONTENT_PROVIDER"
            ApplicationStartInfo.START_REASON_JOB -> "JOB"
            ApplicationStartInfo.START_REASON_ALARM -> "ALARM"
            ApplicationStartInfo.START_REASON_PUSH -> "PUSH"
            ApplicationStartInfo.START_REASON_BOOT_COMPLETE -> "BOOT_COMPLETE"
            ApplicationStartInfo.START_REASON_BACKUP -> "BACKUP"
            ApplicationStartInfo.START_REASON_OTHER -> "OTHER"
            else -> "START_REASON_$reason"
        }

        @SuppressLint("InlinedApi")
        fun startComponentName(component: Int): String = when (component) {
            ApplicationStartInfo.START_COMPONENT_ACTIVITY -> "ACTIVITY"
            ApplicationStartInfo.START_COMPONENT_BROADCAST -> "BROADCAST"
            ApplicationStartInfo.START_COMPONENT_CONTENT_PROVIDER -> "CONTENT_PROVIDER"
            ApplicationStartInfo.START_COMPONENT_SERVICE -> "SERVICE"
            ApplicationStartInfo.START_COMPONENT_OTHER -> "OTHER"
            else -> "COMPONENT_$component"
        }

        @SuppressLint("InlinedApi")
        fun startTypeName(type: Int): String = when (type) {
            ApplicationStartInfo.START_TYPE_COLD -> "COLD"
            ApplicationStartInfo.START_TYPE_WARM -> "WARM"
            ApplicationStartInfo.START_TYPE_HOT -> "HOT"
            else -> "UNSET"
        }
    }
}
