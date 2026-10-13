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
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.nio.BufferUnderflowException
import java.util.Arrays
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protobuf.MumbleUDP
import se.lublin.humla.protocol.AudioHandler
import kotlin.math.max
import kotlin.math.min

/**
 * Audio output pipeline.
 *
 * <p>Java owns transport parsing and the {@link AudioTrack} lifecycle.
 * All DSP (jitter buffering, Opus decode, loss concealment, mixing, bus
 * saturation) runs in {@link NativeAudioOutputEngine}. A single render
 * thread pulls mixed PCM and writes it; there is no decode pool, no Java
 * mixer, and the track is never flushed on underrun so gaps do not click.
 */
class AudioOutput : Runnable, NativeAudioOutputEngine.AudioOutputEngineListener {
    companion object {
        private val TAG: String = AudioOutput::class.java.name

        /** 20 ms render quantum at 48 kHz: bounds batching delay on first audio. */
        private const val RENDER_SAMPLES = AudioHandler.FRAME_SIZE * 2
    }

    private val inactiveLock = Object()
    // Main-thread hop preserved literally (slice-4 control-plane migration
    // deferred: AudioOutput is not a Service and owns no serviceScope, and the
    // native render callback ordering depends on this exact hop).
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listener: AudioOutputListener

    @Volatile
    private var engine: NativeAudioOutputEngine? = null
    // Volatile: written under monitor in start/stop, read on the render
    // thread outside it; a stale fetch must never NPE the render loop.
    @Volatile
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var halfDuplexMuted = false
    private var hasIncomingAudio = false

    constructor(listener: AudioOutputListener) : this(null, listener)

    constructor(context: Context?, listener: AudioOutputListener) {
        this.listener = listener
        // NB: context is accepted for API compatibility but intentionally unused.
    }

    @Throws(AudioInitializationException::class)
    @Synchronized
    fun startPlaying(audioStream: Int) {
        if (thread != null || running) {
            return
        }
        halfDuplexMuted = false

        val quantumBytes = RENDER_SAMPLES * 2
        var minBytes = AudioTrack.getMinBufferSize(
            AudioHandler.SAMPLE_RATE,
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
                .setSampleRate(AudioHandler.SAMPLE_RATE)
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

        try {
            engine = NativeAudioOutputEngine(this)
        } catch (e: UnsatisfiedLinkError) {
            track.release()
            audioTrack = null
            throw AudioInitializationException(e)
        } catch (e: RuntimeException) {
            track.release()
            audioTrack = null
            throw AudioInitializationException(e)
        }
        running = true
        thread = Thread(this)
        val renderThread = thread ?: throw NullPointerException("render thread")
        renderThread.start()
    }

    fun stopPlaying() {
        val thread: Thread?
        synchronized(this) {
            if (!running && this.thread == null) {
                return
            }
            running = false
            halfDuplexMuted = false
            thread = this.thread
        }
        synchronized(inactiveLock) {
            inactiveLock.notifyAll()
        }
        if (thread != null) {
            // The render thread idles in inactiveLock.wait() when nobody
            // is speaking. A notify raced with wait entry strands it and
            // the join below blocks forever, so interrupt as well; the
            // wait responds with InterruptedException and run() exits.
            thread.interrupt()
        }
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
            // In-tree AudioHandler.shutdown always stops from the service
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
            val outputEngine = engine
            if (outputEngine != null) {
                outputEngine.destroy()
                engine = null
            }
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
                synchronized(inactiveLock) {
                    try {
                        inactiveLock.wait(5)
                    } catch (e: InterruptedException) {
                        // stopPlaying interrupts after clearing running;
                        // a stray interrupt just ends pacing for this
                        // quantum instead of busy-waiting on a set flag.
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                synchronized(this) {
                    if (!running) {
                        break
                    }
                }
            }
            var rendered = 0
            var engineCopy = engine
            if (engineCopy != null) {
                rendered = engineCopy.render(mix, 0, RENDER_SAMPLES)
            }
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
                // resume is gapless, and idle until the next packet arrives.
                // When nobody is speaking and zero voices are registered in the
                // native engine, wait indefinitely to eliminate the 50 Hz CPU spin
                // and allow cores to enter deep C-states.
                // If active voices exist (fresh voices filling their startup gate,
                // or wedged voices pending miss expiry), use the 20 ms timed wait
                // to keep paced ticks progressing.
                synchronized(inactiveLock) {
                    if (!hasIncomingAudio) {
                        engineCopy = engine
                        val hasVoices = engineCopy != null && engineCopy.hasActiveVoices()
                        if (!hasVoices) {
                            // When nobody is speaking and zero voices are registered in the
                            // native engine, wait indefinitely to eliminate the 50 Hz CPU spin
                            // and allow cores to enter deep C-states. AudioTrack remains continuously
                            // in PLAYSTATE_PLAYING, rendering zero-PCM digital silence to maintain
                            // the silence shield against OEM watchdog SIGKILL terminations.
                            while (running && !hasIncomingAudio) {
                                try {
                                    inactiveLock.wait()
                                } catch (e: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    break@renderLoop
                                }
                                engineCopy = engine
                                if (engineCopy != null && engineCopy.hasActiveVoices()) {
                                    break
                                }
                            }
                        } else {
                            try {
                                inactiveLock.wait(20)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break@renderLoop
                            }
                        }
                    }
                    hasIncomingAudio = false
                }
            }
        }

        try {
            track.stop()
        } catch (ignored: IllegalStateException) {
        }
    }

    fun queueVoiceData(data: ByteArray?, messageType: HumlaUDPMessageType) {
        // Non-null by construction past the guard: the synchronized block
        // evaluates to the engine, so later uses need no assertion.
        val engineCopy: NativeAudioOutputEngine = synchronized(this) {
            val current = engine
            if (!running || current == null) {
                return
            }
            current
        }
        if (messageType != HumlaUDPMessageType.UDPVoiceOpus) {
            return
        }
        if (data == null || data.isEmpty()) {
            return
        }
        try {
            val msgFlags = data[0].toInt() and 0x1f
            val pds = PacketBuffer(data, data.size)
            pds.skip(1)
            val session = pds.readLong().toInt()
            val user = listener.getUser(session)
            if (user == null || user.isLocalMuted) {
                return
            }
            val seq = pds.readLong().toInt()
            // Bundled datagrams chain header+payload pairs; each frame
            // consumes sequence seq+i in UDP/protobuf 10 ms frame units.
            // A legacy positional trailer (exactly 3 floats = 12 bytes
            // after at least one frame, per upstream MumbleProtocol) is not
            // another Opus frame: stop before parsing it as a header, or
            // the trailing floats decode as a garbage header + payload.
            var queued = 0
            while (pds.left() > 0) {
                if (queued > 0 && pds.left() == 3 * 4) {
                    break
                }
                val header = pds.readLong()
                val size = (header and ((1 shl 13) - 1).toLong()).toInt()
                val isTerminator = header and (1 shl 13).toLong() != 0L
                if (size == 0 && isTerminator) {
                    engineCopy.queuePacket(
                        session, ByteArray(0), 0, seq + queued,
                        msgFlags, true
                    )
                    queued++
                    continue
                }
                if (size <= 0 || size > pds.left()) {
                    break
                }
                val opus = pds.dataBlock(size)
                engineCopy.queuePacket(
                    session, opus, opus.size, seq + queued,
                    msgFlags, isTerminator
                )
                queued++
            }
            if (queued > 0) {
                signalData()
            }
        } catch (e: BufferUnderflowException) {
            Log.v(TAG, "Dropping malformed voice packet", e)
        } catch (e: IllegalArgumentException) {
            Log.v(TAG, "Dropping malformed voice packet", e)
        } catch (e: ArrayIndexOutOfBoundsException) {
            Log.v(TAG, "Dropping malformed voice packet", e)
        }
    }

    fun queueProtobufVoiceData(audioMsg: MumbleUDP.Audio) {
        // Non-null by construction past the guard: the synchronized block
        // evaluates to the engine, so later uses need no assertion.
        val engineCopy: NativeAudioOutputEngine = synchronized(this) {
            val current = engine
            if (!running || current == null) {
                return
            }
            current
        }
        val session = audioMsg.senderSession
        val user = listener.getUser(session)
        if (user == null || user.isLocalMuted) {
            return
        }
        val opus = audioMsg.opusData.toByteArray()
        val seq = audioMsg.frameNumber.toInt()
        val flags = if (audioMsg.hasContext()) audioMsg.context else 0
        val isTerminator = audioMsg.isTerminator
        if (opus.isEmpty()) {
            // A terminator may carry no Opus payload; still forward the marker
            // so the voice drains instead of lingering to the miss-expiry.
            if (isTerminator) {
                engineCopy.queuePacket(session, ByteArray(0), 0, seq, flags, true)
                signalData()
            }
            return
        }
        engineCopy.queuePacket(session, opus, opus.size, seq, flags, isTerminator)
        signalData()
    }

    private fun signalData() {
        synchronized(inactiveLock) {
            hasIncomingAudio = true
            inactiveLock.notify()
        }
    }

    override fun onTalkStateChanged(session: Int, talkStateOrdinal: Int) {
        val state = when (talkStateOrdinal) {
            NativeAudioOutputEngine.TALK_TALKING -> TalkState.TALKING
            NativeAudioOutputEngine.TALK_SHOUTING -> TalkState.SHOUTING
            NativeAudioOutputEngine.TALK_PASSIVE -> TalkState.PASSIVE
            NativeAudioOutputEngine.TALK_WHISPERING -> TalkState.WHISPERING
            else -> {
                Log.w(TAG, "Unknown talk state ordinal: " + talkStateOrdinal)
                TalkState.PASSIVE
            }
        }
        mainHandler.post {
            val user = listener.getUser(session)
            if (user != null && user.talkState != state) {
                user.talkState = state
                listener.onUserTalkStateUpdated(user)
            }
        }
    }

    fun removeUser(session: Int) {
        val engineCopy: NativeAudioOutputEngine?
        synchronized(this) {
            engineCopy = engine
        }
        engineCopy?.removeUser(session)
    }

    /**
     * Checks whether the native audio output engine currently has any active voices.
     * @return true if one or more voices are active in the mixing engine.
     */
    fun hasActiveVoices(): Boolean {
        val engineCopy = engine
        return engineCopy != null && engineCopy.hasActiveVoices()
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

    interface AudioOutputListener {
        /**
         * Called when a user's talking state is changed.
         * @param user The user whose talking state has been modified.
         */
        fun onUserTalkStateUpdated(user: User)

        /**
         * Used to set audio-related user data.
         * @return The user for the associated session.
         */
        fun getUser(session: Int): User?
    }
}
