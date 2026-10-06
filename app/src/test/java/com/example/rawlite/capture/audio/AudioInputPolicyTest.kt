package com.example.rawlite.capture.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioInputPolicyTest {
    private val internal = AudioInputDevice(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel")
    private val usb = AudioInputDevice(2, AudioDeviceInfo.TYPE_USB_DEVICE, "DJI Mic Mini")
    private val wired = AudioInputDevice(3, AudioDeviceInfo.TYPE_WIRED_HEADSET, "Headset")

    @Test fun `connecting USB takes precedence over built in and wired inputs`() {
        assertEquals(usb, AudioInputPolicy.select(listOf(internal, wired, usb), wired.id))
    }

    @Test fun `disconnecting USB falls back to wired then internal`() {
        assertEquals(wired, AudioInputPolicy.select(listOf(internal, wired), usb.id))
        assertEquals(internal, AudioInputPolicy.select(listOf(internal), usb.id))
    }

    @Test fun `output only USB and bluetooth devices do not become recording inputs`() {
        val output = usb.copy(isSource = false)
        val bluetooth = AudioInputDevice(4, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Headset")
        assertEquals(internal, AudioInputPolicy.select(listOf(output, bluetooth, internal)))
        assertNull(AudioInputPolicy.select(listOf(output, bluetooth)))
    }

    @Test fun `adding equal priority input preserves the current microphone`() {
        val second = usb.copy(id = 5)
        assertEquals(second, AudioInputPolicy.select(listOf(usb, second), second.id))
        assertEquals(usb, AudioInputPolicy.select(listOf(second, usb)))
    }

    @Test fun `USB headset and accessory input are external microphones`() {
        for (type in listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)) {
            val input = usb.copy(type = type)
            assertEquals(input, AudioInputPolicy.select(listOf(internal, input)))
        }
    }

    @Test fun `device labels distinguish built in and unnamed external microphones`() {
        assertEquals("Micrófono interno", internal.label)
        assertEquals("DJI Mic Mini", usb.label)
        assertEquals("Micrófono externo", usb.copy(name = "").label)
        assertNull(AudioInputPolicy.select(emptyList()))
    }
}
