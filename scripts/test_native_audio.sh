#!/usr/bin/env bash
#
# scripts/test_native_audio.sh: Compile and execute the native C++ audio engine and crypto test suites.
#
# The engine unit tests use FakeDecoder/FakeVoiceEncoder (hermetic), and the
# Opus interop + fuzz suite below links the real Opus sources from the pinned
# submodule (same CELT/SILK/Opus lists, defines and fixed-point mode as
# libraries/humla/src/main/jni/Android.mk modulo the -O2/-O3 delta noted
# below, minus the NEON intrinsics which need no extra flags on arm64 but
# are x86-hostile). The full Android NDK build (single libhumlaaudio.so)
# must still be verified separately.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENGINE_DIR="$ROOT_DIR/libraries/humla/src/main/jni/audio_engine"
TEST_DIR="$ROOT_DIR/libraries/humla/src/test/cpp"
CRYPTO_DIR="$ROOT_DIR/libraries/humla/src/main/jni/crypto"
OPUS_DIR="$ROOT_DIR/libraries/humla/src/main/jni/opus"
for f in "$ENGINE_DIR/jitter/jitter.c" \
         "$ENGINE_DIR/SoftLimiter.cpp" \
         "$ENGINE_DIR/PreSpeechRingBuffer.cpp" \
         "$ENGINE_DIR/AdaptiveLeveler.cpp" \
         "$ENGINE_DIR/AudioInputEngine.cpp" \
         "$ENGINE_DIR/AudioOutputEngine.cpp" \
         "$ENGINE_DIR/HysteresisVad.cpp" \
         "$ENGINE_DIR/OpusVoiceEncoder.cpp" \
         "$ENGINE_DIR/OpusVoiceDecoder.cpp" \
         "$TEST_DIR/test_biquad_filter.cpp" \
         "$TEST_DIR/test_soft_limiter.cpp" \
         "$TEST_DIR/test_pre_speech_ring_buffer.cpp" \
         "$TEST_DIR/test_adaptive_leveler.cpp" \
         "$TEST_DIR/test_hysteresis_vad.cpp" \
         "$TEST_DIR/test_jitter_buffer.cpp" \
         "$TEST_DIR/test_audio_input_engine.cpp" \
         "$TEST_DIR/test_audio_output_engine.cpp" \
         "$TEST_DIR/test_opus_interop.cpp" \
         "$TEST_DIR/run_audio_tests.cpp" \
         "$CRYPTO_DIR/Aes128.h" \
         "$CRYPTO_DIR/CryptStateOCB2.h" \
         "$CRYPTO_DIR/CryptStateOCB2.cpp" \
         "$CRYPTO_DIR/NativeCryptStateJni.cpp" \
         "$TEST_DIR/test_crypt_state.cpp"; do
    if [[ ! -f "$f" ]]; then
        echo "test_native_audio.sh: missing required file: $f" >&2
        exit 1
    fi
done

BUILD_DIR="$ROOT_DIR/build/test-native"
mkdir -p "$BUILD_DIR"

CXX="${CXX:-g++}"

# Opus 1.6.1 host sources: same lists and defines as Android.mk
# (CELT + SILK + SILK_FIXED + OPUS + OPUS_FLOAT), excluding the
# arch-specific variants (x86 RTCD/SSE/AVX2, ARM RTCD/NEON/NE10, .s asm)
# and the opt-in lpcnet_sources.mk (deep PLC/DRED, fixed-point conflict).
# Host uses -O2 while the NDK build uses -O3; the flag delta is deliberate
# (host test speed) and does not affect codec behavior under test.
OPUS_SRCS=""
for mk in celt_sources.mk silk_sources.mk opus_sources.mk; do
    list=$(sed -n '/^CELT_SOURCES =/,/[^\\]$/p;/^SILK_SOURCES =/,/[^\\]$/p;/^SILK_SOURCES_FIXED =/,/[^\\]$/p;/^OPUS_SOURCES =/,/[^\\]$/p;/^OPUS_SOURCES_FLOAT =/,/[^\\]$/p' "$OPUS_DIR/$mk" \
        | sed -e 's/^[A-Z_]* = *//' -e 's/\\$//' -e 's/#.*//')
    # shellcheck disable=SC2086
    # (word-splitting intended: one file per token)
    for f in $list; do
        OPUS_SRCS="$OPUS_SRCS $OPUS_DIR/$f"
    done
done
# shellcheck disable=SC2086
# (empty OPUS_SRCS means the .mk parse found nothing, e.g. OPUS_DIR moved:
# fail here instead of a cryptic downstream link failure)
if [[ -z "${OPUS_SRCS// }" ]]; then
    echo "test_native_audio.sh: no opus sources collected (check OPUS_DIR=$OPUS_DIR)" >&2
    exit 1
fi
for f in $OPUS_SRCS; do
    if [[ ! -f "$f" ]]; then
        echo "test_native_audio.sh: missing opus source: $f" >&2
        exit 1
    fi
done
OPUS_FLAGS="-DOPUS_BUILD -DVAR_ARRAYS -DFIXED_POINT -DHAVE_LRINTF=1 -O2 -Wno-error=maybe-uninitialized"
OPUS_INC="-I $OPUS_DIR/include -I $OPUS_DIR/celt -I $OPUS_DIR/silk -I $OPUS_DIR/silk/float -I $OPUS_DIR/silk/fixed"

# shellcheck disable=SC2086
# ($OPUS_SRCS/$OPUS_FLAGS/$OPUS_INC expand to multiple words by design)
"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror -UNDEBUG \
    -I "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine" \
    -I "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/jitter" \
    -I "$ROOT_DIR/libraries/humla/src/test/cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/jitter/jitter.c" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/SoftLimiter.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/PreSpeechRingBuffer.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/AdaptiveLeveler.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/HysteresisVad.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_biquad_filter.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_soft_limiter.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_pre_speech_ring_buffer.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_adaptive_leveler.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_hysteresis_vad.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_jitter_buffer.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_audio_input_engine.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_audio_output_engine.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_opus_interop.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/run_audio_tests.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/OpusVoiceEncoder.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/OpusVoiceDecoder.cpp" \
    $OPUS_SRCS \
    $OPUS_FLAGS $OPUS_INC \
    -o "$BUILD_DIR/test_audio_engine"

"$BUILD_DIR/test_audio_engine"

"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror -UNDEBUG \
    -I "$CRYPTO_DIR" \
    "$CRYPTO_DIR/CryptStateOCB2.cpp" \
    "$TEST_DIR/test_crypt_state.cpp" \
    -lpthread \
    -o "$BUILD_DIR/test_crypt_state"

"$BUILD_DIR/test_crypt_state"

# Compile host JNI shared library for host JVM unit tests
JAVA_INC=""
if [[ -n "${JAVA_HOME:-}" && -d "$JAVA_HOME/include" ]]; then
    JAVA_INC="$JAVA_HOME/include"
elif command -v javac >/dev/null 2>&1; then
    DETECTED_JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
    if [[ -d "$DETECTED_JAVA_HOME/include" ]]; then
        JAVA_INC="$DETECTED_JAVA_HOME/include"
    fi
fi

if [[ -z "$JAVA_INC" ]]; then
    echo "test_native_audio.sh: error: could not locate JDK include directory for JNI compilation (check JAVA_HOME)" >&2
    exit 1
fi

case "$(uname -s)" in
    Linux*)
        JNI_MD_INC="$JAVA_INC/linux"
        SO_EXT="so"
        ;;
    Darwin*)
        JNI_MD_INC="$JAVA_INC/darwin"
        SO_EXT="dylib"
        ;;
    *)
        echo "test_native_audio.sh: error: unsupported host operating system: $(uname -s)" >&2
        exit 1
        ;;
esac

if [[ ! -d "$JNI_MD_INC" ]]; then
    echo "test_native_audio.sh: error: platform-specific JNI header directory not found: $JNI_MD_INC" >&2
    exit 1
fi

"$CXX" -std=c++17 -O2 -fPIC -shared -Wall -Wextra -Werror -UNDEBUG \
    -I "$CRYPTO_DIR" \
    -I "$JAVA_INC" \
    -I "$JNI_MD_INC" \
    "$CRYPTO_DIR/CryptStateOCB2.cpp" \
    "$CRYPTO_DIR/NativeCryptStateJni.cpp" \
    -lpthread \
    -o "$BUILD_DIR/libhumlaaudio.${SO_EXT}"

