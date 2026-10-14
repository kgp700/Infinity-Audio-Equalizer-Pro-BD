package com.infinity.audioequalizerpro

import android.media.audiofx.DynamicsProcessing
import kotlin.math.max

/** Public Android audio effect. External player support is firmware/session dependent. */
class ExternalMultiBandEq(sessionId: Int, val bandCount: Int) {
    private val effect: DynamicsProcessing
    private var lastLevels: List<Float>? = null
    private var lastHeadroom = Float.NaN
    private var lastEnabled: Boolean? = null
    init {
        require(bandCount in listOf(10, 15, 31))
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, true, bandCount, false, 0, false, 0, false
        ).build()
        effect = DynamicsProcessing(0, sessionId, cfg)
        effect.enabled = false
    }
    fun apply(gains: List<Float>, enabled: Boolean, envelope: Boolean = false, headroom: Boolean = true) {
        require(gains.size == bandCount)
        // Cascade keeps Android's native band gains; envelope suppresses abrupt isolated spikes.
        val levels = if (envelope && bandCount > 2) gains.mapIndexed { i, v ->
            val left = gains.getOrElse(i - 1) { v }
            val right = gains.getOrElse(i + 1) { v }
            (v * 0.75f + (left + right) * 0.125f).coerceIn(-12f, 12f)
        } else gains.map { it.coerceIn(-12f, 12f) }
        val attenuation = if (headroom) -max(0f, levels.maxOrNull() ?: 0f).coerceAtMost(12f) else 0f
        if (lastLevels != levels) {
            val frequencies = NativeGraphicEq.frequencies(bandCount)
            for (ch in 0 until effect.channelCount) {
                for (i in 0 until bandCount) {
                    if (lastLevels != null && lastLevels!![i] == levels[i]) continue
                    val band = effect.getPreEqBandByChannelIndex(ch, i)
                    band.isEnabled = true
                    band.cutoffFrequency = frequencies[i].toFloat()
                    band.gain = levels[i]
                    effect.setPreEqBandByChannelIndex(ch, i, band)
                }
            }
            lastLevels = levels
        }
        if (lastHeadroom != attenuation) {
            for (ch in 0 until effect.channelCount) effect.setInputGainByChannelIndex(ch, attenuation)
            lastHeadroom = attenuation
        }
        if (lastEnabled != enabled) { effect.enabled = enabled; lastEnabled = enabled }
    }
    fun release() { effect.release() }
}
