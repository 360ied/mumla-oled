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

package se.lublin.humla.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import se.lublin.humla.exception.AudioInitializationException

/**
 * Modern Audio Capture Layer.
 *
 * Captures 16-bit PCM audio natively at 48,000 Hz (10ms frame slices = 480 samples).
 * Uses low-latency audio capture and hardware audio effects.
 */
class AudioInput : Runnable {
    companion object {
        private val TAG: String = AudioInput::class.java.name

        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = SAMPLE_RATE / 100 // 480 samples @ 10ms
    }

    private val listener: AudioInputListener?
    private val audioSource: Int

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var aec: AcousticEchoCanceler? = null

    @Volatile
    private var ns: NoiseSuppressor? = null

    @Volatile
    private var agc: AutomaticGainControl? = null

    private var recordThread: Thread? = null

    @Volatile
    private var recording: Boolean = false

    @Throws(AudioInitializationException::class)
    constructor(listener: AudioInputListener?, audioSource: Int) {
        this.listener = listener
        this.audioSource = audioSource

        audioRecord = setupAudioRecord(audioSource)
        try {
            enableAudioEffects()
        } catch (t: Throwable) {
            // enableAudioEffects catches Exception per effect, but an Error
            // would otherwise abandon the record and any already-enabled
            // effects. releaseEffects is reentrant and thread-free here.
            try {
                releaseEffects()
            } catch (ignored: Throwable) {
            }
            try {
                audioRecord!!.release()
            } catch (ignored: Throwable) {
            }
            audioRecord = null
            throw t
        }
    }

    @Throws(AudioInitializationException::class)
    private fun setupAudioRecord(audioSource: Int): AudioRecord {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            throw AudioInitializationException("Invalid buffer size returned for 48kHz audio capture.")
        }

        // Ensure buffer size is at least 4x frame size and aligned to a
        // whole number of 10 ms frames (frameBytes = 960 for 16-bit mono).
        val frameBytes = FRAME_SIZE * 2
        val minWanted = Math.max(minBufferSize, frameBytes * 4)
        val bufferSize = (minWanted + frameBytes - 1) / frameBytes * frameBytes

        val record: AudioRecord
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            record = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: IllegalArgumentException) {
            throw AudioInitializationException("Failed to instantiate AudioRecord: " + e.message, e)
        } catch (e: UnsupportedOperationException) {
            throw AudioInitializationException("Failed to instantiate AudioRecord: " + e.message, e)
        } catch (e: SecurityException) {
            throw AudioInitializationException("Failed to instantiate AudioRecord: " + e.message, e)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioInitializationException("AudioRecord failed to initialize at 48kHz!")
        }

        return record
    }

    @Synchronized
    private fun enableAudioEffects() {
        if (audioRecord == null) return
        val sessionId = audioRecord!!.audioSessionId

        // AEC first: it needs the coupled capture/render loop, which only
        // exists on a voice route (VOICE_COMMUNICATION source +
        // MODE_IN_COMMUNICATION + voice-routed output). On media routes
        // isAvailable() is typically false and creation returns null —
        // both are logged per route so field data tells us where the HAL
        // actually couples. See docs/echo-cancellation/README.md.
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(sessionId)
                if (aec != null) {
                    val res = aec!!.setEnabled(true)
                    if (res == AudioEffect.SUCCESS) {
                        Log.i(TAG, "Hardware AEC enabled (source=" + audioSource + " session=" + sessionId + ")")
                    } else {
                        Log.w(TAG, "AEC enable failed (source=" + audioSource + " session=" + sessionId + " res=" + res + ")")
                    }
                } else {
                    Log.i(TAG, "AEC unavailable on session " + sessionId + " (source=" + audioSource + "): create returned null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable hardware AEC: " + e.message)
            }
        } else {
            Log.i(TAG, "AEC not available on this device (source=" + audioSource + ")")
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                ns = NoiseSuppressor.create(sessionId)
                if (ns != null) {
                    val res = ns!!.setEnabled(true)
                    if (res == AudioEffect.SUCCESS) {
                        Log.i(TAG, "Hardware Noise Suppressor enabled (source=" + audioSource + " session=" + sessionId + ")")
                    } else {
                        Log.w(TAG, "NS enable failed (source=" + audioSource + " session=" + sessionId + " res=" + res + ")")
                    }
                } else {
                    Log.i(TAG, "NS unavailable on session " + sessionId + " (source=" + audioSource + "): create returned null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable hardware NS: " + e.message)
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(sessionId)
                if (agc != null) {
                    val res = agc!!.setEnabled(true)
                    if (res == AudioEffect.SUCCESS) {
                        Log.i(TAG, "Hardware Automatic Gain Control enabled (source=" + audioSource + " session=" + sessionId + ")")
                    } else {
                        Log.w(TAG, "AGC enable failed (source=" + audioSource + " session=" + sessionId + " res=" + res + ")")
                    }
                } else {
                    Log.i(TAG, "AGC unavailable on session " + sessionId + " (source=" + audioSource + "): create returned null")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable hardware AGC: " + e.message)
            }
        }
    }

    fun startRecording() {
        val stale: Thread = synchronized(this) {
            if (recording) return
            val current = recordThread
            val s = if (current != null && current.isAlive) current else null
            if (s == null) {
                recordThread = null
                recording = true
                val thread = Thread(this, "MumlaAudioInput")
                recordThread = thread
                thread.start()
                return
            }
            s
        }
        // Join outside the monitor: the capture thread needs this monitor
        // for enable/releaseEffects, and must never be blocked by a join.
        var interrupted = false
        try {
            stale.interrupt()
            stale.join(200)
        } catch (e: InterruptedException) {
            interrupted = true
        }
        synchronized(this) {
            if (stale.isAlive) {
                Log.w(TAG, "Previous capture thread still alive after timeout, postponing startRecording")
                if (interrupted) Thread.currentThread().interrupt()
                return
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
            if (recording) return
            recordThread = null
            recording = true
            val thread = Thread(this, "MumlaAudioInput")
            recordThread = thread
            thread.start()
        }
    }

    fun stopRecording() {
        val record: AudioRecord?
        val thread: Thread?
        synchronized(this) {
            if (!recording) return
            recording = false
            record = audioRecord
            thread = recordThread
        }
        // Blocking IPC (stop) and signaling run outside the monitor.
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (ignored: Throwable) {
            }
        }
        thread?.interrupt()
    }

    fun shutdown() {
        val thread: Thread?
        val recordToStop: AudioRecord?
        synchronized(this) {
            recording = false
            thread = recordThread
            recordToStop = audioRecord
        }
        // Unblock a thread parked in blocking read() first: interrupt()
        // alone does not wake AudioRecord.read(). Mirrors stopRecording.
        if (recordToStop != null) {
            try {
                if (recordToStop.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recordToStop.stop()
                }
            } catch (ignored: Throwable) {
            }
        }
        thread?.interrupt()
        // Join outside the monitor for the same reason as startRecording.
        if (thread != null) {
            var interrupted = false
            while (thread.isAlive) {
                try {
                    thread.join()
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
        val record: AudioRecord?
        synchronized(this) {
            recordThread = null
            record = audioRecord
            audioRecord = null
        }
        releaseEffects()
        if (record != null) {
            try {
                record.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    @Synchronized
    private fun releaseEffects() {
        // Throwable per effect: one failing release (even fatal) must not
        // abandon the remaining native handles.
        if (aec != null) {
            try {
                aec!!.release()
            } catch (ignored: Throwable) {
            }
            aec = null
        }
        if (ns != null) {
            try {
                ns!!.release()
            } catch (ignored: Throwable) {
            }
            ns = null
        }
        if (agc != null) {
            try {
                agc!!.release()
            } catch (ignored: Throwable) {
            }
            agc = null
        }
    }

    fun isRecording(): Boolean {
        return recording
    }

    fun getSampleRate(): Int {
        return SAMPLE_RATE
    }

    fun getFrameSize(): Int {
        return FRAME_SIZE
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // Shutdown may have landed between startRecording and this thread's
        // first instruction: never create a record that nobody will drain.
        if (!recording) {
            return
        }
        if (audioRecord == null || audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
            try {
                releaseEffects()
                val staleRecord = audioRecord
                audioRecord = null
                if (staleRecord != null) {
                    try {
                        staleRecord.release()
                    } catch (ignored: Throwable) {
                    }
                }
                if (!recording || Thread.currentThread().isInterrupted) {
                    recording = false
                    return
                }
                audioRecord = setupAudioRecord(audioSource)
                if (!recording || Thread.currentThread().isInterrupted) {
                    // shutdown() raced preamble creation: release the
                    // just-created record instead of leaking the mic.
                    val fresh = audioRecord
                    audioRecord = null
                    releaseEffects()
                    if (fresh != null) {
                        try {
                            fresh.release()
                        } catch (ignored: Throwable) {
                        }
                    }
                    recording = false
                    return
                }
                enableAudioEffects()
            } catch (e: AudioInitializationException) {
                Log.e(TAG, "AudioRecord initialization failed, capture thread aborting: " + e.message)
                recording = false
                return
            } catch (t: Throwable) {
                // An Error must not strand recording on a dead thread:
                // clear the flag so a later start can recover, then rethrow
                // with a breadcrumb (sibling Exception paths all log).
                Log.e(TAG, "Capture thread fatal error: " + t)
                recording = false
                throw t
            }
        }

        try {
            val record = audioRecord
            if (record != null) {
                record.startRecording()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Failed to start recording, recreating AudioRecord: " + e.message)
            try {
                releaseEffects()
                val staleRecord = audioRecord
                audioRecord = null
                if (staleRecord != null) {
                    try {
                        staleRecord.release()
                    } catch (ignored: Throwable) {
                    }
                }
                // Same shutdown guards as the preamble above: a racing
                // shutdown must not resurrect capture on this path either.
                if (!recording || Thread.currentThread().isInterrupted) {
                    recording = false
                    return
                }
                audioRecord = setupAudioRecord(audioSource)
                if (!recording || Thread.currentThread().isInterrupted) {
                    val fresh = audioRecord
                    audioRecord = null
                    releaseEffects()
                    if (fresh != null) {
                        try {
                            fresh.release()
                        } catch (ignored: Throwable) {
                        }
                    }
                    recording = false
                    return
                }
                enableAudioEffects()
                audioRecord!!.startRecording()
            } catch (ex: Exception) {
                Log.e(TAG, "AudioRecord recreation failed: " + ex.message)
                recording = false
                return
            } catch (t: Throwable) {
                Log.e(TAG, "Capture thread fatal error: " + t)
                recording = false
                throw t
            }
        } catch (t: Throwable) {
            // Non-ISE throwables from the initial start (the ISE path with
            // its recreation is above): free the flag with a breadcrumb.
            Log.e(TAG, "Capture thread fatal error: " + t)
            recording = false
            throw t
        }

        val buffer = ShortArray(FRAME_SIZE)
        var consecutiveReadErrors = 0

        try {
            while (recording && !Thread.currentThread().isInterrupted) {
                val record = audioRecord
                if (record == null) {
                    break
                }
                val read = record.read(buffer, 0, FRAME_SIZE)
                if (read > 0) {
                    consecutiveReadErrors = 0
                    val listenerCopy = listener
                    if (listenerCopy != null) {
                        listenerCopy.onAudioInputReceived(buffer, read)
                    }
                } else if (read == 0) {
                    consecutiveReadErrors = 0
                    try {
                        Thread.sleep(2)
                    } catch (e: InterruptedException) {
                        break
                    }
                } else {
                    consecutiveReadErrors++
                    if (consecutiveReadErrors == 1 || consecutiveReadErrors % 50 == 0) {
                        Log.e(TAG, "AudioRecord read error: " + read + " (consecutive=" + consecutiveReadErrors + ")")
                    }
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        Log.e(TAG, "AudioRecord dead object, recreating capture")
                        try {
                            releaseEffects()
                            val stale = audioRecord
                            audioRecord = null
                            if (stale != null) {
                                try {
                                    stale.release()
                                } catch (ignored: Throwable) {
                                }
                            }
                            if (!recording || Thread.currentThread().isInterrupted) {
                                recording = false
                                break
                            }
                            audioRecord = setupAudioRecord(audioSource)
                            enableAudioEffects()
                            audioRecord!!.startRecording()
                            consecutiveReadErrors = 0
                        } catch (ex: Exception) {
                            Log.e(TAG, "AudioRecord recreation after dead object failed: " + ex.message)
                            recording = false
                            break
                        } catch (t: Throwable) {
                            Log.e(TAG, "Capture thread fatal error: " + t)
                            recording = false
                            throw t
                        }
                        continue
                    }
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception in AudioInput capture loop: " + e.message)
            recording = false
        } catch (t: Throwable) {
            Log.e(TAG, "Capture thread fatal error: " + t)
            recording = false
            throw t
        }

        try {
            val record = audioRecord
            if (record != null && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (ignored: Throwable) {
        }
    }

    interface AudioInputListener {
        /**
         * Delivered on the capture thread with a shared reused buffer:
         * the callee must copy before returning, and must handle
         * partial reads (frameSize &lt; FRAME_SIZE).
         */
        fun onAudioInputReceived(frame: ShortArray?, frameSize: Int)
    }
}
