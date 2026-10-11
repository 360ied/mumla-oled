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

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import se.lublin.humla.exception.AudioInitializationException;

/**
 * Modern Audio Capture Layer.
 *
 * Captures 16-bit PCM audio natively at 48,000 Hz (10ms frame slices = 480 samples).
 * Uses low-latency audio capture and hardware audio effects.
 */
public class AudioInput implements Runnable {
    private static final String TAG = "AudioInput";

    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SIZE = SAMPLE_RATE / 100; // 480 samples @ 10ms

    private final AudioInputListener mListener;
    private final int mAudioSource;
    private volatile AudioRecord mAudioRecord;
    private volatile AcousticEchoCanceler mAec;
    private volatile NoiseSuppressor mNs;
    private volatile AutomaticGainControl mAgc;

    private Thread mRecordThread;
    private volatile boolean mRecording;

    public AudioInput(AudioInputListener listener, int audioSource)
            throws AudioInitializationException {
        mListener = listener;
        mAudioSource = audioSource;

        mAudioRecord = setupAudioRecord(audioSource);
        try {
            enableAudioEffects();
        } catch (Throwable t) {
            // enableAudioEffects catches Exception per effect, but an Error
            // would otherwise abandon the record and any already-enabled
            // effects. releaseEffects is reentrant and thread-free here.
            try {
                releaseEffects();
            } catch (Throwable ignored) {
            }
            try {
                mAudioRecord.release();
            } catch (Throwable ignored) {
            }
            mAudioRecord = null;
            throw t;
        }
    }

    private AudioRecord setupAudioRecord(int audioSource) throws AudioInitializationException {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            throw new AudioInitializationException("Invalid buffer size returned for 48kHz audio capture.");
        }

        // Ensure buffer size is at least 4x frame size and aligned to a
        // whole number of 10 ms frames (frameBytes = 960 for 16-bit mono).
        int frameBytes = FRAME_SIZE * 2;
        int minWanted = Math.max(minBufferSize, frameBytes * 4);
        int bufferSize = ((minWanted + frameBytes - 1) / frameBytes) * frameBytes;

        AudioRecord record;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();

                record = new AudioRecord.Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .build();
            } else {
                record = new AudioRecord(audioSource, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            }
        } catch (IllegalArgumentException | UnsupportedOperationException | SecurityException e) {
            throw new AudioInitializationException("Failed to instantiate AudioRecord: " + e.getMessage(), e);
        }

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new AudioInitializationException("AudioRecord failed to initialize at 48kHz!");
        }

        return record;
    }

    private synchronized void enableAudioEffects() {
        if (mAudioRecord == null) return;
        int sessionId = mAudioRecord.getAudioSessionId();

        // AEC first: it needs the coupled capture/render loop, which only
        // exists on a voice route (VOICE_COMMUNICATION source +
        // MODE_IN_COMMUNICATION + voice-routed output). On media routes
        // isAvailable() is typically false and creation returns null —
        // both are logged per route so field data tells us where the HAL
        // actually couples. See docs/echo-cancellation/README.md.
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                mAec = AcousticEchoCanceler.create(sessionId);
                if (mAec != null) {
                    int res = mAec.setEnabled(true);
                    if (res == AudioEffect.SUCCESS) {
                        Log.i(TAG, "Hardware AEC enabled (source=" + mAudioSource
                                + " session=" + sessionId + ")");
                    } else {
                        Log.w(TAG, "AEC enable failed (source=" + mAudioSource
                                + " session=" + sessionId + " res=" + res + ")");
                    }
                } else {
                    Log.w(TAG, "AEC unavailable on session " + sessionId
                            + " (source=" + mAudioSource + "): create returned null");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to enable hardware AEC: " + e.getMessage());
            }
        } else {
            Log.i(TAG, "AEC not available on this device (source=" + mAudioSource + ")");
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                mNs = NoiseSuppressor.create(sessionId);
                if (mNs != null) {
                    int res = mNs.setEnabled(true);
                    if (res == AudioEffect.SUCCESS) {
                        Log.i(TAG, "Hardware Noise Suppressor enabled (source=" + mAudioSource
                                + " session=" + sessionId + ")");
                    } else {
                        Log.w(TAG, "NS enable failed (source=" + mAudioSource
                                + " session=" + sessionId + " res=" + res + ")");
                    }
                } else {
                    Log.w(TAG, "NS unavailable on session " + sessionId
                            + " (source=" + mAudioSource + "): create returned null");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to enable hardware NS: " + e.getMessage());
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                mAgc = AutomaticGainControl.create(sessionId);
                if (mAgc != null) {
                    int res = mAgc.setEnabled(true);
                    if (res == AudioEffect.SUCCESS) {
                        Log.i(TAG, "Hardware Automatic Gain Control enabled (source=" + mAudioSource
                                + " session=" + sessionId + ")");
                    } else {
                        Log.w(TAG, "AGC enable failed (source=" + mAudioSource
                                + " session=" + sessionId + " res=" + res + ")");
                    }
                } else {
                    Log.w(TAG, "AGC unavailable on session " + sessionId
                            + " (source=" + mAudioSource + "): create returned null");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to enable hardware AGC: " + e.getMessage());
            }
        }
    }

    public void startRecording() {
        Thread stale;
        synchronized (this) {
            if (mRecording) return;
            stale = (mRecordThread != null && mRecordThread.isAlive()) ? mRecordThread : null;
            if (stale == null) {
                mRecordThread = null;
                mRecording = true;
                mRecordThread = new Thread(this, "MumlaAudioInput");
                mRecordThread.start();
                return;
            }
        }
        // Join outside the monitor: the capture thread needs this monitor
        // for enable/releaseEffects, and must never be blocked by a join.
        boolean interrupted = false;
        try {
            stale.interrupt();
            stale.join(200);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        synchronized (this) {
            if (stale.isAlive()) {
                Log.w(TAG, "Previous capture thread still alive after timeout, postponing startRecording");
                if (interrupted) Thread.currentThread().interrupt();
                return;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (mRecording) return;
            mRecordThread = null;
            mRecording = true;
            mRecordThread = new Thread(this, "MumlaAudioInput");
            mRecordThread.start();
        }
    }

    public void stopRecording() {
        AudioRecord record;
        Thread thread;
        synchronized (this) {
            if (!mRecording) return;
            mRecording = false;
            record = mAudioRecord;
            thread = mRecordThread;
        }
        // Blocking IPC (stop) and signaling run outside the monitor.
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (Exception ignored) {
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void shutdown() {
        Thread thread;
        AudioRecord recordToStop;
        synchronized (this) {
            mRecording = false;
            thread = mRecordThread;
            recordToStop = mAudioRecord;
        }
        // Unblock a thread parked in blocking read() first: interrupt()
        // alone does not wake AudioRecord.read(). Mirrors stopRecording.
        if (recordToStop != null) {
            try {
                if (recordToStop.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recordToStop.stop();
                }
            } catch (Exception ignored) {
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
        // Join outside the monitor for the same reason as startRecording.
        if (thread != null) {
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
        AudioRecord record;
        synchronized (this) {
            mRecordThread = null;
            record = mAudioRecord;
            mAudioRecord = null;
        }
        releaseEffects();
        if (record != null) {
            try {
                record.release();
            } catch (Exception ignored) {
            }
        }
    }

    private synchronized void releaseEffects() {
        // Throwable per effect: one failing release (even fatal) must not
        // abandon the remaining native handles.
        if (mAec != null) {
            try {
                mAec.release();
            } catch (Throwable ignored) {
            }
            mAec = null;
        }
        if (mNs != null) {
            try {
                mNs.release();
            } catch (Throwable ignored) {
            }
            mNs = null;
        }
        if (mAgc != null) {
            try {
                mAgc.release();
            } catch (Throwable ignored) {
            }
            mAgc = null;
        }
    }

    public boolean isRecording() {
        return mRecording;
    }

    public int getSampleRate() {
        return SAMPLE_RATE;
    }

    public int getFrameSize() {
        return FRAME_SIZE;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        // Shutdown may have landed between startRecording and this thread's
        // first instruction: never create a record that nobody will drain.
        if (!mRecording) {
            return;
        }
        if (mAudioRecord == null || mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            try {
                releaseEffects();
                AudioRecord staleRecord = mAudioRecord;
                mAudioRecord = null;
                if (staleRecord != null) {
                    try {
                        staleRecord.release();
                    } catch (Exception ignored) {
                    }
                }
                if (!mRecording || Thread.currentThread().isInterrupted()) {
                    mRecording = false;
                    return;
                }
                mAudioRecord = setupAudioRecord(mAudioSource);
                if (!mRecording || Thread.currentThread().isInterrupted()) {
                    // shutdown() raced preamble creation: release the
                    // just-created record instead of leaking the mic.
                    AudioRecord fresh = mAudioRecord;
                    mAudioRecord = null;
                    releaseEffects();
                    if (fresh != null) {
                        try {
                            fresh.release();
                        } catch (Exception ignored) {
                        }
                    }
                    mRecording = false;
                    return;
                }
                enableAudioEffects();
            } catch (AudioInitializationException e) {
                Log.e(TAG, "AudioRecord initialization failed, capture thread aborting: " + e.getMessage());
                mRecording = false;
                return;
            } catch (Throwable t) {
                // An Error must not strand mRecording on a dead thread:
                // clear the flag so a later start can recover, then rethrow
                // with a breadcrumb (sibling Exception paths all log).
                Log.e(TAG, "Capture thread fatal error: " + t);
                mRecording = false;
                throw t;
            }
        }

        try {
            AudioRecord record = mAudioRecord;
            if (record != null) {
                record.startRecording();
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "Failed to start recording, recreating AudioRecord: " + e.getMessage());
            try {
                releaseEffects();
                AudioRecord staleRecord = mAudioRecord;
                mAudioRecord = null;
                if (staleRecord != null) {
                    try {
                        staleRecord.release();
                    } catch (Exception ignored) {
                    }
                }
                // Same shutdown guards as the preamble above: a racing
                // shutdown must not resurrect capture on this path either.
                if (!mRecording || Thread.currentThread().isInterrupted()) {
                    mRecording = false;
                    return;
                }
                mAudioRecord = setupAudioRecord(mAudioSource);
                if (!mRecording || Thread.currentThread().isInterrupted()) {
                    AudioRecord fresh = mAudioRecord;
                    mAudioRecord = null;
                    releaseEffects();
                    if (fresh != null) {
                        try {
                            fresh.release();
                        } catch (Exception ignored) {
                        }
                    }
                    mRecording = false;
                    return;
                }
                enableAudioEffects();
                mAudioRecord.startRecording();
            } catch (Exception ex) {
                Log.e(TAG, "AudioRecord recreation failed: " + ex.getMessage());
                mRecording = false;
                return;
            } catch (Throwable t) {
                Log.e(TAG, "Capture thread fatal error: " + t);
                mRecording = false;
                throw t;
            }
        } catch (Throwable t) {
            // Non-ISE throwables from the initial start (the ISE path with
            // its recreation is above): free the flag with a breadcrumb.
            Log.e(TAG, "Capture thread fatal error: " + t);
            mRecording = false;
            throw t;
        }

        final short[] buffer = new short[FRAME_SIZE];
        int consecutiveReadErrors = 0;

        try {
            while (mRecording && !Thread.currentThread().isInterrupted()) {
                AudioRecord record = mAudioRecord;
                if (record == null) {
                    break;
                }
                int read = record.read(buffer, 0, FRAME_SIZE);
                if (read > 0) {
                    consecutiveReadErrors = 0;
                    if (mListener != null) {
                        mListener.onAudioInputReceived(buffer, read);
                    }
                } else if (read == 0) {
                    consecutiveReadErrors = 0;
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        break;
                    }
                } else {
                    consecutiveReadErrors++;
                    if (consecutiveReadErrors == 1 || consecutiveReadErrors % 50 == 0) {
                        Log.e(TAG, "AudioRecord read error: " + read
                                + " (consecutive=" + consecutiveReadErrors + ")");
                    }
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        Log.e(TAG, "AudioRecord dead object, recreating capture");
                        try {
                            releaseEffects();
                            AudioRecord stale = mAudioRecord;
                            mAudioRecord = null;
                            if (stale != null) {
                                try {
                                    stale.release();
                                } catch (Exception ignored) {
                                }
                            }
                            if (!mRecording || Thread.currentThread().isInterrupted()) {
                                mRecording = false;
                                break;
                            }
                            mAudioRecord = setupAudioRecord(mAudioSource);
                            enableAudioEffects();
                            mAudioRecord.startRecording();
                            consecutiveReadErrors = 0;
                        } catch (Exception ex) {
                            Log.e(TAG, "AudioRecord recreation after dead object failed: " + ex.getMessage());
                            mRecording = false;
                            break;
                        } catch (Throwable t) {
                            Log.e(TAG, "Capture thread fatal error: " + t);
                            mRecording = false;
                            throw t;
                        }
                        continue;
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception in AudioInput capture loop: " + e.getMessage());
            mRecording = false;
        } catch (Throwable t) {
            Log.e(TAG, "Capture thread fatal error: " + t);
            mRecording = false;
            throw t;
        }

        try {
            AudioRecord record = mAudioRecord;
            if (record != null && record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (Exception ignored) {
        }
    }

    public interface AudioInputListener {
        /**
         * Delivered on the capture thread with a shared reused buffer:
         * the callee must copy before returning, and must handle
         * partial reads (frameSize &lt; FRAME_SIZE).
         */
        void onAudioInputReceived(short[] frame, int frameSize);
    }
}
