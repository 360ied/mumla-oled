# Copyright (C) 2013 Andrew Comminos
# Copyright (C) 2026 Brian Zhu
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

cmake_minimum_required(VERSION 3.22...4.1)
project(humla_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Route compilation through ccache when available (covers both Android NDK and
# host builds; NDK's android.toolchain.cmake does not read $ENV{NDK_CCACHE}).
if(NOT CMAKE_C_COMPILER_LAUNCHER AND NOT CMAKE_CXX_COMPILER_LAUNCHER)
    if(DEFINED ENV{NDK_CCACHE} AND EXISTS "$ENV{NDK_CCACHE}")
        set(CCACHE_PROGRAM "$ENV{NDK_CCACHE}")
    else()
        find_program(CCACHE_PROGRAM ccache)
    endif()
    if(CCACHE_PROGRAM)
        set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
        set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    endif()
endif()

set(JNI_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")
set(OPUS_DIR "${JNI_ROOT}/opus")
set(RNNOISE_DIR "${JNI_ROOT}/rnnoise")
set(RNNOISE_BUILD_DIR "${JNI_ROOT}/rnnoise-build")
set(AUDIO_ENGINE_DIR "${JNI_ROOT}/audio_engine")
set(CRYPTO_DIR "${JNI_ROOT}/crypto")
set(TEST_CPP_DIR "${JNI_ROOT}/../../test/cpp")

# Parse source lists from upstream Opus .mk files (file(STRINGS) joins \-continuations).
# The opus submodule is pinned to upstream v1.6.1
# (22244de5a79bd1d6d623c32e72bf1954b56235be); only CELT_SOURCES, SILK_SOURCES,
# SILK_SOURCES_FIXED, OPUS_SOURCES, and OPUS_SOURCES_FLOAT are parsed.
# *_ARM_RTCD, *_X86_RTCD, SSE/SSE2/SSE4_1/AVX2, ARM_NEON_INTR, ARM_ASM, and
# lpcnet_sources.mk (dnn/, deep PLC + DRED) are intentionally excluded: ARM RTCD
# files are empty TUs without OPUS_HAVE_RTCD, and x86 SIMD/NEON would diverge
# from the audited fixed-point footprint.
function(parse_opus_mk mk_file var_name out_var)
    file(STRINGS "${mk_file}" lines)
    set(result "")
    foreach(line IN LISTS lines)
        if(line MATCHES "^${var_name}[ \t]*=[ \t]*(.*)$")
            string(REGEX MATCHALL "[^ \t;]+" items "${CMAKE_MATCH_1}")
            foreach(item IN LISTS items)
                if(NOT item MATCHES "^#")
                    list(APPEND result "${OPUS_DIR}/${item}")
                endif()
            endforeach()
        endif()
    endforeach()
    if(NOT result)
        message(FATAL_ERROR "Failed to parse ${var_name} from ${mk_file}")
    endif()
    set(${out_var} "${result}" PARENT_SCOPE)
endfunction()

parse_opus_mk("${OPUS_DIR}/celt_sources.mk" "CELT_SOURCES" CELT_SOURCES)
parse_opus_mk("${OPUS_DIR}/silk_sources.mk" "SILK_SOURCES" SILK_SOURCES)
parse_opus_mk("${OPUS_DIR}/silk_sources.mk" "SILK_SOURCES_FIXED" SILK_SOURCES_FIXED)
parse_opus_mk("${OPUS_DIR}/opus_sources.mk" "OPUS_SOURCES" OPUS_SOURCES)
parse_opus_mk("${OPUS_DIR}/opus_sources.mk" "OPUS_SOURCES_FLOAT" OPUS_SOURCES_FLOAT)

# Re-run configure when upstream Opus source lists change (e.g. submodule
# bump): file(STRINGS) reads are invisible to Ninja's re-run rule otherwise,
# and .cxx/ survives `gradlew clean`, so without this a bump could link stale
# sources with no automatic recovery.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${OPUS_DIR}/celt_sources.mk"
    "${OPUS_DIR}/silk_sources.mk"
    "${OPUS_DIR}/opus_sources.mk"
)
set(OPUS_COMPILE_FLAGS
    -DOPUS_BUILD
    -DVAR_ARRAYS
    -DFIXED_POINT
    -DHAVE_LRINTF=1
)

# Flag & header scoping:
# - humlaopus: plain codec flags; exposes only PUBLIC opus/include.
#   -DUSE_WEIGHTS_FILE must never leak here (it redirects OPUS_SET_DNN_BLOB to
#   lpcnet_plc_load_model/silk_LoadOSCEModels, whose dnn/ sources are omitted).
# - humlarnnoise: PRIVATE -DHAVE_CONFIG_H -DUSE_WEIGHTS_FILE; exposes only
#   PUBLIC rnnoise/include so internal headers (arch.h, pitch.h, kiss_fft.h,
#   celt_lpc.h) never collide with opus/celt/.
add_library(humlaopus STATIC
    ${CELT_SOURCES}
    ${SILK_SOURCES}
    ${SILK_SOURCES_FIXED}
    ${OPUS_SOURCES}
    ${OPUS_SOURCES_FLOAT}
)
target_include_directories(humlaopus
    PUBLIC  "${OPUS_DIR}/include"
    PRIVATE "${OPUS_DIR}/celt"
            "${OPUS_DIR}/silk"
            "${OPUS_DIR}/silk/float"
            "${OPUS_DIR}/silk/fixed"
)

if(ANDROID)
    set(COMMON_ANDROID_FLAGS
        -ffunction-sections
        -fdata-sections
        -fvisibility=hidden
        -fstack-protector-strong
        -D_FORTIFY_SOURCE=2
    )

    target_compile_options(humlaopus PRIVATE
        ${COMMON_ANDROID_FLAGS}
        ${OPUS_COMPILE_FLAGS}
        -O3
        -DNDEBUG
    )

    add_library(humlarnnoise STATIC
        "${RNNOISE_BUILD_DIR}/generated/rnnoise_data.c"
        "${RNNOISE_DIR}/src/rnnoise_tables.c"
        "${RNNOISE_DIR}/src/rnn.c"
        "${RNNOISE_DIR}/src/pitch.c"
        "${RNNOISE_DIR}/src/nnet.c"
        "${RNNOISE_DIR}/src/nnet_default.c"
        "${RNNOISE_DIR}/src/parse_lpcnet_weights.c"
        "${RNNOISE_DIR}/src/kiss_fft.c"
        "${RNNOISE_DIR}/src/denoise.c"
        "${RNNOISE_DIR}/src/celt_lpc.c"
    )
    target_include_directories(humlarnnoise
        PUBLIC  "${RNNOISE_DIR}/include"
        PRIVATE "${RNNOISE_DIR}/src"
                "${RNNOISE_BUILD_DIR}"
                "${RNNOISE_BUILD_DIR}/generated"
    )
    target_compile_options(humlarnnoise PRIVATE
        ${COMMON_ANDROID_FLAGS}
        -DHAVE_CONFIG_H
        -DUSE_WEIGHTS_FILE
        -O3
        -DNDEBUG
        -fno-math-errno
        -fvectorize
        -DVAR_ARRAYS
        "-Wno-#warnings"
    )

    # Single-library layout: Opus, RNNoise, audio engines, Speex jitter buffer,
    # and OCB2 crypto all link into libhumlaaudio.so.
    add_library(humlaaudio SHARED
        "${AUDIO_ENGINE_DIR}/PreSpeechRingBuffer.cpp"
        "${AUDIO_ENGINE_DIR}/SoftLimiter.cpp"
        "${AUDIO_ENGINE_DIR}/AdaptiveLeveler.cpp"
        "${AUDIO_ENGINE_DIR}/HysteresisVad.cpp"
        "${AUDIO_ENGINE_DIR}/RnnoiseProcessor.cpp"
        "${AUDIO_ENGINE_DIR}/OpusVoiceEncoder.cpp"
        "${AUDIO_ENGINE_DIR}/AudioInputEngine.cpp"
        "${AUDIO_ENGINE_DIR}/NativeAudioInputEngineJni.cpp"
        "${AUDIO_ENGINE_DIR}/OpusVoiceDecoder.cpp"
        "${AUDIO_ENGINE_DIR}/AudioOutputEngine.cpp"
        "${AUDIO_ENGINE_DIR}/NativeAudioOutputEngineJni.cpp"
        "${AUDIO_ENGINE_DIR}/NativeCrashTestJni.cpp"
        "${AUDIO_ENGINE_DIR}/jitter/jitter.c"
        "${CRYPTO_DIR}/CryptStateOCB2.cpp"
        "${CRYPTO_DIR}/NativeCryptStateJni.cpp"
    )
    target_include_directories(humlaaudio PRIVATE
        "${AUDIO_ENGINE_DIR}"
        "${AUDIO_ENGINE_DIR}/jitter"
        "${CRYPTO_DIR}"
    )
    target_compile_options(humlaaudio PRIVATE
        ${COMMON_ANDROID_FLAGS}
        ${OPUS_COMPILE_FLAGS}
        -O3
        -DNDEBUG
        $<$<COMPILE_LANGUAGE:CXX>:-fexceptions -fno-rtti>
    )
    target_link_options(humlaaudio PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,-z,relro,-z,now
    )
    target_link_libraries(humlaaudio PRIVATE
        humlaopus
        humlarnnoise
        log
    )
else()
    # Host native test executables & host JNI shared library.
    enable_testing()
    find_package(Threads REQUIRED)
    find_package(JNI REQUIRED)

    set(HOST_FLAGS -O2 -Wall -Wextra -Werror -UNDEBUG)
    # -Wmaybe-uninitialized is GCC-only: AppleClang rejects the unknown group
    # and -Werror promotes that rejection, so gate the suppression on GNU.
    if(CMAKE_C_COMPILER_ID STREQUAL "GNU" AND CMAKE_CXX_COMPILER_ID STREQUAL "GNU")
        list(APPEND HOST_FLAGS -Wno-maybe-uninitialized)
    endif()

    target_compile_options(humlaopus PRIVATE
        ${OPUS_COMPILE_FLAGS}
        ${HOST_FLAGS}
    )

    add_library(humlacrypto_obj OBJECT
        "${CRYPTO_DIR}/CryptStateOCB2.cpp"
    )
    target_include_directories(humlacrypto_obj PRIVATE "${CRYPTO_DIR}")
    target_compile_options(humlacrypto_obj PRIVATE ${HOST_FLAGS})

    add_executable(test_audio_engine
        "${AUDIO_ENGINE_DIR}/jitter/jitter.c"
        "${AUDIO_ENGINE_DIR}/SoftLimiter.cpp"
        "${AUDIO_ENGINE_DIR}/PreSpeechRingBuffer.cpp"
        "${AUDIO_ENGINE_DIR}/AdaptiveLeveler.cpp"
        "${AUDIO_ENGINE_DIR}/AudioInputEngine.cpp"
        "${AUDIO_ENGINE_DIR}/AudioOutputEngine.cpp"
        "${AUDIO_ENGINE_DIR}/HysteresisVad.cpp"
        "${AUDIO_ENGINE_DIR}/OpusVoiceEncoder.cpp"
        "${AUDIO_ENGINE_DIR}/OpusVoiceDecoder.cpp"
        "${TEST_CPP_DIR}/test_biquad_filter.cpp"
        "${TEST_CPP_DIR}/test_soft_limiter.cpp"
        "${TEST_CPP_DIR}/test_pre_speech_ring_buffer.cpp"
        "${TEST_CPP_DIR}/test_adaptive_leveler.cpp"
        "${TEST_CPP_DIR}/test_hysteresis_vad.cpp"
        "${TEST_CPP_DIR}/test_jitter_buffer.cpp"
        "${TEST_CPP_DIR}/test_audio_input_engine.cpp"
        "${TEST_CPP_DIR}/test_audio_output_engine.cpp"
        "${TEST_CPP_DIR}/test_opus_interop.cpp"
        "${TEST_CPP_DIR}/run_audio_tests.cpp"
    )
    target_include_directories(test_audio_engine PRIVATE
        "${AUDIO_ENGINE_DIR}"
        "${AUDIO_ENGINE_DIR}/jitter"
        "${TEST_CPP_DIR}"
    )
    target_compile_options(test_audio_engine PRIVATE ${HOST_FLAGS} ${OPUS_COMPILE_FLAGS})
    target_link_libraries(test_audio_engine PRIVATE humlaopus Threads::Threads m)
    add_test(NAME test_audio_engine COMMAND test_audio_engine)

    add_executable(test_crypt_state
        $<TARGET_OBJECTS:humlacrypto_obj>
        "${TEST_CPP_DIR}/test_crypt_state.cpp"
    )
    target_include_directories(test_crypt_state PRIVATE "${CRYPTO_DIR}" "${TEST_CPP_DIR}")
    target_compile_options(test_crypt_state PRIVATE ${HOST_FLAGS})
    target_link_libraries(test_crypt_state PRIVATE Threads::Threads)
    add_test(NAME test_crypt_state COMMAND test_crypt_state)

    add_library(humlaaudio SHARED
        $<TARGET_OBJECTS:humlacrypto_obj>
        "${CRYPTO_DIR}/NativeCryptStateJni.cpp"
    )
    target_include_directories(humlaaudio PRIVATE "${CRYPTO_DIR}" ${JNI_INCLUDE_DIRS})
    target_compile_options(humlaaudio PRIVATE ${HOST_FLAGS})
    target_link_libraries(humlaaudio PRIVATE Threads::Threads)
endif()
