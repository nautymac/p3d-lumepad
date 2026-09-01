#!/bin/bash
# media3 의 build_ffmpeg.sh 를 arm64-v8a 하나만 만들도록 줄인 것.
# 앱이 arm64 전용이라 나머지 ABI 는 빌드할 이유가 없다 (시간 4배 절약).
set -eu
FFMPEG_MODULE_PATH="$1"
NDK_PATH="$2"
HOST_PLATFORM="$3"
ANDROID_ABI="$4"
ENABLED_DECODERS=("${@:5}")
JOBS="$(nproc 2>/dev/null || echo 4)"
echo "디코더: ${ENABLED_DECODERS[@]} / 잡 $JOBS"

COMMON_OPTIONS="
    --target-os=android
    --enable-static
    --disable-shared
    --disable-doc
    --disable-programs
    --disable-everything
    --disable-avdevice
    --disable-avformat
    --disable-swscale
    --disable-postproc
    --disable-avfilter
    --disable-symver
    --enable-swresample
    --extra-ldexeflags=-pie
    --disable-v4l2-m2m
    --disable-vulkan
    "
TOOLCHAIN_PREFIX="${NDK_PATH}/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin"
for decoder in "${ENABLED_DECODERS[@]}"; do
    COMMON_OPTIONS="${COMMON_OPTIONS} --enable-decoder=${decoder}"
done

cd "${FFMPEG_MODULE_PATH}/jni/ffmpeg"
./configure \
    --libdir=android-libs/arm64-v8a \
    --arch=aarch64 \
    --cpu=armv8-a \
    --cross-prefix="${TOOLCHAIN_PREFIX}/aarch64-linux-android${ANDROID_ABI}-" \
    --nm="${TOOLCHAIN_PREFIX}/llvm-nm" \
    --ar="${TOOLCHAIN_PREFIX}/llvm-ar" \
    --ranlib="${TOOLCHAIN_PREFIX}/llvm-ranlib" \
    --strip="${TOOLCHAIN_PREFIX}/llvm-strip" \
    ${COMMON_OPTIONS}
make -j$JOBS
make install-libs
