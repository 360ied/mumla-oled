/*
 * Copyright (C) 2026 Mumla Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.lublin.humla.audio;

/**
 * Pre-Speech Lookahead Ring Buffer.
 *
 * Retains 80ms (8 frames @ 10ms) of pre-speech audio during silence.
 * Upon speech detection, all buffered frames are flushed in chronological FIFO order
 * before transmitting active speech, completely eliminating onset consonant clipping.
 */
public class PreSpeechRingBuffer {
    public static final int DEFAULT_CAPACITY_FRAMES = 8; // 8 * 10ms = 80ms
    public static final int DEFAULT_FRAME_SIZE = 480;      // 10ms @ 48kHz

    private final short[][] mStorage;
    private final int mCapacity;
    private final int mFrameSize;
    private int mHead;
    private int mCount;

    public PreSpeechRingBuffer() {
        this(DEFAULT_CAPACITY_FRAMES, DEFAULT_FRAME_SIZE);
    }

    public PreSpeechRingBuffer(int frameCapacity, int frameSize) {
        mCapacity = frameCapacity > 0 ? frameCapacity : DEFAULT_CAPACITY_FRAMES;
        mFrameSize = frameSize > 0 ? frameSize : DEFAULT_FRAME_SIZE;
        mStorage = new short[mCapacity][mFrameSize];
        mHead = 0;
        mCount = 0;
    }

    public synchronized void push(short[] pcm, int sampleCount) {
        if (pcm == null || sampleCount <= 0) {
            return;
        }
        int copyCount = Math.min(sampleCount, mFrameSize);
        System.arraycopy(pcm, 0, mStorage[mHead], 0, copyCount);
        if (copyCount < mFrameSize) {
            for (int i = copyCount; i < mFrameSize; i++) {
                mStorage[mHead][i] = 0;
            }
        }
        mHead = (mHead + 1) % mCapacity;
        if (mCount < mCapacity) {
            mCount++;
        }
    }

    public synchronized void flush(AudioConsumer consumer) {
        if (mCount == 0 || consumer == null) {
            return;
        }
        int startIndex = (mHead - mCount + mCapacity) % mCapacity;
        for (int i = 0; i < mCount; i++) {
            int index = (startIndex + i) % mCapacity;
            consumer.accept(mStorage[index], mFrameSize);
        }
        mCount = 0;
    }

    public synchronized void clear() {
        mCount = 0;
        mHead = 0;
    }

    public synchronized int getCount() {
        return mCount;
    }

    public int getCapacity() {
        return mCapacity;
    }

    public int getFrameSize() {
        return mFrameSize;
    }

    public interface AudioConsumer {
        void accept(short[] pcm, int length);
    }
}
