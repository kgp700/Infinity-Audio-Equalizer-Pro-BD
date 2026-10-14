package com.infinity.audioequalizerpro

import android.app.*
import android.content.*
import android.media.audiofx.AudioEffect
import android.os.*
import android.media.AudioManager
import kotlin.math.abs
import androidx.core.app.NotificationCompat

/** Best-effort public audio-effect session broadcasts. Not a universal player tracker. */
class ExternalAudioService : Service() {
    private val sessions = mutableMapOf<Int, AudioEngine>()
    private val multiBand = mutableMapOf<Int, ExternalMultiBandEq>()
    private var globalEq: ExternalMultiBandEq? = null
    private var globalCount = -1
    private fun globalRequested() = prefs.getBoolean("global_mix_enabled", false) && multiMode()
    private fun syncGlobal() {
        if (!globalRequested()) {
            globalEq?.release(); globalEq = null; globalCount = -1
            prefs.edit().putString("global_mix_status", "꺼짐").apply()
            return
        }
        val count = multiCount()
        try {
            if (globalEq == null || globalCount != count) {
                globalEq?.release(); globalEq = ExternalMultiBandEq(0, count); globalCount = count
            }
            val prefix = if (nativeMode()) "native_band" else "android_multi_band"
            val levels = NativeGraphicEq.frequencies(count).map { prefs.getFloat("${prefix}_${count}_$it", 0f) }
            val enabled = prefs.getBoolean("external_eq_enabled", true)
            globalEq!!.apply(levels, enabled, prefs.getBoolean("eq_envelope", false), prefs.getBoolean("eq_headroom", true))
            prefs.edit().putString("global_mix_status", "세션 0 · ${count}밴드 · ${if (enabled) "활성 요청" else "꺼짐"} (음향 미검증)").apply()
        } catch (e: Exception) {
            globalEq?.release(); globalEq = null; globalCount = -1
            prefs.edit().putString("global_mix_status", "오류: ${e.message}").apply()
        }
    }
    private val sessionPackages = mutableMapOf<Int, String>()
    private fun appLabel(pkg: String): String = try {
        @Suppress("DEPRECATION")
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) { pkg }
    private fun publishSessions() {
        val detail = sessions.keys.sorted().joinToString("\n") { id ->
            val pkg = sessionPackages[id].orEmpty()
            val label = if (pkg.isBlank()) "앱 미확인" else appLabel(pkg)
            val dsp = if (multiMode()) "DynamicsProcessing ${multiCount()}밴드 (실제 청음 미검증)" else "Android 기본 EQ"
            "$label${if (pkg.isNotBlank()) " ($pkg)" else ""} · 세션 $id · $dsp"
        }
        prefs.edit().putString("detected_audio_apps", detail.ifBlank { "감지된 활성 오디오 세션 없음" })
            .putInt("detected_audio_session_count", sessions.size).apply()
    }
    private fun nativeMode(): Boolean = prefs.getString("engine_mode", "ANDROID") == "NATIVE"
    private fun multiMode(): Boolean = nativeMode() || prefs.getBoolean("android_multiband_enabled", false)
    private fun multiCount(): Int = if (nativeMode()) bandCount() else prefs.getInt("android_band_count", 10).takeIf { it in listOf(10, 15, 31) } ?: 10
    private fun bandCount(): Int = prefs.getInt("native_band_count", 10).takeIf { it in listOf(10, 15, 31) } ?: 10
    private fun syncMultiBand(id: Int) {
        if (!multiMode() || globalRequested()) {
            multiBand.remove(id)?.release()
            return
        }
        val count = multiCount()
        val old = multiBand[id]
        if (old != null && old.bandCount != count) { old.release(); multiBand.remove(id) }
        val eq = try { multiBand[id] ?: ExternalMultiBandEq(id, count).also { multiBand[id] = it } } catch (e: Exception) {
            prefs.edit().putString("multiband_status", "세션 $id: 엔진 생성 실패: ${e.message}").apply()
            return
        }
        val freqs = NativeGraphicEq.frequencies(count)
        val prefix = if (nativeMode()) "native_band" else "android_multi_band"
        val gains = freqs.map { prefs.getFloat("${prefix}_${count}_$it", 0f) }
        try {
            eq.apply(gains, prefs.getBoolean("external_eq_enabled", true), prefs.getBoolean("eq_envelope", false), prefs.getBoolean("eq_headroom", true))
            prefs.edit().putString("last_session_error", "").putInt("last_detected_session", id).putString("multiband_status", "세션 $id: ${count}밴드 설정 전달됨 (실제 음향 효과는 청음 확인 필요)").apply()
        } catch (e: Exception) {
            multiBand.remove(id)?.release()
            prefs.edit().putString("multiband_status", "세션 $id: 31밴드 엔진 오류: ${e.message}").apply()
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var checkCount = 0
    private val watchdog = object : Runnable {
        override fun run() {
            syncGlobal()
            if (sessions.isNotEmpty()) {
                sessions.toMap().forEach { (id, engine) ->
                    if (multiMode()) {
                        syncMultiBand(id)
                    } else if (!verifyAndRepair(id, engine)) {
                        prefs.edit().putString("last_session_error", "세션 $id 상태 확인 실패; 재생 앱을 다시 시작해 주세요").apply()
                    }
                }
            }
            handler.postDelayed(this, 15_000L)
        }
    }
    private val routeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Route changes can alter the underlying effect chain. Never toggle it blindly.
            handler.postDelayed({ refreshAll() }, 600L)
        }
    }
    private val prefs by lazy { getSharedPreferences("infinity_audio_eq", MODE_PRIVATE) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (id <= 0) return
            if (intent.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
                if (pkg.isNotBlank()) sessionPackages[id] = pkg
            }
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> open(id)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> close(id)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("audio_eq", "EQ 백그라운드 적용", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, "audio_eq")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Infinity Audio Equalizer Pro")
            .setContentText("지원되는 외부 앱 오디오 세션 감지 중")
            .setOngoing(true).build()
        startForeground(102, notification)
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        registerReceiver(routeReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        handler.postDelayed(watchdog, 15_000L)
        syncGlobal()
        prefs.edit().putBoolean("tracking_active", true).apply()
        publishSessions()
    }
    private fun open(id: Int) {
        if (sessions.containsKey(id)) { publishSessions(); return }
        if (multiMode()) {
            sessions[id] = AudioEngine() // Session lifecycle marker; never enable legacy EQ simultaneously.
            syncMultiBand(id)
            publishSessions()
            return
        }
        val engine = AudioEngine()
        if (!engine.attach(id)) {
            prefs.edit().putString("last_session_error", "세션 $id: ${engine.error}").apply()
            return
        }
        val levels = engine.frequencies.map { prefs.getFloat("band_$it", 0f) }
        val enabled = prefs.getBoolean("avoid_enabled", false)
        val center = prefs.getFloat("avoid_freq", 10000f)
        val depth = prefs.getFloat("avoid_depth", 3f)
        val width = prefs.getFloat("avoid_width", 1f)
        val projected = if (enabled) DislikedBand.project(engine.frequencies, levels, center.toDouble(), depth.toDouble(), width.toDouble(), engine.gainRange.start, engine.gainRange.endInclusive) else levels
        // Keep the effect disabled until all bands are programmed.
        projected.forEachIndexed { index, db -> engine.setBand(index, db.coerceAtMost(0f)) }
        engine.setEnabled(prefs.getBoolean("external_eq_enabled", true))
        sessions[id] = engine
        publishSessions()
        prefs.edit().putInt("last_detected_session", id).putString("last_session_error", "").apply()
    }
    private fun close(id: Int) { sessions.remove(id)?.release(); multiBand.remove(id)?.release(); sessionPackages.remove(id); publishSessions() }

    private fun desiredLevels(engine: AudioEngine): List<Float> {
        val base = engine.frequencies.map { prefs.getFloat("band_$it", 0f) }
        val projected = if (prefs.getBoolean("avoid_enabled", false)) DislikedBand.project(
            engine.frequencies, base,
            prefs.getFloat("avoid_freq", 10000f).toDouble(),
            prefs.getFloat("avoid_depth", 3f).toDouble(),
            prefs.getFloat("avoid_width", 1f).toDouble(),
            engine.gainRange.start, engine.gainRange.endInclusive
        ) else base
        return projected.map { it.coerceIn(engine.gainRange.start, 0f) }
    }

    private fun verifyAndRepair(id: Int, engine: AudioEngine): Boolean {
        // Read back actual device band values. No periodic disable/enable, release, or recreation.
        val actual = engine.levels()
        val desired = desiredLevels(engine)
        if (actual.size != desired.size || actual.isEmpty()) return false
        var repaired = false
        desired.forEachIndexed { index, target ->
            if (abs(actual[index] - target) > 0.015f) {
                engine.setBand(index, target)
                repaired = true
            }
        }
        val wantedEnabled = prefs.getBoolean("external_eq_enabled", true)
        if (engine.enabled != wantedEnabled) {
            engine.setEnabled(wantedEnabled)
            repaired = true
        }
        if (repaired) prefs.edit().putString("last_repair", "세션 $id: 밴드 설정 복구").apply()
        return engine.error == null
    }

    private fun refreshAll() {
        syncGlobal()
        sessions.toMap().forEach { (id, engine) ->
            if (multiMode()) {
                engine.release()
                syncMultiBand(id)
            } else {
                multiBand.remove(id)?.release()
                if (engine.frequencies.isEmpty()) {
                    sessions.remove(id)
                    open(id)
                } else verifyAndRepair(id, engine)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == "REFRESH") { refreshAll(); publishSessions() }
        return START_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        unregisterReceiver(routeReceiver)
        unregisterReceiver(receiver)
        sessions.values.forEach { it.release() }; sessions.clear()
        multiBand.values.forEach { it.release() }; multiBand.clear()
        globalEq?.release(); globalEq = null; globalCount = -1
        sessionPackages.clear(); publishSessions()
        prefs.edit().putBoolean("tracking_active", false).apply()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
