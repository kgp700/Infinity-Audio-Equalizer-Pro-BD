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
    private val handler = Handler(Looper.getMainLooper())
    private var checkCount = 0
    private val watchdog = object : Runnable {
        override fun run() {
            if (sessions.isNotEmpty()) {
                sessions.toMap().forEach { (id, engine) ->
                    if (!verifyAndRepair(id, engine)) {
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
        prefs.edit().putBoolean("tracking_active", true).apply()
    }
    private fun open(id: Int) {
        if (sessions.containsKey(id)) return
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
        prefs.edit().putInt("last_detected_session", id).putString("last_session_error", "").apply()
    }
    private fun close(id: Int) { sessions.remove(id)?.release() }

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
        sessions.toMap().forEach { (id, engine) -> verifyAndRepair(id, engine) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == "REFRESH") refreshAll()
        return START_STICKY
    }
    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        unregisterReceiver(routeReceiver)
        unregisterReceiver(receiver)
        sessions.values.forEach { it.release() }; sessions.clear()
        prefs.edit().putBoolean("tracking_active", false).apply()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
