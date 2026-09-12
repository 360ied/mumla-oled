/* Copyright (C) 2002 Jean-Marc Valin
 *
 * Adaptive jitter buffer for VoIP audio packet streams.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * - Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Neither the name of the Xiph.org Foundation nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE FOUNDATION OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef MUMLA_SPEEX_JITTER_H_
#define MUMLA_SPEEX_JITTER_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef int32_t spx_int32_t;
typedef uint32_t spx_uint32_t;
typedef int16_t spx_int16_t;
typedef uint16_t spx_uint16_t;

/** Generic adaptive jitter buffer state */
struct JitterBuffer_;
typedef struct JitterBuffer_ JitterBuffer;

/** Definition of an incoming packet */
typedef struct _JitterBufferPacket JitterBufferPacket;
struct _JitterBufferPacket {
   char        *data;       /**< Data bytes contained in the packet */
   spx_uint32_t len;        /**< Length of the packet in bytes */
   spx_uint32_t timestamp;  /**< Timestamp for the packet */
   spx_uint32_t span;       /**< Time covered by the packet (same units as timestamp) */
   spx_uint16_t sequence;   /**< RTP Sequence number if available (0 otherwise) */
   spx_uint32_t user_data;  /**< User data / flags */
};

/** Packet has been retrieved */
#define JITTER_BUFFER_OK 0
/** Packet is lost or is late */
#define JITTER_BUFFER_MISSING 1
/** A "fake" packet is meant to be inserted here to increase buffering */
#define JITTER_BUFFER_INSERTION 2
/** There was an error in the jitter buffer */
#define JITTER_BUFFER_INTERNAL_ERROR -1
/** Invalid argument */
#define JITTER_BUFFER_BAD_ARGUMENT -2

/** Set minimum amount of extra buffering required (margin) */
#define JITTER_BUFFER_SET_MARGIN 0
/** Get minimum amount of extra buffering required (margin) */
#define JITTER_BUFFER_GET_MARGIN 1

/** Get the amount of available packets currently buffered */
#define JITTER_BUFFER_GET_AVAILABLE_COUNT 3
#define JITTER_BUFFER_GET_AVALIABLE_COUNT 3

/** Assign a function to destroy unused packet */
#define JITTER_BUFFER_SET_DESTROY_CALLBACK 4
#define JITTER_BUFFER_GET_DESTROY_CALLBACK 5

/** Tell the jitter buffer to only adjust the delay in multiples of the step parameter provided */
#define JITTER_BUFFER_SET_DELAY_STEP 6
#define JITTER_BUFFER_GET_DELAY_STEP 7

/** Tell the jitter buffer to only do concealment in multiples of the size parameter provided */
#define JITTER_BUFFER_SET_CONCEALMENT_SIZE 8
#define JITTER_BUFFER_GET_CONCEALMENT_SIZE 9

/** Absolute max amount of loss that can be tolerated regardless of the delay */
#define JITTER_BUFFER_SET_MAX_LATE_RATE 10
#define JITTER_BUFFER_GET_MAX_LATE_RATE 11

/** Equivalent cost of one percent late packet in timestamp units */
#define JITTER_BUFFER_SET_LATE_COST 12
#define JITTER_BUFFER_GET_LATE_COST 13

/** Initialises jitter buffer */
JitterBuffer *jitter_buffer_init(int step_size);

/** Restores jitter buffer to its original state */
void jitter_buffer_reset(JitterBuffer *jitter);

/** Destroys jitter buffer */
void jitter_buffer_destroy(JitterBuffer *jitter);

/** Put one packet into the jitter buffer */
void jitter_buffer_put(JitterBuffer *jitter, const JitterBufferPacket *packet);

/** Get one packet from the jitter buffer */
int jitter_buffer_get(JitterBuffer *jitter, JitterBufferPacket *packet, int desired_span, spx_int32_t *start_offset);

/** Get another packet from the jitter buffer with the same timestamp */
int jitter_buffer_get_another(JitterBuffer *jitter, JitterBufferPacket *packet);

/** Get pointer timestamp of jitter buffer */
int jitter_buffer_get_pointer_timestamp(JitterBuffer *jitter);

/** Advance jitter buffer timestamp */
void jitter_buffer_tick(JitterBuffer *jitter);

/** Update delay to adapt to network jitter */
int jitter_buffer_update_delay(JitterBuffer *jitter, JitterBufferPacket *packet, spx_int32_t *start_offset);

/** Control jitter buffer options */
int jitter_buffer_ctl(JitterBuffer *jitter, int request, void *ptr);

#ifdef __cplusplus
}
#endif

#endif // MUMLA_SPEEX_JITTER_H_
