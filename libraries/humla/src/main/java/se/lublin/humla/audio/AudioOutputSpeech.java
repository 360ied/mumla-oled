/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla.audio;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.Callable;

import se.lublin.humla.audio.javacpp.Opus;
import se.lublin.humla.exception.NativeAudioException;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.model.User;
import se.lublin.humla.net.HumlaUDPMessageType;
import se.lublin.humla.net.PacketBuffer;
import se.lublin.humla.protocol.AudioHandler;

public class AudioOutputSpeech implements Callable<AudioOutputSpeech.Result> {

    interface TalkStateListener {
        void onTalkStateUpdated(int session, TalkState state);
    }

    private final IDecoder mDecoder;
    private final JitterBuffer mJitterBuffer;
    private final Object mJitterLock = new Object();

    private final User mUser;
    private final HumlaUDPMessageType mCodec;
    private int mAudioBufferSize = AudioHandler.FRAME_SIZE;
    private int mRequestedSamples; // Number of samples requested

    // Reusable buffers to avoid allocations in hot loop
    private final ByteBuffer mDirectPacketBuffer = ByteBuffer.allocateDirect(4096);
    private final int[] mPacketInfo = new int[3];

    // State-specific
    private float[] mBuffer;
    private float[] mOut;
    private final float[] mFadeOut;
    private final float[] mFadeIn;
    private final Queue<ByteBuffer> mFrames = new ArrayDeque<>();
    private int mMissCount = 0;
    private boolean mHasTerminator = false;
    private boolean mLastAlive = true;
    private int mBufferFilled, mLastConsume = 0;
    private int ucFlags;

    private final TalkStateListener mTalkStateListener;

    public AudioOutputSpeech(User user, HumlaUDPMessageType codec, int requestedSamples, TalkStateListener listener) throws NativeAudioException {
        mUser = user;
        mCodec = codec;
        mRequestedSamples = requestedSamples;
        mTalkStateListener = listener;

        if (codec != HumlaUDPMessageType.UDPVoiceOpus) {
            throw new NativeAudioException("Unsupported legacy audio codec: " + codec);
        }

        mAudioBufferSize *= 12;
        mDecoder = new Opus.OpusDecoder(AudioHandler.SAMPLE_RATE, 1);

        mBuffer = new float[mAudioBufferSize * 2];
        mOut = new float[mAudioBufferSize];
        mFadeIn = new float[AudioHandler.FRAME_SIZE];
        mFadeOut = new float[AudioHandler.FRAME_SIZE];

        // Sine function to represent fade in/out. Period is FRAME_SIZE.
        float mul = (float) (Math.PI / (2.0 * (float) AudioHandler.FRAME_SIZE));
        for (int i = 0; i < AudioHandler.FRAME_SIZE; i++) {
            mFadeIn[i] = mFadeOut[AudioHandler.FRAME_SIZE - i - 1] = (float) Math.sin((float) i * mul);
        }

        mJitterBuffer = new JitterBuffer(AudioHandler.FRAME_SIZE);
        mJitterBuffer.setMargin(10 * AudioHandler.FRAME_SIZE);
    }

    public void addFrameToBuffer(PacketBuffer pb, byte flags, int seq) {
        if (pb.capacity() < 2 || mCodec != HumlaUDPMessageType.UDPVoiceOpus) {
            return;
        }

        synchronized (mJitterLock) {
            try {
                int samples;
                long header = pb.readLong();
                int size = (int) (header & ((1 << 13) - 1));

                if (size > 0) {
                    byte[] data = pb.dataBlock(size);
                    if (data.length != size) return;

                    int frames = Opus.opus_packet_get_nb_frames(data, size);
                    samples = frames * Opus.opus_packet_get_samples_per_frame(data, AudioHandler.SAMPLE_RATE);
                } else {
                    return;
                }
                pb.rewind();

                int sizeAll = pb.left();
                byte[] dataAll = pb.dataBlock(sizeAll);
                synchronized (mJitterLock) {
                    mJitterBuffer.put(dataAll, sizeAll, AudioHandler.FRAME_SIZE * seq, samples, 0, flags);
                }
            } catch (BufferOverflowException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public Result call() throws Exception {
        if (mBufferFilled - mLastConsume > 0) {
            // Shift over the remaining unconsumed data in the buffer.
            System.arraycopy(mBuffer, mLastConsume, mBuffer, 0, mBufferFilled - mLastConsume);
        }
        mBufferFilled -= mLastConsume;

        mLastConsume = mRequestedSamples;

        if (mBufferFilled >= mRequestedSamples) {
            return new Result(this, mLastAlive, mBuffer, mBufferFilled);
        }

        boolean nextAlive = mLastAlive;

        while (mBufferFilled < mRequestedSamples) {
            int decodedSamples = AudioHandler.FRAME_SIZE;
            resizeBuffer(mBufferFilled + mAudioBufferSize);

            if (!mLastAlive) {
                Arrays.fill(mOut, 0);
            } else {
                int ts;
                float availPackets;
                synchronized (mJitterLock) {
                    ts = mJitterBuffer.getPointerTimestamp();
                    availPackets = (float) mJitterBuffer.getAvailableCount();
                }

                // Make sure we have enough packets in the jitter buffer before decoding
                if (ts == 0) {
                    int want = (int) Math.ceil(mUser.getAverageAvailable());
                    if (availPackets < want) {
                        mMissCount++;
                        if (mMissCount < 20) {
                            Arrays.fill(mOut, 0);
                            System.arraycopy(mOut, 0, mBuffer, mBufferFilled, decodedSamples);
                            mBufferFilled += decodedSamples;
                            continue;
                        }
                    }
                }

                if (mFrames.isEmpty()) {
                    mDirectPacketBuffer.clear();
                    int result;
                    synchronized (mJitterLock) {
                        result = mJitterBuffer.get(mDirectPacketBuffer, mPacketInfo);
                    }

                    if (result == JitterBuffer.JITTER_BUFFER_OK) {
                        int packetLength = mPacketInfo[0];
                        ucFlags = mPacketInfo[1];
                        mDirectPacketBuffer.limit(packetLength);
                        PacketBuffer pb = new PacketBuffer(mDirectPacketBuffer);

                        mMissCount = 0;
                        mHasTerminator = false;
                        try {
                            long header = pb.readLong();
                            int size = (int) (header & ((1 << 13) - 1));
                            mHasTerminator = (header & (1 << 13)) > 0;

                            ByteBuffer audioData = pb.bufferBlock(size);
                            mFrames.add(audioData);
                        } catch (BufferOverflowException | BufferUnderflowException e) {
                            e.printStackTrace();
                        }

                        if (availPackets >= mUser.getAverageAvailable()) {
                            mUser.setAverageAvailable(availPackets);
                        } else {
                            mUser.setAverageAvailable(mUser.getAverageAvailable() * 0.99f);
                        }
                    } else {
                        synchronized (mJitterLock) {
                            mJitterBuffer.updateDelay();
                        }

                        mMissCount++;
                        if (mMissCount > 10) {
                            nextAlive = false;
                        }
                    }
                }

                try {
                    if (!mFrames.isEmpty()) {
                        ByteBuffer data = mFrames.poll();
                        decodedSamples = mDecoder.decodeFloat(data, data.limit(), mOut, mAudioBufferSize);

                        if (mFrames.isEmpty()) {
                            synchronized (mJitterLock) {
                                mJitterBuffer.updateDelay();
                            }
                        }

                        if (mFrames.isEmpty() && mHasTerminator) {
                            nextAlive = false;
                        }
                    } else {
                        decodedSamples = mDecoder.decodeFloat(null, 0, mOut, AudioHandler.FRAME_SIZE);
                    }
                } catch (NativeAudioException e) {
                    e.printStackTrace();
                    decodedSamples = AudioHandler.FRAME_SIZE;
                }

                if (!nextAlive) {
                    for (int i = 0; i < AudioHandler.FRAME_SIZE; i++) {
                        mOut[i] *= mFadeOut[i];
                    }
                } else if (ts == 0) {
                    for (int i = 0; i < AudioHandler.FRAME_SIZE; i++) {
                        mOut[i] *= mFadeIn[i];
                    }
                }

                synchronized (mJitterLock) {
                    for (int i = decodedSamples / AudioHandler.FRAME_SIZE; i > 0; i--) {
                        mJitterBuffer.tick();
                    }
                }
            }

            System.arraycopy(mOut, 0, mBuffer, mBufferFilled, decodedSamples);
            mBufferFilled += decodedSamples;
        }

        if (!nextAlive) ucFlags = 0xFF;

        TalkState talkState;
        switch (ucFlags) {
            case 0:
                talkState = TalkState.TALKING;
                break;
            case 1:
                talkState = TalkState.SHOUTING;
                break;
            case 0xFF:
                talkState = TalkState.PASSIVE;
                break;
            default:
                talkState = TalkState.WHISPERING;
                break;
        }

        mTalkStateListener.onTalkStateUpdated(mUser.getSession(), talkState);

        boolean tmp = mLastAlive;
        mLastAlive = nextAlive;

        return new Result(this, tmp, mBuffer, mRequestedSamples);
    }

    private void resizeBuffer(int newSize) {
        if (newSize > mBuffer.length) {
            mBuffer = Arrays.copyOf(mBuffer, newSize);
        }
    }

    /**
     * Sets the preferred number of samples to return when the callable is executed.
     * @param samples The number of floating point samples to retrieve.
     */
    public void setRequestedSamples(int samples) {
        mRequestedSamples = samples;
    }

    public HumlaUDPMessageType getCodec() {
        return mCodec;
    }

    public User getUser() {
        return mUser;
    }

    public int getSession() {
        return mUser.getSession();
    }

    /**
     * Cleans up all JNI refs linked to this instance.
     */
    public void destroy() {
        if (mDecoder != null) mDecoder.destroy();
        mJitterBuffer.destroy();
    }

    /**
     * The outcome of a decoding pass.
     */
    public static class Result implements IAudioMixerSource<float[]> {
        private final AudioOutputSpeech mSpeechOutput;
        private final boolean mAlive;
        private final float[] mSamples;
        private final int mNumSamples;

        Result(AudioOutputSpeech speechOutput, boolean alive, float[] samples, int numSamples) {
            mSpeechOutput = speechOutput;
            mAlive = alive;
            mSamples = samples;
            mNumSamples = numSamples;
        }

        public AudioOutputSpeech getSpeechOutput() {
            return mSpeechOutput;
        }

        public boolean isAlive() {
            return mAlive;
        }

        @Override
        public float[] getSamples() {
            return mSamples;
        }

        @Override
        public int getNumSamples() {
            return mNumSamples;
        }
    }
}
