# P3D Player

ProMa P10 (무안경 3D 태블릿) 용 통합 3D 플레이어.
기존 3DPlayer / Sight3D / 3DFV 를 리버스엔지니어링해서 얻은 렌더 파이프라인을 재구현했다.
분석 근거는 `../FINDINGS.md` 참고.

## 무엇이 되나

- 로컬 영상 + 네트워크 스트리밍 (http/https, HLS `.m3u8`, DASH `.mpd`, RTSP)
- 스테레오 포맷 5종: **2D / SBS-half / SBS-full / TB-half / TB-full**
- **2D → 3D 강제 변환** (원본 3DPlayer 의 "2D/3D 버튼" 과 동일 알고리즘)
- 깊이·수렴점 실시간 조절 (원본에는 없는 기능)
- 좌우 반전 토글 (콘텐츠마다 L/R 순서가 달라서 필요)
- 파일별 포맷 기억 — 한 번 맞춰두면 다음에 그 파일은 자동으로 그 포맷
- **이어보기** — 파일별로 마지막 위치를 기억해 다음 재생 때 그 지점으로 이동
- **빠른 이동** — `◀◀` `▶▶` 짧게 30초 / 길게 5분
- **자동 판별로 되돌리기** — 잘못 저장된 선택을 지우고 픽셀 판별을 다시 실행
- **화면 비 선택** — 자동 / 16:9 / 2.40:1 / 1.85:1 / 4:3 / 꽉 채우기
- **엔진 선택** — libVLC(소리) ↔ ExoPlayer(4K 하드웨어 디코딩), 파일별로 저장
- **3D 컨트롤 센터** — 남의 앱(YouTube 등)을 3DFV 화이트리스트에 등록/해제

## 렌더 파이프라인

```
ExoPlayer ─▶ SurfaceTexture(OES)
                   │
                   ├── 좌안 크롭 ──▶ FBO 왼쪽 절반
                   └── 우안 크롭 ──▶ FBO 오른쪽 절반   (2D 소스면 여기에만 시어 적용)
                                          │
                            frag3D.sh + libholography 마스크
                                          │
                                          ▼
                                    렌티큘러 인터레이스 출력
```

### 2D → 3D 변환

원본 `frag2dto3d.sh` 의 수식을 그대로 재현했다. 세로 위치에 따라 가로로 미는
**그라디언트 시어** — 화면 아래는 가깝고 위는 멀다는 지면 평면 가정이다.

```glsl
t.x += uShearTop - vTex.y * uShearSlope;   // 원본: 0.004 - y*screenHeight*0.0000122
```

좌안은 원본 그대로 두고 우안에만 적용하므로, 한쪽 눈은 항상 선명하다.
원본은 상수 고정이지만 여기서는 `깊이` 슬라이더로 배율을 조절할 수 있다.

## 네이티브 의존

`libholography.so` 하나뿐이다 (3DPlayer APK 의 arm64-v8a / armeabi-v7a 판).
`Holography.update()` 가 현재 바인드된 `GL_TEXTURE_2D` 에 패널 렌티큘러 마스크를 써 넣고,
`frag3D.sh` 의 `Sampler1` 이 그것을 받아 픽셀별로 좌/우를 섞는다.

**정적 JNI 네이밍이라 클래스는 반드시 `com.future.Holography.Holography` 여야 한다.**
패키지명을 바꾸면 심볼이 해석되지 않는다.

원본이 쓰던 `libDrawVideoC.so` 는 쓰지 않는다. 그 함수들의 인자는 width/height 가 아니라
어트리뷰트 로케이션이고, 하는 일은 고정 크롭용 정점 세팅뿐이라 자바에서 UV 로 처리하는 편이
letterbox 제어까지 되어 낫다.

## 빌드

```powershell
$env:JAVA_HOME    = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME = "C:\Android\sdk"
cd C:\Users\nauty\proma3d\app3d
& "C:\Gradle\gradle-8.7\bin\gradle.bat" assembleDebug --no-daemon

C:\Users\nauty\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## 주의

- 이 앱은 **스스로 인터레이스를 렌더**하므로 3DFV 화이트리스트에 등록하면 안 된다.
  등록하면 SurfaceFlinger 가 한 번 더 처리해서 깨진다. (원본 3DPlayer 도 화이트리스트에 없다)
- **DTS 오디오는 이 기기에서 재생 불가** (엔진 무관). 아래 "DTS" 절 참고.
- APK 는 arm64-v8a 전용이다. libVLC 가 ABI 당 ~30MB 라 32비트까지 담으면 과도해진다.

## 구조

```
com.future.Holography.Holography   JNI 바인딩 (이름 고정)
com.nauty.p3d.SourceFormat         스테레오 포맷 + 자동판별
com.nauty.p3d.Fv3d                 3DFV 브로드캐스트 API
com.nauty.p3d.MainActivity         라이브러리 / URL 입력
com.nauty.p3d.PlayerActivity       플레이어 + 컨트롤
com.nauty.p3d.Fv3dControlActivity  3D 컨트롤 센터
com.nauty.p3d.gl.Stereo3DView      GLSurfaceView + 드로우 루프
com.nauty.p3d.gl.SourceRenderer    OES -> FBO (크롭 + 시어)
com.nauty.p3d.gl.InterlaceRenderer FBO -> 화면 (frag3D + 마스크)
com.nauty.p3d.gl.Fbo / GlUtil / BlitRenderer
```

## 재생 엔진

`com.nauty.p3d.engine.VideoEngine` 뒤에 두 구현이 있다.

| | libVLC (기본) | ExoPlayer |
|---|---|---|
| 디코딩 | 자체 FFmpeg (+ HW 폴백) | 기기 MediaCodec |
| 입력면 | `SurfaceTexture` (IVLCVout) | `Surface` |

**엔진 선택 버튼이 설정 패널에 있다** (파일별로 저장, 재생 위치 유지).
기본은 libVLC — 이 기기에 DTS/AC3 디코더가 없어 ExoPlayer 로는 3D 영화 대부분이 무음이다.
그런데 MediaCodec 이 거부하는 소스에서는 반대가 된다: 3840x1080 10bit HEVC 는
libVLC 가 소프트웨어로 떨어져 21fps 밖에 못 내고, ExoPlayer 는 하드웨어로 27.6fps 를 낸다.
어느 쪽이 나은지가 파일마다 갈리므로 사용자가 고르게 두었다. 아래 "4K HEVC" 절 참고.

선택은 **전역이 아니라 파일별로 저장**한다. 전역으로 저장하면 한 파일 때문에 바꾼 설정이
다른 모든 파일에 따라붙어 조용히 무음이 된다 (실제로 겪었다).
디버깅용 인텐트 엑스트라 `--es engine EXO|VLC` 는 이번 재생에만 적용되고 저장되지 않는다.

3D 파이프라인은 엔진과 무관하다 — 어느 쪽이든 프레임이 같은 OES 텍스처로 들어온다.

## 알려진 버그와 수정 이력

**증상: 몇 초 재생 후 화면 정지**
`frameAvailable` 불린 하나로 GL 스레드에 프레임 도착을 알리던 구조의 경합이었다.
`onDrawFrame` 이 플래그를 읽고 지우는 사이 `onFrameAvailable` 이 다시 들어오면 그 통지가
사라지고, `updateTexImage()` 가 호출되지 않아 디코더가 버퍼를 반납받지 못해 재생 전체가 멈췄다.

수정: `AtomicInteger` 로 밀린 프레임 수를 세어 전부 소비하고, 드로우 직후 남은 게 있으면
다시 `requestRender()`. 추가로 300ms 워치독을 두어 통지가 유실돼도 영구 정지가 불가능하게 했다.

측정 (94초 영상, 초당 fps):
```
수정 전:  24.69  11.88   0.55 ← 스톨   18.63  24.63 ...
수정 후:  26.73  27.96  26.34  26.57  28.31  25.81  28.02  26.83
          27.18  27.41  27.32  26.76  28.02  26.98  27.86  27.00
```

### libVLC 연동에서 걸렸던 것

SurfaceTexture 로 libVLC 를 받을 때 화면이 단색으로만 나왔다. 원인은 두 가지였고 둘 다 필요했다.

1. **자막 서피스 요구** — VLC 의 안드로이드 vout 은 자막 블렌딩용 서피스를 따로 요구한다.
   영상용 SurfaceTexture 하나만 주면 이렇게 거부하고 폴백 vout 으로 넘어간다:
   ```
   E/VLC: vout display: can't get Subtitles Surface
   W/VLC: vout display: cannot blend subtitles with an opaque surface, trying next vout
   ```
   → `--no-spu`, `--no-osd` 로 자막을 끈다. 3D 파이프라인에 자막을 합성하지 않으므로 손해가 없다.

2. **vout 시작 전 window size 미설정** — `onNewVideoLayout` 은 재생이 시작된 뒤에야 오는데,
   vout 은 그 전에 창 크기를 알아야 디스플레이를 초기화한다. `attachViews()` 앞에서
   화면 크기로 `setWindowSize()` 를 한 번 잡아줘야 한다.

`setHWDecoderEnabled(true, true)` 로 하드웨어 직접 렌더를 강제해도 그림은 나오지만,
그건 원인 해결이 아니었다 (`force=false` 로도 정상 동작). 폴백이 막히면 MediaCodec 이
못 하는 코덱을 소프트웨어로 처리하지 못하므로 `force=false` 를 유지한다.

**디버깅 메모:** MTK PictureQuality HAL 이 SELinux 에 막혀 초당 수십 줄씩 경고를 뱉는다.
logcat 버퍼가 밀려서 앱 로그가 사라지니, 태그로 거르지 말고 pid 로 걸러야 한다.
```
adb logcat -d | grep " <pid> "
```

## 엔진 지정 (테스트/바로가기용)

```
adb shell am start -n com.nauty.p3d/.PlayerActivity \
  -d "content://media/external/video/media/45" \
  --es title "snowflight_2V3D.mp4" --es engine VLC
```
`engine` 엑스트라는 `EXO` 또는 `VLC`. 지정하면 저장된 선택을 덮어쓴다.

## DTS 오디오 — 이 기기에서는 불가 (조사 종료)

`Edge.of.Tomorrow...DTS-HD.MA.7.1.mkv` 처럼 오디오가 DTS 단독인 파일은 **엔진과 무관하게 무음**이다.

근거:
```
/vendor/etc/media_codecs_mediatek_audio.xml 의 오디오 디코더 전부:
  MP3, GSM, RAW, G711, WMA, ADPCM, APE, ALAC     ← DTS/AC3/E-AC3/TrueHD 없음

ExoPlayer:  audio/vnd.dts ch=6  [디코더 없음]
libVLC:     트랙 선택까지는 됨. 그러나
            E/VLC: audio output: module not functional
            E/VLC: decoder: failed to create audio output
```

시도했다가 되돌린 것들 (전부 효과 없었고, `--aout`/`--stereo-mode` 는 일반 파일 볼륨만 낮췄다):
- `--aout=opensles_android`
- `--stereo-mode=1`
- `MediaPlayer.setAudioOutputDevice("stereo")`

libVLC 를 넣은 값어치는 남아 있다 (컨테이너/코덱 범위, 네트워크 프로토콜). 다만
**DTS 해결책은 아니므로 그렇게 안내하지 말 것.** 사용자 판단으로 조사 종료.

## 자막 렌더링 버그 (수정됨)

**증상: 두 번째 줄부터 글자를 가로지르는 투명한 선**

외곽선용과 채움용으로 `StaticLayout` 을 각각 만든 것이 원인이었다.
`Paint.Style.STROKE` 는 선 두께가 글자 폭 측정에 반영돼 **줄바꿈 위치와 줄 높이가 달라진다.**
그래서 첫 줄은 맞고 둘째 줄부터 외곽선과 채움이 세로로 어긋나, 겹친 두 사본이
글자를 가로지르는 선처럼 보였다.

수정: 레이아웃은 하나만 만들고 페인트의 style 만 바꿔 두 번 그린다.
```java
StaticLayout layout = new StaticLayout(text, paint, ...);
paint.setStyle(STROKE); paint.setColor(BLACK); layout.draw(c);
paint.setStyle(FILL);   paint.setColor(WHITE); layout.draw(c);
```

자막 세로 위치는 설정 패널의 `자막 위치` 슬라이더로 조절한다 (화면 높이의 0~40%, 기본 4%).
크기·위치·깊이 세 값은 저장되므로 매번 다시 맞출 필요가 없다.

## 스테레오 배치 자동판별 — 파일명이 아니라 픽셀로

파일명 휴리스틱만으로는 부족하다. 실제로
`spider.man.into.the.spider.verse.2018.3d.1080p.bluray.x264-veto.mkv` 는 이름에 `3d` 만 있고
`sbs` 가 없어서 2D 로 잡혔고, SBS 프레임을 좌우에 통째로 복제해 **화면에 같은 그림이 두 개**
나왔다.

`StereoDetect` 가 본편 4개 지점의 프레임을 `MediaMetadataRetriever` 로 뽑아,
좌/우 절반과 상/하 절반의 평균 절대차를 프레임 대비와 비교한다. SBS 프레임은 좌우 절반이
시차만큼만 다르므로 바로 갈린다. 검은 화면 등 대비가 낮은 표본은 제외한다.

```
스테레오 판별 표본: SBS=4 TB=0 2D=0 (1920x808)     ← 약 3초 소요
```

판별 우선순위: **저장된 사용자 선택 > 픽셀 판별 > (판별 실패 시) 파일명 / 2D**.
파일명은 판별이 끝날 때까지의 임시값으로만 쓰고 결과가 나오면 덮어쓴다 — 이름은 틀리게
붙어 있는 경우가 흔하기 때문. 자동 판별 결과는 저장하지 않는다 (사용자가 직접 고른 것만 저장).

### 인터레이스 검증 도구

3D 가 실제로 걸렸는지는 눈으로 판단하지 말 것. `InterlaceCheck.java` 가
인접 열/행 휘도차 비율로 판정한다 (인터레이스 출력은 열 차이가 크다).
```
java InterlaceCheck.java shot.png
  ratio 4.26 → INTERLACED (3D)
  ratio 0.79 → FLAT (2D)
```
주의: 하늘/암전처럼 디테일 없는 장면에서는 지표가 무의미하다. 반드시 질감 있는 장면으로 볼 것.

## 소스 포맷이 정해지는 규칙

```
저장된 사용자 선택  >  픽셀 판별(StereoDetect)  >  (판별 실패 시) 파일명 / 2D
```

`소스` 버튼을 누르면 그 값이 **그 파일에 대해 영구 저장**되고, 이후로는 픽셀 판별이 실행되지
않는다. 실수로 잘못 누르면 3D 가 깨진 채 고정되므로 되돌릴 수단이 필요하다:
설정 패널의 **`↺ 자동 판별로 되돌리기`** 가 저장값을 지우고 판별을 다시 돌린다.

설정 패널 상단에 현재 소스가 어디서 온 값인지 항상 표시된다.
| 표시 | 의미 |
|---|---|
| `소스: 자동 판별` | 픽셀 판별 결과 (저장 안 됨) |
| `소스: 수동 선택 (저장됨)` | 직접 고른 값 — 자동 판별이 막혀 있는 상태 |
| `소스: 판별 중…` | 판별 진행 중 |

3D 가 이상하면 이 줄을 먼저 볼 것.

## 검증 기록

| 파일 | 판별 | 인터레이스 비율 |
|---|---|---|
| snowflight_2V3D.mp4 (1920x1080) | SBS_HALF | 4.26 |
| spider.man...veto.mkv (1920x808) | SBS=4/4 → SBS_HALF | 3.61 |
| Edge.of.Tomorrow...mkv (1920x1080) | SBS=4/4 → SBS_HALF | — (DTS 무음) |

자막 통제 실험: 자막 유무에 따른 인터레이스 비율 `4.26 vs 4.26` — 자막은 3D 에 영향 없음.

## 3DFV 오버레이 — 남의 앱을 패널 3D 로 쓰기

Chrome 에서 화면 왼쪽에 뜨는 `›` 핸들이 3DFV 의 FloatView 다. 누르면
`일반 / SBS-full / SBS-half / 상하` 와 깊이를 고를 수 있다. 이게 뜨는 조건이 갈린다.

```java
// Service3D 타이머 루프
mIsLandscape && mInWhitelist && mIsKeyguardGone && !mIsCustomActivity   → 오버레이 표시
```

| 등록 경로 | mIsCustomActivity | 결과 |
|---|---|---|
| **파일 화이트리스트** | false | **오버레이 표시** — 모드·깊이 직접 선택 |
| 브로드캐스트 (`Service3D.request`) | true | 오버레이 없이 고정 모드로 즉시 적용 |

그래서 3D 컨트롤 센터는 **파일 방식**을 쓴다 (`Fv3dWhitelist`).

### 화이트리스트 파일 우선순위

```java
// Service3D.getFVWhiteList()
"/sdcard/K3DX/config/white_list2.config"     // 점 없는 파일을 먼저 찾고
"/sdcard/K3DX/config/.white_list2.config"    // 없을 때만 벤더 파일
```

우리는 점 없는 파일에만 쓴다. 벤더 파일은 건드리지 않고, 우리 파일을 지우면 원상복구된다
(컨트롤 센터의 `기본값 복원`).

### 반영시키기 — close_self 는 반드시 재시작과 함께

3DFV 는 화이트리스트를 **서비스 시작 때만** 읽는다. `close_self` 브로드캐스트로 정지시킬 수
있지만, 그 핸들러는 `auto_start=false` 를 저장한다 — 그대로 두면 **다음 부팅에 3D 서비스가
아예 안 뜬다.** 다행히 `Service3D.onCreate()` 가 message 2100 을 `arg1=1` 로 보내
`auto_start` 를 true 로 되돌리므로, **정지 직후 재시작하면 안전하다.**

```java
sendBroadcast(new Intent("com.wztech.service.close_self"));
// 1.5초 뒤
startForegroundService(new Intent("com.wztech.service").setPackage("com.wztech.service3d"));
```

### 액티비티 이름은 실제 실행 중인 것과 맞아야 한다

기기 기본 화이트리스트의 YouTube 항목이 낡아서 오버레이가 뜨지 않았다.

```
등록돼 있던 것 : com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity
SurfaceFlinger 보고: com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell$HomeActivity#0
                                               └─ "/" 뒤 "#" 앞이 비교 키
```

YouTube 20.x 로 올라가며 패키지 경로가 `apps.youtube` → `youtube` 로 바뀐 탓이다.
그래서 컨트롤 센터는 `PackageManager.GET_ACTIVITIES` 로 **그 앱의 모든 액티비티**를 등록한다.
스트리밍·게임 앱은 재생 화면이 런처와 다른 액티비티라 이게 필요하다
(예: Moonlight 은 런처가 `com.limelight.PcView`, 스트리밍은 `com.limelight.Game`).

## 기기 종속성

`libholography.so` 는 렌티큘러 마스크를 코드가 아니라 파일에서 읽는다.

```
/sdcard/3DKanKan/matrix     8,192,000 bytes    ← 패널별 보정값
```

기기 출고 시 Sight3D 가 만들어 둔 것이라, **이 파일이 없거나 다른 패널의 값이면 3D 가
정렬되지 않는다.** 즉 같은 ProMa P10 에서만 동작한다.

### 오버레이 등록 검증 기록

| 앱 | 등록 | 결과 |
|---|---|---|
| Chrome | 기기 기본 `30@` | 원래부터 정상 |
| YouTube 20.26.40 | `10@...Shell$HomeActivity` (기본 항목이 낡아 추가) | 오버레이 정상 |
| Moonlight | `10@com.limelight.Game` 외 (컨트롤 센터가 전체 액티비티 등록) | **PC 스트리밍에서 정상 동작 확인** |

Moonlight 은 spacedesk 와 같은 화면 미러링 계열이라 windowType `1`(sv) 이 맞았다.

## 이어보기와 빠른 이동

**이어보기**는 파일별로 마지막 재생 위치를 기억한다 (`pos:<파일명>`).

| | |
|---|---|
| 저장 시점 | 재생 중 5초 간격 + 앱 이탈·목록 이동·종료 시 즉시 |
| 저장 안 함 | 15초 이전 (의미 없음), 끝에서 30초 이내 (다 본 것으로 보고 기록 삭제) |
| 복원 | 다음 재생 시 자동 이동 + `이어보기 mm:ss` 토스트 |

5초 주기 저장 덕분에 **강제 종료나 크래시에도 위치가 남는다.**
`am force-stop` 으로 확인: 25초 재생 후 강제 종료 → `pos = 19651` 기록됨.

구현에서 주의할 점: **재생을 시작하자마자 seek 하면 안 된다.** libVLC 의 seek 는 길이를
모르는 상태에서는 무시되고, ExoPlayer 도 준비 전이면 불안정하다. 그래서 이동을 예약해
두고 `tick()` 에서 **길이가 확정된 첫 순간에** 실행한다 (`pendingResumeMs`).

**빠른 이동** 버튼은 짧게 누르면 30초, 길게 누르면 5분 이동한다.

### seek 이 느린 이유

대용량 영화에서 이동에 몇 초 걸리는 것은 정상이다.

- **키프레임 간격** — 임의 지점을 표시하려면 앞선 키프레임부터 디코딩해야 한다.
  BluRay 립은 간격이 길다. 코덱 구조라 우회 불가.
- **파일 크기** — 8~17GB 파일은 색인 조회와 버퍼 재충전 자체에 시간이 든다.

개선한 것: VLC 의 seek 를 `setPosition(비율)` 에서 **`setTime(ms)`** 로 바꿨다.
비율 방식은 파일 오프셋을 추정해 그 지점부터 재동기화하지만, `setTime` 은 디먹서의
타임스탬프 색인(MKV 의 Cues)을 쓰므로 더 빠르고 정확하다. 색인이 없는 파일에서만
비율 방식으로 후퇴한다.

## 4K HEVC 가 끊기는 이유와 대처

**증상: 3840x1080 10bit HEVC (full-SBS) 파일이 끊기고, 화면 비도 틀렸다.**

원인이 두 가지 겹쳐 있었다.

### 1. libVLC 가 소프트웨어로 디코딩한다

```
E/VLC-std: [hevc @ ...] Could not find ref with POC 660
E/VLC    : libvlc decoder: more than 5 seconds of late video -> dropping frame (computer too slow ?)
```
`[hevc @ ...]` 는 libavcodec, 즉 **소프트웨어** 디코더다. MediaCodec 이 이 스트림을 거부해서
폴백이 걸렸다. 같은 파일을 ExoPlayer(MediaCodec 전용)로 열면 하드웨어로 잘 돌아간다.

| | fps | 소리 |
|---|---|---|
| libVLC (소프트웨어) | 19.8 → 21 (튜닝 후) | **나온다** (AC3 자체 디코딩) |
| ExoPlayer (하드웨어) | **27.6** | 안 난다 (기기에 AC3 디코더 없음) |

**`setHWDecoderEnabled(true, true)` 로는 못 고친다.** libVLC 3.6.0 의 바이트코드를 보면
`force` 인자는 기기 판별이 `UNKNOWN` 일 때만 쓰이고, 만들어지는 옵션은 언제나
`:codec=mediacodec_ndk,iomx,all` 처럼 **마지막이 `all`** 이라 avcodec 폴백이 항상 열려 있다.
(이 기기는 `ro.board.platform=mt6797`, 블랙리스트에 없어 판별 결과가 `ALL` 이다.)

그래서 폴백을 막는 대신 폴백 경로를 빠르게 했다 — 가로 2560px 이상이면
`--avcodec-skiploopfilter 4`(디블로킹 생략) + `--avcodec-threads` + `--avcodec-fast`.
19.8 → 21fps. 부족하면 설정에서 ExoPlayer 로 바꾸면 된다 (소리는 포기).

병목은 디코더지 저장소나 GPU 가 아니다. 순차 읽기는 **162 MB/s** 인데
이 파일의 비트레이트는 12 Mbps 다.

### 2. `onNewVideoLayout` 이 오지 않는 소스가 있다

이 파일에서는 VLC 의 `onNewVideoLayout` 콜백이 **한 번도 오지 않았다.** 그러면
`setVideoSize()` 가 호출되지 않아 소스 크기가 기본값 16:9 에 머물고, SurfaceTexture 버퍼도
시작할 때 잡아둔 화면 크기(2560x1504)에 머물러 VLC 가 거기에 맞춰 스케일해 버린다.

화면 실측 (검은 띠 두께로 표시영역 역산):
```
수정 전: 1422x1512  종횡비 0.940   ← 기본값 16:9 를 full-SBS 로 나눈 (16/2)/9 = 0.889
수정 후: 2560x1448  종횡비 1.768   ← 3840x1080 full-SBS 의 한쪽 눈 = 1920x1080 = 1.778
```

수정: 콜백을 기다리지 않고 `MediaMetadataRetriever` 로 **재생 전에 해상도를 알아내어**
`setVideoSize()` 와 VLC 의 window/buffer 크기를 미리 맞춘다. 콜백이 오면 그때 갱신한다.

### 3. 판별용 썸네일 크기로 half/full 을 나누고 있었다

`getFrameAtTime()` 이 돌려주는 비트맵은 축소돼 올 수 있다. 이 파일은 3840x1080 인데
비트맵은 1920x1080 으로 와서 종횡비가 1.78 이 되고, `>= 2.6` 이어야 full 로 보는 규칙에
걸려 **full-SBS 가 half-SBS 로 잡혔다.**

수정: half/full 판정은 `METADATA_KEY_VIDEO_WIDTH/HEIGHT`(컨테이너 값)로 한다.
로그도 둘 다 찍는다 — `표본 1920x1080, 원본 3840x1080`.
추가로 디코더가 알려준 실제 해상도가 full 을 가리키면 픽셀 판별 결과를 덮어쓴다.
픽셀 판별은 **배치(SBS/TB/2D)** 를, 해상도는 **half/full** 을 정한다.

### 화면 비 수동 선택

그래도 틀리는 소스가 있을 수 있어 설정에 `화면 비` 버튼을 두었다.
`자동 → 16:9 → 2.40:1 → 1.85:1 → 4:3 → 꽉 채우기` 순으로 순환하고 저장된다.
`꽉 채우기` 는 화면 비율에 맞춰 늘려 레터박스를 없앤다.

### 근본 해결

이 기기의 하드웨어 디코더는 3840x2176 까지 지원한다. PC 에서 8bit 로 다시 인코딩하면
libVLC 에서도 하드웨어로 돌아가 **소리와 부드러움을 둘 다** 얻는다.
`ffmpeg -i in.mkv -c:v libx265 -pix_fmt yuv420p -c:a copy out.mkv`
