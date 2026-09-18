#!/usr/bin/env bash
#
# scripts/test_native_audio.sh: Compile and execute the native C++ audio engine test suite.
#
# This host build is hermetic: the output-engine tests use a FakeDecoder, so
# no libopus is linked here. The Android NDK build (libraries/humla/src/main/jni,
# libhumlaaudio.so + libjniopus.so) must be verified separately; this script
# does not cover it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENGINE_DIR="$ROOT_DIR/libraries/humla/src/main/jni/audio_engine"
TEST_DIR="$ROOT_DIR/libraries/humla/src/test/cpp"
for f in "$ENGINE_DIR/jitter/jitter.c" \
         "$ENGINE_DIR/SoftLimiter.cpp" \
         "$ENGINE_DIR/PreSpeechRingBuffer.cpp" \
         "$ENGINE_DIR/AdaptiveLeveler.cpp" \
         "$ENGINE_DIR/AudioInputEngine.cpp" \
         "$ENGINE_DIR/AudioOutputEngine.cpp" \
         "$ENGINE_DIR/HysteresisVad.cpp" \
         "$TEST_DIR/test_biquad_filter.cpp" \
         "$TEST_DIR/test_soft_limiter.cpp" \
         "$TEST_DIR/test_pre_speech_ring_buffer.cpp" \
         "$TEST_DIR/test_adaptive_leveler.cpp" \
         "$TEST_DIR/test_hysteresis_vad.cpp" \
         "$TEST_DIR/test_jitter_buffer.cpp" \
         "$TEST_DIR/test_audio_input_engine.cpp" \
         "$TEST_DIR/test_audio_output_engine.cpp" \
         "$TEST_DIR/run_audio_tests.cpp"; do
    if [[ ! -f "$f" ]]; then
        echo "test_native_audio.sh: missing required file: $f" >&2
        exit 1
    fi
done

BUILD_DIR="$ROOT_DIR/build/test-native"
mkdir -p "$BUILD_DIR"

CXX="${CXX:-g++}"

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
    "$ROOT_DIR/libraries/humla/src/test/cpp/run_audio_tests.cpp" \
    -o "$BUILD_DIR/test_audio_engine"

"$BUILD_DIR/test_audio_engine"
