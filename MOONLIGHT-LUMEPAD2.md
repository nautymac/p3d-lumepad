# Moonlight 을 Lume Pad 2 에서 3D 로 — 우리가 알아낸 것 정리

*이 문서는 [DepthFlix](README.md) 를 만들며 실기에서 확인한 것을 Moonlight 연동 관점으로
다시 묶은 것이다. 확인한 것과 추정을 구분해 적었다.*

PC 에서 3D Vision / Geo-11 로 SBS 를 뽑아 Moonlight 으로 Lume Pad 2 에 스트리밍하고,
그것을 무안경 3D 로 보는 것이 목표다.

---

## 먼저: ProMa 에서 쓰던 방법은 여기서 안 통한다

ProMa P10 에서는 Moonlight 에 **손을 대지 않았다.** 3DFV 라는 시스템 서비스의
화이트리스트에 `com.limelight.Game` 을 등록하면, 그 액티비티가 가로모드로 최상단일 때
서비스가 SurfaceFlinger 에 지시해 패널을 3D 로 전환했다. 앱은 그냥 SBS 를 그리기만
하면 됐다.

**Lume Pad 2 에는 그런 서비스가 없고, 있을 수도 없다.**

| | ProMa P10 | Lume Pad 2 |
|---|---|---|
| 패널 | 렌티큘러, **고정 패턴** | 8방향 회절 + **얼굴추적** |
| 위빙 | 마스크 한 장으로 끝 (`libholography`) | 얼굴 위치에 따라 **매 프레임 달라짐** (CNSDK) |
| 남의 앱을 3D 로 | 화이트리스트로 가능 | **불가능** |

고정 패턴이 아니기 때문에, 밖에서 화면을 가로채 3D 로 바꿔줄 방법이 없다. 위빙은
그림을 그리는 앱 자신이 CNSDK 를 불러서 해야 한다. 즉 **Moonlight 을 고쳐야 한다.**

---

## 필요한 것

### 1. CNSDK 를 앱에 넣는다

기기의 `com.moonlight.leia` APK 에서 꺼낸다 (Leia 의 저작물이라 재배포 불가 — 각자
자기 기기에서 꺼내야 한다).

```bash
adb shell pm path com.moonlight.leia
adb pull <위 경로> leia.apk
unzip leia.apk -d leia/

# 네이티브
cp leia/lib/arm64-v8a/libleiaSDK.so     app/src/main/jniLibs/arm64-v8a/
cp leia/lib/arm64-v8a/libleiaspdlog.so  app/src/main/jniLibs/arm64-v8a/
# 셰이더와 버전 파일 (없으면 CNSDK 가 초기화에 실패한다)
cp -r leia/assets/shaders  app/src/main/assets/
cp leia/assets/cnsdk.version app/src/main/assets/
# 클래스는 dex2jar 등으로 jar 로 만들어 app/libs/ 에
```

매니페스트에도 이것이 필요하다.

```xml
<uses-library android:name="com.leia.android.lights" android:required="false"/>

<!-- targetSdk 를 30 이상으로 올릴 거면 반드시. CNSDK 가 시작하면서 이 패키지들을
     조회하는데, 가시성 제한으로 실패하면 예외를 지우지 않고 다음 JNI 호출로 넘어가
     프로세스가 로그도 없이 abort 된다. -->
<queries>
    <package android:name="com.leia.headtrackingservice" />
    <package android:name="com.leialoft.display.config" />
</queries>
```

**카메라 권한은 필요 없다.** 얼굴추적은 기기의 Leia 서비스가 하고 우리는 결과만
받는다. 실측으로 확인했다 — `CAMERA` 가 거부된 상태에서도 위빙이 정상 동작한다.

### 2. 화면에 `InterlacedSurfaceView` 를 깐다

```java
InterlacedSurfaceView view = new InterlacedSurfaceView(activity);

InputViewsAsset asset = new InputViewsAsset();
asset.CreateEmptySurfaceForVideo(3840, 1200, st -> {
    st.setDefaultBufferSize(3840, 1200);
    Surface out = new Surface(st);      // ← 여기에 SBS 를 그려 넣으면 된다
});
view.setViewAsset(asset);

try (InterlacedSurfaceViewConfigAccessor c = view.getConfig()) {
    c.setSourceSize(3840, 1200);        // 두 눈이 담긴 전체 프레임
    c.setNumTiles(2, 1);                // 그것을 좌우로 가른다
    c.setScaleType(ScaleType.FIT_CENTER);
}

LeiaSDK.InitArgs args = new LeiaSDK.InitArgs();
args.platform = new PlatformInitArgs();
args.platform.app      = activity.getApplication();
args.platform.activity = activity;      // 빠지면 createSDK 가 null 을 준다
args.platform.context  = activity;      // 마찬가지
args.enableFaceTracking = true;
args.delegate = this;                   // didInitialize 콜백을 받는다
sdk = LeiaSDK.createSDK(args);
```

**3840x1200 인 이유**: 패널이 보고하는 View Resolution 이 눈당 1920x1200 이다.
더 올려봐야 패널이 표현하지 못하는 픽셀에 대역폭만 쓴다.

### 3. 디코더 출력을 그 서피스로 보낸다

Moonlight 은 MediaCodec 출력을 `SurfaceView` 에 바로 그린다. 그 사이에 한 단계가
들어가야 한다.

```
MediaCodec ─▶ SurfaceTexture(OES) ─▶ GL ─▶ CNSDK 입력면 ─▶ 위빙 ─▶ 패널
                                     └ 레터박스 / 좌우 크롭 / 수렴 보정
```

스트림이 이미 SBS 라면 GL 이 할 일은 **좌우를 각각 3840x1200 의 절반에 맞춰 넣는
것**뿐이다. 우리 [`Stereo3DView`](app3d/app/src/main/java/com/nauty/p3d/gl/Stereo3DView.java)
가 정확히 그 일을 한다 — `setExternalSbsTarget(surface, 3840, 1200)` 을 부르면
인터레이스 단계를 건너뛰고 그 서피스로 내보낸다.

---

## 반드시 알아야 할 함정 넷 (전부 실기에서 물린 것)

### ① 한 Surface 에 EGL 윈도우 표면은 하나뿐

CNSDK 입력면에 우리 GL 이 붙어 있는데 다른 것이 또 붙으려 하면
`eglCreateWindowSurface` 가 **`EGL_BAD_ALLOC (0x3003)`** 을 낸다. 그리는 주체를
바꿀 때는 먼저 놓은 쪽이 완전히 놓을 때까지 기다려야 한다 (우리는 GL 스레드에
`CountDownLatch` 를 걸어 확인한다).

증상이 고약하다 — 화면이 그냥 검게 나오고, 원인이 로그 한 줄에만 있다.

### ② NoFaceMode 를 꺼라

CNSDK 는 기본값으로 **얼굴을 잠깐 놓치면 3D 백라이트를 끈다.** 로그에
`NoFaceMode Backlight attempting to turn off` 가 뜬다. 게임 중에 고개를 돌리거나
조명이 바뀌면 3D 가 툭 풀렸다가 돌아온다.

```java
@Override public void didInitialize(LeiaSDK s) {
    s.enableBacklight(false);   // 이전 세션이 3D 로 남겨둔 것을 정리
    s.enableNoFaceMode(false);  // ← 이것
    // ...
}
```

이걸 못 찾아서 한참 헤맸다. "위빙이 해제된다" 는 증상의 원인이 이것이었다.

### ③ 회절 광원 비율을 올리지 마라

`Settings.System` 의 `backlight_mode3d_ratio_3d` 를 기본값 1.2 에서 올리면 화면이
확실히 밝아진다. **하지만 크로스토크가 늘어 잔상이 생긴다.**

1.6 으로 올려두고 한동안 썼는데, 그 사이 2D→3D 결과가 영상마다 심하게 흔들려서
엔진·수렴·프레임 지연을 한참 뒤졌다. **전부 헛짚은 것이었고** 1.2 로 되돌리자
같은 영상들이 전부 정상으로 나왔다. 정지 SBS 그림에서까지 재현된 것이 결정적인
단서였는데 한참 뒤에야 알아챘다.

> 3D 품질이 전반적으로 나빠지면 파이프라인보다 패널 설정을 먼저 의심할 것.
> 잔상은 크로스토크의 전형적인 증상이다.

바꾸려면 재부팅이 필요하다는 점도 함께 기억해 둘 것 — 설정만 쓰고 확인하면
"안 먹는다" 고 오진하게 된다.

### ④ `LeiaLightsManager.factoryReset()` 을 부르지 마라

라이브러리 기본값을 써 넣는데, 그 값이 이 기기의 공장값과 다르다. 특히
`interlacing_matrix_*` 를 `"0,0,0,0;..."` 으로 채워버리는데 이 기기는 원래 그 값이
비어 있다(공장 보정값 사용). 되돌리기 번거롭다.

---

## 재생을 언제 시작할 것인가

띄우자마자 그리기 시작하면 **기본 시점으로 짜다가 추적이 붙는 순간 위빙이 제자리를
찾으면서 화면이 한 번 튄다.** 재생 시작 2~3초쯤에 화면이 2D→3D 로 확 바뀌는 것처럼
보인다.

얼굴추적이 붙을 때까지 기다렸다 시작하면 그 튐이 보이지 않는다.

```java
boolean started = sdk.isFaceTrackingStarted();
Vector3 face = sdk.getPrimaryFace();
```

다만 **끝내 안 붙어도 시작은 해야 한다** — 우리는 2초 타임아웃을 뒀다.

그리고 추적이 멎어 보인다고 **껐다 켜지 마라.** 5초마다 재시작을 걸었더니 서비스가
카메라를 잡을 틈이 없어 영영 못 붙었다 (`Active Camera Clients: []`). 고치려던 것을
더 망가뜨린 사례다. 관찰만 하고 내버려 두는 편이 낫다.

---

## Moonlight 에 특히 중요한 것: 수렴 보정

여기가 이 문서에서 가장 값나가는 부분이다.

PC 에서 3D Vision / Geo-11 로 뽑은 SBS 는 **만들 때 쓰던 모니터와 그때의 convergence
설정이 픽셀 수로 굳어 있다.** 같은 그림을 태블릿 눈 상자(1920px)에 맞춰 늘리거나
줄이면 시차도 같은 비율로 변한다.

기기의 HelixMod 스크린샷 36장을 실측했다. 좌우 절반이 **통째로** 어긋나 있었다:

| | 1920px 눈 기준 |
|---|---|
| 전역 오프셋 (좌우가 통째로 밀린 양) | **20 ~ 224 px** (눈 폭의 최대 11.7%) |
| 장면 자체의 깊이 폭 | 28 ~ 184 px |

대조군으로 잘 만든 3D 사진(Lume Pad 기본 샘플)을 재보니 **−11 ~ +11 px** 이었다.
게임 SBS 가 열 배 이상 어긋나 있다는 뜻이다. 24인치 모니터에서 만든 값을 10인치
태블릿에 그대로 올리니 두 눈이 모을 수 있는 한계를 넘는다.

**이걸 보정하지 않으면 스트리밍 3D 는 불편하거나 아예 안 맞는다.**

### 어떻게 재고 어떻게 맞추나

[`Disparity.java`](app3d/app/src/main/java/com/nauty/p3d/Disparity.java) 가 두 단계로 잰다.

1. **전역 정합** — 겹치는 영역 전체를 견줘 큰 오프셋 `d0` 를 찾는다. 이것이
   "이 소스가 통째로 밀린 양"
2. **국소 블록 매칭** — `d0` 근처에서만 훑어 장면의 깊이 폭을 구한다

한 단계로 ±8% 만 훑었을 때는 표본 대부분이 탐색 범위 끝에 붙어 값이 전혀 못
미더웠다. 실제 시차가 범위 밖이었기 때문이다. 이 실패가 2단계로 간 이유다.

맞추는 규칙은 **장면 중심을 화면 평면에 놓는 것**으로 했다. "가장 앞을 화면에"
가 더 자연스러워 보이지만, 어느 쪽이 앞인지 확정할 수가 없었다 — 상식적인 단서
(아래쪽이 가깝다)로 재보니 36장 중 31장이 거꾸로 나왔는데, 좌우가 뒤바뀐 것인지
HUD 를 화면 깊이에 고정해 둔 탓인지 가릴 수 없었다. 중심을 화면에 놓으면 깊이 폭의
절반이 앞, 절반이 뒤로 갈려서 **어느 해석이든** 편한 범위에 들어온다.

### 거는 방법

좌/우 뷰를 그릴 때 **뷰포트를 반씩 반대로 밀고 가위질(scissor)로 각자의 절반 밖을
막는다.**

```java
int conv = Math.round(convergencePx * 0.5f * halfW / eyeDisplayWidth());

GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
GLES20.glScissor(0, 0, halfW, fboH);
GLES20.glViewport(dxL - conv, dy, dw, dh);   // 좌안
// ... 그리기
GLES20.glScissor(halfW, 0, fboW - halfW, fboH);
GLES20.glViewport(dxR + conv, dy, dw, dh);   // 우안
// ... 그리기
GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
```

UV 를 미는 방법도 있지만 그러면 **옆 눈의 그림을 끌어다 쓰게 된다** — 화면 좌우 끝에
반대쪽 눈 조각이 묻어난다. 가위질하면 드러난 자리가 검은색이 된다.

눈금은 **화면 픽셀**로 잡는 것이 중요하다. 소스 픽셀 기준으로 잡으면 스트리밍
해상도가 바뀔 때마다 같은 값의 뜻이 달라진다.

### Moonlight 에서는 자동으로 걸 수 있다

플레이어와 달리 스트리밍은 **호스트가 정해져 있다.** 한 번 측정해서 그 PC·그 게임의
값으로 저장해 두면, 다음부터는 잴 필요 없이 바로 걸면 된다. 측정은 첫 몇 초의
프레임 한 장이면 충분하다 (표본 수백 개, 1초 이내).

---

## 지연 (확인하지 않음 — 추정)

Moonlight 은 지연에 민감한데 우리는 플레이어라 재본 적이 없다. 늘어나는 곳은 둘이다.

- **우리 GL 한 패스** — 3840x1200 텍스처 복사 수준. 1ms 안쪽일 것이다
- **CNSDK 위빙** — 프레임 지연이 얼마인지 모른다. 얼굴추적 결과를 기다린다면
  추가 프레임이 붙을 수 있다

실제로 붙여 보고 재봐야 한다. 만약 CNSDK 쪽 지연이 크다면 **경쟁 게임에는 못 쓰고
싱글 플레이 전용**이 될 수 있다.

참고로 우리 파이프라인 자체는 3840x1080 10bit HEVC 를 하드웨어 디코딩으로
27.6fps, 2560x1440 62fps 게임 캡처를 문제없이 처리한다.

---

## 안 해도 되는 것

- **스테레오 배치 자동판별** — Moonlight 은 스트림이 SBS 인지 이미 안다.
  플레이어에서 필요했던 [`StereoDetect`](app3d/app/src/main/java/com/nauty/p3d/StereoDetect.java)
  는 여기서 쓸 일이 없다
- **2D→3D 신경망 변환** — 호스트가 이미 진짜 스테레오를 보내준다. 다만 2D 게임을
  3D 로 보고 싶다면 Leia 의 변환기를 같은 방식으로 끌어 쓸 수는 있다
  (`LeiaMediaSDK` — 기기의 시스템 앱을 `DexClassLoader` 로 우리 프로세스에 올린다.
  자세한 것은 [LUMEPAD2-PORT.md](LUMEPAD2-PORT.md) 참고)
- **자막** — 스트리밍에 자막은 없다

---

## 가져다 쓸 수 있는 파일

전부 [MIT](LICENSE) 다.

| 파일 | 하는 일 |
|---|---|
| [`gl/Stereo3DView.java`](app3d/app/src/main/java/com/nauty/p3d/gl/Stereo3DView.java) | OES 텍스처 → 레터박스·크롭·수렴 → SBS 프레임을 남의 서피스로 |
| [`Disparity.java`](app3d/app/src/main/java/com/nauty/p3d/Disparity.java) | 시차 측정 (2단계 블록 매칭) |
| [`panel/Panel.java`](app3d/app/src/main/java/com/nauty/p3d/panel/Panel.java) | CNSDK 초기화·수명 관리, 위의 함정들 회피가 전부 들어 있다 |
| [`gl/ExternalGlTarget.java`](app3d/app/src/main/java/com/nauty/p3d/gl/ExternalGlTarget.java) | 남의 Surface 에 그리기 위한 두 번째 EGL 표면 |

`Panel.java` 는 우리 `PanelBackend` 인터페이스에 묶여 있으니 그 부분만 걷어내면 된다.
CNSDK 를 다루는 부분은 그대로 쓸 수 있다.

---

## 요약

1. Lume Pad 2 에서는 **Moonlight 자체를 고쳐야 한다.** 밖에서 3D 로 바꿔줄 방법이 없다
2. CNSDK 를 기기에서 꺼내 넣고, `InterlacedSurfaceView` 에 3840x1200 SBS 를 그려 넣는다
3. 함정 넷: **EGL 표면 중복 · NoFaceMode · 광원 비율 · factoryReset**
4. **수렴 보정이 핵심이다.** 게임 SBS 는 좌우가 통째로 최대 224px 어긋나 있고,
   보정하지 않으면 태블릿에서 융합되지 않는다
5. 지연은 재봐야 안다
