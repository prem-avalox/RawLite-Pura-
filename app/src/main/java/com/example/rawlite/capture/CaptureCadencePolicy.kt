package com.example.rawlite.capture

internal object CaptureCadencePolicy {
    const val AUTO_CADENCE_LOCK_THRESHOLD_FPS = 30

    fun frameDurationNs(fps: Int): Long {
        return 1_000_000_000L / fps.coerceAtLeast(1)
    }

    fun shouldFreezeAutoExposureForRawRecording(
        includeRaw: Boolean,
        fps: Int,
        manualExposure: Boolean,
        snapshotIso: Int?,
        snapshotExposureNs: Long?
    ): Boolean {
        return includeRaw &&
            fps < AUTO_CADENCE_LOCK_THRESHOLD_FPS &&
            !manualExposure &&
            snapshotIso != null &&
            snapshotExposureNs != null
    }

    fun exposurePolicyName(
        manualExposure: Boolean,
        explicitExposureLock: Boolean,
        autoCadenceLock: Boolean
    ): String {
        return when {
            manualExposure -> "manual"
            explicitExposureLock -> "locked"
            autoCadenceLock -> "auto-cadence-lock"
            else -> "auto"
        }
    }
}
