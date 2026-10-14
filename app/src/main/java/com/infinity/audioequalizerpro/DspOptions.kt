package com.infinity.audioequalizerpro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.*

enum class EngineMode(val title: String) { ANDROID("기본 DSP · Android AudioEffect"), NATIVE("자체 DSP · Infinity Native") }
enum class PerformanceMode(val title: String, val frames: Int, val fade: Int) {
    LOW_LATENCY("저지연 · 128프레임", 128, 128),
    BALANCED("균형 · 256프레임", 256, 256),
    STABLE("안정성 · 1024프레임", 1024, 1024)
}

object NativeGraphicEq {
    fun frequencies(count: Int): List<Int> {
        require(count in listOf(10, 15, 31))
        return (0 until count).map { i ->
            // Logarithmically spaced between 20Hz and 20kHz.
            (20.0 * (1000.0).pow(i.toDouble() / (count - 1))).roundToInt()
        }
    }
    fun filters(frequencies: List<Int>, gains: List<Float>): DoubleArray {
        require(frequencies.size == gains.size && frequencies.size <= 31)
        val result = ArrayList<Double>(frequencies.size * 3)
        frequencies.zip(gains).forEach { (hz, db) ->
            if (kotlin.math.abs(db) > 0.001f) {
                result.add(hz.toDouble())
                result.add(db.coerceIn(-12f, 12f).toDouble())
                result.add(1.4) // Approximate graphic EQ overlap; not a measured response.
            }
        }
        return result.toDoubleArray()
    }
}

/** Only an app-owned test signal is rendered here; external playback remains on Android AudioEffect. */
object DspPreview {
    fun play(mode: PerformanceMode, frequency: Double, gainDb: Double, q: Double, preampDb: Double, avoidEnabled: Boolean, avoidHz: Double, avoidDb: Double, avoidWidth: Double, stereoWidth: Double, graphicFrequencies: List<Int> = emptyList(), graphicGains: List<Float> = emptyList(), done: (String) -> Unit) {
        Thread {
            try {
                val rate = 48000
                val minBufferBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                check(minBufferBytes > 0) { "PCM_FLOAT 출력 미지원" }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(max(minBufferBytes, mode.frames * 2 * 4 * 4)).build()
                try {
                    NativeDsp().use { dsp ->
                        val graphicFilters = NativeGraphicEq.filters(graphicFrequencies, graphicGains)
                        val extraFilters = if (avoidEnabled && avoidDb > 0.0)
                            doubleArrayOf(frequency, gainDb, q, avoidHz, -avoidDb, DislikedBand.nativeBellQ(avoidWidth))
                            else doubleArrayOf(frequency, gainDb, q)
                        val filters = graphicFilters + extraFilters
                        dsp.configure(rate, filters, preampDb, mode.fade)
                        dsp.setStereoWidth(stereoWidth)
                        track.play()
                        var written = 0
                        val total = rate * 3
                        while (written < total) {
                            val frames = min(mode.frames, total - written)
                            val pcm = FloatArray(frames * 2)
                            for (i in 0 until frames) {
                                val time = (written + i).toDouble() / rate
                                val v = 0.12 * (sin(2 * PI * 220 * time) + sin(2 * PI * 880 * time))
                                pcm[2*i] = (v + 0.04 * sin(2 * PI * 440 * time)).toFloat(); pcm[2*i+1] = (v + 0.04 * sin(2 * PI * 660 * time)).toFloat()
                            }
                            dsp.process(pcm)
                            // PCM_FLOAT hardware output may still clamp; demo is intentionally quiet.
                            val count = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                            check(count > 0) { "AudioTrack 출력 실패: $count" }
                            written += count / 2
                        }
                    }
                } finally { track.stop(); track.release() }
                done("자체 DSP 테스트 신호 재생 완료 (3초)")
            } catch (e: Exception) { done("테스트 실패: ${e.message}") }
        }.start()
    }
}
