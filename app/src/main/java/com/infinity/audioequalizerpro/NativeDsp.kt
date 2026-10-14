package com.infinity.audioequalizerpro

/** Processes stereo interleaved float PCM owned by this app. Does not intercept other apps. */
class NativeDsp : AutoCloseable {
    companion object { init { System.loadLibrary("infinitydsp") } }
    private var handle = nativeCreate()
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeConfigure(ptr: Long, sampleRate: Int, filters: DoubleArray, preampDb: Double, fadeFrames: Int)
    private external fun nativeProcess(ptr: Long, pcm: FloatArray)
    private external fun nativeSetStereoWidth(ptr: Long, amount: Double)
    @Synchronized fun configure(sampleRate: Int, filters: DoubleArray, preampDb: Double, fadeFrames: Int = 256) {
        check(handle != 0L); nativeConfigure(handle, sampleRate, filters, preampDb, fadeFrames)
    }
    @Synchronized fun setStereoWidth(amount: Double) { check(handle != 0L); nativeSetStereoWidth(handle, amount.coerceIn(0.0, 0.5)) }
    @Synchronized fun process(pcm: FloatArray) { check(handle != 0L); nativeProcess(handle, pcm) }
    @Synchronized override fun close() { if (handle != 0L) { nativeDestroy(handle); handle = 0L } }
}
