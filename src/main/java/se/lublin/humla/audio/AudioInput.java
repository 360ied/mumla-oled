/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
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
    private AudioRecord mAudioRecord;
    private final String mEchoCancellationMethod;
    private AcousticEchoCanceler mAec;
    private NoiseSuppressor mNs;
    private AutomaticGainControl mAgc;

    private Thread mRecordThread;
    private volatile boolean mRecording;

    public AudioInput(AudioInputListener listener, int audioSource, String echoCancellationMethod)
            throws AudioInitializationException {
        mListener = listener;
        mEchoCancellationMethod = echoCancellationMethod != null ? echoCancellationMethod : "none";

        mAudioRecord = setupAudioRecord(audioSource);
        enableAudioEffects();
    }

    public AudioInput(AudioInputListener listener, int audioSource, int sampleRate, String echoCancellationMethod)
            throws AudioInitializationException {
        this(listener, audioSource, echoCancellationMethod);
    }

    private AudioRecord setupAudioRecord(int audioSource) throws AudioInitializationException {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            throw new AudioInitializationException("Invalid buffer size returned for 48kHz audio capture.");
        }

        // Ensure buffer size is at least 4x frame size and aligned
        int bufferSize = Math.max(minBufferSize, FRAME_SIZE * 2 * 4);

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
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new AudioInitializationException("Failed to instantiate AudioRecord: " + e.getMessage(), e);
        }

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new AudioInitializationException("AudioRecord failed to initialize at 48kHz!");
        }

        return record;
    }

    private void enableAudioEffects() {
        if (mAudioRecord == null) return;
        int sessionId = mAudioRecord.getAudioSessionId();

        if ("system".equalsIgnoreCase(mEchoCancellationMethod)) {
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    mAec = AcousticEchoCanceler.create(sessionId);
                    if (mAec != null) {
                        mAec.setEnabled(true);
                        Log.i(TAG, "Hardware Acoustic Echo Cancellation enabled");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to enable hardware AEC: " + e.getMessage());
                }
            } else {
                Log.w(TAG, "Hardware AEC requested but not available on this device");
            }
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                mNs = NoiseSuppressor.create(sessionId);
                if (mNs != null) {
                    mNs.setEnabled(true);
                    Log.i(TAG, "Hardware Noise Suppressor enabled");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to enable hardware NS: " + e.getMessage());
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                mAgc = AutomaticGainControl.create(sessionId);
                if (mAgc != null) {
                    mAgc.setEnabled(true);
                    Log.i(TAG, "Hardware Automatic Gain Control enabled");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to enable hardware AGC: " + e.getMessage());
            }
        }
    }

    public synchronized void startRecording() {
        if (mRecording) return;
        mRecording = true;
        mRecordThread = new Thread(this, "MumlaAudioInput");
        mRecordThread.start();
    }

    public synchronized void stopRecording() {
        if (!mRecording) return;
        mRecording = false;
        if (mRecordThread != null) {
            try {
                mRecordThread.interrupt();
                mRecordThread.join(500);
            } catch (InterruptedException ignored) {
            }
            mRecordThread = null;
        }
    }

    public synchronized void shutdown() {
        stopRecording();
        releaseEffects();
        if (mAudioRecord != null) {
            try {
                if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    mAudioRecord.stop();
                }
                mAudioRecord.release();
            } catch (Exception ignored) {
            }
            mAudioRecord = null;
        }
    }

    private void releaseEffects() {
        if (mAec != null) {
            mAec.release();
            mAec = null;
        }
        if (mNs != null) {
            mNs.release();
            mNs = null;
        }
        if (mAgc != null) {
            mAgc.release();
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

        if (mAudioRecord == null || mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized, capture thread aborting");
            return;
        }

        try {
            mAudioRecord.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to start recording: " + e.getMessage());
            return;
        }

        final short[] buffer = new short[FRAME_SIZE];

        while (mRecording && !Thread.currentThread().isInterrupted()) {
            int read = mAudioRecord.read(buffer, 0, FRAME_SIZE);
            if (read > 0) {
                if (mListener != null) {
                    mListener.onAudioInputReceived(buffer, read);
                }
            } else if (read < 0) {
                Log.e(TAG, "AudioRecord read error: " + read);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        try {
            if (mAudioRecord != null && mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                mAudioRecord.stop();
            }
        } catch (Exception ignored) {
        }
    }

    public interface AudioInputListener {
        void onAudioInputReceived(short[] frame, int frameSize);
    }
}
