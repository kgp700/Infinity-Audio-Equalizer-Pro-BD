package com.infinity.audioequalizerpro

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.provider.Settings

/** Discovery only. Dolby is proprietary: this app does not instantiate or impersonate it. */
object DolbyCompatibility {
    fun report(): String = try {
        val matches = AudioEffect.queryEffects().orEmpty().filter {
            (it.name + " " + it.implementor).contains("dolby", ignoreCase = true)
        }
        if (matches.isEmpty()) "공개 AudioEffect 목록에서 Dolby 효과를 찾지 못했습니다. 미탐지라고 Dolby 미설치를 의미하지는 않습니다."
        else "Dolby 관련 효과 감지: " + matches.joinToString { it.name } + ". 실제 제어 및 동시 적용은 지원되지 않습니다."
    } catch (e: Exception) { "Dolby 효과 조회 실패: ${e.message}" }

    fun openSystemAudioSettings(context: Context): Boolean = try {
        context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) { false }
}
