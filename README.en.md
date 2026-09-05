# DepthFlix — a 3D video and photo viewer for the Lume Pad 2

*[한국어](README.md) · English*

A viewer built to watch **3D video and 3D photos properly** on the
[Leia Lume Pad 2](https://www.leiainc.com/) (LPD-20W: an 8-view diffractive
lightfield panel with face tracking).

The stock LeiaPlayer can show them too. This one differs in these ways:

- **It works out the SBS/TB layout from pixels and resolution**, not from the filename
- **It measures the convergence and corrects it** — SBS captured from games is
  offset wholesale for the display it was made on, and on a tablet your eyes
  cannot fuse it
- **It draws subtitles into the left and right views separately** — a flat 2D
  overlay comes apart in the weave
- **2D video is converted by Leia's own neural engine** — the one already on the device
- **DTS and AC3 actually play** (FFmpeg audio extension)

---

## Install

Grab the APK from [Releases](../../releases) and install it. On a Lume Pad 2
**there is nothing else to do** — no adb setup, no root.

| Requirement | |
|---|---|
| Device | Lume Pad 2 (LPD-20W) |
| Permission | Storage read, once. The app asks on first launch |
| Face tracking | Done by the device's Leia service. This app runs with the camera permission denied |

---

## Using it

### The list

The app opens on a list of **video folders**. Tap one to see its files.

| | |
|---|---|
| **Photos / Videos** | Switches the list between photos and videos |
| **Long-press** a folder | Pins it to the top of the list (★). Long-press again to unpin |
| **Back** | File list → folder list |
| **Open URL / stream** | Opens an `http(s)` / `m3u8` / `mpd` / `rtsp` address directly |

A tag such as `Side-by-side (full)` next to a video name is a **guess from the
filename**. The real layout is worked out from pixels when the file opens.

### The player

Tap the screen once to show and hide the bottom bar.

| Button | Video | Photo |
|---|---|---|
| ◀◀ ▶▶ | 30 s, or 5 min on long-press | Previous / next, or 10 at a time on long-press |
| ❚❚ | Play / pause | — |
| Time | position / length | index / total |
| ⚙ Settings | Panel on the right | Same |

Photos step **only within the folder you opened**.

### The settings panel

**3D**

| | |
|---|---|
| **Source** | Pick the layout by hand (2D / side-by-side half·full / over-under half·full). Your choice is remembered per file |
| **Output** | 3D · 2D · SBS check |
| **Swap L/R** | When the depth comes out inverted |
| **↺ Back to automatic detection** | Clears a wrong manual choice and detects again |
| **Aspect** | Auto · 16:9 · 2.40:1 · 1.85:1 · 4:3 · Fill screen |
| **Aspect fine-tune** | 1.00 – 3.00, for files squeezed into an arbitrary ratio |
| **Depth** | Parallax strength of the 2D→3D conversion |
| **Convergence** | See below |

**Subtitles**

A subtitle file (`.srt` / `.smi`) sitting next to the video is loaded
automatically. Otherwise **Choose subtitle** searches the video's folder,
`Movies`, `Download` and `Subtitles`. Size, position and depth (how far it
floats toward you) are adjustable and remembered.

---

## Convergence — half the reason this app exists

SBS captured from a game has **the monitor it was made on, and the convergence
setting of that moment, frozen into it as a pixel count**. Scale the same
picture into the tablet's eye box and the parallax scales with it.

Measured across 36 HelixMod screenshots on the device, the two halves were
offset **wholesale** — from 20 px to 224 px (11.7 % of the eye width) at a
1920 px eye. The depth range of the scene itself was only 28–184 px, far less.
A value made for a 24-inch monitor, put on a 10-inch tablet, exceeds what your
eyes can converge.

- The **Convergence** slider shifts the on-screen parallax in pixels. Positive
  pushes the scene behind the screen, negative brings it forward. Remembered
  per file.
- **Auto convergence (centre the scene on screen)** measures the parallax of
  the current frame and puts the middle of the scene on the screen plane. Half
  the depth range then sits in front and half behind, which lands inside a
  comfortable range. The measured range is shown in the settings panel.

The scale is in **screen pixels**, so the same number means the same thing
whether the source is 3344 or 5120 wide.

---

## How it works

```
video/photo ─▶ decoder ─▶ OES texture ─▶ our GL
                                          │  letterbox · aspect · SBS/TB crop
                                          │  convergence · subtitles (per eye)
                                          ▼
                    2D source ──▶ Leia neural converter ──┐
                    3D source ──▶ SBS frame ──────────────┴─▶ CNSDK weave ─▶ screen
```

We do not do the last step. This panel is 8-view diffractive and the weave
changes every frame with the viewer's head position, so a fixed pattern cannot
produce 3D. CNSDK does it.

**2D→3D uses Leia's own neural converter.** The models and the SNPE/Hexagon
runtime live in a system app on the device (`com.leiainc.media.service`); we
load that into our process and use it. No Leia binary is redistributed here.
Compared against real stereo on the same frame, it is clearly better than the
shear method we used before.

The full engineering record is in [`LUMEPAD2-PORT.md`](LUMEPAD2-PORT.md)
(Korean), wrong turns included.

**To put 3D into another app**, see [`MOONLIGHT-LUMEPAD2.md`](MOONLIGHT-LUMEPAD2.md)
(Korean). Using Moonlight as the worked example, it covers getting CNSDK into an app
and the four traps we hit on real hardware. This panel has no fixed pattern, so
nothing outside an app can turn its output into 3D — the app that draws must weave.

---

## Building it yourself

CNSDK belongs to Leia and cannot be redistributed, so **it is not in this
repository**. Pull it off your own device.

```bash
# 1) find the app that carries CNSDK
adb shell pm path com.moonlight.leia

# 2) pull and unpack it
adb pull <the path printed above> leia.apk
unzip leia.apk -d leia/

# 3) put the pieces where the build expects them
cp leia/lib/arm64-v8a/libleiaSDK.so     app3d/app/src/main/jniLibs/arm64-v8a/
cp leia/lib/arm64-v8a/libleiaspdlog.so  app3d/app/src/main/jniLibs/arm64-v8a/
cp -r leia/assets/shaders               app3d/app/src/main/assets/
cp leia/assets/cnsdk.version            app3d/app/src/main/assets/

# 4) convert the CNSDK classes to a jar at app3d/app/libs/leia-cnsdk.jar
#    (classes*.dex through dex2jar or similar)
```

Then build as usual.

```bash
cd app3d
gradle assembleDebug          # or assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To sign a release build, create `app3d/keystore.properties` (kept out of the
repository).

```properties
storeFile=/absolute/path/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The FFmpeg audio extension (AC3/E-AC3/DTS/TrueHD) is built with the script in
[`app3d/ffmpeg/README.md`](app3d/ffmpeg/README.md). The project builds without
it, but those codecs will be silent.

---

## What is deliberately absent

| | Why |
|---|---|
| `app3d/app/libs/leia-cnsdk.jar` | Leia's property. Take it from your device |
| `app3d/app/src/main/jniLibs/` | Same (`libleiaSDK.so`, `libleiaspdlog.so`) |
| `app3d/app/src/main/assets/shaders/`, `cnsdk.version` | Same |
| `app3d/ffmpeg/src/main/jni/ffmpeg/` | Regenerated by the build script |
| Signing keys | Naturally |

---

## A sibling repository

The work for the ProMa P10 (a lenticular glasses-free 3D tablet) lives in a
separate repository. The panel is different enough that the whole last render
stage differs, and that one depends on vendor assets.

---

## Licence

[MIT](LICENSE) — for our code in this repository.

The app is built with the following, each under its own terms.

| | |
|---|---|
| [AndroidX Media3 (ExoPlayer)](https://github.com/androidx/media) | Apache-2.0 |
| [libVLC for Android](https://code.videolan.org/videolan/vlc-android) | LGPL-2.1+ |
| [FFmpeg](https://ffmpeg.org/) | LGPL-2.1+ as configured here |
| Leia CNSDK / LeiaMediaSDK | Proprietary, Leia Inc. **Not in this repository** — take it from your own device |
