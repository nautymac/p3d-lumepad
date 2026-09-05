# Lume Pad 2 포팅 — 진행 상태

기준일 2026-09-04. 기기: `LumePadGen2` / `LPD-20W`, **Android 12**, arm64-v8a.
무선 디버깅 `192.168.50.119:40529` (페어링 완료).

참고 노트: `D:\OneDrive\temp\lume-pad-2-cnsdk-notes.md` (사용자가 Artemis 포크에서 실기로 정리한 것).

---

## 실기에서 확인한 것

### 전체화면 2D→3D 는 불가능

ProMa 의 3DFV 같은 시스템 컴포지터가 없다.

| 확인 | 결과 |
|---|---|
| SurfaceFlinger 위빙/인터레이스 훅 | 없음 (Leia 레이어는 LeiaTube 자기 것뿐) |
| Leia 시스템 서비스 | `leia_lights_service` 하나 — **백라이트 제어일 뿐** |
| 관련 설정값 | `backlight_mode3d_ratio_3d` 등 백라이트 비율만 |
| 화이트리스트 개념 | 없음 |

패널이 8방향 회절 + 얼굴추적이라 **각 앱이 자기 프레임을 직접 위빙**해야 한다.
따라서 YouTube 앱 자체를 3D 로 바꾸는 경로는 없다.

### LeiaTube 공유 경로는 지금 깨져 있다

`ACTION_SEND` + `www.youtube.com` 필터가 있어 링크를 넘기면
"Playing shared video in 3D using AI" 까지는 간다. 그러나 실패한다:

```
I okhttp: <-- 200 OK https://leiatube-api.leialoft.com/api/v1/app-config   ← 백엔드는 살아있음
I LayoutDetectionModel: Model successfully loaded in 838ms                 ← NPU 모델 정상
W System.err: java.lang.RuntimeException: setDataSource failed: status = 0x80000000
    at TubeUtils.getVideoType(TubeUtils.kt:196)
    at TubeRepository.resultFromLibrary(TubeRepository.kt:277)
```

앱은 내장 yt-dlp(`libpython.zip.so`) + `libaria2c.so` 로 영상을 **직접 내려받은 뒤** 변환한다.
Leia 서버가 주는 설정의 추출기 옵션이 `youtube:player_client=android_vr,ios` 인데
YouTube 가 막아 스트림 URL 이 무효가 된다 → `setDataSource` 실패 → "Invalid Request".

**기기나 AI 문제가 아니라 추출기가 낡은 것이다.** Leia 가 갱신하지 않는 한 계속 실패한다.
그러므로 "링크를 LeiaTube 로 보내기" 버튼은 지금 값어치가 없다.

### 반대로, 온디바이스 AI 스택은 살아 있다

```
remote_handle64_open: Successfully opened libSnpeHtpV68Skel.so on domain 3
LayoutDetectionModel: Allocating SNPE buffers complete / loaded in 838ms
```
`com.leiainc.media.service` 가 Qualcomm SNPE + Hexagon NPU 커널을 통째로 품고 있다
(`libSNPE.so` 11MB, `libSnpeHtpV68/V69/V73Skel.so`, `libandroidmediasdk.so` 44MB).
다만 **런처 액티비티만 exported 라 외부에서 호출할 수 없다.** 우리가 쓰려면 모델을 직접 들고 가야 한다.

---

## 확보한 것

```
app3d/app/libs/leia-cnsdk.jar                     115 클래스 (com.leia.*)
app3d/app/src/leia/jniLibs/arm64-v8a/libleiaSDK.so     2.1MB
app3d/app/src/leia/jniLibs/arm64-v8a/libleiaspdlog.so  1.4MB
```

`com.moonlight.leia` APK 를 dex2jar v2.4 로 변환해 `com/leia/**` 만 추린 것.
`LeiaSDK` 는 `System.loadLibrary("leiaSDK")` 하나만 부른다.

**이 셋은 `.gitignore` 로 막아 뒀다. 재배포 불가 — 절대 커밋하지 말 것.**

작업 사본: `C:\Users\nauty\lume-port\` (추출 원본, dex2jar 도구)

### 공식 경로도 하나 있다

`com.leia.android.lights` 는 **시스템 공유 라이브러리로 선언돼 있다**:
```xml
/system/etc/permissions/com.leia.android.lights.xml
<library name="com.leia.android.lights" file="/system/framework/com.leia.android.lights.jar"/>
```
`LeiaLightsManager` 에 `BacklightMode{MODE_2D, MODE_3D, MODE_3D_EXPERIMENTAL, MODE_IMMERSIVE,
MODE_TRANSITION}`, `setBacklightMode()`, `getBacklightType()`, `ViewConfig(NumberOfViews)` 가 있다.
**백라이트 전환만 필요하면 `<uses-library>` 로 공식 API 를 쓸 수 있다.** 위빙은 여전히 CNSDK 몫.

---

## 포팅 설계

우리 파이프라인은 이미 SBS FBO 를 만든 뒤 인터레이스한다. **마지막 한 단계만 교체하면 된다.**

```
ProMa : ... → FBO(L|R) → [libholography 마스크 + frag3D] → GLSurfaceView
Leia  : ... → FBO(L|R) → [CNSDK 서피스로 전달]          → InterlacedSurfaceView
```

자막·화면비·이어보기·엔진 선택·FFmpeg 오디오·스테레오 판별은 그대로 재사용된다.
반대로 `Fv3d*`(화이트리스트, 3D 컨트롤 센터)는 Lume Pad 2 에서 의미가 없다.

### 기하 (노트 7절 — 여기서 제일 많이 헤맨다고 적혀 있음)

- `setSourceSize` 는 **두 눈이 담긴 전체 프레임** 크기다. `numTiles(2,1)` 이 반으로 가른다.
- 눈당 권장 1920x1200 → 프레임 **3840x1200**. half-SBS 로 보내면 가로로 눌린다.
- `ScaleType` 을 믿지 말 것. `FIT_CENTER` 로 설정해도 실제로는 화면을 채운다.
  → **넘기기 전에 눈당 상자를 패널 종횡비(16:10)로 잡고 영상을 중앙 배치 + 레터박스**.
  우리 `Stereo3DView` 의 레터박스 계산을 그대로 쓸 수 있다.
- `setSourceSize` 소유권은 한 곳에만. 프레임에 눈이 몇 개 담기는지 아는 쪽이 가져야 한다.

### 초기화 함정 (노트 4절)

1. `<queries>` 로 `com.leia.headtrackingservice`, `com.leialoft.display.config` 선언.
   빠지면 **로그도 없이 프로세스 abort**. (우리는 targetSdk 28 이라 원래 제한이 없지만 그래도 넣는다)
2. `InitArgs.platform` 에 `app` 뿐 아니라 **`activity` 와 `context` 까지** 채워야 `createSDK` 가 null 이 아니다.
3. 초기화는 **비동기**다. `createSDK` 직후 `isInitialized()` 는 false — `Delegate.didInitialize` 를 기다린다.
4. `java.util.logging` 계열 로거는 이 기기 logcat 에 안 나온다. 진단은 `android.util.Log`.

### 생명주기 (노트 9절)

백라이트와 카메라는 시스템 공용이다. **앞에 있고 3D 일 때만** 잡는다.
`onPause` 에서 `startFaceTracking(false)` + `enableBacklight(false)`.
숨긴 `GLSurfaceView` 를 쓰면 그쪽 `onResume`/`onPause` 도 직접 불러야 한다.

### 2D→3D

지금 우리 방식(세로 그라디언트 시어)은 "장면은 기울어진 바닥면" 이라는 추측 하나뿐이다.
Lume Pad 2 에서는 실제 깊이맵을 쓸 수 있다:
- **MiDaS v2 int8 TFLite** — 노트에서 이 기기 6~9ms/프레임으로 검증. Intel 공개 모델이라 라이선스 무리 없음
- 또는 SNPE 로 NPU 사용 (Leia 방식). 더 빠르지만 SNPE SDK 별도 필요

---

## 다음 할 일

1. `app3d` 에 `proma` / `leia` 제품 플레이버 구성 (leia 만 CNSDK 의존)
2. `PanelBackend` 심(seam) 도입 — ProMa 는 현행 인터레이스, Leia 는 CNSDK 서피스
3. 최소 스파이크: 우리 SBS FBO 를 CNSDK 에 물려 3D 가 나오는지 실기 확인
4. 되면 자막·이어보기·오디오는 이미 있는 것을 붙이기만 하면 된다

---

## 2단계 완료 — 우리 파이프라인이 CNSDK 를 구동한다 (2026-09-05)

```
ExoPlayer -> Stereo3DView (OES -> FBO 좌/우 절반) -> CNSDK 서피스 -> 위빙 -> 패널
```
인터레이스 단계만 CNSDK 로 넘기고 레터박스·시어·자막은 그대로 쓴다.
실기 확인: 비율 정상, `InterlaceCheck` 가 `INTERLACED (3D)` 판정.

### 추가된 것

- `gl/ExternalGlTarget` — 같은 GL 컨텍스트에 두 번째 EGL 윈도우 서피스를 만들어
  프레임마다 갈아끼운다. `GLSurfaceView` 는 컨텍스트를 EGL10 으로 만드는데 윈도우
  서피스는 EGL14 로 만들게 되므로, config 를 새로 고르지 말고 현재 컨텍스트의
  `EGL_CONFIG_ID` 로 되찾아야 한다.
- `Stereo3DView.setExternalSbsTarget(surface, w, h)` — 있으면 인터레이스를 건너뛰고
  FBO 를 그 서피스로 blit 한다. FBO 크기도 그 값이 된다.
- `Stereo3DView.setUseHolography(false)` — Lume Pad 2 에서는 마스크를 만들지 않는다.

### 레터박스 계산을 두 패널에 맞게 일반화

`eyeDisplayAspect()` / `eyeDisplayWidth()` 를 도입했다. 눈 하나가 **화면에서** 갖는
상자를 기준으로 소스를 맞춘 뒤, 결과를 FBO 반쪽 픽셀로 환산한다.

|  | FBO 반쪽 | 화면에서의 눈 | 환산 |
|---|---|---|---|
| ProMa | 1280x1600 | 2560x1600 (아나모픽 2배) | x0.5 |
| Lume Pad 2 | 1920x1200 | 1920x1200 (그대로) | x1 |

시어 세기와 자막 크기도 `surfW` 대신 `eyeDisplayWidth()` 를 쓴다.
ProMa 경로의 결과값은 대수적으로 종전과 동일하다(확인함). 다만 **실기 확인은 못 했다** —
ProMa 가 연결돼 있지 않았다. USB 연결되면 회귀 확인이 필요하다.

### 노트에 없던 함정

**CNSDK 는 `assets/shaders/*` 와 `assets/cnsdk.version` 을 APK 에서 읽는다.**
빼먹으면 `Interlacer.doPostProcess` 안에서 셰이더가 null 이라 SIGSEGV 로 죽는다
(`leia::opengl::Shader::UseVariantWithPermutations`, fault addr 0x20).
jar 와 .so 만으로는 부족하다.

또 하나: `--ei w/h` 는 **CNSDK 로 내보낼 프레임 크기**지 소스 크기가 아니다.
소스 해상도는 `ExoPlayer.onVideoSizeChanged` 에서 `setVideoSize()` 로 따로 알려줘야
레터박스가 맞는다.

## 다음

1. PlayerActivity 를 leia 플레이버에서도 쓰게 한다 (지금은 스파이크 액티비티만)
   — 자막·이어보기·엔진 선택·FFmpeg 오디오가 한꺼번에 붙는다
2. 스테레오 자동판별을 그대로 태운다 (LeiaTube 도 layoutdetection 을 쓴다)
3. 2D→3D 는 시어 대신 MiDaS 깊이맵으로 (노트 10절, 이 기기 6~9ms/프레임)

---

## 3단계 완료 — 실제 플레이어가 Lume Pad 2 에서 돈다 (2026-09-05)

`LeiaSpikeActivity` 가 아니라 **`PlayerActivity` 가 그대로** 이 패널에서 동작한다.
자막·이어보기·엔진 선택·FFmpeg 오디오·픽셀 판별이 전부 따라왔다.

### PanelBackend 심

패널마다 다른 것은 마지막 한 단계뿐이라, 그것만 인터페이스로 갈랐다.

```
main   panel/PanelBackend    outputView / attach / useHolography / 생명주기
proma  panel/Panel           아무것도 안 함 (Stereo3DView 자체가 출력)
leia   panel/Panel           CNSDK 초기화 + InterlacedSurfaceView + 외부 타깃 연결
```

`PlayerActivity` 는 `Panel.create()` 만 부르면 된다. CNSDK 를 참조하는 코드가
leia 소스셋 밖으로 나가지 않으므로 proma 빌드는 CNSDK 없이 그대로 빌드된다.
CNSDK 뷰가 화면을 차지할 때는 우리 GLSurfaceView 를 1x1 로 깔고 탭 대상만 옮긴다.

### 잡은 버그: FBO 크기 경합

증상: 어떤 파일은 위빙이 안 되고 화면이 뭉개진다 (레고 배트맨 등).

```
I P3D : surface 1x1, FBO 1x1          <- 여기서 굳어버린다
I P3D : 외부 타깃 준비 3840x1200
```

FBO 를 `onSurfaceChanged` 에서만 만들었더니 순서에 걸렸다. 우리 GLSurfaceView 는
1x1 이라 표면이 먼저 준비되는 경우가 있고, 그때는 아직 외부 타깃이 없어 FBO 가
1x1 로 잡힌다. 그 뒤 CNSDK 서피스가 와도 **뷰 크기가 안 변하니 onSurfaceChanged 가
다시 오지 않아** FBO 가 1x1 인 채로 남고, 그것을 화면 전체로 늘려 뿌리게 된다.
스파이크에서 멀쩡했던 건 콜백 순서가 우연히 반대였기 때문이다.

수정: 매 프레임 외부 타깃 크기와 FBO 크기를 비교해 다르면 다시 만든다.
순서와 무관해진다.

---

## 저장소 분리 (2026-09-05)

Lume Pad 2 포팅이 공용 코드를 건드리는데 ProMa 실기로 확인할 수 없어서, 아예 갈랐다.

| 저장소 | 대상 | 상태 |
|---|---|---|
| `nautymac/proma3d` | ProMa P10 전용 | v1.2.4 상태로 되돌림. 릴리스 v1.0.0~v1.2.4 그대로 |
| `nautymac/p3d-lumepad` | Lume Pad 2 (이 저장소) | 전체 이력 포함해 분리 |

ProMa 기기에 설치된 것도 v1.2.4 라 동작 중인 버전에는 영향이 없다.
공통 개선(자막, 이어보기, 오디오 등)이 생기면 한쪽에서 만들고 다른 쪽으로 옮겨야 한다 —
그게 완전 분리의 대가다.

### 새로 받아 빌드할 때 필요한 것

둘 다 재배포 불가라 저장소에 없다. 로컬에서 채워야 한다.

```
app3d/app/libs/leia-cnsdk.jar                       CNSDK (이 문서 위쪽 절차)
app3d/app/src/leia/jniLibs/arm64-v8a/*.so           libleiaSDK.so, libleiaspdlog.so
app3d/app/src/leia/assets/shaders/*                 CNSDK 셰이더 — 없으면 SIGSEGV
app3d/app/src/leia/assets/cnsdk.version
app3d/ffmpeg/src/main/jni/ffmpeg/                   FFmpeg 정적 라이브러리 (ffmpeg/README.md)
```

빌드:
```
gradle assembleLeiaRelease      # Lume Pad 2 용
```
`proma` 플레이버도 남아 있지만 이 저장소에서는 쓰지 않는다.

---

## 2D→3D 실측 비교 (2026-09-05)

정지 프레임 하나로 네 가지를 만들어 패널에서 직접 비교했다. 실기 비교 도구는
`LeiaImageActivity` — SBS 이미지를 CNSDK 서피스에 그대로 그린다.

```
A  실제 SBS (정답)            원본에 들어있는 진짜 스테레오
B  MiDaS 깊이맵               왼눈 하나에서 합성
C  시어 (지금 앱)             왼눈 하나에서 합성
D  MiDaS + 경계보정           B 에 joint bilateral upsampling 추가
```

### 잡은 버그 — 2D→3D 깊이 순서가 거꾸로였다

블록 매칭으로 좌우 시차를 재보니 (눈당 1920px 기준):

| 위치 | A 정답 | B 깊이맵 | C 시어(수정 전) |
|---|---|---|---|
| 위 | +2 | +4 | **-4** |
| 아래 | -8 | -6 | **+4** |

실제 콘텐츠는 지면(아래)이 앞, 하늘(위)이 뒤인데 우리 시어는 반대였다.
셰이더 `t.x += (shearTop - v*shearSlope)` 는 샘플링 위치를 옮기므로 양수면 그 눈의
그림이 왼쪽으로 밀린다. `v=1` 이 화면 아래이므로 원래 식은 위를 앞으로 보내고 있었다.

기울기 부호를 뒤집으니 `위 +8 -> 아래 -8` 로 실제와 방향이 맞았고,
실기에서도 "이전보다 좋아졌다" 는 평가를 받았다. **`Stereo3DView` 에 반영함.**

이 버그는 ProMa 저장소에도 그대로 있다 (같은 코드에서 갈라져 나왔다).
실기 확인이 가능해지면 거기도 고쳐야 한다.

### 시차 크기 — 실제 콘텐츠는 생각보다 훨씬 작다

정답의 시차 폭이 **10px, 눈당 폭의 0.5%** 였다. 처음에 임의로 ±20px(폭 40px)을 줬는데
실제의 4배였다. 비교는 ±8px 로 맞춰서 했다.

### 깊이맵의 진짜 문제는 해상도가 아니라 경계였다

B 에 대한 실기 평가: "인물 입체감이 약하고 얼굴에 뭉개짐이 있다."
둘 다 원인이 하나다 — 256x256 깊이를 그냥 확대하면 깊이 경계가 물체 윤곽과 어긋난다.

**joint bilateral upsampling** (색이 비슷한 이웃에만 가중치) 하나를 넣자 두 결함이
같이 사라졌다. 실기 평가: "D 가 더 입체감이 있고 뭉개짐과 불편함은 없어졌다."

즉 모델을 키울 필요가 없다. MiDaS v2.1 small(256x256) 로 충분하고,
**부족한 것은 추론이 아니라 후처리였다.**

### 실시간화 비용 (추정)

| 단계 | 비용 |
|---|---|
| MiDaS 추론 (256x256, int8) | 6~9ms — 노트 실측 |
| 경계보정 7x7 | PC 자바 단일스레드 1.6s. GPU 프래그먼트 셰이더로 옮기면 per-pixel 49 탭 |
| DIBR 워프 | per-pixel 게더 1회 — 싸다 |

경계보정을 전해상도 49탭으로 하면 1920x1200 기준 프레임당 1억 회가 넘는 텍스처
페치다. 절반 해상도로 구하고 확대하거나 5x5 로 줄이면 현실적인 범위로 내려온다.
읽기 경로(OES -> 256x256 FBO -> glReadPixels 256KB)는 싸다.

남은 위험은 **시간적 안정성** — 프레임마다 깊이가 흔들리면 정지 장면도 꿈틀거린다.
EMA 평활이 필요하고, 그건 아직 시험하지 않았다.

## 2D→3D 를 Leia 엔진으로 (2026-09-05)

시어를 버리고 기기에 깔린 Leia 의 신경망 변환기를 쓴다. 같은 프레임으로 비교한 결과다.

| | 만든 방법 | 평가 |
|---|---|---|
| A | 진짜 SBS (게임이 그린 좌우 눈) | 정답 |
| B | Leia 엔진 (왼쪽 눈만 주고 변환) | **"월등하다", "A 보다 좋다"** |
| C | 우리 시어 (왼쪽 눈만 주고 변환) | — |

정답보다 낫다는 평이 나온 이상 시어를 고집할 이유가 없다. 시어 코드는 남겨뒀다 —
ProMa 는 그것으로 간다.

### 엔진을 어떻게 빌려 쓰나

`com.leiainc.media.service` (v0.5.28, 시스템 앱) 안에 다 들어 있다.

```
res/*.dlc                     모델 7개, 약 330MB
lib/arm64-v8a/libSNPE.so      SNPE 런타임
lib/arm64-v8a/libSnpeHtpV73Skel.so   Hexagon 스켈 (V73 = SD8 Gen2)
com.leiainc.androidsdk.video.mono.MonoVideoSurfaceRendererImpl
```

`LeiaMediaSDK` 는 **IPC 가 아니다.** `PackageManager` 로 그 APK 경로를 얻어
`DexClassLoader` 로 열고, 클래스와 네이티브 라이브러리를 **우리 프로세스에서** 돌린다.
그래서 우리가 재배포하는 바이너리가 없다 — 인터페이스 선언 두 개와 로더 하나가 전부다.

로더 클래스를 `com.leiainc.leiamediasdk` 패키지에 둔 이유가 있다. DexClassLoader 의
부모가 우리 앱 로더라 클래스 해석이 부모 우선인데, 서비스 쪽 모델 코드가
`LeiaMediaSDK.getAppWrapper()` 를 부른다. 그 호출이 우리 쪽으로 와야 한다.

### 함정 셋

1. **컨텍스트를 아무거나 넘기면 안 된다.** 구현체가 생성자에서
   `ctx.getApplicationInfo().nativeLibraryDir` 로 `ADSP_LIBRARY_PATH` 를 잡는다.
   우리 앱을 가리키면 Hexagon 스켈을 못 찾는다. `createPackageContext` 로 서비스
   컨텍스트를 만들어 넘긴다. (셰이더와 모델은 구현체가 알아서 서비스 리소스를 연다)

2. **한 서피스에 EGL 윈도우 표면을 둘이 만들 수 없다.** 우리 GL 이 CNSDK 출력면을
   쥔 채로 넘기면 `eglCreateWindowSurface` 가 `EGL_BAD_ALLOC(0x3003)` 을 낸다.
   2D 로 정해져 있으면 처음부터 우리가 잡지 않는다.

3. **판별 전 임시값으로 엔진을 만들면 안 된다.** 파일명 추측이 MONO_2D 였다가
   0.5초 뒤 판별이 SBS 라고 답하면, 만들어지고 있던 엔진과 출력면을 두고 다툰다.
   확정된 값(판별 결과·저장된 선택·수동 선택)일 때만 패널에 알린다.

### 배치가 맞아떨어진 점

`RenderConfig.getDefaultRenderConfig()` 가 Lume Pad 2 에서 `hTiles=2, vTiles=1` 이다.
즉 엔진 출력이 우리가 CNSDK 에 넘기던 SBS 와 같은 2타일이라 CNSDK 설정은 그대로다.

```
2D 소스 : 디코더 ▶ 우리 GL(모노 1920x1200) ▶ Leia 엔진 ▶ 2타일 ▶ CNSDK ▶ 패널
3D 소스 : 디코더 ▶ 우리 GL(SBS 3840x1200) ─────────────▶ CNSDK ▶ 패널
```

레터박스·화면비·자막은 여전히 우리 GL 몫이다. 시차만 엔진이 만든다.

실측: 엔진 준비 0.9~1.6초, 추론 프레임당 11~20ms, 깊이 추정 25~30fps,
입력 640x384 (`MODEL_2D3D_VIDEO_M640`, 런타임 DSP).

## 3D 화면 밝기 (2026-09-05)

3D 모드는 균일 백라이트를 완전히 끄고(`backlight_mode3d_ratio_2d = 0.0`) 회절 광원만
쓴다. 그래서 같은 설정값에서도 2D 보다 어둡다. 두 가지로 올렸다.

**창 밝기 고정** (코드에 들어 있음). 적응형 밝기가 시스템 설정 252 인데 203 까지
내려놓고 있었다. 재생 중에는 창 속성으로 최대에 묶는다. 화면을 벗어나면 저절로 풀린다.

**회절 광원 비율은 건드리지 않는다 (1.2 유지).** 한때 1.6 으로 올렸고 확실히 밝아졌지만,
그 대가가 크로스토크였다. 좌우 뷰가 서로 새면서 잔상이 생기고 3D 가 무너진다.
평면인 UI 만 또렷하게 남아 "인터페이스가 3D 화면과 분리돼 보인다" 는 증상으로 나타났다.
1.2 로 되돌리자 곧바로 정상이 됐다.

이 값을 캘리브레이션 밖으로 올리면 안 된다는 뜻이다. 밝기는 창 밝기 고정으로 얻는 것까지다.

```
# 값을 바꿔야 할 일이 생기면 (기본값은 1.2)
adb shell settings put system backlight_mode3d_ratio_3d 1.2
adb reboot        # ← 이게 필요하다
```

**재부팅이 반드시 필요하다.** Leia 의 정식 API(`LeiaLightsManagerV8.setBacklightRatios`)
도 같은 `Settings.System` 키에 쓰기만 하고, 백라이트 서비스는 시작할 때 한 번 읽는다.
쓴 직후에 `getBacklightRatios` 로 물으면 서비스는 여전히 옛 값을 답한다.
서비스를 죽여서 다시 읽게 하려 들면 백라이트가 3D 에 물린 채 남아 화면이 먹는다.

되돌리려면 `1.2` 를 쓰고 다시 재부팅한다. 원래 값:
`mode3d_ratio_3d=1.2`, `mode3d_ratio_2d=0.0`, `mode2d_ratio_3d=0.25`, `colormode_3d=7`.

진단 도구: `LeiaLightsActivity` — 서비스가 실제로 들고 있는 값을 묻는다.
Settings 값과 서비스 값을 나란히 찍어주므로 "썼는데 반영이 안 된다" 를 바로 가른다.

```
adb shell appops set com.nauty.p3d WRITE_SETTINGS allow
adb shell am start -n com.nauty.p3d/com.nauty.p3d.leia.LeiaLightsActivity
adb shell am start -n com.nauty.p3d/com.nauty.p3d.leia.LeiaLightsActivity --ef r3d 1.6
```

이 값을 두고 두 번 틀린 판단을 했다. 처음에는 "설정이 안 먹는다" 고 봤는데 재부팅을
안 한 탓이었고, 그때 본 검은 화면은 밝기가 아니라 위의 함정 3(서피스 다툼)이었다.
그 다음에는 1.6 이 잘 도는 것을 보고 채택했는데, 밝아진 대신 크로스토크가 늘어
2D·3D 가릴 것 없이 잔상이 생겼다. 그 뒤로 엔진·수렴·프레임 지연을 한참 뒤졌지만
전부 헛짚은 것이었고, 1.2 로 되돌리는 것으로 끝났다.

교훈: 3D 품질이 전반적으로 나빠지면 파이프라인보다 패널 설정을 먼저 의심할 것.
잔상은 크로스토크의 전형적인 증상이다.

### 엔진의 한계처럼 보였던 것들 (전부 밝기 탓이었다)

회절 광원을 1.6 으로 올려둔 동안, 2D→3D 결과가 영상에 따라 심하게 흔들리고 잔상이
남았다. 그때 다음과 같이 진단했는데 **전부 틀렸다.**

- "60fps 게임 캡처는 깊이 추정(20fps)이 못 따라가서 그렇다"
- "좌우 비교 화면(DLSS 4 vs 5)은 신경망에 최악의 입력이라 그렇다"
- "자동 수렴이 매 프레임 널뛰어 화면이 좌우로 흔들린다"
- "RGB 프레임 지연 기본값(5)이 이 소스에 맞지 않는다"

1.2 로 되돌리자 같은 영상들이 전부 정상으로 나왔다. 엔진은 62fps 2560x1440
게임 캡처도, 좌우 분할 비교 영상도 문제없이 처리한다.

증상이 2D 와 3D 양쪽에 나타난 시점에서 이미 파이프라인 밖의 문제였다 —
3D 소스는 엔진을 아예 거치지 않기 때문이다. 정지 SBS 그림에서까지 재현된 것이
결정적인 단서였는데 한참 뒤에야 알아챘다.
