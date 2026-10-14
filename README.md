# Infinity Audio Equalizer Pro v0.3

Android Studio Kotlin/Compose + Android AudioEffect + C++17 JNI DSP.

## Engine scope
- Android AudioEffect: existing device-supported EQ for accessible audio session IDs, best effort only. Session 0 is not universal.
- Infinity Native DSP: working stereo float PCM peaking filter, adjustable frequency/gain/Q, static preamp, smooth filter-bank transition, no AGC/limiter/compressor. Currently wired to **app-owned 3-second test playback only**. It does NOT process Spotify/YouTube or other apps.
- Engine selection and latency profile settings are persisted. Switching to Native does not intercept other apps or replace Android's global audio pipeline.
- Three buffer profiles 128/256/1024 frames, corresponding to 2.67/5.33/21.33 ms at 48kHz **per block** (not end-to-end latency). AudioTrack may add substantial buffering.

## Build
Open root in Android Studio, sync Gradle, install Android SDK 35, NDK and CMake (3.22.1 or newer), run app. Offline environment here does not include Android SDK, so APK compile and device tests have not been performed.

## Quality caveats
- DSP output is unclipped float; hardware float output may clip at output. Set preamp low enough to maintain headroom. No automatic volume pumping.
- Filter state changes are crossfaded. DSP uses double precision coefficients and accumulators; output float PCM.
- No universal custom-DSP hook exists for third-party apps with ordinary app privileges. Capturing third-party playback with MediaProjection requires user consent and cooperating apps, and does not provide a general way to replace their output. Root/system integration would be a separate, high-risk device-specific implementation.
- No claimed Poweramp audio equivalence or tested latency without device measurement.

## v0.4: 비선호 대역 자연 감쇠
- 효과 탭에 비선호 주파수 중심(40~20000Hz), 감쇠량(0~12dB), 폭(0.25~4옥타브), ON/OFF 및 영구 저장 추가.
- 기본 DSP는 실제 장치가 노출한 Equalizer 밴드의 중심 주파수에 로그-가우시안 감쇠량을 투영. 기존 사용자가 지정한 기본 EQ 값은 별도 보존하여 옵션 OFF 시 복구. 밴드가 적은 기기에서는 정밀한 10kHz 감쇠가 불가능할 수 있음.
- 자체 DSP 테스트 PCM에서는 기존 피킹 필터에 별도 감쇠 Biquad를 추가. 로그-가우시안과 RBJ 피킹 필터의 곡선은 정확히 동일하지 않음.
- 컴프레서/AGC/리미터 미사용. 볼륨 펌핑을 만들지 않는 정적 감쇠. 기존 출력 경로/기기 리미터 동작까지 보장하지 않음.
- 타사 앱의 자체 DSP 처리는 여전히 지원되지 않음. Android SDK/실기기 미보유로 전체 APK 빌드와 음향 측정 미완료.

## v0.5: numeric disliked-frequency interval and target app scope
- Effect screen: start/end frequency entered numerically (20–20,000 Hz), validated and persisted on Apply; invalid intervals are rejected without altering active filter.
- The entered range is converted to geometric center and logarithmic full-width at half attenuation; narrow ranges are limited to 0.25 octave and wide ranges to 4 octaves. The edges are not brick-wall cutoffs.
- Existing static Gaussian projection for Android EQ and native peaking approximation are retained; no AGC, compressor or limiter is introduced.
- Settings disclose compatibility for YouTube, browsers, Melon, Spotify, Bugs, Genie and video players. No false per-app attachment claim: Android AudioEffect requires accessible sessions, native DSP only processes app-owned PCM.

## v0.7: spatial audio and Dolby compatibility
- Native stereo widening: adjustable 0–50% Mid/Side processing on app-owned PCM only, OFF by default. Fixed normalization bounds the channel peak for bounded input; no AGC, compressor, limiter or extra lookahead buffer. Fixed normalization also lowers the center component when widening is enabled; loudness-matched listening tests are recommended.
- Android system Virtualizer is retained as the best-effort spatial effect for accessible external audio sessions. Its behavior is OEM-dependent.
- Dolby integration: query public Android AudioEffect descriptors for Dolby branding and provide a link to system sound settings. **No Dolby Atmos implementation, proprietary decoder, hidden API control, or guarantee of simultaneous effects.**
- No app-wide native DSP insertion into third-party apps; Android SDK build and real-device verification remain pending.

## v0.8 외부 앱 오디오 세션 감지 (실험적)
- 사용자가 설정에서 활성화하는 포그라운드 서비스는 Android 공개 `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` / `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION` 방송을 수신하여 해당 세션에 플랫폼 `Equalizer`를 생성합니다.
- 일반 앱이 타사 오디오 세션을 보편적으로 발견할 수 있는 API는 없으므로 이 방송을 보내지 않는 플레이어에서는 작동하지 않습니다. 방송 자체도 최신 Android 버전/기기에서 제한될 수 있습니다.
- 외부 앱에는 네이티브 C++ DSP가 아닌 Android AudioEffect가 적용됩니다. 실제 오디오 변화는 기기에서 반드시 청음으로 확인해야 합니다.
- 포그라운드 알림과 서비스 중지 스위치를 제공합니다. 재생 앱 별 자동 식별, 실제 적용 증명, 블루투스 기기별 자동 전환, Android Auto 오디오 적용은 아직 구현/검증되지 않았습니다.
- 현재 환경에는 Android SDK/Gradle이 없어 APK 빌드 및 실기기 검증을 하지 못했습니다.

## v0.9 세션 일치성 감시 (2026-10-15)
- 활성 세션별 15초 간격으로 실제 밴드값을 읽어 저장된 목표와 비교합니다. 값이 어긋난 밴드만 재적용합니다.
- REFRESH는 EQ 객체를 해제하거나 ON/OFF하지 않고 기존 세션을 재검증합니다.
- AUDIO_BECOMING_NOISY 알림 뒤 재검증합니다. 블루투스 재연결이나 모든 출력 경로 변경을 감지한다고 보장하지 않습니다.
- 세션 자체가 교체되었는데 앱에서 공개 세션 이벤트를 발행하지 않는 경우에는 자동 복구가 불가능할 수 있습니다.
- Android AudioEffect가 보고하는 밴드값과 실제 청감 효과는 일치하지 않을 수 있습니다. 실제 출력 경로의 무음/우회 여부는 PCM에 접근하지 않고는 확정할 수 없습니다.
- 이 버전은 갑작스러운 음량 변화 방지를 위해 자동 EQ 재생성, 강제 OFF/ON 복구를 의도적으로 하지 않습니다.
- Android SDK 및 실기기 테스트 환경이 없어 APK 빌드 및 실측 검증은 수행하지 않았습니다.


## Android Studio build (v0.9.1)
1. Open the `InfinityAudioEqualizerPro` **folder**, not the ZIP or `app` folder.
2. Install Android SDK Platform 35, SDK Build Tools, NDK 27.2.12479018, and CMake 3.22.1 via SDK Manager. Android Studio may offer to install missing components during sync.
3. Use JDK 17 (Android Studio bundled JBR), connect to the Internet for the first Gradle sync and dependencies, then Build > Build APK(s).
4. Command line: `gradlew.bat assembleDebug` on Windows or `./gradlew assembleDebug` on macOS/Linux.
5. APK: `app/build/outputs/apk/debug/app-debug.apk`.

The included `gradle-wrapper.jar` is a minimal source-compiled bootstrap for Gradle 8.11.1, **not the official Gradle Wrapper binary**. It downloads Gradle from services.gradle.org over HTTPS. For strict supply-chain verification, regenerate the official wrapper on a networked machine and pin a verified distribution SHA-256. First sync requires Internet. Full Android build/device behavior has not been verified in this environment.


## v0.9.2 automated APK build
- GitHub Actions workflow: `.github/workflows/android-debug-apk.yml`. Upload the project to a GitHub repository, enable Actions, then Actions → Build Android Debug APK → Run workflow. Download the APK artifact from the completed run. GitHub Actions requires Internet and repository access.
- CI installs Java 17, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1, and invokes a separately installed **official Gradle 8.11.1** (not the previous custom wrapper bootstrap).
- Linux local build: install Android SDK/NDK/CMake and Gradle 8.11.1, set `ANDROID_HOME`, run `./build_apk_linux.sh`.
- This environment cannot download Android SDK because outbound DNS is unavailable; **no APK has been built or runtime-tested**. GitHub Actions execution has not yet been verified. CI failures must be fixed from the first Build APK error log.
- This workflow creates an unsigned-for-release **debug APK** signed with the Android debug key, not a Play Store release build.
