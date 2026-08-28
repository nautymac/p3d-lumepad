# proma3d

*[한국어](README.md) · English*

Work around the ProMa P10 — a glasses-free autostereoscopic tablet
(MTK X20 / Android 8.0 / arm64).

The stock 3D apps that ship on the device (3DPlayer / Sight3D / 3DFV) were reverse
engineered to recover the render pipeline, and a new unified 3D player was built on
top of what that revealed.

## Contents

| Path | What it is |
|---|---|
| [`app3d/`](app3d/) | **P3D Player** — the new 3D player (Android project) |
| [`app3d/README.en.md`](app3d/README.en.md) | App structure, build instructions, bugs hit and their causes |
| [`FINDINGS.en.md`](FINDINGS.en.md) | Full reverse-engineering notes (3DFV API, shader math, bugs in the stock app) |
| `apks/` | The stock APKs that were analysed (pulled from the device) |
| `shaders/`, `libs/` | Shaders and native libraries extracted from those APKs |
| `InterlaceCheck.java` | Decides from pixels whether a screenshot is actually 3D-interlaced |
| `Crop.java` | Crops and magnifies part of a screenshot (for inspecting artifacts) |

## Key findings

- **3DFV is the system 3D service.** Its `onBind()` returns null, so the public API is
  **broadcasts**, not binding. When a whitelisted activity is frontmost in landscape,
  it instructs SurfaceFlinger to switch the panel into 3D.
- **The only native library actually required for 3D rendering is `libholography.so`.**
  It writes the lenticular mask into a GL texture, and `frag3D.sh` uses that mask to
  blend the left and right views per pixel.
- Static JNI naming means the class must be exactly `com.future.Holography.Holography`.

See [`FINDINGS.en.md`](FINDINGS.en.md) for the details.

## Note

`apks/`, `shaders/` and `libs/` are the work of the device vendors (WZ Tech, MediaTek and
others). They are a personal backup extracted from a device I own, not a redistribution.

Decompiled sources (`src/`) and test screenshots are excluded from this repository.
The decompilation can be regenerated from `apks/` with jadx at any time:

```
java -cp jadx-gui-1.5.6-all.jar jadx.cli.JadxCLI -d src/3DPlayer apks/3DPlayer_1114.apk
```
