#!/usr/bin/env bash
# Builds libhsocrvk.so (Vulkan compute backend) for every Android ABI with the NDK's own clang,
# after compiling the GLSL shader to SPIR-V with the NDK's glslc. Output: app/src/main/jniLibs.
set -euo pipefail
cd "$(dirname "$0")/.."
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  NDK=$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"/ndk/* | sort -V | tail -1)
fi
echo "NDK: $NDK"
HOST=linux-x86_64
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
GLSLC="$NDK/shader-tools/$HOST/glslc"
"$GLSLC" --version | head -2
"$GLSLC" -fshader-stage=compute --target-env=vulkan1.0 -O -mfmt=num -o native/classify.spv.inc native/classify.comp
echo "SPIR-V words: $(tr ',' '\n' < native/classify.spv.inc | grep -c 0x)"
OUT=app/src/main/jniLibs
for pair in "arm64-v8a aarch64-linux-android26" "armeabi-v7a armv7a-linux-androideabi26" "x86_64 x86_64-linux-android26" "x86 i686-linux-android26"; do
  set -- $pair
  mkdir -p "$OUT/$1"
  "$TC/clang" --target="$2" -std=c11 -O2 -fPIC -shared -Wall -Wextra -Werror=implicit-function-declaration \
    -I native -o "$OUT/$1/libhsocrvk.so" native/vk_classify.c -lvulkan \
    -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -s
  echo "$1: libhsocrvk.so $(stat -c%s "$OUT/$1/libhsocrvk.so") bytes, needs: $("$TC/llvm-readelf" -d "$OUT/$1/libhsocrvk.so" | grep NEEDED | sed 's/.*\[\(.*\)\]/\1/' | tr '\n' ' ')"
done
