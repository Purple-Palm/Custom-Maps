package io.github.purplepalm.custommaps.car

import android.annotation.SuppressLint
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import io.github.purplepalm.custommaps.CustomMapsApplication

/**
 * The service the car's host binds to, to show the app on the car's screen: Android Auto lists the
 * app by it, in the navigation category. It stays in the composition root (DECISIONS 12); its sessions
 * come from [io.github.purplepalm.custommaps.AppGraph].
 *
 * It runs in the app's one process, with the drive and the phone's screen. It is only ever bound,
 * never started: a started service would outlive the host's unbind and hand the old binder to the next
 * bind, whose session would then never hear that it ended. The manifest declares the car's main
 * display only, so there is one session at a time.
 */
class CustomMapsCarAppService : CarAppService() {

    /**
     * The hosts that may bind: those of the library's own allow-list, Android Auto and the Android
     * Automotive templates host, each by the digest of its signing certificate. The same in every build
     * type, the debug build included, which is the one driven every day: a host that the list does not
     * name would see the map, the routes and the destination, and could press the app's buttons
     * (DECISIONS 30). Lint calls the list private to the library, but the library's own documentation
     * of this method names it, and a copy would not follow its updates.
     */
    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(sessionInfo: SessionInfo): Session =
        (application as CustomMapsApplication).graph.carSession(sessionInfo)
}
