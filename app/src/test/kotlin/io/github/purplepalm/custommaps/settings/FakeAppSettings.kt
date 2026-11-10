package io.github.purplepalm.custommaps.settings

import io.github.purplepalm.custommaps.nav.model.CameraStyle
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [AppSettings] kept in memory. Every test that needs settings uses this one fake, so a new setting
 * is added to the tests in one place.
 */
class FakeAppSettings(
    simulateDrive: Boolean = false,
    cameraStyle: CameraStyle = CameraStyle.DEFAULT,
) : AppSettings {

    /** While true every change fails as it does on a full disk, and the stored value stays. */
    var storageFull = false

    override val simulateDrive = MutableStateFlow(simulateDrive)

    override suspend fun setSimulateDrive(enabled: Boolean) = store { simulateDrive.value = enabled }

    override val cameraStyle = MutableStateFlow(cameraStyle)

    override suspend fun setCameraStyle(style: CameraStyle) = store { cameraStyle.value = style }

    private fun store(change: () -> Unit) {
        if (storageFull) throw IOException("No space left on device")
        change()
    }
}
