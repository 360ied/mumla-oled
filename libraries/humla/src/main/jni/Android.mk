# Copyright (C) 2013 Andrew Comminos
# Copyright (C) 2026 Mumla Developers
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

include $(CLEAR_VARS)

LOCAL_PATH          := $(ROOT)/speex/libspeex
LOCAL_MODULE        := jnispeex
LOCAL_C_INCLUDES    := $(ROOT)/speex/include/
LOCAL_SRC_FILES     := cb_search.c      exc_10_32_table.c   exc_8_128_table.c   filters.c \
                       gain_table.c     hexc_table.c        high_lsp_tables.c   lsp.c \
                       ltp.c            speex.c             stereo.c            vbr.c \
                       vq.c bits.c      exc_10_16_table.c   exc_20_32_table.c   exc_5_256_table.c \
                       exc_5_64_table.c gain_table_lbr.c    hexc_10_32_table.c  lpc.c \
                       lsp_tables_nb.c  modes.c             modes_wb.c          nb_celp.c \
                       quant_lsp.c      sb_celp.c           speex_callbacks.c   speex_header.c \
                       window.c         resample.c          jitter.c            preprocess.c \
                       mdf.c            kiss_fft.c          kiss_fftr.c         fftwrap.c \
                       filterbank.c     scal.c \
                       $(ROOT)/jnispeex.cpp
LOCAL_CFLAGS           := -D__EMX__ -DUSE_KISS_FFT -DFIXED_POINT -DEXPORT=''
LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384"
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_PATH          := $(ROOT)/celt-0.11.0-src/libcelt
LOCAL_MODULE        := jnicelt11
LOCAL_SRC_FILES     := bands.c celt.c cwrs.c entcode.c entdec.c entenc.c header.c kiss_fft.c \
                       laplace.c mathops.c mdct.c modes.c pitch.c plc.c quant_bands.c rate.c vq.c \
                       $(ROOT)/jnicelt11.cpp
LOCAL_C_INCLUDES    := $(ROOT)/celt-0.11.0-src/libcelt/
LOCAL_CFLAGS        := -I$(ROOT)/celt-0.11.0-build -DHAVE_CONFIG_H -fvisibility=hidden
LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384"
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_PATH          := $(ROOT)/celt-0.7.0-src/libcelt
LOCAL_MODULE        := jnicelt7
LOCAL_SRC_FILES     := bands.c celt.c cwrs.c entcode.c entdec.c entenc.c header.c kiss_fft.c \
                       kiss_fftr.c laplace.c mdct.c modes.c pitch.c psy.c quant_bands.c rangedec.c \
                       rangeenc.c rate.c vq.c $(ROOT)/jnicelt7.cpp
LOCAL_C_INCLUDES    := $(ROOT)/celt-0.7.0-src/libcelt/
LOCAL_CFLAGS        := -I$(ROOT)/celt-0.7.0-build -DHAVE_CONFIG_H -fvisibility=hidden
LOCAL_CPP_FEATURES := exceptions
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384"
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_PATH   := $(ROOT)/opus
LOCAL_MODULE := jniopus

include $(LOCAL_PATH)/celt_sources.mk
include $(LOCAL_PATH)/silk_sources.mk
include $(LOCAL_PATH)/opus_sources.mk

ifeq ($(TARGET_ARCH), arm)
CELT_SOURCES += $(CELT_SOURCES_ARM)
SILK_SOURCES += $(SILK_SOURCES_ARM)
endif

# TODO: add support for floating-point?
SILK_SOURCES += $(SILK_SOURCES_FIXED)
OPUS_SOURCES += $(OPUS_SOURCES_FLOAT)
# end fixed point

LOCAL_C_INCLUDES    := $(LOCAL_PATH)/include $(LOCAL_PATH)/celt $(LOCAL_PATH)/silk \
                       $(LOCAL_PATH)/silk/float $(LOCAL_PATH)/silk/fixed
LOCAL_SRC_FILES     := $(CELT_SOURCES) $(SILK_SOURCES) $(OPUS_SOURCES) $(ROOT)/jniopus.cpp
LOCAL_CFLAGS        := -DOPUS_BUILD -DVAR_ARRAYS -DFIXED_POINT
LOCAL_CPP_FEATURES  := exceptions
LOCAL_LDLIBS        := -llog
LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384"
include $(BUILD_SHARED_LIBRARY)

# Modern Audio Input Engine (Oboe/AAudio + RNNoise + Lookahead Ring Buffer + Hysteresis VAD + Soft Limiter + Opus CBR)
include $(CLEAR_VARS)
LOCAL_PATH := $(ROOT)
LOCAL_MODULE := humlaaudio
LOCAL_C_INCLUDES := $(ROOT)/opus/include $(ROOT)/opus/celt $(ROOT)/opus/silk \
                    $(ROOT)/rnnoise/include $(ROOT)/rnnoise/src \
                    $(ROOT)/rnnoise-build $(ROOT)/rnnoise-build/generated \
                    $(ROOT)/audio_engine
LOCAL_SRC_FILES := rnnoise-build/generated/rnnoise_data.c \
                   rnnoise/src/rnnoise_tables.c \
                   rnnoise/src/rnn.c \
                   rnnoise/src/pitch.c \
                   rnnoise/src/nnet.c \
                   rnnoise/src/nnet_default.c \
                   rnnoise/src/parse_lpcnet_weights.c \
                   rnnoise/src/kiss_fft.c \
                   rnnoise/src/denoise.c \
                   rnnoise/src/celt_lpc.c \
                   audio_engine/PreSpeechRingBuffer.cpp \
                   audio_engine/SoftLimiter.cpp \
                   audio_engine/HysteresisVad.cpp \
                   audio_engine/RnnoiseProcessor.cpp \
                   audio_engine/OpusVoiceEncoder.cpp \
                   audio_engine/AudioInputEngine.cpp \
                   audio_engine/NativeAudioInputEngineJni.cpp
LOCAL_CFLAGS := -I$(ROOT)/rnnoise-build -DHAVE_CONFIG_H -DUSE_WEIGHTS_FILE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections -DVAR_ARRAYS
LOCAL_CPP_FEATURES := exceptions
LOCAL_SHARED_LIBRARIES := jniopus
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS += "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections"
include $(BUILD_SHARED_LIBRARY)

