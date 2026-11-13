package io.github.purplepalm.custommaps.diagnostics

import android.annotation.SuppressLint
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi

/** Names for the system settings that both the device and the network lines report. */
internal object SystemNames {

    /** How often Android lets the app run in the background: ACTIVE is best, RESTRICTED worst. Asks the system. */
    @RequiresApi(Build.VERSION_CODES.P)
    fun standbyBucket(context: Context): String? =
        context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket?.let(::standbyBucketName)

    // The constants are compiled in; phones that predate a bucket never report it.
    @SuppressLint("InlinedApi")
    fun standbyBucketName(bucket: Int): String = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
        else -> "BUCKET_$bucket"
    }

    /**
     * A display's power state ([Display.getState]): ON, OFF, the always-on DOZE and DOZE_SUSPEND,
     * VR, ON_SUSPEND, or UNKNOWN.
     */
    // The constants are compiled in; phones that predate a state never report it.
    @SuppressLint("InlinedApi")
    fun displayStateName(state: Int): String = when (state) {
        Display.STATE_ON -> "ON"
        Display.STATE_OFF -> "OFF"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_VR -> "VR"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        Display.STATE_UNKNOWN -> "UNKNOWN"
        else -> "STATE_$state"
    }

    /**
     * Data Saver as it applies to this app in the background. ON covers both Data Saver on without
     * an exemption for the app and "Allow background data usage" off for it.
     */
    fun dataSaverName(status: Int?): String? = when (status) {
        null -> null
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "OFF"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "ON_APP_EXEMPT"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "ON"
        else -> "STATUS_$status"
    }
}
