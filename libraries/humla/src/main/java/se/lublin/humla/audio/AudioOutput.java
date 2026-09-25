/*
 * Copyright (C) 2014 Andrew Comminos
 * Copyright (C) 2026 Brian Zhu
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
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.nio.BufferUnderflowException;
import java.util.Arrays;

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

    /** 20 ms render quantum at 48 kHz: bounds batching delay on first audio. */
    private static final int RENDER_SAMPLES = AudioHandler.FRAME_SIZE * 2;

    private final Object mInactiveLock = new Object();
    private final Handler mMainHandler;
    private final AudioOutputListener mListener;

    private NativeAudioOutputEngine mEngine;
    private AudioTrack mAudioTrack;
    private Thread mThread;
    private boolean mRunning = false;
    private volatile boolean mHalfDuplexMuted = false;
    private boolean mHasIncomingAudio = false;

    public AudioOutput(AudioOutputListener listener) {
        mListener = listener;
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    public synchronized void startPlaying(int audioStream)
            throws AudioInitializationException {
        if (mThread != null || mRunning) {
            return;
        }
        mHalfDuplexMuted = false;

        final int quantumBytes = RENDER_SAMPLES * 2;
        int minBytes = AudioTrack.getMinBufferSize(AudioHandler.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            minBytes = quantumBytes;
        }
        // Floor of two render quanta (~40 ms) bounds output latency while
        // still satisfying the hardware minimum buffer requirement.
        final int trackBytes = Math.max(minBytes, quantumBytes * 2);
        Log.v(TAG, "Render quantum " + RENDER_SAMPLES + " samples, track "
                + trackBytes + " bytes (system min " + minBytes + ")");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Legacy stream types are superseded by AudioAttributes on
                // API 23+; map voice-call routing explicitly, media otherwise.
                final int usage = (audioStream == AudioManager.STREAM_VOICE_CALL)
                        ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                        : AudioAttributes.USAGE_MEDIA;
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(usage)
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

        try {
            mEngine = new NativeAudioOutputEngine(this);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            mAudioTrack.release();
            mAudioTrack = null;
            throw new AudioInitializationException(e);
        }
        mRunning = true;
        mThread = new Thread(this);
        mThread.start();
    }

    public void stopPlaying() {
        final Thread thread;
        synchronized (this) {
            if (!mRunning && mThread == null) {
                return;
            }
            mRunning = false;
            mHalfDuplexMuted = false;
            thread = mThread;
        }
        synchronized (mInactiveLock) {
            mInactiveLock.notifyAll();
        }
        if (thread != null) {
            // The render thread idles in mInactiveLock.wait() when nobody
            // is speaking. A notify raced with wait entry strands it and
            // the join below blocks forever, so interrupt as well; the
            // wait responds with InterruptedException and run() exits.
            thread.interrupt();
        }
        if (thread != null && thread != Thread.currentThread()) {
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            // Release native resources only once the render thread is dead;
            // a stop requested from the render thread itself defers cleanup
            // to the next call after run() has exited.
            if (mThread != null && mThread.isAlive()) {
                return;
            }
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

    public void setHalfDuplexMuted(boolean muted) {
        mHalfDuplexMuted = muted;
    }

    public boolean isHalfDuplexMuted() {
        return mHalfDuplexMuted;
    }

    @Override
    public void run() {
        Log.v(TAG, "Started thread.");
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        synchronized (this) {
            // Never revive a stopped pipeline: startPlaying already set
            // mRunning before starting the thread.
            if (Thread.currentThread() != mThread || !mRunning) {
                return;
            }
        }
        try {
            mAudioTrack.play();
        } catch (IllegalStateException ignored) {
        }

        // Render-lead pacing: bound how far ahead of the playback head this
        // loop may queue audio. The track's write path only blocks when its
        // entire buffer is full, so on a fresh track — or after an idle
        // drain, or once the engine's startup gate opens — the loop would
        // sprint ahead of the 10 ms packet arrival cadence and drain the
        // jitter buffer's safety margin: every frame the buffer cannot yet
        // serve becomes loss concealment, heard as a buzz under the first
        // syllables of each burst. Pacing against the device's consumption
        // clock mirrors desktop Mumble's pull-model mixer, where the
        // backend asks for exactly the frames it is about to play. The
        // bound still covers the track's minimum buffer so the sink never
        // starves while the loop idles between wakeups.
        final int trackFrames =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? mAudioTrack.getBufferSizeInFrames()
                        : RENDER_SAMPLES * 2; // pre-M: write-blocking bounds
        final Pacer pacer = new Pacer(RENDER_SAMPLES, trackFrames);
        final short[] mix = new short[RENDER_SAMPLES];
        while (true) {
            synchronized (this) {
                if (!mRunning) {
                    break;
                }
            }
            if (mAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    mAudioTrack.play();
                } catch (IllegalStateException ignored) {
                }
            }
            // Block until one more quantum fits inside the lead bound. The
            // 5 ms poll is cheap relative to the 20 ms quantum; the head
            // advances only while the track is fed. When resuming from idle,
            // or if playback stalls (e.g. on underrun or route change),
            // writtenTotal is rebased to prevent deadlock.
            while (true) {
                final int head = mAudioTrack.getPlaybackHeadPosition();
                final Pacer.Action action = pacer.check(head);
                if (action == Pacer.Action.PROCEED) {
                    break;
                }
                if (action == Pacer.Action.STALL_BREAK) {
                    Log.w(TAG, "Playback head stalled at " + (head & 0xFFFFFFFFL)
                            + " for " + (Pacer.MAX_STALL_POLLS * 5)
                            + " ms (writtenTotal=" + pacer.writtenTotal
                            + ", played=" + (pacer.playedWrap + (head & 0xFFFFFFFFL))
                            + "); rebasing render lead");
                    if (mAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        try {
                            mAudioTrack.play();
                        } catch (IllegalStateException ignored) {
                        }
                    }
                    break;
                }
                synchronized (mInactiveLock) {
                    try {
                        mInactiveLock.wait(5);
                    } catch (InterruptedException e) {
                        // stopPlaying interrupts after clearing mRunning;
                        // a stray interrupt just ends pacing for this
                        // quantum instead of busy-waiting on a set flag.
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                synchronized (this) {
                    if (!mRunning) {
                        break;
                    }
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
                if (mHalfDuplexMuted) {
                    Arrays.fill(mix, 0, rendered, (short) 0);
                }
                int offset = 0;
                while (offset < rendered) {
                    int written = mAudioTrack.write(mix, offset, rendered - offset);
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack.write failed: " + written);
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        break;
                    }
                    if (written == 0) {
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    offset += written;
                    pacer.onWritten(written);
                }
            } else {
                pacer.onIdle();
                // No live voice rendered this quantum. Keep the track playing so
                // resume is gapless, and idle until the next packet arrives.
                // When nobody is speaking and zero voices are registered in the
                // native engine, wait indefinitely to eliminate the 50 Hz CPU spin
                // and allow cores to enter deep C-states.
                // If active voices exist (fresh voices filling their startup gate,
                // or wedged voices pending miss expiry), use the 20 ms timed wait
                // to keep paced ticks progressing.
                synchronized (mInactiveLock) {
                    if (mEngine == null || !mEngine.hasActiveVoices()) {
                        while (mRunning && !mHasIncomingAudio && (mEngine == null || !mEngine.hasActiveVoices())) {
                            try {
                                mInactiveLock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } else {
                        try {
                            mInactiveLock.wait(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    mHasIncomingAudio = false;
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
        if (data == null || data.length == 0) {
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
            // Bundled datagrams chain header+payload pairs; each frame
            // consumes sequence seq+i in UDP/protobuf 10 ms frame units.
            int queued = 0;
            while (pds.left() > 0) {
                long header = pds.readLong();
                int size = (int) (header & ((1 << 13) - 1));
                boolean isTerminator = (header & (1 << 13)) != 0;
                if (size == 0 && isTerminator) {
                    engine.queuePacket(session, new byte[0], 0, seq + queued,
                            msgFlags, true);
                    queued++;
                    continue;
                }
                if (size <= 0 || size > pds.left()) {
                    break;
                }
                byte[] opus = pds.dataBlock(size);
                engine.queuePacket(session, opus, opus.length, seq + queued,
                        msgFlags, isTerminator);
                queued++;
            }
            if (queued > 0) {
                signalData();
            }
        } catch (BufferUnderflowException | IllegalArgumentException
                | ArrayIndexOutOfBoundsException e) {
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
        int seq = (int) audioMsg.getFrameNumber();
        byte flags = (byte) (audioMsg.hasContext() ? audioMsg.getContext() : 0);
        boolean isTerminator = audioMsg.getIsTerminator();
        if (opus.length == 0) {
            // A terminator may carry no Opus payload; still forward the marker
            // so the voice drains instead of lingering to the miss-expiry.
            if (isTerminator) {
                engine.queuePacket(session, new byte[0], 0, seq, flags, true);
                signalData();
            }
            return;
        }
        engine.queuePacket(session, opus, opus.length, seq, flags, isTerminator);
        signalData();
    }

    private void signalData() {
        synchronized (mInactiveLock) {
            mHasIncomingAudio = true;
            mInactiveLock.notify();
        }
    }

    @Override
    public void onTalkStateChanged(final int session, final int talkStateOrdinal) {
        final TalkState state;
        switch (talkStateOrdinal) {
            case NativeAudioOutputEngine.TALK_TALKING:
                state = TalkState.TALKING;
                break;
            case NativeAudioOutputEngine.TALK_SHOUTING:
                state = TalkState.SHOUTING;
                break;
            case NativeAudioOutputEngine.TALK_PASSIVE:
                state = TalkState.PASSIVE;
                break;
            case NativeAudioOutputEngine.TALK_WHISPERING:
                state = TalkState.WHISPERING;
                break;
            default:
                Log.w(TAG, "Unknown talk state ordinal: " + talkStateOrdinal);
                state = TalkState.PASSIVE;
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

    public void removeUser(int session) {
        final NativeAudioOutputEngine engine;
        synchronized (this) {
            engine = mEngine;
        }
        if (engine != null) {
            engine.removeUser(session);
        }
    }

    /**
     * Manages render-lead pacing against the AudioTrack playback head.
     * Prevents render loops from out-pacing real-time consumption while
     * eliminating deadlocks caused by underruns, route resets, or idle periods.
     */
    static class Pacer {
        // 40 polls * 5 ms = 200 ms: comfortably exceeds Bluetooth A2DP
        // underrun restart latency (~80-100 ms) while swiftly breaking out
        // of genuine hardware deadlocks.
        static final int MAX_STALL_POLLS = 40;
        final int renderSamples;
        final int maxLeadSamples;
        long writtenTotal = 0L;
        long playedWrap = 0L;
        int lastHead = 0;
        int stallHead = -1;
        int stallCount = 0;
        boolean wasIdle = true;

        enum Action {
            PROCEED,
            WAIT,
            STALL_BREAK
        }

        Pacer(int renderSamples, int trackFrames) {
            this.renderSamples = renderSamples;
            // Bound render lead to at most two quanta (~40 ms) so the loop
            // never outruns the native jitter buffer's margin (40 ms) into
            // large sink buffers (e.g. Bluetooth A2DP 4800-11532 frames),
            // while respecting smaller sink floors.
            this.maxLeadSamples = Math.max(renderSamples,
                    Math.min(renderSamples * 2, Math.max(0, trackFrames)));
        }

        Action check(int head) {
            final long headUnsigned = head & 0xFFFFFFFFL;
            if (headUnsigned < (lastHead & 0xFFFFFFFFL)) {
                if ((lastHead & 0xFFFFFFFFL) >= 0x80000000L) {
                    // Genuine 32-bit playback-head wrap (~24.9 h at 48 kHz).
                    playedWrap += 1L << 32;
                } else {
                    // Spurious head reset (reported on some OEM builds after route changes).
                    writtenTotal = playedWrap + headUnsigned;
                    Log.w(TAG, "Playback head reset detected at "
                            + headUnsigned + "; rebasing render lead");
                }
            }
            lastHead = head;
            final long played = playedWrap + headUnsigned;
            if (writtenTotal < played) {
                writtenTotal = played;
            }
            if (wasIdle) {
                writtenTotal = played;
                wasIdle = false;
                stallHead = -1;
                stallCount = 0;
                return Action.PROCEED;
            }
            if (writtenTotal + renderSamples - played <= maxLeadSamples) {
                stallHead = -1;
                stallCount = 0;
                return Action.PROCEED;
            }
            if (head == stallHead) {
                stallCount++;
                if (stallCount >= MAX_STALL_POLLS) {
                    writtenTotal = played;
                    stallHead = -1;
                    stallCount = 0;
                    return Action.STALL_BREAK;
                }
            } else {
                stallHead = head;
                stallCount = 1;
            }
            return Action.WAIT;
        }

        void onWritten(int written) {
            writtenTotal += written;
        }

        void onIdle() {
            wasIdle = true;
            stallHead = -1;
            stallCount = 0;
        }
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
