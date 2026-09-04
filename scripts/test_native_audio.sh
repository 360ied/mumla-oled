#!/usr/bin/env bash
#
# scripts/test_native_audio.sh: Compile and execute the native C++ audio engine test suite.
#
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BUILD_DIR="$ROOT_DIR/build/test-native"
mkdir -p "$BUILD_DIR"

g++ -std=c++17 -O2 -Wall -Wextra -Werror \
    -I "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/SoftLimiter.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/PreSpeechRingBuffer.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/AdaptiveLeveler.cpp" \
    "$ROOT_DIR/libraries/humla/src/main/jni/audio_engine/HysteresisVad.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_biquad_filter.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_soft_limiter.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_pre_speech_ring_buffer.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_adaptive_leveler.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/test_hysteresis_vad.cpp" \
    "$ROOT_DIR/libraries/humla/src/test/cpp/run_audio_tests.cpp" \
    -o "$BUILD_DIR/test_audio_engine"

"$BUILD_DIR/test_audio_engine"
