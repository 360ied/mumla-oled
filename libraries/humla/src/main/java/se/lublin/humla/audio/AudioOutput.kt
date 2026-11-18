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

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.Arrays
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.voice.TalkActivityPublisher
import kotlin.math.max
import kotlin.math.min

/**
 * Audio output pipeline (step 5a.3b, spec-literal).
 *
 * Owns only the AudioTrack lifecycle and the render thread. The voice
 * engine (output engine, rings, policy, socket, notifier) is
 * application-scoped in the VoiceEngine facade; render drives it through
 * [RenderDriver] but owns nothing. Parsing, sender filtering, and removals
 * are native; talk state travels as levels through the service-lifetime
 * publisher. There is no decode pool, no Java mixer, and the track is never
 * flushed on underrun so gaps do not click.
 */
class AudioOutput : Runnable, OutputPipeline {
    companion object {
        private val TAG: String = AudioOutput::class.java.name

        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = SAMPLE_RATE / 100 // 480 samples @ 10ms

        /** 20 ms render quantum at 48 kHz: bounds batching delay on first audio. */
        private const val RENDER_SAMPLES = FRAME_SIZE * 2
    }

    private val renderDriver: RenderDriver
    private val talkPublisher: TalkActivityPublisher

    @Volatile
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var halfDuplexMuted = false

    constructor(renderDriver: RenderDriver, talkPublisher: TalkActivityPublisher) : this(null, renderDriver, talkPublisher)

    constructor(context: Context?, renderDriver: RenderDriver, talkPublisher: TalkActivityPublisher) {
        this.renderDriver = renderDriver
        this.talkPublisher = talkPublisher
        // NB: context is accepted for API compatibility but intentionally unused.
    }

    @Throws(AudioInitializationException::class)
    @Synchronized
    override fun startPlaying(audioStream: Int) {
        if (thread != null || running) {
            return
        }
        halfDuplexMuted = false

        val quantumBytes = RENDER_SAMPLES * 2
        var minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) {
            minBytes = quantumBytes
        }
        // Floor of two render quanta (~40 ms) bounds output latency while
        // still satisfying the hardware minimum buffer requirement.
        val trackBytes = max(minBytes, quantumBytes * 2)
        Log.v(TAG, "Render quantum " + RENDER_SAMPLES + " samples, track " + trackBytes + " bytes (system min " + minBytes + ")")

        try {
            // Legacy stream types are superseded by AudioAttributes;
            // map voice-call routing explicitly, media otherwise.
            val usage = if (audioStream == AudioManager.STREAM_VOICE_CALL) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }
            val attributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(trackBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            builder.setPerformanceMode(
                AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            )
            audioTrack = builder.build()
        } catch (e: IllegalArgumentException) {
            throw AudioInitializationException(e)
        } catch (e: UnsupportedOperationException) {
            throw AudioInitializationException(e)
        }
        val track = audioTrack ?: throw NullPointerException("AudioTrack")
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            audioTrack = null
            throw AudioInitializationException("AudioTrack init failed")
        }

        running = true
        thread = Thread(this)
        val renderThread = thread ?: throw NullPointerException("render thread")
        renderThread.start()
    }

    /**
     * Stops playback and releases the track. Safe from any thread
     * except the render thread itself: a self-stop defers cleanup to the
     * next external call after run() exits (see below). In-tree callers
     * always stop from the service thread. The shared engine is untouched:
     * VoiceEngine owns it across sessions; the publisher clear below runs
     * after the render join, and the version-gated notifier cannot
     * resurrect cleared levels without new renders.
     */
    override fun stopPlaying() {
        val thread: Thread?
        synchronized(this) {
            if (!running && this.thread == null) {
                // Never started (or already stopped): no thread to join, but
                // the service-lifetime publisher may still hold levels from
                // a previous pipeline — leave no stale indicator behind.
                // update is lock-free, safe here.
                talkPublisher.clearRemote()
                return
            }
            running = false
            halfDuplexMuted = false
            thread = this.thread
        }
        // Wake the render semaphore: sem_post is remembered across a racing
        // wait entry, so no interrupt is needed (5a.3b render wake).
        renderDriver.wakeForStop()
        if (thread != null && thread != Thread.currentThread()) {
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
        synchronized(this) {
            // Release native resources only once the render thread is dead;
            // a stop requested from the render thread itself must not be
            // issued here (no join-from-self, no release under a live loop):
            // it defers cleanup to the next external call after run() exits.
            // In-tree VoiceEngine.tearDownPipeline always stops from the service
            // thread, so this path is a future-caller API trap only.
            val renderThread = this.thread
            if (renderThread != null && renderThread.isAlive) {
                return
            }
            this.thread = null
            val track = audioTrack
            if (track != null) {
                try {
                    track.stop()
                } catch (ignored: IllegalStateException) {
                }
                track.release()
                audioTrack = null
            }
            // The output engine is shared: VoiceEngine owns and resets it.
            // Clear remote levels so no snapshot from the old pipeline can
            // light an indicator afterwards.
            talkPublisher.clearRemote()
        }
    }

    @Synchronized
    fun isPlaying(): Boolean {
        return running
    }

    fun setHalfDuplexMuted(muted: Boolean) {
        halfDuplexMuted = muted
    }

    fun isHalfDuplexMuted(): Boolean {
        return halfDuplexMuted
    }

    override fun run() {
        Log.v(TAG, "Started thread.")
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // Fetch the track under the same guard that validates the thread:
        // stopPlaying() nulls audioTrack between a detached check and fetch
        // otherwise, NPEing the render thread (dead audio, not a crash).
        val track = synchronized(this) {
            // Never revive a stopped pipeline: startPlaying already set
            // running before starting the thread.
            if (Thread.currentThread() != thread || !running) {
                return
            }
            audioTrack
        } ?: throw NullPointerException("AudioTrack")
        try {
            track.play()
        } catch (ignored: IllegalStateException) {
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
        val trackFrames = track.bufferSizeInFrames
        val pacer = Pacer(RENDER_SAMPLES, trackFrames)
        val mix = ShortArray(RENDER_SAMPLES)
        renderLoop@ while (true) {
            synchronized(this) {
                if (!running) {
                    break
                }
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    track.play()
                } catch (ignored: IllegalStateException) {
                }
            }
            // Block until one more quantum fits inside the lead bound. The
            // 5 ms poll is cheap relative to the 20 ms quantum; the head
            // advances only while the track is fed. When resuming from idle,
            // or if playback stalls (e.g. on underrun or route change),
            // writtenTotal is rebased to prevent deadlock.
            while (true) {
                val head = track.playbackHeadPosition
                val action = pacer.check(head)
                if (action == Pacer.Action.PROCEED) {
                    break
                }
                if (action == Pacer.Action.STALL_BREAK) {
                    Log.w(
                        TAG, "Playback head stalled at " + (head.toLong() and 0xFFFFFFFFL) +
                            " for " + (Pacer.MAX_STALL_POLLS * 5) +
                            " ms (writtenTotal=" + pacer.writtenTotal +
                            ", played=" + (pacer.playedWrap + (head.toLong() and 0xFFFFFFFFL)) +
                            "); rebasing render lead"
                    )
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        try {
                            track.play()
                        } catch (ignored: IllegalStateException) {
                        }
                    }
                    break
                }
                try {
                    Thread.sleep(5)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                synchronized(this) {
                    if (!running) {
                        break
                    }
                }
            }
            val rendered = renderDriver.render(mix, 0, RENDER_SAMPLES)
            if (rendered > 0) {
                if (halfDuplexMuted) {
                    Arrays.fill(mix, 0, rendered, 0.toShort())
                }
                var offset = 0
                while (offset < rendered) {
                    val written = track.write(mix, offset, rendered - offset)
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack.write failed: " + written)
                        try {
                            Thread.sleep(10)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break@renderLoop
                        }
                        break
                    }
                    if (written == 0) {
                        try {
                            Thread.sleep(2)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break@renderLoop
                        }
                        continue
                    }
                    offset += written
                    pacer.onWritten(written)
                }
            } else {
                pacer.onIdle()
                // No live voice rendered this quantum. Keep the track playing so
                // resume is gapless, and idle in the native semaphore until the
                // next packet arrives (5a.3b render wake). No timeout when
                // nobody is speaking and zero voices are registered in the
                // native engine (eliminates the 50 Hz CPU spin); 20 ms when
                // active voices exist (startup gate or miss expiry) to keep
                // paced ticks progressing.
                // The last render already folded liveness; no separate engine
                // call.
                val hasVoices = renderDriver.lastHasActiveVoices()
                renderDriver.awaitWork(if (hasVoices) 20 else -1)
            }
        }

        try {
            track.stop()
        } catch (ignored: IllegalStateException) {
        }
    }

    /**
     * Manages render-lead pacing against the AudioTrack playback head.
     * Prevents render loops from out-pacing real-time consumption while
     * eliminating deadlocks caused by underruns, route resets, or idle periods.
     * Widened to public: Kotlin has no package visibility (was package-private
     * for the JVM pacer test).
     */
    class Pacer(renderSamples: Int, trackFrames: Int) {
        companion object {
            // 40 polls * 5 ms = 200 ms: comfortably exceeds Bluetooth A2DP
            // underrun restart latency (~80-100 ms) while swiftly breaking out
            // of genuine hardware deadlocks.
            const val MAX_STALL_POLLS = 40
        }

        @JvmField
        val renderSamples: Int = renderSamples

        @JvmField
        val maxLeadSamples: Int = max(
            renderSamples,
            min(renderSamples * 2, max(0, trackFrames))
        )

        @JvmField
        var writtenTotal: Long = 0L

        @JvmField
        var playedWrap: Long = 0L

        private var lastHead: Int = 0
        private var stallHead: Int = -1

        @JvmField
        var stallCount: Int = 0

        @JvmField
        var wasIdle: Boolean = true

        enum class Action {
            PROCEED,
            WAIT,
            STALL_BREAK
        }

        fun check(head: Int): Action {
            val headUnsigned = head.toLong() and 0xFFFFFFFFL
            if (headUnsigned < (lastHead.toLong() and 0xFFFFFFFFL)) {
                if ((lastHead.toLong() and 0xFFFFFFFFL) >= 0x80000000L) {
                    // Genuine 32-bit playback-head wrap (~24.9 h at 48 kHz).
                    playedWrap += 1L shl 32
                } else {
                    // Spurious head reset (reported on some OEM builds after route changes).
                    writtenTotal = playedWrap + headUnsigned
                    Log.w(
                        TAG, "Playback head reset detected at " +
                            headUnsigned + "; rebasing render lead"
                    )
                }
            }
            lastHead = head
            val played = playedWrap + headUnsigned
            if (writtenTotal < played) {
                writtenTotal = played
            }
            if (wasIdle) {
                writtenTotal = played
                wasIdle = false
                stallHead = -1
                stallCount = 0
                return Action.PROCEED
            }
            if (writtenTotal + renderSamples - played <= maxLeadSamples) {
                stallHead = -1
                stallCount = 0
                return Action.PROCEED
            }
            if (head == stallHead) {
                stallCount++
                if (stallCount >= MAX_STALL_POLLS) {
                    writtenTotal = played
                    stallHead = -1
                    stallCount = 0
                    return Action.STALL_BREAK
                }
            } else {
                stallHead = head
                stallCount = 1
            }
            return Action.WAIT
        }

        fun onWritten(written: Int) {
            writtenTotal += written
        }

        fun onIdle() {
            wasIdle = true
            stallHead = -1
            stallCount = 0
        }
    }
}
