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
- **엔진 선택** — ExoPlayer(기본) ↔ libVLC, 파일별로 저장
- **AC3 · E-AC3 · DTS · TrueHD 재생** — FFmpeg 오디오 확장을 직접 빌드해 넣었다
- **3D 컨트롤 센터** — 남의 앱(YouTube 등)을 3DFV 화이트리스트에 등록/해제

## 렌더 파이프라인

```
ExoPlayer ─▶ SurfaceTexture(OES)
                   │
                   ├── 좌안 크롭 ──▶ FBO 왼쪽 절반
                   └── 우안 크롭 ──▶ FBO 오른쪽 절반   (2D 소스면 두 눈에 시어 절반씩 반대로)
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

**시어는 두 눈에 절반씩 반대로 나눠 건다.** 원본 3DPlayer 는 좌안을 그대로 두고 우안에만
걸었는데, 그러면 시차는 맞아도 **융합된 상(cyclopean image)이 두 눈 위치의 평균**이라
곧은 세로선이 `s(v)/2` 만큼 기울어 보인다 — 시차 강도를 올리면 기둥이 옆으로 누워 보이는
원인이 이것이다. 좌안 `-s(v)/2`, 우안 `+s(v)/2` 로 나누면 시차(우−좌)는 그대로면서
평균이 0 이 되어 세로선이 곧게 선다. 눈당 표본 이동량도 절반이라 가장자리 뭉개짐도 준다.

원본은 상수 고정이지만 여기서는 `깊이` 슬라이더로 배율을 조절할 수 있다.

**영점은 원본과 다르게 화면 중앙에 둔다.** 원본 상수쌍의 영점은
`0.004/0.031232 = v 0.128` 로 사실상 화면 맨 위다. 그러면 시차가 아래로만 몰려서
슬라이더를 움직여도 위쪽은 변하지 않고, 아래에서는 한쪽 눈이 depth=1 에 약 70px
(슬라이더 최대 3.0 에서는 200px 넘게) 밀린다. 융합 한계를 넘으면 입체가 아니라
찌그러짐으로 보인다. 기울기는 원본 그대로 두고 영점만 옮기면 입체감은 유지하면서
한쪽 눈의 최대 이동량이 절반이 된다.

같은 프레임을 정지시켜(`--ei freezems`) 수정 전후를 비교한 띠별 평균차:
```
화면 위치      수정 전   수정 후
위 10~20%        2.05     11.48    ← 위쪽이 슬라이더에 반응하기 시작
20~30%          19.34     31.79
중앙 40~60%     34.12     12.65    ← 최대였던 곳이 영점이 됨
아래 60~70%     28.29     18.32
```

눈별 시어량은 `--es output SBS_DEBUG` 로 인터레이스 전 FBO 를 좌우로 띄워 직접 잰다
(인터레이스된 화면으로는 크로스토크 때문에 눈이 분리되지 않아 측정이 안 된다).
depth 300 실측 — 눈당 폭 1280 기준, 화면에서는 2배:
```
세로위치      좌안    우안  |   시차   기울어짐
12~25%        +20     -20  |    -40      0.0
25~37%        +12     -12  |    -24      0.0
37~50%         +4      -4  |     -8      0.0
50~62%         -4      +4  |     +8      0.0
62~75%        -12     +12  |    +24      0.0
75~87%        -20     +20  |    +40      0.0
```
두 눈이 정확히 반대로 밀려 기울어짐이 모든 띠에서 0 이고, 시차는 -40 → +40 으로
중앙에서 부호가 바뀐다. 원본처럼 우안에만 걸면 좌안이 0 이므로 기울어짐이
시차의 절반(화면 기준 최대 40px, 위아래 합쳐 80px)만큼 남는다 — 그게 기둥이 누워 보이던 값이다.
시어는 2D 소스에만 적용되므로 실제 3D(SBS/TB) 영상은 영향이 없다.

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
- AC3/DTS 등은 **FFmpeg 오디오 확장**이 처리한다 (`ffmpeg/` 모듈). 그 모듈의 정적
  라이브러리는 저장소에 없으므로 새로 받아 빌드하려면 [`ffmpeg/README.md`](ffmpeg/README.md) 를 먼저 볼 것.
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
androidx.media3.decoder.ffmpeg.*      FFmpeg 오디오 확장 (media3 1.3.1 소스, ffmpeg/ 모듈)
```

## 재생 엔진

`com.nauty.p3d.engine.VideoEngine` 뒤에 두 구현이 있다.

| | ExoPlayer (기본) | libVLC |
|---|---|---|
| 영상 디코딩 | 기기 MediaCodec (하드웨어) | 자체 FFmpeg — 이 기기에선 **항상 소프트웨어** |
| 오디오 | MediaCodec + FFmpeg 확장 (AC3/E-AC3/DTS/TrueHD) | 자체 디코더 |
| 입력면 | `Surface` | `SurfaceTexture` (IVLCVout) |
| 프로토콜 | http/https, HLS, DASH | + RTSP, SMB, FTP, MMS |

**기본이 ExoPlayer 인 이유.** 이 기기에서 libVLC 는 MediaCodec 을 아예 못 쓴다:
```
W/VLC: libvlc decoder: Exception occurred in MediaCodecInfo.getCapabilitiesForType
```
코덱 목록을 훑다 예외를 맞고 하드웨어 디코더가 없다고 판단해 소프트웨어로 떨어진다
(MTK 코덱 메타데이터가 망가져 있다 — `Unrecognized profile/level ... for video/mp4v-es`).
1080p 는 소프트웨어로도 버티지만 3840x1080 10bit HEVC 는 21fps 로 무너진다.

예전에 libVLC 를 기본으로 둔 이유는 AC3/DTS 무음 문제였는데, **FFmpeg 오디오 확장을
직접 빌드해 넣어서 해결됐다** (`ffmpeg/` 모듈, 아래 "오디오 코덱" 절).

libVLC 는 ExoPlayer 가 못 여는 컨테이너·프로토콜용으로 남는다. RTSP/SMB 같은 스킴은
자동으로 libVLC 로 열고, 그 밖에는 설정 패널의 엔진 버튼으로 파일별로 바꿀 수 있다.

선택은 **전역이 아니라 파일별로 저장**한다. 전역으로 저장하면 한 파일 때문에 바꾼 설정이
다른 모든 파일에 따라붙는다 (실제로 겪었다).
디버깅용 인텐트 엑스트라 `--es engine EXO|VLC` 는 이번 재생에만 적용되고 저장되지 않는다.
`--ei vlcverbose 2` 를 주면 libVLC 가 모듈 선택 과정을 전부 찍는다.

3D 파이프라인은 엔진과 무관하다 — 어느 쪽이든 프레임이 같은 OES 텍스처로 들어온다.

## 오디오 코덱 — FFmpeg 확장 (해결됨)

**증상: AC3·DTS 트랙이 무음.** 기기 MediaCodec 에 그 디코더가 없다.
```
/vendor/etc/media_codecs_mediatek_audio.xml 의 오디오 디코더 전부:
  MP3, GSM, RAW, G711, WMA, ADPCM, APE, ALAC     ← AC3/E-AC3/DTS/TrueHD 없음
```

해결: media3 의 FFmpeg 오디오 확장을 직접 빌드해 넣었다 (`ffmpeg/` 모듈).
`androidx.media3:media3-decoder-ffmpeg` 는 Maven 에 없고 NDK 로 빌드해야 한다.
빌드 절차는 [`ffmpeg/README.md`](ffmpeg/README.md).

기기에 없는 디코더만 켰다 (`ac3 eac3 dca truehd mlp`). APK 증가분은 **0.55MB**.

측정 (기본 엔진, 재생 중 AudioTrack 활성 여부로 소리 확인):

| 파일 | 오디오 | fps | 소리 |
|---|---|---|---|
| Coraline 3840x1080 10bit HEVC | AC3 5.1 | 28.1 | O |
| Edge of Tomorrow 1080p | DTS-HD MA 7.1 | 27.3 | O |
| Spider-Man 1080p | DTS | 27.7 | O |
| The Boys S03E01 1080p | E-AC3 | 27.8 | O |

**이전 문서의 "DTS 는 이 기기에서 불가" 는 틀린 결론이었다.** 그때는 libVLC 의 오디오 출력
모듈이 실패하는 것만 보고 기기 한계로 단정했는데, 디코딩을 FFmpeg 확장이 하고 출력을
ExoPlayer 의 AudioTrack 이 맡으니 그대로 재생된다.

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

## DTS 오디오 — 한때 "불가" 로 닫았던 건 (해결됨)

기록으로 남긴다. **결론이 틀렸었다.**

당시 근거는 이랬다:
```
/vendor/etc/media_codecs_mediatek_audio.xml 의 오디오 디코더 전부:
  MP3, GSM, RAW, G711, WMA, ADPCM, APE, ALAC     ← DTS/AC3/E-AC3/TrueHD 없음

ExoPlayer:  audio/vnd.dts ch=6  [디코더 없음]
libVLC:     트랙 선택까지는 됨. 그러나
            E/VLC: audio output: module not functional
            E/VLC: decoder: failed to create audio output
```
여기서 "기기에 디코더가 없다 = 방법이 없다" 로 넘어간 것이 비약이었다.
**디코더는 앱이 들고 오면 된다.** FFmpeg 오디오 확장을 넣자 그대로 재생됐다
(`audio/vnd.dts ch=6 [재생가능]`, 27.3fps, AudioTrack 활성).

libVLC 쪽에서 시도했다가 되돌린 것들은 여전히 효과가 없다. 그건 출력 모듈 문제였고,
확장을 넣은 지금은 ExoPlayer 의 AudioTrack 경로를 쓰므로 해당되지 않는다:
- `--aout=opensles_android`
- `--stereo-mode=1`
- `MediaPlayer.setAudioOutputDevice("stereo")`   ← 일반 파일 볼륨만 낮아졌다

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

## 목록에 안 나오는 영상 (미디어 색인)

목록은 MediaStore 를 조회해서 만든다. 그런데 **파일을 만든 앱이 스캔을 요청하지 않으면
그 파일은 색인되지 않아** 아무리 기다려도 목록에 나오지 않는다. 안드로이드 8 의 미디어
스캐너는 부팅 때와 `MEDIA_SCANNER_SCAN_FILE` 브로드캐스트를 받았을 때만 도는데,
다운로더 중에는 그걸 안 부르는 것이 있다.

실제로 `Download/Seal/` 의 mkv 두 개 중 하나만 색인돼 있었다:
```
Download/Seal/SPINE ｜ ... .mkv                ← 색인됨 (목록에 나옴)
Download/Seal/METRO EXODUS + DLSS 5 ... .mkv   ← 색인 안 됨 (안 나옴)
```

그래서 앱이 직접 챙긴다. `MainActivity.onResume` 에서
1. 외부 저장소를 깊이 5 까지 훑어 영상 확장자 파일을 모으고
2. MediaStore 의 `_data` 목록에 없는 것을 골라
3. `MediaScannerConnection.scanFile()` 로 넘긴 뒤
4. 스캔이 끝나면 목록을 다시 읽는다.

`Android/` 밑과 `.nomedia` 가 있는 폴더는 건너뛴다 (앱 전용 데이터이거나 사용자가 숨긴 것).
색인만 시켜주면 그 뒤는 평소 경로(`content://`)로 열리므로 자막 탐색·이어보기 키 같은
나머지 동작은 그대로다. 다른 앱에서도 그 파일이 보이게 되는 건 덤이다.

`onResume` 에 건 이유는 다른 앱으로 영상을 받아온 직후 돌아왔을 때 바로 나와야 하기 때문이다.

## 검증 기록

| 파일 | 판별 | 인터레이스 비율 |
|---|---|---|
| snowflight_2V3D.mp4 (1920x1080) | SBS_HALF | 4.26 |
| spider.man...veto.mkv (1920x808) | SBS=4/4 → SBS_HALF | 3.61 |
| Edge.of.Tomorrow...mkv (1920x1080) | SBS=4/4 → SBS_HALF | — (DTS 무음) |

자막 통제 실험: 자막 유무에 따른 인터레이스 비율 `4.26 vs 4.26` — 자막은 3D 에 영향 없음.

## 사용법 — 다른 앱을 3D 로 보기 (등록 · 재등록)

YouTube, Moonlight 처럼 **자체 3D 렌더가 없는 앱**을 패널 3D 로 보려면 3DFV 화이트리스트에
등록해야 한다. 앱 안의 `3D 컨트롤 센터` 가 그 일을 한다.

### 등록 절차

1. P3D Player 첫 화면에서 **`3D 컨트롤 센터`** 를 누른다.
2. 목록에서 대상 앱을 고른다.
3. 소스 포맷을 고른다 — 보통 **`좌우 SBS (half)`**. 화면 전체가 좌우 한 쌍인 영상이면 full.
4. `등록` 을 누른다. 액티비티가 여러 개면 전부 등록된다
   (스트리밍·게임 앱은 재생 화면이 런처 액티비티와 다르기 때문에 전부 걸어야 확실하다).
5. **`지금 적용`** 을 눌러 3DFV 를 재시작한다. 이 단계를 건너뛰면 반영되지 않는다.
6. 대상 앱을 **가로모드 전체화면**으로 실행하면 화면 왼쪽에 `›` 핸들이 뜬다.
   누르면 3D 를 켜고 깊이를 조절할 수 있다.

해제는 같은 자리에서 `등록 해제`, 전체 되돌리기는 `기본값 복원`.

### 주의

- **P3D Player 자신은 등록하지 말 것.** 스스로 인터레이스를 렌더하므로 SurfaceFlinger 가
  한 번 더 처리해 화면이 깨진다. (원본 3DPlayer 도 화이트리스트에 없다)
- 오버레이가 안 뜨면 대개 **액티비티 이름이 바뀐 것**이다. 앱 업데이트 후 흔하다 —
  실제로 YouTube 20.26.40 에서 기본 항목이 안 맞아 새로 등록해야 했다.
  컨트롤 센터에서 그 앱을 다시 등록하면 현재 액티비티 이름으로 다시 쓴다.
- 등록 내용은 앱이 아니라 `/sdcard/K3DX/config/white_list2.config` 에 있다.
  그래서 **P3D Player 를 업데이트하거나 지웠다 다시 깔아도 그대로 유지된다.**

### 확인된 등록 예

```
10@com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity
10@com.google.android.youtube.app.honeycomb.Shell$HomeActivity   ← YouTube 20.x
10@com.limelight.Game
20@com.limelight.PcView
20@com.limelight.AppView
```
형식은 `<windowType><sourceType>@<액티비티 클래스>` 다.
windowType `1`(sv) 은 스트리밍처럼 서피스뷰로 그리는 앱, `2` 는 둘 다, `3` 은 최상위 레이어.

## 기기를 초기화하면 무엇을 다시 해야 하나

공장초기화는 `/data` 와 `/sdcard` 를 지운다. 3DFV·Sight3D·3DPlayer 는 `/vendor` 에 있는
시스템 앱이라 **지워지지 않는다.** 다시 해야 하는 것은 이것뿐이다.

| 항목 | 초기화 후 | 복구 방법 |
|---|---|---|
| P3D Player | 지워짐 | APK 재설치 |
| 저장소 권한 | 해제됨 | 첫 실행 시 허용 |
| 화이트리스트 (YouTube·Moonlight 등록) | 지워짐 | 3D 컨트롤 센터에서 재등록 (위 절차) |
| 이어보기 위치 · 파일별 포맷 | 지워짐 | 복구 불가 (다시 쌓인다) |
| `/sdcard/3DKanKan/matrix` | 지워짐 | **아무것도 안 해도 된다** — 아래 참고 |

### matrix 파일은 백업할 필요가 없다 (실측)

`/sdcard/3DKanKan/matrix` (8,192,000 바이트 = 2560x1600x2) 는 렌티큘러 마스크다.
기기 고유 캘리브레이션처럼 보여서 잃으면 3D 가 안 될까 걱정되지만, **캐시일 뿐이다.**

파일을 치우고 같은 프레임을 정지시켜 캡처한 뒤 원본과 픽셀 비교했다:
```
평균차 0.0000  최대차 0  (표본 1,024,000)
```
완전히 동일하다. `libholography.so` 는 파일이 없으면 같은 마스크를 스스로 만든다
(임포트 심볼에 `open`/`read`/`write` 가 다 있고, 로그의 `readBitmapFile ... read matrix
8192000` 은 파일 유무와 무관하게 같은 내용을 낸다).

즉 **초기화 후 P3D Player 만 다시 설치하면 3D 는 그대로 동작한다.**

## 다른 기기에서도 되나

| 대상 | 되나 | 이유 |
|---|---|---|
| 같은 ProMa P10 | **된다** | 같은 패널·같은 3DFV. APK 설치 후 화이트리스트만 등록하면 끝 |
| 다른 무안경 3D 기기 | **거의 안 된다** | 렌티큘러 피치·기울기가 다르면 `libholography` 가 만드는 마스크가 안 맞는다. 3DFV 서비스(`com.wztech.service3d`)도 없다 |
| 일반 안드로이드 기기 | 플레이어로만 | 3D 출력은 의미가 없다. 2D 출력·자막·SBS/TB 추출은 동작한다 |

APK 는 **arm64-v8a 전용, minSdk 21** 이다. 32비트 기기에는 설치되지 않는다.
다른 무안경 3D 기기에 관해서는 실제로 시험해 보지 못했다 — 기기가 없다.

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

3D 출력은 `libholography.so` 가 만드는 렌티큘러 마스크에 묶여 있다. 그 마스크는
ProMa P10 패널의 기하에 맞춰져 있어 **다른 패널에서는 정렬되지 않는다.**

```
/sdcard/3DKanKan/matrix     8,192,000 bytes = 2560x1600x2
```

이 파일은 그 마스크의 **캐시일 뿐이다.** 처음에는 출고 시 만들어진 패널별 보정값이라
잃으면 안 되는 줄 알았는데, 파일을 치우고 같은 프레임을 정지시켜 캡처해 원본과 비교하니
픽셀 단위로 완전히 같았다 (평균차 0.0000, 최대차 0). 없으면 `libholography` 가 같은 것을
다시 만든다. 자세한 근거는 "기기를 초기화하면 무엇을 다시 해야 하나" 절에 있다.

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
| libVLC (소프트웨어) | 19.8 -> 21 (튜닝 후) | 나온다 (AC3 자체 디코딩) |
| ExoPlayer (하드웨어) | **27.6** | 처음엔 안 났다. FFmpeg 확장으로 **해결** |

원인은 libVLC 가 이 기기에서 MediaCodec 조회 중 예외를 맞는 것이다 ("재생 엔진" 절 참고).
스트림 문제가 아니라 기기 코덱 메타데이터 문제라 소스를 바꿔도 libVLC 는 늘 소프트웨어다.

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

### 결론

재인코딩은 필요 없어졌다. 기본 엔진을 ExoPlayer 로 돌리고 오디오는 FFmpeg 확장이 맡는다.
`--avcodec-skiploopfilter 4` 같은 libVLC 소프트웨어 튜닝은 libVLC 를 쓰기로 한 경우를 위해
남겨둔다 (19.8 → 21fps).
