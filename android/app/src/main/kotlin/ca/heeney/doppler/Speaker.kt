package ca.heeney.doppler

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Whether music would come out of the phone itself: nothing plugged in, paired or cast to. */
object Speaker {
    private val private = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK, 26 /* BLE headset */,
        27 /* BLE speaker */, 30 /* BLE broadcast */, 29 /* HDMI eARC */, 10 /* HDMI ARC */, 31 /* dock analog */,
    )

    fun isPhone(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type in private }
    }

    /** A name for where it's going, for the screen. */
    fun outputName(context: Context): String? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val device = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in private } ?: return null
        return device.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
    }
}
