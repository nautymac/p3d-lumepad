# DepthFlix — Lume Pad 2 용 3D 영상·사진 뷰어

*한국어 · [English](README.en.md)*

[Leia Lume Pad 2](https://www.leiainc.com/) (LPD-20W, 8방향 회절 라이트필드 +
얼굴추적) 에서 **3D 영상과 3D 사진을 제대로 보기 위해** 만든 뷰어다.

기기에 들어 있는 LeiaPlayer 로도 볼 수는 있지만, 이쪽은 다음이 다르다.

- **SBS/TB 배치를 픽셀과 해상도로 직접 판별한다** — 파일 이름을 믿지 않는다
- **수렴을 실제로 재서 맞춘다** — 게임에서 뽑은 SBS 는 만든 화면 기준으로 좌우가
  통째로 어긋나 있어서, 태블릿에 그대로 올리면 두 눈이 모으지 못한다
- **자막을 좌·우 뷰에 각각 그린다** — 화면 위에 2D 로 얹으면 위빙을 거치며 겹쳐 보인다
- **2D 영상은 Leia 자신의 신경망 변환기로 3D 화한다** — 기기 안에 이미 있는 것을 쓴다
- **DTS·AC3 를 소리 나게 재생한다** (FFmpeg 오디오 확장)

---

## 설치

[Releases](../../releases) 의 APK 를 받아 설치하면 된다. Lume Pad 2 라면
**그 밖에 할 일이 없다** — adb 설정도, 루팅도 필요 없다.

| 필요한 것 | |
|---|---|
| 기기 | Lume Pad 2 (LPD-20W) |
| 권한 | 저장소 읽기 하나. 처음 실행할 때 앱이 물어본다 |
| 얼굴추적 | 기기의 Leia 서비스가 한다. 이 앱은 카메라 권한 없이 동작한다 |

---

## 쓰는 법

### 목록

앱을 열면 **영상 폴더** 목록이 나온다. 폴더를 누르면 그 안의 파일이 보인다.

| 조작 | |
|---|---|
| **사진 보기 / 영상 보기** | 목록을 사진과 영상 사이에서 바꾼다 |
| 폴더를 **길게 누르기** | 그 폴더를 목록 맨 위에 고정한다 (★). 다시 누르면 해제 |
| **뒤로** | 파일 목록 → 폴더 목록 |
| **URL/스트리밍 열기** | `http(s)` / `m3u8` / `mpd` / `rtsp` 주소를 직접 연다 |

영상 이름 옆의 `[좌우 SBS (full)]` 같은 표시는 **이름에서 추측한** 배치다.
실제 배치는 열면서 픽셀로 다시 판별한다.

### 재생 화면

화면을 한 번 누르면 아래 재생바가 나타나고 사라진다.

| 버튼 | 영상 | 사진 |
|---|---|---|
| ◀◀ ▶▶ | 짧게 30초, 길게 5분 이동 | 이전/다음 장, 길게 10장 |
| ❚❚ | 재생/일시정지 | — |
| 시간 표시 | 위치 / 길이 | 몇 번째 / 전체 |
| ⚙ 설정 | 오른쪽 설정판 | 같음 |

사진은 **지금 들어간 폴더 안에서만** 넘어간다.

### 설정판

**3D**

| | |
|---|---|
| **소스** | 배치를 직접 고른다 (2D / 좌우 half·full / 상하 half·full). 고르면 그 파일에 기억된다 |
| **출력** | 3D · 2D · SBS 확인 |
| **좌우반전** | 깊이가 반대로 보일 때 |
| **↺ 자동 판별로 되돌리기** | 잘못 고른 것을 지우고 다시 판별한다 |
| **화면 비** | 자동 · 16:9 · 2.40:1 · 1.85:1 · 4:3 · 꽉 채우기 |
| **화면 비 미세조정** | 1.00 ~ 3.00. 임의 비율로 눌러 담은 파일용 |
| **깊이** | 2D→3D 변환의 시차 강도 |
| **수렴 보정** | 아래 설명 |

**자막**

자막 파일(`.srt` / `.smi`)이 영상 옆에 있으면 자동으로 읽는다. 없으면
**자막 선택** 으로 영상 폴더 · `Movies` · `Download` · `Subtitles` 를 뒤져 고른다.
크기 · 위치 · 깊이(앞으로 튀어나오는 정도)를 조절할 수 있고, 값은 기억된다.

---

## 수렴 보정 — 이 앱을 만든 이유의 절반

게임에서 뽑은 SBS 스크린샷·영상은 **만들 때 쓰던 모니터와 그때의 convergence
설정이 픽셀 수로 굳어** 있다. 같은 그림을 태블릿 눈 상자에 맞춰 늘리거나 줄이면
시차도 같은 비율로 변한다.

기기의 HelixMod 스크린샷 36장을 재봤더니 좌우 절반이 **통째로** 어긋나 있었다 —
1920px 눈 기준으로 20px 부터 224px(눈 폭의 11.7%)까지. 정작 장면 자체의 깊이 폭은
28~184px 로 그보다 훨씬 작다. 24인치 모니터에서 만든 값을 10인치 태블릿에 그대로
올리니 두 눈이 모을 수 있는 한계를 넘는 것이다.

- **수렴 보정** 슬라이더 — 화면에 나가는 시차를 픽셀 단위로 밀고 당긴다.
  양수면 장면이 화면 뒤로, 음수면 앞으로 온다. 파일마다 기억된다.
- **수렴 자동 (장면 중심을 화면에)** — 지금 그림의 시차를 실제로 재서
  장면의 중심을 화면 평면에 놓는다. 깊이 폭의 절반은 앞, 절반은 뒤로 갈려서
  두 눈이 모으기 편한 범위에 들어온다. 측정한 시차 범위는 설정판에 같이 표시된다.

눈금이 **화면 픽셀**이라 소스 해상도가 3344 든 5120 이든 같은 값이 같은 뜻이다.

---

## 어떻게 동작하나

```
영상/사진 ─▶ 디코더 ─▶ OES 텍스처 ─▶ 우리 GL
                                       │  레터박스 · 화면비 · SBS/TB 크롭
                                       │  수렴 보정 · 자막(좌우 각각)
                                       ▼
                       2D 소스 ──▶ Leia 신경망 변환기 ──┐
                       3D 소스 ──▶ SBS 프레임 ─────────┴─▶ CNSDK 위빙 ─▶ 화면
```

마지막 단계(위빙)는 우리가 하지 않는다. 이 패널은 8방향 회절이고 얼굴 위치에 따라
매 프레임 짜임이 달라져서, 고정 패턴으로는 3D 가 서지 않기 때문이다. CNSDK 에 넘긴다.

**2D→3D 는 Leia 자신의 신경망 변환기를 쓴다.** 기기의 시스템 앱
(`com.leiainc.media.service`) 이 모델과 SNPE·Hexagon 실행기를 들고 있어서, 그것을
우리 프로세스로 불러들여 쓴다. 우리가 재배포하는 Leia 바이너리는 없다.
같은 프레임으로 진짜 스테레오와 비교해 봤을 때 우리가 쓰던 시어 방식보다 확실히 낫다.

자세한 기록은 [`LUMEPAD2-PORT.md`](LUMEPAD2-PORT.md) 에 있다 — 잘못 짚었던
진단까지 그대로 남겨뒀다.

**다른 앱에 3D 를 붙이려면** [`MOONLIGHT-LUMEPAD2.md`](MOONLIGHT-LUMEPAD2.md) 를 보라.
Moonlight 을 예로 들어, CNSDK 를 앱에 넣는 법과 실기에서 물린 함정 넷을 정리했다.
이 패널은 고정 패턴이 아니라서 밖에서 남의 화면을 3D 로 바꿔줄 방법이 없다 —
그리는 앱 자신이 위빙을 해야 한다.

---

## 직접 빌드하려면

CNSDK 는 Leia 의 것이라 재배포할 수 없어서 **저장소에 들어 있지 않다.**
본인 기기에서 꺼내야 한다.

```bash
# 1) 기기에서 CNSDK 를 들고 있는 앱을 찾는다
adb shell pm path com.moonlight.leia

# 2) APK 를 꺼내 푼다
adb pull <위에서 나온 경로> leia.apk
unzip leia.apk -d leia/

# 3) 필요한 것만 제자리에 놓는다
cp leia/lib/arm64-v8a/libleiaSDK.so     app3d/app/src/main/jniLibs/arm64-v8a/
cp leia/lib/arm64-v8a/libleiaspdlog.so  app3d/app/src/main/jniLibs/arm64-v8a/
cp -r leia/assets/shaders               app3d/app/src/main/assets/
cp leia/assets/cnsdk.version            app3d/app/src/main/assets/

# 4) CNSDK 클래스를 jar 로 만들어 app3d/app/libs/leia-cnsdk.jar 에 둔다
#    (classes*.dex 를 dex2jar 등으로 변환)
```

그 다음 평소대로 빌드한다.

```bash
cd app3d
gradle assembleDebug          # 또는 assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

서명해서 배포하려면 `app3d/keystore.properties` 를 만든다 (저장소에 넣지 않는다).

```properties
storeFile=/절대/경로/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

FFmpeg 오디오 확장(AC3/E-AC3/DTS/TrueHD)은 [`app3d/ffmpeg/README.md`](app3d/ffmpeg/README.md)
의 스크립트로 만든다. 없어도 빌드는 되지만 그 코덱들이 무음이 된다.

---

## 저장소에 없는 것

| | 왜 |
|---|---|
| `app3d/app/libs/leia-cnsdk.jar` | Leia 의 저작물. 기기에서 꺼내 쓸 것 |
| `app3d/app/src/main/jniLibs/` | 같음 (`libleiaSDK.so`, `libleiaspdlog.so`) |
| `app3d/app/src/main/assets/shaders/`, `cnsdk.version` | 같음 |
| `app3d/ffmpeg/src/main/jni/ffmpeg/` | 빌드 스크립트로 재생성 |
| 서명 키 | 당연히 |

---

## 라이선스

[MIT](LICENSE) — 이 저장소에 담긴 우리 코드에 한한다.

이 앱은 다음을 함께 쓴다. 각자의 조건을 따른다.

| | |
|---|---|
| [AndroidX Media3 (ExoPlayer)](https://github.com/androidx/media) | Apache-2.0 |
| [libVLC for Android](https://code.videolan.org/videolan/vlc-android) | LGPL-2.1+ |
| [FFmpeg](https://ffmpeg.org/) | LGPL-2.1+ (기본 설정 기준) |
| Leia CNSDK / LeiaMediaSDK | Leia Inc. 사유. **저장소에 없다** — 각자 기기에서 꺼내 쓸 것 |
