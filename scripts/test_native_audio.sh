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

# Route compilation through ccache when available (the Nix dev shell ships it,
# with CCACHE_DIR shared across worktrees via the common git dir). Falls back
# to $CXX directly when ccache is absent.
# NOTE: $CXX is intentionally expanded *unquoted* at the compile invocations
# below so a "ccache g++" wrapper word-splits correctly.
if command -v ccache >/dev/null 2>&1 && [[ "$CXX" != *ccache* ]]; then
    CXX="ccache $CXX"
fi

# This script itself is a build input (flags live here): callers pass it to
# the build driver so flag edits trigger a rebuild.
THIS_SCRIPT="$SCRIPT_DIR/test_native_audio.sh"

# Host JDK headers for the JNI shared library (needed before the build
# driver below so the JNI target can compile concurrently with the others).
JAVA_INC=""
if [[ -n "${JAVA_HOME:-}" && -d "$JAVA_HOME/include" ]]; then
    JAVA_INC="$JAVA_HOME/include"
elif command -v javac >/dev/null 2>&1; then
    DETECTED_JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
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

# Parallel incremental object build. Each translation unit compiles to
# $OBJ_DIR/<profile>/....o with up to $JOBS concurrent jobs as single-TU
# invocations (which is also what makes the ccache wrapper above effective:
# ccache cannot cache multi-TU compile-and-link command lines and falls back
# to the real compiler). Flags are identical to the previous single-command
# builds, only split into per-TU compile plus a final link.
OBJ_DIR="$BUILD_DIR/obj"
mkdir -p "$OBJ_DIR"
# Parallelism: default to core count, capped so huge-core machines do not
# exhaust RAM with concurrent -O2 compiles. An explicit JOBS= is honored
# as-is (assumed deliberate).
NPROC="$(nproc 2>/dev/null || echo 4)"
[[ "$NPROC" =~ ^[0-9]+$ ]] && (( NPROC >= 1 )) || NPROC=4
if [[ -n "${JOBS:-}" ]]; then
    [[ "$JOBS" =~ ^[0-9]+$ ]] && (( JOBS >= 1 )) || JOBS=4
else
    JOBS=$(( NPROC > 16 ? 16 : NPROC ))
fi

# Portable mtime probe: GNU stat uses -c, BSD (macOS) uses -f. Probed once;
# helpers below take the portable path. (Inside `nix develop` GNU stat is
# present on every platform, but standalone runs should not depend on it.)
if stat -c '%Y' "$THIS_SCRIPT" >/dev/null 2>&1; then
    STAT_MTIME_ARGS="-c %Y"
else
    STAT_MTIME_ARGS="-f %m"
fi
stat_mtime() {
    # shellcheck disable=SC2086
    # ($STAT_MTIME_ARGS word-splits into flag + format, by design)
    stat $STAT_MTIME_ARGS "$1"
}

# Toolchain fingerprint: mtime tracking cannot see compiler upgrades, so a
# compiler change wipes all objects — a stale .o from another toolchain must
# never link into test binaries. The full $CXX string is fingerprinted, not
# just --version output, so wrapper/flag changes invalidate too.
TOOLCHAIN_STAMP="$OBJ_DIR/.toolchain_fingerprint"
# NOTE: the candidate fingerprint lives in $BUILD_DIR (not $OBJ_DIR), because
# a toolchain change wipes $OBJ_DIR below, which would delete the candidate.
TOOLCHAIN_CANDIDATE="$BUILD_DIR/.toolchain_fingerprint.new"
# shellcheck disable=SC2086
# ($CXX word-splits when wrapped with ccache, by design)
{ printf '%s\n' "$CXX"; $CXX --version; } > "$TOOLCHAIN_CANDIDATE" 2>/dev/null || echo "unknown-toolchain" > "$TOOLCHAIN_CANDIDATE"
if ! cmp -s "$TOOLCHAIN_STAMP" "$TOOLCHAIN_CANDIDATE" 2>/dev/null; then
    echo "test_native_audio.sh: toolchain changed, rebuilding all native targets..." >&2
    rm -rf "${OBJ_DIR:?}"
    mkdir -p "$OBJ_DIR"
    mv "$TOOLCHAIN_CANDIDATE" "$TOOLCHAIN_STAMP"
else
    rm -f "$TOOLCHAIN_CANDIDATE"
fi

read -ra OPUS_FLAGS_ARR <<< "$OPUS_FLAGS"
read -ra OPUS_INC_ARR <<< "$OPUS_INC"

# A TU is stale when its object is missing, its source is newer, or any
# header under the dep dirs is newer (conservative header tracking: touching
# a widely-included header rebuilds dependent TUs, which is rare and always
# safe). Header epochs are precomputed once per profile (see below), so this
# stays a couple of builtins plus one stat per TU instead of a find walk.
tu_stale() {
    local obj="$1" src="$2"; shift 2
    [[ -f "$obj" ]] || return 0
    [[ "$src" -nt "$obj" ]] && return 0
    local obj_ep ne
    obj_ep=$(stat_mtime "$obj")
    for ne in "$@"; do
        [[ -n "$ne" && "$ne" -gt "$obj_ep" ]] && return 0
    done
    return 1
}

# Newest header mtime (epoch) under a dir, or empty when there are none.
# Single find per dir per profile; callers hoist this out of the per-TU loop.
newest_header_ep() {
    local d="$1"
    [[ -d "$d" ]] || return 0
    # shellcheck disable=SC2086
    # ($STAT_MTIME_ARGS word-splits into flag + format, by design)
    find "$d" -name '*.h' -exec stat $STAT_MTIME_ARGS {} + 2>/dev/null | sort -n | tail -n 1
}

# Generic driver. Caller sets SRCS[@] (sources), CXXFLAGS[@] (compile flags)
# and LINKFLAGS[@] (link flags); $1 = output binary, $2 = object subdir,
# remaining args = header dep dirs. A change to this script or the opus
# source lists wipes the profile's objects (flags/file set are inputs too).
compile_and_link() {
    local output="$1" objdir="$2"; shift 2
    mkdir -p "$objdir"
    local global_dep
    for global_dep in "$THIS_SCRIPT" "$OPUS_DIR"/celt_sources.mk "$OPUS_DIR"/silk_sources.mk "$OPUS_DIR"/opus_sources.mk; do
        if [[ -f "$global_dep" && "$global_dep" -nt "$output" ]]; then
            echo "test_native_audio.sh: build inputs changed, rebuilding $(basename "$output")..." >&2
            rm -rf "${objdir:?}"
            mkdir -p "$objdir"
            break
        fi
    done
    # Header-set tracking: deletions/renames never advance mtimes, so hash
    # the sorted header list per profile and wipe on change (mtime-only
    # tracking would otherwise link stale objects fail-open).
    local header_cksum d
    header_cksum="$(for d in "$@"; do [[ -d "$d" ]] && find "$d" -name '*.h' -print; done | LC_ALL=C sort | cksum)"
    if [[ -f "$objdir/.header_set.cksum" ]]; then
        if [[ "$(cat "$objdir/.header_set.cksum")" != "$header_cksum" ]]; then
            echo "test_native_audio.sh: header set changed, rebuilding $(basename "$output")..." >&2
            rm -rf "${objdir:?}"
            mkdir -p "$objdir"
        fi
    fi
    printf '%s' "$header_cksum" > "$objdir/.header_set.cksum"
    local -a dep_eps=()
    for d in "$@"; do
        dep_eps+=("$(newest_header_ep "$d")")
    done
    local -a all_objs=() todo_src=() todo_obj=()
    local src rel obj
    for src in "${SRCS[@]}"; do
        rel="${src#$ROOT_DIR/}"
        obj="$objdir/$rel.o"
        mkdir -p "${obj%/*}"
        all_objs+=("$obj")
        if tu_stale "$obj" "$src" "${dep_eps[@]}"; then
            todo_src+=("$src")
            todo_obj+=("$obj")
        fi
    done
    if (( ${#todo_src[@]} > 0 )); then
        echo "test_native_audio.sh: compiling ${#todo_src[@]} file(s) for $(basename "$output")..." >&2
    fi
    local i=0 n=${#todo_src[@]} fail=0
    while (( i < n )); do
        local -a pids=() btmp=() bobj=()
        local k tmp_obj
        for (( k = 0; k < JOBS && i < n; k++, i++ )); do
            # Compile to a temp file and rename on success: a killed compiler
            # must never leave a fresh-dated partial .o that masks retry.
            # (BASHPID expands in the parent, so names are unique per TU and
            # per script invocation.)
            tmp_obj="${todo_obj[$i]}.tmp.${BASHPID}"
            # shellcheck disable=SC2086
            # ($CXX word-splits when wrapped with ccache, by design)
            $CXX "${CXXFLAGS[@]}" -c "${todo_src[$i]}" -o "$tmp_obj" &
            pids+=($!); btmp+=("$tmp_obj"); bobj+=("${todo_obj[$i]}")
        done
        local pid
        for pid in "${pids[@]}"; do
            wait "$pid" || fail=1
        done
        if (( fail )); then
            rm -f "${btmp[@]}"
        else
            local m
            for (( m = 0; m < ${#btmp[@]}; m++ )); do
                mv "${btmp[$m]}" "${bobj[$m]}"
            done
        fi
    done
    if (( fail )); then
        echo "test_native_audio.sh: compilation failed." >&2
        return 1
    fi
    local need_link=0
    [[ -f "$output" ]] || need_link=1
    if (( ! need_link )); then
        for obj in "${all_objs[@]}"; do
            [[ "$obj" -nt "$output" ]] && { need_link=1; break; }
        done
    fi
    if (( need_link )); then
        echo "test_native_audio.sh: linking $(basename "$output")..." >&2
        $CXX "${all_objs[@]}" "${LINKFLAGS[@]}" -o "$output" || return 1
    else
        echo "test_native_audio.sh: $(basename "$output") up-to-date, skipping build." >&2
    fi
}

# Audio engine test binary: engine sources plus hermetic tests plus the real
# Opus sources (same lists, defines and fixed-point mode as the NDK build).
SRCS=(
    "$ENGINE_DIR/jitter/jitter.c"
    "$ENGINE_DIR/SoftLimiter.cpp"
    "$ENGINE_DIR/PreSpeechRingBuffer.cpp"
    "$ENGINE_DIR/AdaptiveLeveler.cpp"
    "$ENGINE_DIR/AudioInputEngine.cpp"
    "$ENGINE_DIR/AudioOutputEngine.cpp"
    "$ENGINE_DIR/HysteresisVad.cpp"
    "$TEST_DIR/test_biquad_filter.cpp"
    "$TEST_DIR/test_soft_limiter.cpp"
    "$TEST_DIR/test_pre_speech_ring_buffer.cpp"
    "$TEST_DIR/test_adaptive_leveler.cpp"
    "$TEST_DIR/test_hysteresis_vad.cpp"
    "$TEST_DIR/test_jitter_buffer.cpp"
    "$TEST_DIR/test_audio_input_engine.cpp"
    "$TEST_DIR/test_audio_output_engine.cpp"
    "$TEST_DIR/test_opus_interop.cpp"
    "$TEST_DIR/run_audio_tests.cpp"
    "$ENGINE_DIR/OpusVoiceEncoder.cpp"
    "$ENGINE_DIR/OpusVoiceDecoder.cpp"
)
# shellcheck disable=SC2086
# (word-splitting intended: one file per token)
for f in $OPUS_SRCS; do SRCS+=("$f"); done
CXXFLAGS=(-std=c++17 -O2 -Wall -Wextra -Werror -UNDEBUG
    -I "$ENGINE_DIR"
    -I "$ENGINE_DIR/jitter"
    -I "$TEST_DIR"
    "${OPUS_FLAGS_ARR[@]}"
    "${OPUS_INC_ARR[@]}")
LINKFLAGS=()
compile_and_link "$BUILD_DIR/test_audio_engine" "$OBJ_DIR/audio" \
    "$ENGINE_DIR" "$TEST_DIR" \
    "$OPUS_DIR/include" "$OPUS_DIR/celt" "$OPUS_DIR/silk" || exit 1

# CryptState unit test binary.
SRCS=("$CRYPTO_DIR/CryptStateOCB2.cpp" "$TEST_DIR/test_crypt_state.cpp")
CXXFLAGS=(-std=c++17 -O2 -Wall -Wextra -Werror -UNDEBUG -I "$CRYPTO_DIR")
LINKFLAGS=(-lpthread)
compile_and_link "$BUILD_DIR/test_crypt_state" "$OBJ_DIR/crypt" \
    "$CRYPTO_DIR" "$TEST_DIR" || exit 1

# Host JNI shared library for host JVM unit tests.
SRCS=("$CRYPTO_DIR/CryptStateOCB2.cpp" "$CRYPTO_DIR/NativeCryptStateJni.cpp")
CXXFLAGS=(-std=c++17 -O2 -fPIC -Wall -Wextra -Werror -UNDEBUG
    -I "$CRYPTO_DIR" -I "$JAVA_INC" -I "$JNI_MD_INC")
LINKFLAGS=(-shared -lpthread)
compile_and_link "$BUILD_DIR/libhumlaaudio.${SO_EXT}" "$OBJ_DIR/jni" \
    "$CRYPTO_DIR" "$JAVA_INC" "$JNI_MD_INC" || exit 1

"$BUILD_DIR/test_audio_engine"

"$BUILD_DIR/test_crypt_state"

