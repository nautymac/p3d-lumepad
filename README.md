# proma3d

*한국어 · [English](README.en.md)*

ProMa P10 (무안경 3D 태블릿, MTK X20 / Android 8.0 / arm64) 관련 작업 모음.

기기에 기본 탑재된 3D 앱들(3DPlayer / Sight3D / 3DFV)을 리버스엔지니어링해서
렌더 파이프라인을 알아내고, 그걸 바탕으로 통합 3D 플레이어를 새로 만들었다.

## 구성

| 경로 | 내용 |
|---|---|
| [`app3d/`](app3d/) | **P3D Player** — 새로 만든 3D 플레이어 (안드로이드 프로젝트) |
| [`app3d/README.md`](app3d/README.md) | 앱 구조·빌드 방법·겪은 버그와 원인 |
| [`FINDINGS.md`](FINDINGS.md) | 리버스엔지니어링 전체 기록 (3DFV API, 셰이더 수식, 원본 버그) |
| `apks/` | 분석 대상이 된 기기 기본 앱 APK (기기에서 추출) |
| `shaders/`, `libs/` | APK 에서 꺼낸 셰이더와 네이티브 라이브러리 |
| `InterlaceCheck.java` | 스크린샷에 3D 인터레이스가 걸렸는지 픽셀로 판정 |
| `Crop.java` | 스크린샷 일부를 잘라 확대 (아티팩트 확인용) |

## 핵심 요약

- **3DFV** 는 시스템 3D 서비스다. `onBind()` 가 null 이라 바인딩이 아니라
  **브로드캐스트가 공개 API** 다. 화이트리스트에 등록된 액티비티가 가로모드로
  최상단일 때 SurfaceFlinger 에 지시해 패널을 3D 로 전환한다.
- **3D 렌더에 실제로 필요한 네이티브는 `libholography.so` 하나**뿐이다.
  이게 렌티큘러 마스크를 GL 텍스처에 써 넣고, `frag3D.sh` 가 그 마스크로
  좌/우 뷰를 픽셀 단위로 섞는다.
- 정적 JNI 네이밍이라 클래스는 반드시 `com.future.Holography.Holography` 여야 한다.

자세한 내용은 [`FINDINGS.md`](FINDINGS.md) 참고.

## 참고

여기 담긴 `apks/`, `shaders/`, `libs/` 는 기기 제조사(WZ Tech / MediaTek 등)의
저작물이며, 본인 소유 기기에서 추출한 개인 백업이다. 재배포용이 아니다.

디컴파일 소스(`src/`)와 테스트 스크린샷은 저장소에서 제외했다.
디컴파일은 `apks/` 에서 jadx 로 언제든 다시 만들 수 있다:

```
java -cp jadx-gui-1.5.6-all.jar jadx.cli.JadxCLI -d src/3DPlayer apks/3DPlayer_1114.apk
```
