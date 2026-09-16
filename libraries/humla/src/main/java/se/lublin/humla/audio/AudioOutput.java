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

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.nio.BufferUnderflowException;

import se.lublin.humla.exception.AudioInitializationException;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.model.User;
import se.lublin.humla.net.HumlaUDPMessageType;
import se.lublin.humla.net.PacketBuffer;
import se.lublin.humla.protobuf.MumbleUDP;
import se.lublin.humla.protocol.AudioHandler;

/**
 * Audio output pipeline.
 *
 * <p>Java owns transport parsing and the {@link AudioTrack} lifecycle.
 * All DSP (jitter buffering, Opus decode, loss concealment, mixing, bus
 * saturation) runs in {@link NativeAudioOutputEngine}. A single render
 * thread pulls mixed PCM and writes it; there is no decode pool, no Java
 * mixer, and the track is never flushed on underrun so gaps do not click.
 */
public class AudioOutput implements Runnable,
        NativeAudioOutputEngine.AudioOutputEngineListener {
    private static final String TAG = AudioOutput.class.getName();

    /** 60 ms render quantum at 48 kHz. */
    private static final int RENDER_SAMPLES = AudioHandler.FRAME_SIZE * 6;

    private final Object mInactiveLock = new Object();
    private final Handler mMainHandler;
    private final AudioOutputListener mListener;

    private NativeAudioOutputEngine mEngine;
    private AudioTrack mAudioTrack;
    private Thread mThread;
    private boolean mRunning = false;

    public AudioOutput(AudioOutputListener listener) {
        mListener = listener;
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    public synchronized Thread startPlaying(int audioStream)
            throws AudioInitializationException {
        if (mThread != null || mRunning) {
            return null;
        }

        final int quantumBytes = RENDER_SAMPLES * 2;
        int minBytes = AudioTrack.getMinBufferSize(AudioHandler.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            minBytes = quantumBytes;
        }
        final int trackBytes = Math.max(minBytes, quantumBytes * 4);
        Log.v(TAG, "Render quantum " + RENDER_SAMPLES + " samples, track "
                + trackBytes + " bytes (system min " + minBytes + ")");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                AudioFormat format = new AudioFormat.Builder()
                        .setSampleRate(AudioHandler.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                AudioTrack.Builder builder = new AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(trackBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setPerformanceMode(
                            AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                }
                mAudioTrack = builder.build();
            } else {
                mAudioTrack = new AudioTrack(audioStream,
                        AudioHandler.SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        trackBytes,
                        AudioTrack.MODE_STREAM);
            }
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new AudioInitializationException(e);
        }
        if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            mAudioTrack.release();
            mAudioTrack = null;
            throw new AudioInitializationException("AudioTrack init failed");
        }

        mEngine = new NativeAudioOutputEngine(this);
        mThread = new Thread(this);
        mThread.start();
        return mThread;
    }

    public void stopPlaying() {
        synchronized (this) {
            if (!mRunning && mThread == null) {
                return;
            }
            mRunning = false;
        }
        synchronized (mInactiveLock) {
            mInactiveLock.notify();
        }
        try {
            mThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            mThread = null;
            if (mAudioTrack != null) {
                try {
                    mAudioTrack.stop();
                } catch (IllegalStateException ignored) {
                }
                mAudioTrack.release();
                mAudioTrack = null;
            }
            if (mEngine != null) {
                mEngine.destroy();
                mEngine = null;
            }
        }
    }

    public synchronized boolean isPlaying() {
        return mRunning;
    }

    @Override
    public void run() {
        Log.v(TAG, "Started thread.");
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        synchronized (this) {
            mRunning = true;
        }
        mAudioTrack.play();

        final short[] mix = new short[RENDER_SAMPLES];
        while (true) {
            synchronized (this) {
                if (!mRunning) {
                    break;
                }
            }
            int rendered = 0;
            NativeAudioOutputEngine engine;
            synchronized (this) {
                engine = mEngine;
            }
            if (engine != null) {
                rendered = engine.render(mix, 0, RENDER_SAMPLES);
            }
            if (rendered > 0) {
                mAudioTrack.write(mix, 0, rendered);
            } else {
                // Nobody is speaking. Keep the track playing so resume is
                // gapless, and idle until the next packet arrives.
                synchronized (mInactiveLock) {
                    try {
                        mInactiveLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        try {
            mAudioTrack.stop();
        } catch (IllegalStateException ignored) {
        }
    }

    public void queueVoiceData(byte[] data, HumlaUDPMessageType messageType) {
        NativeAudioOutputEngine engine;
        synchronized (this) {
            if (!mRunning || mEngine == null) {
                return;
            }
            engine = mEngine;
        }
        if (messageType != HumlaUDPMessageType.UDPVoiceOpus) {
            return;
        }
        try {
            byte msgFlags = (byte) (data[0] & 0x1f);
            PacketBuffer pds = new PacketBuffer(data, data.length);
            pds.skip(1);
            int session = (int) pds.readLong();
            User user = mListener.getUser(session);
            if (user == null || user.isLocalMuted()) {
                return;
            }
            int seq = (int) pds.readLong();
            long header = pds.readLong();
            int size = (int) (header & ((1 << 13) - 1));
            boolean isTerminator = (header & (1 << 13)) != 0;
            if (size <= 0 || size > pds.left()) {
                return;
            }
            byte[] opus = pds.dataBlock(size);
            engine.queuePacket(session, opus, opus.length, seq, msgFlags,
                    isTerminator);
            signalData();
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            Log.v(TAG, "Dropping malformed voice packet", e);
        }
    }

    public void queueProtobufVoiceData(MumbleUDP.Audio audioMsg) {
        NativeAudioOutputEngine engine;
        synchronized (this) {
            if (!mRunning || mEngine == null) {
                return;
            }
            engine = mEngine;
        }
        int session = audioMsg.getSenderSession();
        User user = mListener.getUser(session);
        if (user == null || user.isLocalMuted()) {
            return;
        }
        byte[] opus = audioMsg.getOpusData().toByteArray();
        if (opus.length == 0) {
            return;
        }
        int seq = (int) audioMsg.getFrameNumber();
        byte flags = (byte) (audioMsg.hasContext() ? audioMsg.getContext() : 0);
        engine.queuePacket(session, opus, opus.length, seq, flags,
                audioMsg.getIsTerminator());
        signalData();
    }

    private void signalData() {
        synchronized (mInactiveLock) {
            mInactiveLock.notify();
        }
    }

    @Override
    public void onTalkStateChanged(final int session, final int talkStateOrdinal) {
        final TalkState state;
        switch (talkStateOrdinal) {
            case NativeAudioOutputEngine.TALK_SHOUTING:
                state = TalkState.SHOUTING;
                break;
            case NativeAudioOutputEngine.TALK_PASSIVE:
                state = TalkState.PASSIVE;
                break;
            case NativeAudioOutputEngine.TALK_WHISPERING:
                state = TalkState.WHISPERING;
                break;
            case NativeAudioOutputEngine.TALK_TALKING:
            default:
                state = TalkState.TALKING;
                break;
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                final User user = mListener.getUser(session);
                if (user != null && user.getTalkState() != state) {
                    user.setTalkState(state);
                    mListener.onUserTalkStateUpdated(user);
                }
            }
        });
    }

    public interface AudioOutputListener {
        /**
         * Called when a user's talking state is changed.
         * @param user The user whose talking state has been modified.
         */
        void onUserTalkStateUpdated(User user);

        /**
         * Used to set audio-related user data.
         * @return The user for the associated session.
         */
        User getUser(int session);
    }
}
