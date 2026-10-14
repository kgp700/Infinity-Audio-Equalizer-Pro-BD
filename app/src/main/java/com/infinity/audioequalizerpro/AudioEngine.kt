package com.infinity.audioequalizerpro

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log

/** Public AudioEffect implementation. Session 0 is a best-effort legacy mix and is NOT universal. */
class AudioEngine {
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    var error: String? = null; private set
    var frequencies: List<Int> = emptyList(); private set
    var gainRange: ClosedFloatingPointRange<Float> = -15f..15f; private set
    var enabled = false; private set
    var sessionId = 0; private set

    fun attach(session: Int = 0): Boolean {
        release()
        sessionId = session
        return try {
            eq = Equalizer(0, session).apply { enabled = false }
            frequencies = (0 until (eq?.numberOfBands?.toInt() ?: 0)).map { eq!!.getCenterFreq(it.toShort()) / 1000 }
            val range = eq!!.bandLevelRange
            gainRange = (range[0] / 100f)..(range[1] / 100f)
            try { bass = BassBoost(0, session).apply { enabled = false } } catch (e: Exception) { Log.w("AudioFX", "Bass unavailable", e) }
            try { virtualizer = Virtualizer(0, session).apply { enabled = false } } catch (e: Exception) { Log.w("AudioFX", "Virtualizer unavailable", e) }
            try { loudness = LoudnessEnhancer(session).apply { enabled = false } } catch (e: Exception) { Log.w("AudioFX", "Loudness unavailable", e) }
            enabled = false; error = null; true
        } catch (e: Exception) {
            error = "오디오 효과 연결 실패: ${e.message}"; release(); false
        }
    }
    fun levels(): List<Float> = try { (frequencies.indices).map { eq!!.getBandLevel(it.toShort()) / 100f } } catch (_: Exception) { emptyList() }
    fun setBand(index: Int, db: Float) {
        try { if (index in frequencies.indices) eq?.setBandLevel(index.toShort(), (db.coerceIn(gainRange.start, gainRange.endInclusive) * 100).toInt().toShort()) }
        catch (e: Exception) { error = "밴드 설정 실패: ${e.message}" }
    }
    fun setBass(value: Float) { try { bass?.setStrength((value.coerceIn(0f, 1f) * 1000).toInt().toShort()); bass?.enabled = enabled && value > 0f } catch (e: Exception) { error = e.message } }
    fun setSpatial(value: Float) { try { virtualizer?.setStrength((value.coerceIn(0f, 1f) * 1000).toInt().toShort()); virtualizer?.enabled = enabled && value > 0f } catch (e: Exception) { error = e.message } }
    fun setLoudness(db: Float) { try { loudness?.setTargetGain((db.coerceIn(0f, 12f) * 100).toInt()); loudness?.enabled = enabled && db > 0f } catch (e: Exception) { error = e.message } }
    fun setEnabled(on: Boolean) {
        enabled = on
        try { eq?.enabled = on; bass?.enabled = on && (bass?.roundedStrength ?: 0) > 0; virtualizer?.enabled = on && (virtualizer?.roundedStrength ?: 0) > 0; loudness?.enabled = on && (loudness?.targetGain ?: 0) > 0 }
        catch (e: Exception) { error = e.message }
    }
    fun release() {
        listOf(eq, bass, virtualizer, loudness).forEach { try { it?.release() } catch (_: Exception) {} }
        eq = null; bass = null; virtualizer = null; loudness = null
        frequencies = emptyList(); enabled = false
    }
}
