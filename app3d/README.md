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
- **자동 판별로 되돌리기** — 잘못 저장된 선택을 지우고 픽셀 판별을 다시 실행
- **재생 엔진 전환** — ExoPlayer ↔ libVLC 를 재생 중에 위치 유지한 채 교체
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

`com.nauty.p3d.engine.VideoEngine` 뒤에 두 구현이 있다. 플레이어 하단 `엔진` 버튼으로
재생 위치를 유지한 채 전환되고, 선택은 저장된다.

| | ExoPlayer (기본) | libVLC |
|---|---|---|
| 디코딩 | 기기 MediaCodec | 자체 FFmpeg (+ HW 폴백) |
| 강점 | 가볍다, HLS/DASH | MKV, DTS/AC3, RTSP/SMB |
| 입력면 | `Surface` | `SurfaceTexture` (IVLCVout) |

3D 파이프라인은 엔진과 무관하다 — 어느 쪽이든 프레임이 같은 OES 텍스처로 들어온다.
libVLC 초기화가 실패하면 자동으로 ExoPlayer 로 되돌아간다.

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

판별 우선순위: **저장된 사용자 선택 > 파일명 명시 토큰 > 픽셀 판별 > 2D**.
자동 판별 결과는 저장하지 않는다 (사용자가 직접 고른 것만 저장).

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
