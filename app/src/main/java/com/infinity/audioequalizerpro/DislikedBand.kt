package com.infinity.audioequalizerpro

import kotlin.math.*

/** Smooth, strictly subtractive preference attenuation. Never uses dynamic gain control. */
object DislikedBand {
    fun attenuationDb(hz: Double, centerHz: Double, depthDb: Double, widthOctaves: Double): Double {
        if (hz <= 0.0 || centerHz <= 0.0 || !hz.isFinite() || !centerHz.isFinite()) return 0.0
        val width = widthOctaves.coerceIn(0.25, 4.0)
        val octaves = log2(hz / centerHz)
        // Width represents full width at half attenuation.
        return -depthDb.coerceIn(0.0, 12.0) * exp(-4.0 * ln(2.0) * octaves * octaves / (width * width))
    }

    /** Convert user-entered frequency boundaries into a smooth bell's center and FWHM. */
    fun rangeParameters(startHz: Double, endHz: Double): Pair<Double, Double>? {
        if (!startHz.isFinite() || !endHz.isFinite() || startHz < 20.0 || endHz > 20000.0 || startHz >= endHz) return null
        val center = sqrt(startHz * endHz)
        val width = log2(endHz / startHz).coerceIn(0.25, 4.0)
        return center to width
    }

    fun project(centerFrequencies: List<Int>, base: List<Float>, centerHz: Double, depthDb: Double,
                widthOctaves: Double, minDb: Float, maxDb: Float): List<Float> =
        centerFrequencies.mapIndexed { index, hz ->
            (base.getOrElse(index) { 0f } + attenuationDb(hz.toDouble(), centerHz, depthDb, widthOctaves).toFloat())
                .coerceIn(minDb, maxDb)
        }

    /** RBJ bell approximation for native PCM processing; exact shape differs from Gaussian preview. */
    fun nativeBellQ(widthOctaves: Double): Double {
        val bw = widthOctaves.coerceIn(0.25, 4.0)
        return (sqrt(2.0.pow(1.0 / bw)) / (2.0.pow(bw / 2.0) - 2.0.pow(-bw / 2.0))).coerceIn(0.2, 12.0)
    }
}
