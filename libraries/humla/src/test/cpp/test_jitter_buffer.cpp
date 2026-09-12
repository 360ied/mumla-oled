/*
 * Copyright (C) 2026 Mumla Developers
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "TestHarness.h"
#include "speex_jitter.h"

#include <cstring>
#include <iostream>

namespace {

void testJitterBufferInitAndDestroy() {
    g_testCount++;
    JitterBuffer* jb = jitter_buffer_init(480);
    TEST_ASSERT(jb != nullptr);

    int ts = jitter_buffer_get_pointer_timestamp(jb);
    TEST_ASSERT_EQ(0, ts);

    int avail = -1;
    jitter_buffer_ctl(jb, JITTER_BUFFER_GET_AVAILABLE_COUNT, &avail);
    TEST_ASSERT_EQ(0, avail);

    jitter_buffer_reset(jb);
    jitter_buffer_destroy(jb);
    std::cout << "  [PASS] testJitterBufferInitAndDestroy" << std::endl;
}

void testJitterBufferPutAndGet() {
    g_testCount++;
    JitterBuffer* jb = jitter_buffer_init(480);
    TEST_ASSERT(jb != nullptr);

    char payload[] = "opus_packet_sample_data_12345678";
    JitterBufferPacket putPkt;
    putPkt.data = payload;
    putPkt.len = sizeof(payload);
    putPkt.timestamp = 0;
    putPkt.span = 480;
    putPkt.sequence = 1;
    putPkt.user_data = 42;

    jitter_buffer_put(jb, &putPkt);

    int avail = 0;
    jitter_buffer_ctl(jb, JITTER_BUFFER_GET_AVAILABLE_COUNT, &avail);
    TEST_ASSERT_EQ(1, avail);

    char outBuf[128];
    JitterBufferPacket getPkt;
    getPkt.data = outBuf;
    getPkt.len = sizeof(outBuf);

    spx_int32_t startOffset = 0;
    int res = jitter_buffer_get(jb, &getPkt, 480, &startOffset);
    TEST_ASSERT_EQ(JITTER_BUFFER_OK, res);
    TEST_ASSERT_EQ(sizeof(payload), getPkt.len);
    TEST_ASSERT(std::memcmp(payload, outBuf, sizeof(payload)) == 0);
    TEST_ASSERT_EQ(42u, getPkt.user_data);

    jitter_buffer_destroy(jb);
    std::cout << "  [PASS] testJitterBufferPutAndGet" << std::endl;
}

void testJitterBufferOutOfOrderPackets() {
    g_testCount++;
    JitterBuffer* jb = jitter_buffer_init(480);
    TEST_ASSERT(jb != nullptr);

    char data1[] = "packet_one";
    char data2[] = "packet_two";

    // Insert packet 2 first (ts=480)
    JitterBufferPacket pkt2;
    pkt2.data = data2;
    pkt2.len = sizeof(data2);
    pkt2.timestamp = 480;
    pkt2.span = 480;
    pkt2.sequence = 2;
    pkt2.user_data = 2;
    jitter_buffer_put(jb, &pkt2);

    // Insert packet 1 second (ts=0)
    JitterBufferPacket pkt1;
    pkt1.data = data1;
    pkt1.len = sizeof(data1);
    pkt1.timestamp = 0;
    pkt1.span = 480;
    pkt1.sequence = 1;
    pkt1.user_data = 1;
    jitter_buffer_put(jb, &pkt1);

    char outBuf[128];
    JitterBufferPacket getPkt;
    getPkt.data = outBuf;
    getPkt.len = sizeof(outBuf);

    // Retrieve first packet: should be packet 1
    spx_int32_t startOffset = 0;
    int res1 = jitter_buffer_get(jb, &getPkt, 480, &startOffset);
    TEST_ASSERT_EQ(JITTER_BUFFER_OK, res1);
    TEST_ASSERT_EQ(1u, getPkt.user_data);
    TEST_ASSERT(std::memcmp(data1, outBuf, sizeof(data1)) == 0);

    // Retrieve second packet: should be packet 2
    getPkt.len = sizeof(outBuf);
    int res2 = jitter_buffer_get(jb, &getPkt, 480, &startOffset);
    TEST_ASSERT_EQ(JITTER_BUFFER_OK, res2);
    TEST_ASSERT_EQ(2u, getPkt.user_data);
    TEST_ASSERT(std::memcmp(data2, outBuf, sizeof(data2)) == 0);

    jitter_buffer_destroy(jb);
    std::cout << "  [PASS] testJitterBufferOutOfOrderPackets" << std::endl;
}

void testJitterBufferMarginAndTick() {
    g_testCount++;
    JitterBuffer* jb = jitter_buffer_init(480);
    TEST_ASSERT(jb != nullptr);

    int margin = 10 * 480;
    int ctlRes = jitter_buffer_ctl(jb, JITTER_BUFFER_SET_MARGIN, &margin);
    TEST_ASSERT_EQ(0, ctlRes);

    int getMargin = 0;
    ctlRes = jitter_buffer_ctl(jb, JITTER_BUFFER_GET_MARGIN, &getMargin);
    TEST_ASSERT_EQ(0, ctlRes);
    TEST_ASSERT_EQ(margin, getMargin);

    // Put a packet at ts=0, span=480
    char payload[] = "frame";
    JitterBufferPacket putPkt;
    putPkt.data = payload;
    putPkt.len = sizeof(payload);
    putPkt.timestamp = 0;
    putPkt.span = 480;
    putPkt.sequence = 1;
    putPkt.user_data = 0;
    jitter_buffer_put(jb, &putPkt);

    // Get the packet
    char outBuf[128];
    JitterBufferPacket getPkt;
    getPkt.data = outBuf;
    getPkt.len = sizeof(outBuf);
    spx_int32_t startOffset = 0;
    int res = jitter_buffer_get(jb, &getPkt, 480, &startOffset);
    TEST_ASSERT_EQ(JITTER_BUFFER_OK, res);

    int ts = jitter_buffer_get_pointer_timestamp(jb);
    TEST_ASSERT_EQ(480, ts);

    jitter_buffer_tick(jb);

    jitter_buffer_destroy(jb);
    std::cout << "  [PASS] testJitterBufferMarginAndTick" << std::endl;
}

} // namespace

void run_jitter_buffer_tests() {
    std::cout << "--- JitterBuffer Tests ---" << std::endl;
    testJitterBufferInitAndDestroy();
    testJitterBufferPutAndGet();
    testJitterBufferOutOfOrderPackets();
    testJitterBufferMarginAndTick();
}
