package com.infinity.audioequalizerpro

import android.media.audiofx.DynamicsProcessing

/** Experimental public-API multi-band effect for an already discovered external audio session. */
class ExternalMultiBandEq(sessionId: Int, val bandCount: Int) {
    private val effect: DynamicsProcessing
    init {
        require(bandCount in listOf(10, 15, 31))
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, true, bandCount, false, 0, false, 0, false
        ).build()
        effect = DynamicsProcessing(0, sessionId, cfg)
        effect.enabled = false
    }
    fun apply(gains: List<Float>, enabled: Boolean) {
        require(gains.size == bandCount)
        val frequencies = NativeGraphicEq.frequencies(bandCount)
        for (ch in 0 until effect.channelCount) {
            for (i in 0 until bandCount) {
                val band = effect.getPreEqBandByChannelIndex(ch, i)
                band.isEnabled = true
                band.cutoffFrequency = frequencies[i].toFloat()
                band.gain = gains[i].coerceIn(-12f, 12f)
                effect.setPreEqBandByChannelIndex(ch, i, band)
            }
        }
        effect.enabled = enabled
    }
    fun release() { effect.release() }
}
