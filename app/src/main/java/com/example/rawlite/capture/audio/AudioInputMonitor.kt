package com.example.rawlite.capture.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal data class AudioInputDevice(
    val id: Int,
    val type: Int,
    val name: String,
    val isSource: Boolean = true
) {
    val isExternal: Boolean get() = type in AudioInputPolicy.externalTypes
    val label: String get() = when {
        isExternal -> name.ifBlank { "Micrófono externo" }
        type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micrófono interno"
        else -> name.ifBlank { "Micrófono del sistema" }
    }
}

/** Only recording inputs are eligible; Bluetooth communication routing is separate. */
internal object AudioInputPolicy {
    val externalTypes = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL
    )

    fun select(devices: List<AudioInputDevice>, previousId: Int? = null): AudioInputDevice? =
        devices.filter { it.isSource && (it.isExternal || it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) }
            .minWithOrNull(compareBy<AudioInputDevice> { priority(it.type) }
                .thenBy { if (it.id == previousId) 0 else 1 }.thenBy { it.id })

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET -> 0
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 2
        else -> 1
    }
}

internal fun AudioDeviceInfo.inputDescription() = AudioInputDevice(
    id, type, productName?.toString().orEmpty(), isSource
)

/** Each owner registers and releases its own subscription, including in-flight captures. */
internal class AudioInputMonitor(
    context: Context,
    private val onChanged: (AudioDeviceInfo?) -> Unit
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var started = false
    private var selectedId: Int? = null
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        manager.unregisterAudioDeviceCallback(callback)
    }

    @Synchronized
    private fun refresh() {
        if (!started) return
        val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val selected = AudioInputPolicy.select(devices.map { it.inputDescription() }, selectedId)
        selectedId = selected?.id
        onChanged(devices.firstOrNull { it.id == selectedId })
    }
}
