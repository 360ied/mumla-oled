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
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#

ROOT := $(call my-dir)

COMMON_CFLAGS := -ffunction-sections -fdata-sections -fvisibility=hidden -fstack-protector-strong -D_FORTIFY_SOURCE=2
COMMON_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,relro,-z,now

# Opus Voice Codec (linked into humlaaudio; see below).
# The opus submodule is pinned to upstream v1.6.1
# (22244de5a79bd1d6d623c32e72bf1954b56235be); source lists come from its
# celt_sources.mk / silk_sources.mk / opus_sources.mk. Kept flags are
# -DVAR_ARRAYS -DFIXED_POINT -DHAVE_LRINTF=1 with SILK_SOURCES_FIXED +
# OPUS_SOURCES_FLOAT. lpcnet_sources.mk (dnn/, deep PLC + DRED) is opt-in
# and conflicts with fixed-point, so it is intentionally not included.
include $(CLEAR_VARS)
LOCAL_PATH   := $(ROOT)/opus

include $(LOCAL_PATH)/celt_sources.mk
include $(LOCAL_PATH)/silk_sources.mk
include $(LOCAL_PATH)/opus_sources.mk

ifeq ($(TARGET_ARCH), arm)
# 1.6.x renamed the old CELT_SOURCES_ARM to RTCD dispatch + NEON variants.
# The .s assembly (CELT_SOURCES_ARM_ASM) and NE10 (external lib) variants
# are intentionally excluded; the plain-C RTCD stubs work everywhere and
# the NEON intrinsics need no extra flags on arm64-v8a.
CELT_SOURCES += $(CELT_SOURCES_ARM_RTCD)
SILK_SOURCES += $(SILK_SOURCES_ARM_RTCD)
endif

SILK_SOURCES += $(SILK_SOURCES_FIXED)
OPUS_SOURCES += $(OPUS_SOURCES_FLOAT)

# Stash the codec lists for the humlaopus stanza below (CLEAR_VARS wipes
# module-local vars, so they must be copied to globals here).
HUMLA_OPUS_C_INCLUDES := $(LOCAL_PATH)/include $(LOCAL_PATH)/celt $(LOCAL_PATH)/silk \
                         $(LOCAL_PATH)/silk/float $(LOCAL_PATH)/silk/fixed
HUMLA_OPUS_SRC_FILES := $(addprefix opus/,$(CELT_SOURCES) $(SILK_SOURCES) $(OPUS_SOURCES))
HUMLA_OPUS_CFLAGS := -DOPUS_BUILD -DVAR_ARRAYS -DFIXED_POINT -DHAVE_LRINTF=1 -O3

# Flag scoping: ndk-build has no per-file CFLAGS, so opus/ and RNNoise
# sources compile as static libraries with their own LOCAL_CFLAGS.
# - humlaopus: plain codec flags. -DUSE_WEIGHTS_FILE must never leak here:
#   it re-targets opus_decoder_ctl(OPUS_SET_DNN_BLOB) at
#   lpcnet_plc_load_model/silk_LoadOSCEModels, whose dnn/ sources are not
#   compiled (deep PLC/DRED stay off).
# - humlarnnoise: -DHAVE_CONFIG_H (committed rnnoise-build/config.h),
#   -DUSE_WEIGHTS_FILE (weights-blob build), -fvectorize.
# - humlaaudio: the JNI glue + engines, linked against both (whole-archive
#   so the codec symbols survive --gc-sections/--exclude-libs).
HUMLA_RNNOISE_C_INCLUDES := $(ROOT)/rnnoise/include $(ROOT)/rnnoise/src \
                            $(ROOT)/rnnoise-build $(ROOT)/rnnoise-build/generated
HUMLA_RNNOISE_SRC_FILES := rnnoise-build/generated/rnnoise_data.c \
                           rnnoise/src/rnnoise_tables.c \
                           rnnoise/src/rnn.c \
                           rnnoise/src/pitch.c \
                           rnnoise/src/nnet.c \
                           rnnoise/src/nnet_default.c \
                           rnnoise/src/parse_lpcnet_weights.c \
                           rnnoise/src/kiss_fft.c \
                           rnnoise/src/denoise.c \
                           rnnoise/src/celt_lpc.c
HUMLA_RNNOISE_CFLAGS := -I$(ROOT)/rnnoise-build -DHAVE_CONFIG_H -DUSE_WEIGHTS_FILE -O3 -fno-math-errno -fvectorize -DVAR_ARRAYS -Wno-\#warnings

include $(CLEAR_VARS)
LOCAL_PATH := $(ROOT)
LOCAL_MODULE := humlaopus
LOCAL_C_INCLUDES := $(HUMLA_OPUS_C_INCLUDES)
LOCAL_SRC_FILES := $(HUMLA_OPUS_SRC_FILES)
LOCAL_CFLAGS := $(COMMON_CFLAGS) $(HUMLA_OPUS_CFLAGS)
LOCAL_EXPORT_C_INCLUDES := $(HUMLA_OPUS_C_INCLUDES)
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_PATH := $(ROOT)
LOCAL_MODULE := humlarnnoise
LOCAL_C_INCLUDES := $(HUMLA_RNNOISE_C_INCLUDES)
LOCAL_SRC_FILES := $(HUMLA_RNNOISE_SRC_FILES)
LOCAL_CFLAGS := $(COMMON_CFLAGS) $(HUMLA_RNNOISE_CFLAGS)
LOCAL_EXPORT_C_INCLUDES := $(HUMLA_RNNOISE_C_INCLUDES)
include $(BUILD_STATIC_LIBRARY)

# NOTE: single-library layout: the Opus codec and RNNoise sources above link
# into humlaaudio — there is no separate opus module. Load only "humlaaudio"
# (see NativeAudioInputEngine.java, NativeAudioOutputEngine.java,
# CryptState.java); the old two-library load order is gone. There is no
# separate JitterBuffer JNI bridge: the Speex jitter buffer
# (audio_engine/jitter/jitter.c) is used internally by AudioOutputEngine, so
# no JitterBufferJni source belongs in this list.
# Modern Audio Input/Output Engines & Jitter Buffer
# (RNNoise + Lookahead Ring Buffer + Hysteresis VAD + Soft Limiter + Opus CBR + Adaptive Jitter Buffer + Native Output Mix)
include $(CLEAR_VARS)
LOCAL_PATH := $(ROOT)
LOCAL_MODULE := humlaaudio
LOCAL_C_INCLUDES := $(HUMLA_OPUS_C_INCLUDES) \
                    $(HUMLA_RNNOISE_C_INCLUDES) \
                    $(ROOT)/audio_engine \
                    $(ROOT)/audio_engine/jitter \
                    $(ROOT)/crypto
LOCAL_SRC_FILES := audio_engine/PreSpeechRingBuffer.cpp \
                   audio_engine/SoftLimiter.cpp \
                   audio_engine/AdaptiveLeveler.cpp \
                   audio_engine/HysteresisVad.cpp \
                   audio_engine/RnnoiseProcessor.cpp \
                   audio_engine/OpusVoiceEncoder.cpp \
                   audio_engine/AudioInputEngine.cpp \
                   audio_engine/NativeAudioInputEngineJni.cpp \
                   audio_engine/OpusVoiceDecoder.cpp \
                   audio_engine/AudioOutputEngine.cpp \
                   audio_engine/NativeAudioOutputEngineJni.cpp \
                   audio_engine/jitter/jitter.c \
                   crypto/CryptStateOCB2.cpp \
                   crypto/NativeCryptStateJni.cpp
LOCAL_CFLAGS := $(COMMON_CFLAGS) $(HUMLA_OPUS_CFLAGS)
LOCAL_CPP_FEATURES := exceptions
LOCAL_WHOLE_STATIC_LIBRARIES := humlaopus humlarnnoise
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS += $(COMMON_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)
