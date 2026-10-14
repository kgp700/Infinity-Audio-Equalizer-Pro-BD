package com.infinity.audioequalizerpro

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.SharedPreferences
import org.json.JSONObject

/** Route classification is advisory. Android Auto cannot be reliably inferred from a Bluetooth route. */
object OutputProfiles {
    const val ANDROID_AUTO = "android_auto"
    fun detected(context: Context): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val chosen = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
        return when (chosen?.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            else -> "speaker"
        }
    }
    fun label(key: String) = when (key) {
        ANDROID_AUTO -> "Android Auto · 기타 기기"
        "usb" -> "USB 오디오"
        "bluetooth" -> "블루투스 오디오"
        "wired" -> "유선 헤드폰"
        "speaker" -> "내장 스피커"
        else -> "기타 기기"
    }
    fun save(prefs: SharedPreferences, key: String, frequencies: List<Int>, levels: List<Float>, avoidEnabled: Boolean, start: String, end: String, depth: Float, mode: EngineMode, performance: PerformanceMode) {
        val json = JSONObject().put("frequencies", frequencies.joinToString(","))
            .put("levels", levels.joinToString(","))
            .put("avoidEnabled", avoidEnabled).put("start", start).put("end", end)
            .put("depth", depth.toDouble()).put("engine", mode.name).put("performance", performance.name)
        prefs.edit().putString("output_profile_$key", json.toString()).apply()
    }
    fun load(prefs: SharedPreferences, key: String): JSONObject? = prefs.getString("output_profile_$key", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
}
