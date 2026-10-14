package com.infinity.audioequalizerpro

import android.os.Bundle
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.pow

private val Bg = Color(0xFF090E17)
private val Panel = Color(0xFF151D2C)
private val Accent = Color(0xFF5988FF)
private val Ink = Color(0xFFEAF0FF)

class MainActivity : ComponentActivity() {
    private val engine = AudioEngine()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("infinity_audio_eq", MODE_PRIVATE)
        setContent {
            var page by remember { mutableIntStateOf(0) }
            var engineMode by remember { mutableStateOf(EngineMode.entries.firstOrNull { it.name == prefs.getString("engine_mode", "ANDROID") } ?: EngineMode.ANDROID) }
            var perfMode by remember { mutableStateOf(PerformanceMode.entries.firstOrNull { it.name == prefs.getString("perf_mode", "BALANCED") } ?: PerformanceMode.BALANCED) }
            var nativeFreq by remember { mutableFloatStateOf(3200f) }
            var nativeGain by remember { mutableFloatStateOf(0f) }
            var nativeQ by remember { mutableFloatStateOf(1f) }
            var nativePreamp by remember { mutableFloatStateOf(-6f) }
            var nativeBandCount by remember { mutableIntStateOf(prefs.getInt("native_band_count", 10).takeIf { it in listOf(10, 15, 31) } ?: 10) }
            val nativeFrequencies = remember(nativeBandCount) { NativeGraphicEq.frequencies(nativeBandCount) }
            var nativeGains by remember(nativeBandCount) {
                mutableStateOf(nativeFrequencies.map { prefs.getFloat("native_band_${nativeBandCount}_$it", 0f) })
            }
            var androidMultiBand by remember { mutableStateOf(prefs.getBoolean("android_multiband_enabled", false)) }
            var androidBandCount by remember { mutableIntStateOf(prefs.getInt("android_band_count", 10).takeIf { it in listOf(10, 15, 31) } ?: 10) }
            val androidFrequencies = remember(androidBandCount) { NativeGraphicEq.frequencies(androidBandCount) }
            var androidMultiGains by remember(androidBandCount) {
                mutableStateOf(androidFrequencies.map { prefs.getFloat("android_multi_band_${androidBandCount}_$it", 0f) })
            }
            val isNative = engineMode == EngineMode.NATIVE
            val useMultiBand = isNative || androidMultiBand
            val displayFrequencies = if (isNative) nativeFrequencies else if (androidMultiBand) androidFrequencies else engine.frequencies
            var gains by remember { mutableStateOf(emptyList<Float>()) }
            val displayGains = if (isNative) nativeGains else if (androidMultiBand) androidMultiGains else gains
            var connected by remember { mutableStateOf(false) }
            var globalMultiBand by remember { mutableStateOf<ExternalMultiBandEq?>(null) }
            var multiBandStatus by remember { mutableStateOf("연결 시도 전") }
            fun updateGlobalMultiBand() {
                globalMultiBand?.release()
                globalMultiBand = null
                if (!useMultiBand || prefs.getBoolean("global_mix_enabled", false)) {
                    multiBandStatus = if (useMultiBand) "글로벌 믹스: 백그라운드 서비스 관리" else "기본 Equalizer 사용"
                    return
                }
                try {
                    val count = if (isNative) nativeBandCount else androidBandCount
                    val effect = ExternalMultiBandEq(0, count)
                    val levels = if (isNative) nativeGains else androidMultiGains
                    effect.apply(levels, prefs.getBoolean("external_eq_enabled", true))
                    globalMultiBand = effect
                    multiBandStatus = "세션 0 · ${count}밴드 효과 설정 전달 (실제 출력 적용 미확인)"
                } catch (e: Exception) {
                    multiBandStatus = "다중 밴드 연결 실패: ${e.message}"
                }
            }
            var tracking by remember { mutableStateOf(prefs.getBoolean("external_tracking_requested", false)) }
            var avoidEnabled by remember { mutableStateOf(prefs.getBoolean("avoid_enabled", false)) }
            var avoidFreq by remember { mutableFloatStateOf(prefs.getFloat("avoid_freq", 10000f)) }
            var avoidStartText by remember { mutableStateOf(prefs.getInt("avoid_start_hz", 8000).toString()) }
            var avoidEndText by remember { mutableStateOf(prefs.getInt("avoid_end_hz", 12000).toString()) }
            var avoidInputError by remember { mutableStateOf("") }
            var avoidDepth by remember { mutableFloatStateOf(prefs.getFloat("avoid_depth", 3f)) }
            var avoidWidth by remember { mutableFloatStateOf(prefs.getFloat("avoid_width", 1.0f)) }
            var baseGains by remember { mutableStateOf(emptyList<Float>()) }
            fun projected(base: List<Float>): List<Float> = if (avoidEnabled)
                DislikedBand.project(engine.frequencies, base, avoidFreq.toDouble(), avoidDepth.toDouble(), avoidWidth.toDouble(), engine.gainRange.start, engine.gainRange.endInclusive)
                else base
            fun applyPreference(base: List<Float>) {
                if (tracking) startService(Intent(this@MainActivity, ExternalAudioService::class.java).setAction("REFRESH"))
                if (!connected) return
                projected(base).forEachIndexed { i, v -> engine.setBand(i, v) }
                gains = engine.levels()
            }
            var routeKey by remember { mutableStateOf(prefs.getString("selected_route", "speaker") ?: "speaker") }
            var detectedRoute by remember { mutableStateOf(OutputProfiles.detected(this@MainActivity)) }
            fun noticeRoutePlaceholder() { /* No profile: preserve current sound until user saves. */ }
            fun loadRouteProfile(key: String) {
                routeKey = key
                prefs.edit().putString("selected_route", key).apply()
                val obj = OutputProfiles.load(prefs, key)
                if (obj == null) { noticeRoutePlaceholder(); return }
                val fs = obj.optString("frequencies").split(",").mapNotNull { it.toIntOrNull() }
                val vs = obj.optString("levels").split(",").mapNotNull { it.toFloatOrNull() }
                baseGains = engine.frequencies.map { f -> fs.indexOf(f).let { i -> if (i in vs.indices) vs[i] else 0f } }
                avoidEnabled = obj.optBoolean("avoidEnabled", false)
                avoidStartText = obj.optString("start", "8500")
                avoidEndText = obj.optString("end", "12700")
                avoidDepth = obj.optDouble("depth", 3.0).toFloat()
                val range = DislikedBand.rangeParameters(avoidStartText.toDoubleOrNull() ?: 8500.0, avoidEndText.toDoubleOrNull() ?: 12700.0)
                if (range != null) { avoidFreq = range.first.toFloat(); avoidWidth = range.second.toFloat() }
                engineMode = EngineMode.entries.firstOrNull { it.name == obj.optString("engine") } ?: EngineMode.ANDROID
                perfMode = PerformanceMode.entries.firstOrNull { it.name == obj.optString("performance") } ?: PerformanceMode.BALANCED
                applyPreference(baseGains)
            }
            var previewRunning by remember { mutableStateOf(false) }
            var on by remember { mutableStateOf(true) }
            var session by remember { mutableStateOf("0") }
            var bass by remember { mutableFloatStateOf(0f) }
            var space by remember { mutableFloatStateOf(0f) }
            var nativeWidth by remember { mutableFloatStateOf(prefs.getFloat("native_width", 0f)) }
            var dolbyReport by remember { mutableStateOf("확인 버튼을 눌러 기기 공개 효과 목록을 조회하세요.") }
            var loud by remember { mutableFloatStateOf(0f) }
            var notice by remember { mutableStateOf("기본 설정은 세션 0입니다. 모든 앱에 적용되지는 않습니다.") }
            var presetName by remember { mutableStateOf("My EQ") }
            val presetKeys = remember { mutableStateListOf<String>().apply { addAll(prefs.getString("preset_names", "")!!.split("|").filter { it.isNotBlank() }) } }
            fun connect() {
                val id = session.toIntOrNull()
                if (id == null || id < 0) { notice = "유효한 오디오 세션 ID를 입력하세요."; return }
                connected = engine.attach(id)
                gains = engine.levels()
                if (connected) {
                    gains.forEachIndexed { i, _ -> prefs.getFloat("band_${engine.frequencies.getOrNull(i)}", 0f).let { engine.setBand(i, it) } }
                    baseGains = engine.levels()
                    applyPreference(baseGains)
                    engine.setBass(bass); engine.setSpatial(space); engine.setLoudness(loud)
                    engine.setEnabled(on && !useMultiBand)
                    notice = "세션 $id 연결됨 · 실제 재생 앱 적용 여부는 기기에서 확인하세요."
                } else notice = engine.error ?: "연결 실패"
            }
            LaunchedEffect(Unit) { connect() }
            DisposableEffect(Unit) { onDispose { globalMultiBand?.release() } }
            LaunchedEffect(isNative, androidMultiBand, androidBandCount, nativeBandCount, on) {
                if (useMultiBand) engine.setEnabled(false) else engine.setEnabled(on)
                updateGlobalMultiBand()
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel, onSurface = Ink)) {
                Scaffold(containerColor = Bg, topBar = {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, null, tint = Accent)
                        Spacer(Modifier.width(10.dp))
                        Text("Infinity Audio Equalizer Pro", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = { on = it; engine.setEnabled(it && !useMultiBand); prefs.edit().putBoolean("external_eq_enabled", it).apply(); if (tracking) startService(Intent(this@MainActivity, ExternalAudioService::class.java).setAction("REFRESH")) })
                    }
                }, bottomBar = {
                    NavigationBar(containerColor = Panel) {
                        listOf(Triple("홈", Icons.Default.Home, 0), Triple("EQ", Icons.Default.Equalizer, 1), Triple("효과", Icons.Default.Tune, 2), Triple("프리셋", Icons.Default.Bookmarks, 3), Triple("설정", Icons.Default.Settings, 4)).forEach { (label, icon, id) ->
                            NavigationBarItem(selected = page == id, onClick = { page = id }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 10.sp) })
                        }
                    }
                }) { inset ->
                    LazyColumn(Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item { InfoCard(notice, if (connected) "DSP 연결됨" else "DSP 연결 안 됨") }
                        when (page) {
                            0 -> {
                                item { Section("현재 오디오 엔진") { Text(if (useMultiBand) "${if (isNative) "자체" else "기본"} DSP · ${displayFrequencies.size}밴드 (DynamicsProcessing 실험)" else "기본 DSP · ${engine.frequencies.size}밴드", color = Ink); Text(multiBandStatus, color = Color.LightGray, fontSize = 12.sp); Text("세션: ${engine.sessionId} / 상태: ${if (on && connected) "활성" else "비활성"}", color = Color.LightGray) } }
                                item { Section("EQ 응답 미리보기") { EqGraph(displayFrequencies, displayGains); Text("시각화는 밴드 레벨 보간이며 실제 측정된 주파수 응답이 아닙니다.", fontSize = 11.sp, color = Color.Gray) } }
                                item { Section("빠른 이동") { Button(onClick = { page = 1 }, modifier = Modifier.fillMaxWidth()) { Text("그래픽 이퀄라이저 열기") }; OutlinedButton(onClick = { page = 4 }, modifier = Modifier.fillMaxWidth()) { Text("오디오 세션 연결 설정") } } }
                            }
                            1 -> {
                                item { Section("그래픽 이퀄라이저") {
                                    Text(if (useMultiBand) "다중 밴드 ${displayFrequencies.size}개 · 외부 앱 적용 여부 확인 필요" else "기기 기본 EQ ${engine.frequencies.size}밴드", color = Ink)
                                    if (!isNative) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("기본 DSP 다중 밴드 실험", color = Ink, modifier = Modifier.weight(1f))
                                            Switch(checked = androidMultiBand, onCheckedChange = {
                                                androidMultiBand = it
                                                prefs.edit().putBoolean("android_multiband_enabled", it).apply()
                                                if (tracking) startService(Intent(this@MainActivity, ExternalAudioService::class.java).setAction("REFRESH"))
                                            })
                                        }
                                    }
                                    if (useMultiBand) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(10, 15, 31).forEach { count ->
                                                FilterChip(selected = (if (isNative) nativeBandCount else androidBandCount) == count, onClick = {
                                                    if (isNative) {
                                                        nativeBandCount = count
                                                        prefs.edit().putInt("native_band_count", count).apply()
                                                    } else {
                                                        androidBandCount = count
                                                        prefs.edit().putInt("android_band_count", count).apply()
                                                    }
                                                    if (tracking) startService(Intent(this@MainActivity, ExternalAudioService::class.java).setAction("REFRESH"))
                                                }, label = { Text("${count}밴드") })
                                            }
                                        }
                                    }
                                    EqGraph(displayFrequencies, displayGains)
                                } }
                                itemsIndexed(displayFrequencies) { i, hz ->
                                    Section("${hz} Hz") {
                                        val value = displayGains.getOrElse(i) { 0f }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Slider(value = value, onValueChange = { v ->
                                                if (isNative) {
                                                    nativeGains = nativeGains.toMutableList().apply { this[i] = v }
                                                    prefs.edit().putFloat("native_band_${nativeBandCount}_$hz", v).apply()
                                                    updateGlobalMultiBand()
                                                    if (tracking) startService(Intent(this@MainActivity, ExternalAudioService::class.java).setAction("REFRESH"))
                                                } else if (androidMultiBand) {
                                                    androidMultiGains = androidMultiGains.toMutableList().apply { this[i] = v }
                                                    prefs.edit().putFloat("android_multi_band_${androidBandCount}_$hz", v).apply()
                                                    updateGlobalMultiBand()
                                                    if (tracking) startService(Intent(this@MainActivity, ExternalAudioService::class.java).setAction("REFRESH"))
                                                } else {
                                                    baseGains = baseGains.toMutableList().apply { this[i] = v }
                                                    applyPreference(baseGains)
                                                    prefs.edit().putFloat("band_$hz", v).apply()
                                                }
                                            }, valueRange = if (useMultiBand) -12f..12f else engine.gainRange, enabled = on && (useMultiBand || connected), modifier = Modifier.weight(1f))
                                            Text("%+.1f dB".format(value), color = Ink, modifier = Modifier.width(88.dp), textAlign = TextAlign.End)
                                        }
                                    }
                                }
                                item { OutlinedButton(onClick = {
                                    if (isNative) {
                                        nativeGains = List(nativeBandCount) { 0f }
                                        val editor = prefs.edit()
                                        nativeFrequencies.forEach { editor.putFloat("native_band_${nativeBandCount}_$it", 0f) }
                                        editor.apply()
                                        updateGlobalMultiBand()
                                    } else if (androidMultiBand) {
                                        androidMultiGains = List(androidBandCount) { 0f }
                                        val editor = prefs.edit()
                                        androidFrequencies.forEach { editor.putFloat("android_multi_band_${androidBandCount}_$it", 0f) }
                                        editor.apply()
                                        updateGlobalMultiBand()
                                    } else {
                                        baseGains = List(engine.frequencies.size) { 0f }
                                        baseGains.indices.forEach { prefs.edit().putFloat("band_${engine.frequencies[it]}", 0f).apply() }
                                        applyPreference(baseGains)
                                    }
                                }, modifier = Modifier.fillMaxWidth(), enabled = useMultiBand || connected) { Text("EQ 초기화") } }
                            }
                            2 -> {
                                item { Section("비선호 주파수 자연 감쇠") {
                                    Text("선택 대역을 부드럽게 낮춥니다. 자동 음량 조절·컴프레서·리미터를 사용하지 않습니다.", color = Color.LightGray, fontSize = 12.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("활성화", color = Ink, modifier = Modifier.weight(1f))
                                        Switch(checked = avoidEnabled, onCheckedChange = { avoidEnabled = it; prefs.edit().putBoolean("avoid_enabled", it).apply(); applyPreference(baseGains) })
                                    }
                                    Text("감쇠 주파수 범위 (Hz) · 숫자 직접 입력", color = Ink)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedTextField(value = avoidStartText, onValueChange = { avoidStartText = it.filter(Char::isDigit).take(5) }, label = { Text("시작 Hz") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                        OutlinedTextField(value = avoidEndText, onValueChange = { avoidEndText = it.filter(Char::isDigit).take(5) }, label = { Text("끝 Hz") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                                    }
                                    if (avoidInputError.isNotEmpty()) Text(avoidInputError, color = Color(0xFFFF8D8D), fontSize = 12.sp)
                                    Button(onClick = {
                                        val startHz = avoidStartText.toDoubleOrNull()
                                        val endHz = avoidEndText.toDoubleOrNull()
                                        val parameters = if (startHz != null && endHz != null) DislikedBand.rangeParameters(startHz, endHz) else null
                                        if (parameters == null) avoidInputError = "20~20,000Hz 범위에서 시작 주파수는 끝보다 작아야 합니다."
                                        else {
                                            avoidFreq = parameters.first.toFloat()
                                            avoidWidth = parameters.second.toFloat()
                                            prefs.edit().putInt("avoid_start_hz", startHz!!.toInt()).putInt("avoid_end_hz", endHz!!.toInt()).putFloat("avoid_freq", avoidFreq).putFloat("avoid_width", avoidWidth).apply()
                                            avoidInputError = ""
                                            applyPreference(baseGains)
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("주파수 범위 적용") }
                                    Text("실제 감쇠 곡선: 중심 ${avoidFreq.toInt()}Hz / 폭 %.2f옥타브 · 경계에서 갑자기 잘리지 않고 완만하게 감쇠".format(avoidWidth), color = Color.LightGray, fontSize = 12.sp)
                                    Text("최대 감쇠 %.1f dB".format(avoidDepth), color = Ink)
                                    Slider(avoidDepth, { avoidDepth = it; prefs.edit().putFloat("avoid_depth", it).apply(); applyPreference(baseGains) }, valueRange = 0f..12f)
                                    Text("기본 DSP에서는 기기 밴드에 근사 적용됩니다. 자체 DSP는 피킹 필터로 적용되므로 응답 곡선이 완전히 같지는 않습니다.", color = Color.Gray, fontSize = 11.sp)
                                } }
                                item { InfoCard("음량 펌핑 방지를 위해 저음 강화·서라운드·라우드니스는 시작 시 모두 OFF입니다. 추가 효과는 신호 피크를 증가시킬 수 있으므로 필요한 경우에만 활성화하세요. 이 버전에는 피크 기반 자동 헤드룸 보정이 없습니다.", "음질 우선 · 안전 기본값") }
                                item { Section("저음 강화") { EffectSlider(bass, 0f..1f, { bass = it; engine.setBass(it); prefs.edit().putFloat("bass", it).apply() }, connected && on, "${(bass * 100).toInt()}%") } }
                                item { Section("가상 서라운드") { EffectSlider(space, 0f..1f, { space = it; engine.setSpatial(it); prefs.edit().putFloat("space", it).apply() }, connected && on, "${(space * 100).toInt()}%") } }
                                item { Section("Infinity Spatial Audio · 자체 DSP") {
                                    Text("중앙 보컬을 유지하도록 설계한 정적 Mid/Side 스테레오 폭 조절입니다. 추가 지연 버퍼·AGC·리미터가 없습니다. 현재는 앱 내부 테스트 PCM에만 적용됩니다.", color = Color.LightGray, fontSize = 12.sp)
                                    Text("확장 강도 ${(nativeWidth * 100).toInt()}%", color = Ink)
                                    Slider(nativeWidth, { nativeWidth = it; prefs.edit().putFloat("native_width", it).apply() }, valueRange = 0f..0.5f)
                                    Text("스테레오 확장은 위상/공간 문제의 근본 해결책이 아닙니다. 모노 호환성과 보컬 위치를 비교하세요.", color = Color.Gray, fontSize = 11.sp)
                                } }
                                item { Section("Dolby Atmos · 기기 연동") {
                                    Text("Dolby Atmos는 라이선스가 필요한 별도 엔진입니다. 이 앱에는 Dolby 디코더/렌더러가 포함되지 않으며, 기기에 제공된 Dolby 설정을 안내합니다.", color = Color.LightGray, fontSize = 12.sp)
                                    Text(dolbyReport, color = Ink, fontSize = 12.sp)
                                    OutlinedButton(onClick = { dolbyReport = DolbyCompatibility.report() }, modifier = Modifier.fillMaxWidth()) { Text("Dolby 효과 검색") }
                                    OutlinedButton(onClick = { if (!DolbyCompatibility.openSystemAudioSettings(this@MainActivity)) dolbyReport = "시스템 소리 설정을 열 수 없습니다." }, modifier = Modifier.fillMaxWidth()) { Text("시스템 소리 설정 열기") }
                                    Text("기기에서 Dolby가 활성화되어도 EQ와 동시에 처리되는지, 순서가 무엇인지는 보장할 수 없습니다.", color = Color.Gray, fontSize = 11.sp)
                                } }
                                item { Section("라우드니스 강화") { EffectSlider(loud, 0f..12f, { loud = it; engine.setLoudness(it); prefs.edit().putFloat("loud", it).apply() }, connected && on, "%.1f dB".format(loud)) } }
                                item { InfoCard("컴프레서, 리미터, 파라메트릭 EQ는 Android 표준 AudioEffect만으로 범용 구현할 수 없어 이 빌드에 포함되지 않습니다.", "지원 범위") }
                            }
                            3 -> {
                                item { Section("프리셋 저장") { OutlinedTextField(value = presetName, onValueChange = { presetName = it }, label = { Text("프리셋 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Button(onClick = {
                                    val name = presetName.trim().replace("|", "_")
                                    if (name.isNotBlank()) {
                                        val obj = JSONObject().put("freq", engine.frequencies.joinToString(",")).put("levels", baseGains.joinToString(",")).put("bass", bass.toDouble()).put("space", space.toDouble()).put("loud", loud.toDouble())
                                        prefs.edit().putString("preset_$name", obj.toString()).apply()
                                        if (name !in presetKeys) { presetKeys.add(name); prefs.edit().putString("preset_names", presetKeys.joinToString("|")).apply() }
                                        notice = "프리셋 '$name' 저장 완료"
                                    }
                                }, enabled = connected, modifier = Modifier.fillMaxWidth()) { Text("현재 설정 저장") } } }
                                itemsIndexed(presetKeys) { _, name ->
                                    Section(name) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Button(onClick = {
                                                try {
                                                    val obj = JSONObject(prefs.getString("preset_$name", "{}")!!)
                                                    val fs = obj.optString("freq").split(",").mapNotNull { it.toIntOrNull() }
                                                    val vs = obj.optString("levels").split(",").mapNotNull { it.toFloatOrNull() }
                                                    baseGains = engine.frequencies.map { f -> fs.indexOf(f).let { idx -> if (idx >= 0 && idx < vs.size) vs[idx] else 0f } }; applyPreference(baseGains)
                                                     bass = obj.optDouble("bass", 0.0).toFloat(); space = obj.optDouble("space", 0.0).toFloat(); loud = obj.optDouble("loud", 0.0).toFloat()
                                                    engine.setBass(bass); engine.setSpatial(space); engine.setLoudness(loud)
                                                    prefs.edit().putFloat("bass", bass).putFloat("space", space).putFloat("loud", loud).apply()
                                                    notice = "'$name' 적용됨"
                                                } catch (e: Exception) { notice = "프리셋 읽기 실패: ${e.message}" }
                                            }, enabled = connected, modifier = Modifier.weight(1f)) { Text("적용") }
                                            Spacer(Modifier.width(12.dp))
                                            OutlinedButton(onClick = { presetKeys.remove(name); prefs.edit().remove("preset_$name").putString("preset_names", presetKeys.joinToString("|")).apply() }) { Text("삭제") }
                                        }
                                    }
                                }
                            }
                            else -> {
                                item { Section("출력 장치별 EQ · Poweramp 방식") {
                                    Text("감지된 출력: ${OutputProfiles.label(detectedRoute)}", color = Ink)
                                    Text("Android Auto는 자동 감지가 확실하지 않아 수동으로 독립 프로필을 선택합니다. Bluetooth 차량 프로필과 공유하지 않습니다.", color = Color.LightGray, fontSize = 12.sp)
                                    OutlinedButton(onClick = { detectedRoute = OutputProfiles.detected(this@MainActivity) }, modifier = Modifier.fillMaxWidth()) { Text("현재 출력 다시 감지") }
                                    listOf("speaker", "wired", "usb", "bluetooth", OutputProfiles.ANDROID_AUTO, "other").forEach { key ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = routeKey == key, onClick = { loadRouteProfile(key); notice = "${OutputProfiles.label(key)} 프로필 선택됨 · 저장된 설정이 있으면 불러왔습니다." })
                                            Text(OutputProfiles.label(key), color = Ink)
                                        }
                                    }
                                    Button(onClick = {
                                        OutputProfiles.save(prefs, routeKey, engine.frequencies, baseGains, avoidEnabled, avoidStartText, avoidEndText, avoidDepth, engineMode, perfMode)
                                        notice = "${OutputProfiles.label(routeKey)} 전용 프로필 저장 완료"
                                    }, modifier = Modifier.fillMaxWidth()) { Text("선택한 출력 장치에 EQ 설정 저장") }
                                    Text("현재 버전은 출력 전환 시 자동 적용이 아니라 수동 선택 방식입니다. Android Auto 연결 시 DSP가 실제 차량 소리에 적용되는지는 별도로 검증해야 합니다.", color = Color.Gray, fontSize = 11.sp)
                                } }
                                item { Section("DSP 엔진 선택") {
                                    EngineMode.entries.forEach { mode ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = engineMode == mode, onClick = { engineMode = mode; prefs.edit().putString("engine_mode", mode.name).apply(); if (tracking) startService(Intent(this@MainActivity, ExternalAudioService::class.java).setAction("REFRESH")) })
                                            Text(mode.title, color = Ink)
                                        }
                                    }
                                    Text("두 모드 모두 10·15·31밴드를 선택할 수 있습니다. 외부 앱 다중 밴드는 Android DynamicsProcessing 실험이며 C++ DSP 직접 삽입이 아닙니다. 기본 DSP 다중 밴드를 끄면 기존 기기 EQ로 복귀합니다.", color = Color.LightGray, fontSize = 12.sp)
                                } }
                                item { Section("성능 및 지연 설정") {
                                    PerformanceMode.entries.forEach { mode ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = perfMode == mode, onClick = { perfMode = mode; prefs.edit().putString("perf_mode", mode.name).apply() })
                                            Text(mode.title, color = Ink)
                                        }
                                    }
                                    Text("48kHz 기준 블록 길이: %.2f ms · 실제 전체 출력 지연은 기기/버퍼/블루투스에 따라 추가됩니다.".format(perfMode.frames * 1000.0 / 48000), color = Color.LightGray, fontSize = 12.sp)
                                } }
                                item { Section("자체 DSP 실동작 테스트 · 3초") {
                                    Text("선택된 자체 EQ 밴드 + Peaking 필터 · 48kHz 스테레오 · 자동 리미터/컴프레서 없음", color = Color.LightGray, fontSize = 12.sp)
                                    Text("주파수 ${nativeFreq.toInt()} Hz", color = Ink)
                                    Slider(nativeFreq, { nativeFreq = it }, valueRange = 100f..12000f)
                                    Text("Gain %.1f dB".format(nativeGain), color = Ink)
                                    Slider(nativeGain, { nativeGain = it }, valueRange = -12f..12f)
                                    Text("Q %.2f".format(nativeQ), color = Ink)
                                    Slider(nativeQ, { nativeQ = it }, valueRange = 0.2f..12f)
                                    Text("프리앰프 %.1f dB".format(nativePreamp), color = Ink)
                                    Slider(nativePreamp, { nativePreamp = it }, valueRange = -24f..0f)
                                    Button(onClick = {
                                        previewRunning = true
                                        DspPreview.play(perfMode, nativeFreq.toDouble(), nativeGain.toDouble(), nativeQ.toDouble(), nativePreamp.toDouble(), avoidEnabled, avoidFreq.toDouble(), avoidDepth.toDouble(), avoidWidth.toDouble(), nativeWidth.toDouble(), if (isNative) nativeFrequencies else emptyList(), if (isNative) nativeGains else emptyList()) { msg ->
                                            runOnUiThread { notice = msg; previewRunning = false }
                                        }
                                    }, enabled = !previewRunning, modifier = Modifier.fillMaxWidth()) { Text(if (previewRunning) "테스트 재생 중" else "자체 DSP 테스트 소리 재생") }
                                } }
                                item { Section("주요 사용 앱 · 호환성 안내") {
                                    Text("YouTube, 삼성 인터넷/Chrome 등 브라우저, 멜론, Spotify, 벅스, 지니, 비디오 플레이어", color = Ink)
                                    Text("기본 DSP: 해당 앱의 접근 가능한 Audio Session에만 적용될 수 있습니다. 세션 0으로 전체 앱 적용은 보장되지 않습니다.", color = Color.LightGray, fontSize = 12.sp)
                                    Text("자체 DSP: 현재는 앱 내부 테스트 PCM에만 적용됩니다. 다른 앱 오디오를 임의로 가로채거나 재출력하지 않습니다.", color = Color.LightGray, fontSize = 12.sp)
                                    Text("브라우저·동영상·스트리밍 앱의 DRM, 오프로드, 독점 출력 경로는 기기별로 적용되지 않을 수 있습니다.", color = Color.LightGray, fontSize = 12.sp)
                                } }
                                item { Section("글로벌 믹스 EQ · 실험적") {
                                    var globalMix by remember { mutableStateOf(prefs.getBoolean("global_mix_enabled", false)) }
                                    var globalStatus by remember { mutableStateOf(prefs.getString("global_mix_status", "미시도") ?: "미시도") }
                                    Text("세션 0에 다중 밴드 효과를 연결합니다. 실제 음악 출력 적용은 기기마다 다릅니다.", color = Color.LightGray, fontSize = 12.sp)
                                    Switch(checked = globalMix, onCheckedChange = { enabled ->
                                        globalMix = enabled
                                        prefs.edit().putBoolean("global_mix_enabled", enabled).apply()
                                        updateGlobalMultiBand()
                                        if (enabled && !tracking) {
                                            tracking = true
                                            prefs.edit().putBoolean("external_tracking_requested", true).apply()
                                            ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, ExternalAudioService::class.java))
                                        } else if (tracking) startService(Intent(this@MainActivity, ExternalAudioService::class.java).setAction("REFRESH"))
                                    })
                                    Text("상태: $globalStatus", color = Ink, fontSize = 12.sp)
                                    OutlinedButton(onClick = {
                                        if (tracking) startService(Intent(this@MainActivity, ExternalAudioService::class.java).setAction("REFRESH"))
                                        globalStatus = prefs.getString("global_mix_status", "미시도") ?: "미시도"
                                    }) { Text("글로벌 상태 새로고침") }
                                } }
                                item { Section("외부 앱 EQ · 자동 세션 추적 (실험적)") {
                                    Text("멜론·YouTube·Spotify 등에서 공개 오디오 효과 세션 이벤트가 전달되면 세션별 Android EQ를 연결합니다. 이벤트를 보내지 않는 앱, 오프로드 및 일부 블루투스 경로는 적용되지 않을 수 있습니다.", color = Color.LightGray, fontSize = 12.sp)
                                    Switch(checked = tracking, onCheckedChange = { enabled ->
                                        tracking = enabled
                                        prefs.edit().putBoolean("external_tracking_requested", enabled).apply()
                                        val intent = Intent(this@MainActivity, ExternalAudioService::class.java)
                                        if (enabled) ContextCompat.startForegroundService(this@MainActivity, intent)
                                        else stopService(intent)
                                    })
                                    var sessionDiagnostic by remember { mutableStateOf(prefs.getString("detected_audio_apps", "아직 감지 정보 없음") ?: "") }
                                    var sessionStatus by remember { mutableStateOf(prefs.getString("multiband_status", "상태 정보 없음") ?: "") }
                                    Text("감지된 외부 오디오 앱 / 연결 세션", color = Ink)
                                    Text(sessionDiagnostic, color = Color.LightGray, fontSize = 12.sp)
                                    Text("다중 밴드 DSP: $sessionStatus", color = Color.LightGray, fontSize = 12.sp)
                                    Text("최근 감지 세션: ${prefs.getInt("last_detected_session", -1)} · ${prefs.getString("last_session_error", "")}", color = Ink)
                                    Text("글로벌 다중 밴드: $multiBandStatus", color = Ink, fontSize = 12.sp)
                                    Button(onClick = {
                                        sessionDiagnostic = prefs.getString("detected_audio_apps", "감지된 정보 없음") ?: ""
                                        sessionStatus = prefs.getString("multiband_status", "상태 정보 없음") ?: ""
                                        notice = "오디오 앱/세션 진단 정보를 새로고침했습니다."
                                    }) { Text("감지 앱 및 DSP 상태 새로고침") }
                                    Text("감지/설정 전달 성공은 실제 음향 적용의 증거가 아닙니다.", color = Color.Gray, fontSize = 11.sp)
                                    Text("세션 감지는 실제 음질 변화의 증거가 아닙니다. 음악 재생 중 EQ ±6dB 비교로 직접 확인하세요. 서비스는 알림을 표시하며 앱에서 끌 수 있습니다.", color = Color.Gray, fontSize = 11.sp)
                                } }
                                item { Section("오디오 세션 연결") { OutlinedTextField(value = session, onValueChange = { session = it.filter(Char::isDigit) }, label = { Text("Audio Session ID (0 = 레거시 믹스)") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Button(onClick = { connect() }, modifier = Modifier.fillMaxWidth()) { Text("연결 / 재연결") } } }
                                item { InfoCard("세션 0은 최신 Android에서 지원되지 않거나 실제 재생 신호에 적용되지 않을 수 있습니다. 타사 앱의 Audio Session ID는 일반 앱이 보편적으로 획득할 수 없습니다. 세션 0의 성공 표시가 실제 음향 효과 적용을 보장하지 않습니다.", "호환성 안내") }
                                item { InfoCard("이 앱은 녹음·화면 캡처·접근성 권한을 요구하지 않습니다. 다른 앱의 오디오를 가로채거나 우회하지 않습니다.", "권한 및 개인정보") }
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onDestroy() { engine.release(); super.onDestroy() }
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}
@Composable private fun InfoCard(message: String, title: String) { Section(title) { Text(message, color = Color(0xFFBAC7DF), fontSize = 13.sp, lineHeight = 19.sp) } }
@Composable private fun EffectSlider(value: Float, range: ClosedFloatingPointRange<Float>, update: (Float) -> Unit, enabled: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) { Slider(value, update, Modifier.weight(1f), enabled = enabled, valueRange = range); Text(label, color = Ink, modifier = Modifier.width(72.dp), textAlign = TextAlign.End) }
}
@Composable private fun EqGraph(freq: List<Int>, gains: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(175.dp)) {
        val w = size.width; val h = size.height
        for (i in 0..6) drawLine(Color(0xFF273349), Offset(0f, h * i / 6), Offset(w, h * i / 6), 1f)
        for (i in 0..8) drawLine(Color(0xFF273349), Offset(w * i / 8, 0f), Offset(w * i / 8, h), 1f)
        if (freq.isEmpty() || gains.isEmpty()) return@Canvas
        val pts = freq.zip(gains).filter { it.first > 0 }.map { (f, db) ->
            val x = ((log10(f.toFloat()) - log10(20f)) / (log10(20000f) - log10(20f))).coerceIn(0f, 1f) * w
            Offset(x, (0.5f - db / 36f).coerceIn(0f, 1f) * h)
        }.sortedBy { it.x }
        if (pts.isNotEmpty()) {
            val path = Path().apply { moveTo(pts.first().x, pts.first().y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, Accent, style = Stroke(width = 3.dp.toPx()))
            pts.forEach { drawCircle(Color(0xFF78D9FF), radius = 4.dp.toPx(), center = it) }
        }
    }
}
