# FFmpeg 오디오 디코더 모듈

`androidx/media` 1.3.1 의 `lib-decoder-ffmpeg` 모듈을 그대로 가져온 것이다.

## 왜 소스를 들고 있나

`androidx.media3:media3-decoder-ffmpeg` 는 **Maven 에 배포되지 않는다.** 공식 컴포넌트지만
FFmpeg 을 직접 빌드해야 해서 소스로만 제공된다. 확인:
```
> Could not find androidx.media3:media3-decoder-ffmpeg:1.3.1
```

## 왜 필요한가

기기 MediaCodec 에 AC3/E-AC3/DTS/TrueHD 디코더가 없다
(`/vendor/etc/media_codecs_mediatek_audio.xml` 에는 MP3/GSM/RAW/G711/WMA/ADPCM/APE/ALAC 뿐).
이것 때문에 ExoPlayer 로는 3D 영화 대부분이 무음이었고, 그래서 4K HEVC 처럼 MediaCodec 이
꼭 필요한 소스에서도 ExoPlayer 를 쓸 수 없었다. 이 모듈이 그 코덱들을 소프트웨어로 디코딩한다.

측정 (설치 후):
```
Coraline 3840x1080 10bit HEVC  AC3 5.1   27.6 fps + 소리   (전: libVLC 21fps, ExoPlayer 무음)
Edge of Tomorrow  1080p        DTS-HD    27.1 fps + 소리   (전: 어느 엔진이든 무음)
Spider-Man        1080p        DTS       27.4 fps + 소리
The Boys          1080p        E-AC3     27.8 fps + 소리
```

## 패키지명을 바꾸지 말 것

`ffmpeg_jni.cc` 의 JNI 심볼이 `androidx.media3.decoder.ffmpeg` 에 묶여 있다.
바꾸면 `libffmpegJNI.so` 를 못 찾는다.

## 다시 빌드하는 법

`src/main/jni/ffmpeg/` (헤더 + 정적 라이브러리, 19MB) 는 저장소에 없다. 이렇게 만든다.

```bash
# 1. 준비물
#    NDK r26  : sdkmanager "ndk;26.3.11579264"
#    CMake    : sdkmanager "cmake;3.31.6"
mkdir -p ~/ffmpeg-build && cd ~/ffmpeg-build
git clone --depth 1 --branch n6.0 https://github.com/FFmpeg/FFmpeg.git ffmpeg

# 2. 빌드 (arm64-v8a 만 — 앱이 arm64 전용이라 나머지는 시간 낭비)
#    Windows Git Bash 라면 TMPDIR 을 반드시 POSIX 경로로 둘 것.
#    기본값인 C:\Users\... 를 쓰면 configure 가 "Sanity test failed" 로 죽는다.
export TMPDIR=/tmp/ffbuild && mkdir -p $TMPDIR
export PATH="$ANDROID_HOME/ndk/26.3.11579264/prebuilt/windows-x86_64/bin:$PATH"   # make, yasm
mkdir -p fakemodule/jni && mv ffmpeg fakemodule/jni/ffmpeg
bash <이 파일이 있는 폴더>/build-ffmpeg-arm64.sh \
    "$(pwd)/fakemodule" "$ANDROID_HOME/ndk/26.3.11579264" "windows-x86_64" 21 \
    ac3 eac3 dca truehd mlp

# 3. 결과를 모듈로 복사
DST=<이 파일이 있는 폴더>/src/main/jni/ffmpeg
mkdir -p $DST/android-libs/arm64-v8a
cp fakemodule/jni/ffmpeg/android-libs/arm64-v8a/*.a $DST/android-libs/arm64-v8a/
for d in libavcodec libavutil libswresample; do
    mkdir -p $DST/$d && cp fakemodule/jni/ffmpeg/$d/*.h $DST/$d/
done
cp fakemodule/jni/ffmpeg/config.h $DST/
```

디코더를 더 넣고 싶으면 마지막 인자 목록에 추가한다 (`--enable-decoder=` 로 전달된다).
지금은 기기에 없는 것만 넣었다 — 있는 코덱까지 넣으면 라이브러리만 커진다.
