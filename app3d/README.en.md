# P3D Player

*[한국어](README.md) · English*

A unified 3D video player for the ProMa P10 (glasses-free autostereoscopic tablet).
It reimplements the render pipeline recovered by reverse-engineering the stock
3DPlayer / Sight3D / 3DFV apps. See `../FINDINGS.en.md` for the analysis it is based on.

## What it does

- Local files + network streaming (http/https, HLS `.m3u8`, DASH `.mpd`, RTSP)
- Five stereo layouts: **2D / SBS-half / SBS-full / TB-half / TB-full**
- **Forced 2D → 3D conversion** (same algorithm as the stock 3DPlayer's "2D/3D" button)
- Live depth and convergence adjustment (not in the stock app)
- Left/right swap toggle (L/R order varies per release)
- Per-file layout memory — set it once and that file opens that way next time
- **Revert to auto-detect** — clears a wrongly saved choice and re-runs pixel detection
- **Engine switching** — swap ExoPlayer ↔ libVLC mid-playback, position preserved
- **3D Control Center** — register/unregister other apps (YouTube etc.) in the 3DFV whitelist

## Render pipeline

```
ExoPlayer ─▶ SurfaceTexture(OES)
                   │
                   ├── left-eye crop  ──▶ FBO left half
                   └── right-eye crop ──▶ FBO right half   (2D source: shear applied here only)
                                          │
                            frag3D.sh + libholography mask
                                          │
                                          ▼
                                  lenticular interlaced output
```

### 2D → 3D conversion

This reproduces the formula from the stock `frag2dto3d.sh`: a **gradient shear** that
displaces the image horizontally as a function of vertical position — the classic
ground-plane assumption that the bottom of the frame is near and the top is far.

```glsl
t.x += uShearTop - vTex.y * uShearSlope;   // stock: 0.004 - y*screenHeight*0.0000122
```

Only the right eye is sheared; the left eye stays untouched, so one eye is always sharp.
The stock app hardcodes the constants; here the `Depth` slider scales them.

## Native dependency

Just `libholography.so` (the arm64-v8a / armeabi-v7a build from the 3DPlayer APK).
`Holography.update()` writes the panel's lenticular mask into the currently bound
`GL_TEXTURE_2D`, and `Sampler1` in `frag3D.sh` uses it to blend left/right per pixel.

**Static JNI naming means the class must be exactly `com.future.Holography.Holography`.**
Rename the package and the symbols will not resolve.

`libDrawVideoC.so`, which the stock app uses, is not needed here. Its arguments are
attribute locations rather than width/height, and all it does is set up vertices for
fixed crops — doing that in Java with UVs is simpler and gives proper letterbox control.

## Build

```powershell
$env:JAVA_HOME    = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME = "C:\Android\sdk"
cd C:\Users\nauty\proma3d\app3d
& "C:\Gradle\gradle-8.7\bin\gradle.bat" assembleDebug --no-daemon

C:\Users\nauty\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

The vendor files (`libholography.so` and the `.sh` shaders) are checked in, since this is a
private personal repository. They were extracted from the stock apps on my own device and are
not for redistribution — if this repo is ever made public they must be removed and pulled from
the device at build time instead.

## Cautions

- This app **renders the interlacing itself**, so it must NOT be added to the 3DFV
  whitelist. If it is, SurfaceFlinger processes it a second time and the image breaks.
  (The stock 3DPlayer is not in the whitelist either.)
- **DTS audio cannot play on this device**, regardless of engine. See the DTS section below.
- The APK is arm64-v8a only. libVLC is ~30MB per ABI, so including 32-bit would bloat it.

## Structure

```
com.future.Holography.Holography   JNI binding (name is fixed)
com.nauty.p3d.SourceFormat         stereo layouts + detection
com.nauty.p3d.StereoDetect         pixel-based layout detection
com.nauty.p3d.Fv3d                 3DFV broadcast API
com.nauty.p3d.MainActivity         library / URL entry
com.nauty.p3d.PlayerActivity       player + controls
com.nauty.p3d.Fv3dControlActivity  3D Control Center
com.nauty.p3d.gl.Stereo3DView      GLSurfaceView + draw loop
com.nauty.p3d.gl.SourceRenderer    OES -> FBO (crop + shear)
com.nauty.p3d.gl.InterlaceRenderer FBO -> screen (frag3D + mask)
com.nauty.p3d.gl.SubtitleRenderer  subtitle bitmap -> both eye views
com.nauty.p3d.gl.Fbo / GlUtil / BlitRenderer
com.nauty.p3d.subtitle.Subtitles   SRT / SMI parsing + charset detection
com.nauty.p3d.subtitle.SubtitleBitmap  text -> bitmap
```

## Playback engines

Two implementations sit behind `com.nauty.p3d.engine.VideoEngine`. The `Engine` button in
the settings panel swaps them without losing playback position, and the choice is saved.

| | ExoPlayer (default) | libVLC |
|---|---|---|
| Decoding | device MediaCodec | bundled FFmpeg (+ HW fallback) |
| Strengths | light, HLS/DASH | container coverage, RTSP/SMB |
| Input surface | `Surface` | `SurfaceTexture` (IVLCVout) |

The 3D pipeline is engine-agnostic — either way frames arrive in the same OES texture.
If libVLC fails to initialise, the app falls back to ExoPlayer automatically.

## Known bugs and fixes

**Symptom: playback freezes after a few seconds**

A race in the single `frameAvailable` boolean used to signal frame arrival to the GL thread.
If `onFrameAvailable` fired again while `onDrawFrame` was reading and clearing the flag,
that notification was lost, `updateTexImage()` was never called, the decoder never got its
buffer back, and playback stopped entirely.

Fix: count pending frames with an `AtomicInteger`, consume all of them, and call
`requestRender()` again if more arrived during the draw. A 300ms watchdog makes a permanent
stall impossible even if a notification is lost some other way.

Measured (94s clip, fps per second):
```
before:  24.69  11.88   0.55 ← stall   18.63  24.63 ...
after:   26.73  27.96  26.34  26.57  28.31  25.81  28.02  26.83
         27.18  27.41  27.32  26.76  28.02  26.98  27.86  27.00
```

### What blocked the libVLC integration

Feeding libVLC into a SurfaceTexture produced a flat single-colour screen. There were two
causes, and both fixes were needed.

1. **It demands a subtitle surface** — VLC's Android vout wants a separate surface for
   subtitle blending. Give it only a video SurfaceTexture and it rejects the vout and falls
   through to another one:
   ```
   E/VLC: vout display: can't get Subtitles Surface
   W/VLC: vout display: cannot blend subtitles with an opaque surface, trying next vout
   ```
   → Disable subtitles with `--no-spu`, `--no-osd`. We never composite VLC's subtitles into
   the 3D pipeline, so nothing is lost.

2. **Window size not set before the vout starts** — `onNewVideoLayout` only arrives after
   playback begins, but the vout needs the window size before that to initialise its display.
   Call `setWindowSize()` once with the screen size before `attachViews()`.

Forcing direct hardware rendering with `setHWDecoderEnabled(true, true)` also produces a
picture, but that was not the actual fix (`force=false` works too). Blocking the fallback
would prevent software decoding of codecs MediaCodec cannot handle, so `force=false` stays.

**Debugging note:** the MTK PictureQuality HAL is blocked by SELinux and emits dozens of
warnings per second. That floods the logcat buffer and evicts the app's own lines, so filter
by pid rather than by tag:
```
adb logcat -d | grep " <pid> "
```

## Forcing an engine (for testing / shortcuts)

```
adb shell am start -n com.nauty.p3d/.PlayerActivity \
  -d "content://media/external/video/media/45" \
  --es title "snowflight_2V3D.mp4" --es engine VLC
```
The `engine` extra takes `EXO` or `VLC`. It overrides the saved choice.

## DTS audio — impossible on this device (investigation closed)

Files whose only audio track is DTS, such as `Edge.of.Tomorrow...DTS-HD.MA.7.1.mkv`,
play **silently regardless of engine**.

Evidence:
```
All audio decoders in /vendor/etc/media_codecs_mediatek_audio.xml:
  MP3, GSM, RAW, G711, WMA, ADPCM, APE, ALAC     ← no DTS/AC3/E-AC3/TrueHD

ExoPlayer:  audio/vnd.dts ch=6  [no decoder]
libVLC:     selects the track, but then
            E/VLC: audio output: module not functional
            E/VLC: decoder: failed to create audio output
```

Tried and reverted (none worked, and `--aout`/`--stereo-mode` only lowered the volume of
normal files):
- `--aout=opensles_android`
- `--stereo-mode=1`
- `MediaPlayer.setAudioOutputDevice("stereo")`

libVLC still earns its place (container/codec coverage, network protocols), but it is
**not a DTS workaround, so do not present it as one.** Closed at the user's request.

## Subtitle rendering bug (fixed)

**Symptom: a transparent line crossing the glyphs from the second line onward**

The cause was building a separate `StaticLayout` for the outline and for the fill.
`Paint.Style.STROKE` folds the stroke width into text measurement, so **line breaks and
line heights differ.** The first line matched, but from the second line the outline and the
fill drifted apart vertically, and the two overlapping copies read as a line through the text.

Fix: build one layout and draw it twice, changing only the paint style.
```java
StaticLayout layout = new StaticLayout(text, paint, ...);
paint.setStyle(STROKE); paint.setColor(BLACK); layout.draw(c);
paint.setStyle(FILL);   paint.setColor(WHITE); layout.draw(c);
```

Subtitle vertical position is adjustable from the `Subtitle position` slider in the settings
panel (0–40% of screen height, default 4%). Size, position and depth are all persisted.

### Subtitles must be drawn into both eye views

A subtitle drawn as a normal Android view on top of the GLSurfaceView will not work.
The lenticular panel routes each pixel column to one eye, so a 2D overlay gives each eye a
different set of glyph fragments and the text reads as blurred and doubled. Subtitles are
therefore rendered into both halves of the FBO with opposite horizontal shifts, so they get
interlaced with the video and sit at a controllable depth.

## Stereo layout detection — by pixels, not by filename

Filename heuristics are not enough. In practice
`spider.man.into.the.spider.verse.2018.3d.1080p.bluray.x264-veto.mkv` has `3d` in the name
but no `sbs`, so it was treated as 2D, its SBS frame was duplicated into both halves, and
**the same picture appeared twice on screen**.

`StereoDetect` pulls frames from four points in the feature with `MediaMetadataRetriever`
and compares the mean absolute difference between the left/right halves and the top/bottom
halves against the frame's own contrast. An SBS frame's halves differ only by parallax, so
it separates cleanly. Low-contrast samples (black frames and the like) are discarded.

```
stereo detection votes: SBS=4 TB=0 2D=0 (1920x808)     ← about 3 seconds
```

Priority: **saved user choice > pixel detection > (if detection fails) filename / 2D**.
The filename is only a placeholder until detection finishes, then it is overwritten — names are
often wrong. Detection results are not persisted; only an explicit user choice is.

### Interlace verification tool

Do not judge by eye whether 3D is actually applied. `InterlaceCheck.java` decides from the
ratio of adjacent-column to adjacent-row luma difference (interlaced output has a large
column difference).
```
java InterlaceCheck.java shot.png
  ratio 4.26 → INTERLACED (3D)
  ratio 0.79 → FLAT (2D)
```
Caveat: the metric is meaningless on detail-free scenes such as open sky or a dark frame.
Always measure on a textured scene.

## How the source layout is decided

```
saved user choice  >  pixel detection (StereoDetect)  >  (if detection fails) filename / 2D
```

Pressing the `Source` button **saves that value permanently for that file**, and pixel
detection no longer runs for it. Press it by accident and 3D stays broken, so there has to
be a way back: **`↺ Revert to auto-detect`** in the settings panel clears the saved value
and re-runs detection.

The top of the settings panel always shows where the current layout came from.
| Display | Meaning |
|---|---|
| `Source: auto-detected` | pixel detection result (not saved) |
| `Source: manual (saved)` | explicit choice — auto-detection is blocked |
| `Source: detecting…` | detection in progress |

If 3D looks wrong, read this line first.

## Verification log

| File | Detection | Interlace ratio |
|---|---|---|
| snowflight_2V3D.mp4 (1920x1080) | SBS_HALF | 4.26 |
| spider.man...veto.mkv (1920x808) | SBS=4/4 → SBS_HALF | 3.61 |
| Edge.of.Tomorrow...mkv (1920x1080) | SBS=4/4 → SBS_HALF | — (DTS silent) |

Controlled subtitle experiment: interlace ratio with vs. without subtitles was `4.26 vs 4.26`
— subtitles have no effect on 3D.

## The 3DFV overlay — putting other apps on the 3D panel

The `›` handle on the left edge in Chrome is 3DFV's FloatView. Tapping it offers
`Normal / SBS-full / SBS-half / Top-bottom` plus a depth slider. Whether it appears depends on
how the app was registered.

```java
// Service3D timer loop
mIsLandscape && mInWhitelist && mIsKeyguardGone && !mIsCustomActivity   → overlay shown
```

| Registration path | mIsCustomActivity | Result |
|---|---|---|
| **Whitelist file** | false | **Overlay shown** — pick mode and depth yourself |
| Broadcast (`Service3D.request`) | true | Applied immediately in a fixed mode, no overlay |

So the 3D Control Center uses the **file** path (`Fv3dWhitelist`).

### Whitelist file precedence

```java
// Service3D.getFVWhiteList()
"/sdcard/K3DX/config/white_list2.config"     // tries the undotted file first
"/sdcard/K3DX/config/.white_list2.config"    // vendor file only if that is missing
```

We only ever write the undotted file. The vendor file is left untouched, and deleting ours
restores the stock behaviour (`Restore defaults` in the Control Center).

### Applying changes — close_self must always be paired with a restart

3DFV reads the whitelist **only when the service starts**. The `close_self` broadcast stops it,
but that handler also stores `auto_start=false` — leave it there and **the 3D service will not
start on the next boot.** Fortunately `Service3D.onCreate()` sends message 2100 with `arg1=1`,
setting `auto_start` back to true, so **stopping and immediately restarting is safe.**

```java
sendBroadcast(new Intent("com.wztech.service.close_self"));
// 1.5s later
startForegroundService(new Intent("com.wztech.service").setPackage("com.wztech.service3d"));
```

### The activity name must match what is actually running

The stock whitelist entry for YouTube was stale, so no overlay appeared.

```
Registered      : com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity
SurfaceFlinger  : com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell$HomeActivity#0
                                             └─ the key is between "/" and "#"
```

YouTube 20.x moved its package path from `apps.youtube` to `youtube`. That is why the Control
Center registers **every activity of the package** via `PackageManager.GET_ACTIVITIES`:
streaming and game apps render in a different activity from their launcher one
(Moonlight launches `com.limelight.PcView` but streams in `com.limelight.Game`).

## Device dependency

`libholography.so` does not carry the lenticular mask in code — it reads it from a file.

```
/sdcard/3DKanKan/matrix     8,192,000 bytes    ← per-panel calibration
```

Sight3D generates it at the factory. **Without it, or with another panel's values, the 3D does
not line up** — so this only works on the same ProMa P10.
