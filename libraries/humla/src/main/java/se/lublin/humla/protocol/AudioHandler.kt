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

package se.lublin.humla.protocol

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.google.protobuf.ByteString
import se.lublin.humla.Constants
import se.lublin.humla.R
import se.lublin.humla.audio.AudioInput
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.NativeAudioInputEngine
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.audio.inputmode.ToggleInputMode
import se.lublin.humla.exception.AudioException
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.model.User
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.net.PacketBuffer
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protobuf.MumbleUDP
import se.lublin.humla.util.HumlaLogger
import se.lublin.humla.util.HumlaNetworkListener

/**
 * Modern Audio Protocol Handler.
 *
 * Bridges network audio messages to low-latency capture and playback.
 * Powered natively by NativeAudioInputEngine with RNNoise DSP, Pre-Speech Ring Buffer (80ms),
 * Dual-Threshold Hysteresis VAD, Soft-Knee Saturation Limiter, and Mandatory Hard CBR Opus.
 */
class AudioHandler : HumlaNetworkListener,
    AudioInput.AudioInputListener, NativeAudioInputEngine.AudioInputEngineListener {

    companion object {
        // Canonical 48 kHz / 480-sample (10 ms) framing shared with AudioInput
        // and the native engine: keep all three in agreement (no single source;
        // JNI and the capture layer each need their own constants).
        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = SAMPLE_RATE / 100 // 480 samples @ 10ms

        private val TAG = AudioHandler::class.java.name

        private fun sanitizeFramesPerPacket(fpp: Int): Int {
            return Constants.sanitizeFramesPerPacket(fpp)
        }

        /**
         * Pure bandwidth-degradation decision: does not call Android APIs, so JVM tests can cover it.
         * Do not add logging, native calls, or context access here.
         */
        // Widened to public: Kotlin has no package visibility; JVM tests pin this.
        @JvmStatic
        fun computeEffectiveConfig(bitrate: Int, framesPerPacket: Int, maxBandwidth: Int): EffectiveAudioConfig {
            if (maxBandwidth == -1) {
                return EffectiveAudioConfig(bitrate, sanitizeFramesPerPacket(framesPerPacket))
            }
            var degradedBitrate = bitrate
            var degradedFpp = framesPerPacket

            // fpp==6 is already the largest packet: no growth branch applies by
            // design, so only the bitrate loop below degrades (pinned by test).
            if (HumlaConnection.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth) {
                if (degradedFpp <= 4 && maxBandwidth <= 32000) {
                    degradedFpp = 4
                } else if (degradedFpp == 1 && maxBandwidth <= 64000) {
                    degradedFpp = 2
                } else if (degradedFpp == 2 && maxBandwidth <= 48000) {
                    degradedFpp = 4
                }
                while (HumlaConnection.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth && degradedBitrate > 8000) {
                    degradedBitrate -= 1000
                }
            }
            degradedBitrate = Math.max(8000, degradedBitrate)
            degradedFpp = sanitizeFramesPerPacket(degradedFpp)
            return EffectiveAudioConfig(degradedBitrate, degradedFpp)
        }
    }

    private val mContext: Context
    private val mLogger: HumlaLogger
    private val mInput: AudioInput
    private val mOutput: AudioOutput
    private val mOutputListener: AudioOutput.AudioOutputListener
    private val mEncodeListener: AudioEncodeListener
    private val mNativeEngine: NativeAudioInputEngine

    @Volatile
    private var mSession: Int = 0
    private var mCodec: HumlaUDPMessageType? = null

    // Route intent, not a literal stream: on the voice route, VOICE_CALL
    // selects earpiece, anything else speakerphone (see initialize()).
    // On the media route the value is ignored for output (always MUSIC).
    private val mAudioStream: Int
    private val mAudioSource: Int

    /**
     * Voice route (VoIP triple) vs media route. Set only at pipeline
     * creation from the service route policy, mirroring setScoEnabled:
     * live pipelines are recreated to apply changes.
     */
    @Volatile
    private var mVoiceRoute = true

    @Volatile
    private var mBitrate: Int

    @Volatile
    private var mFramesPerPacket: Int

    /**
     * Construction-time targets that bandwidth adaptation degrades from and
     * restores to (upstream parity: AudioInput reseeds from settings on
     * every adjustBandwidth). Seeding from the live degraded values would
     * ratchet quality down permanently after a transient cap.
     */
    private val mTargetBitrate: Int
    private val mTargetFramesPerPacket: Int

    @Volatile
    private var mFramesPerPacketListener: FramesPerPacketListener? = null
    private val mInputMode: IInputMode
    private val mAmplitudeBoost: Float

    @Volatile
    private var mInitialized: Boolean = false

    @Volatile
    private var mSelfMuted: Boolean = false

    @Volatile
    private var mServerMuted: Boolean = false

    /**
     * Moderator-applied deafening, tracked separately so a deaf-only packet
     * (murmur's else-if broadcast form omits the paired mute) still closes
     * the gate. Mirrors User.setDeafened coercion; like the rest of the gate
     * it is read and written under synchronized(this).
     */
    @Volatile
    private var mServerDeafened: Boolean = false

    @Volatile
    private var mSuppressed: Boolean = false

    /**
     * Once the client has expressed a desired self-mute, the gate follows
     * only that: echoes are delayed copies of our own in-order requests and
     * carry no newer information, so a stale echo must never move the gate
     * (rapid mute-unmute-mute would otherwise transiently reopen the mic
     * while the UI shows muted). No reset is needed: the handler instance is
     * per-connection, and intra-connection pipeline recreations re-seed the
     * gate from the optimistically updated User in {@link #initialize}.
     */
    @Volatile
    private var mOptimisticSelfMuteActive: Boolean = false
    private val mHalfDuplex: Boolean

    @Volatile
    private var mScoActive: Boolean = false

    // TODO(ODD-21-followup): collapse this now-constant flag; the Builder
    // hardcodes preprocessor-on and no production path can reach off.
    private val mPreprocessorEnabled: Boolean
    private var mAdaptiveLevelerEnabled: Boolean

    @Volatile
    private var mTalking: Boolean = false

    @Volatile
    private var mTargetId: Byte = 0

    @Volatile
    private var mProtobufUdp: Boolean = false

    // Pre-allocated packet buffers for zero heap allocation on audio path
    private val mPacketBufferLock = Any()

    // Guards the live bitrate/fpp pair so readers never see a torn mix.
    private val mBandwidthLock = Any()
    private val mProtobufPacketBuffer = ByteArray(2048)
    private val mLegacyPacketBuffer = ByteArray(1024)
    private val mLegacyDataStream = PacketBuffer(mLegacyPacketBuffer, 1024)

    @Throws(AudioInitializationException::class)
    constructor(
        context: Context, logger: HumlaLogger, audioStream: Int, audioSource: Int,
        sampleRate: Int, targetBitrate: Int, targetFramesPerPacket: Int,
        inputMode: IInputMode, targetId: Byte, amplitudeBoost: Float,
        halfDuplexEnabled: Boolean,
        preprocessorEnabled: Boolean,
        encodeListener: AudioEncodeListener,
        outputListener: AudioOutput.AudioOutputListener
    ) : this(
        context, logger, audioStream, audioSource, sampleRate, targetBitrate, targetFramesPerPacket,
        inputMode, targetId, amplitudeBoost, halfDuplexEnabled,
        preprocessorEnabled, true, encodeListener, outputListener
    )

    @Throws(AudioInitializationException::class)
    constructor(
        context: Context, logger: HumlaLogger, audioStream: Int, audioSource: Int,
        sampleRate: Int, targetBitrate: Int, targetFramesPerPacket: Int,
        inputMode: IInputMode, targetId: Byte, amplitudeBoost: Float,
        halfDuplexEnabled: Boolean,
        preprocessorEnabled: Boolean, adaptiveLevelerEnabled: Boolean,
        encodeListener: AudioEncodeListener,
        outputListener: AudioOutput.AudioOutputListener
    ) {
        mContext = context
        mLogger = logger
        mAudioStream = audioStream
        mBitrate = targetBitrate
        mFramesPerPacket = sanitizeFramesPerPacket(targetFramesPerPacket)
        mTargetBitrate = targetBitrate
        mTargetFramesPerPacket = sanitizeFramesPerPacket(targetFramesPerPacket)
        mInputMode = inputMode
        mAmplitudeBoost = amplitudeBoost
        mHalfDuplex = halfDuplexEnabled
        mPreprocessorEnabled = preprocessorEnabled
        mAdaptiveLevelerEnabled = adaptiveLevelerEnabled
        mEncodeListener = encodeListener
        mOutputListener = outputListener
        mTalking = false
        mTargetId = targetId
        mAudioSource = audioSource

        var nativeMode = NativeAudioInputEngine.INPUT_MODE_VOICE_ACTIVITY
        if (mInputMode is ToggleInputMode) {
            nativeMode = NativeAudioInputEngine.INPUT_MODE_PUSH_TO_TALK
        } else if (mInputMode is ContinuousInputMode) {
            nativeMode = NativeAudioInputEngine.INPUT_MODE_CONTINUOUS
        }

        var modelData: ByteArray? = null
        if (mPreprocessorEnabled) {
            NativeAudioInputEngine.loadRnnoiseModel(context)
            modelData = NativeAudioInputEngine.getCachedRnnoiseModel()
        }

        mNativeEngine = NativeAudioInputEngine(
            mBitrate,
            mFramesPerPacket,
            mAmplitudeBoost,
            mPreprocessorEnabled,
            mAdaptiveLevelerEnabled,
            nativeMode,
            modelData,
            this
        )

        // Anything below owns system handles (native engine above, mic
        // below): clean up on failure so a failed connect never abandons
        // a live native handle or a held mic.
        try {
            if (mInputMode is ActivityInputMode) {
                val actMode = mInputMode as ActivityInputMode
                mNativeEngine.setVadThresholds(actMode.vadMax, actMode.vadMin)
            }

            val input = AudioInput(this, mAudioSource)
            try {
                mOutput = AudioOutput(mContext, mOutputListener)
            } catch (t: Throwable) {
                // Output construction failed after capture was already
                // holding the mic: shut it down before propagating. Errors
                // must not leak either (same path as Exceptions).
                try {
                    input.shutdown()
                } catch (ignored: Throwable) {
                }
                throw t
            }
            mInput = input
        } catch (t: Throwable) {
            // Covers AudioInput construction too: destroy the native engine
            // on any failure, checked or fatal, without masking the original.
            try {
                mNativeEngine.destroy()
            } catch (ignored: Throwable) {
            }
            throw t
        }
    }

    /** Destroys capture + native engine made owned before a later init step failed. */
    private fun destroyInputAndEngine() {
        // Best-effort teardown must never mask the original failure, fatal
        // or otherwise: Throwable guards throughout, original rethrown.
        try {
            mInput.shutdown()
        } catch (ignored: Throwable) {
        }
        try {
            mNativeEngine.destroy()
        } catch (ignored: Throwable) {
        }
    }

    @Synchronized
    @Throws(AudioException::class)
    fun initialize(self: User?, maxBandwidth: Int, codec: HumlaUDPMessageType?) {
        if (mInitialized || self == null) return
        mSession = self.session

        setMaxBandwidth(maxBandwidth)
        setCodec(codec)
        mSelfMuted = self.isSelfMuted
        mServerMuted = self.isMuted
        mServerDeafened = self.isDeafened
        mSuppressed = self.isSuppressed
        val isMuted = mSelfMuted || mServerMuted || mServerDeafened || mSuppressed

        try {
            mOutput.startPlaying(
                if (mVoiceRoute || mScoActive) AudioManager.STREAM_VOICE_CALL
                else AudioManager.STREAM_MUSIC
            )
        } catch (t: Throwable) {
            // The constructor owns a native engine + capture (AudioRecord +
            // HW effects): destroy the partial pipeline before rethrow so
            // the caller never abandons a half-built handler holding the mic.
            // Throwable-wide: an unchecked or fatal throw from startPlaying
            // (e.g. SecurityException) must not skip teardown either.
            destroyInputAndEngine()
            throw t
        }
        // The stream int is route intent on the voice route (see field):
        // earpiece vs speakerphone. Set explicitly both ways — the flag is
        // global AudioManager state and a speaker-then-earpiece recreate
        // must clear it; media routes clear it for hygiene (voice-call
        // routing ignores it there, but no stale flag may leak across).
        setSpeakerphone(mVoiceRoute && !mScoActive && mAudioStream != AudioManager.STREAM_VOICE_CALL)
        mInitialized = true
        updateMuteState(isMuted)
    }

    private fun startRecording() {
        // No outer hold on mInput: AudioInput entry points self-synchronize
        // and join outside their monitor; holding it here would re-pin the
        // lock across startRecording's bounded join.
        if (!mInput.isRecording()) {
            mInput.startRecording()
        }
    }

    @Synchronized
    private fun updateMuteState(muted: Boolean) {
        // Doze shield: the microphone stays open for the whole connection, even
        // while muted. Recording-active is the strongest signal Android/Vivo
        // honor against force-suspend on battery + screen-off; closing the mic
        // (commit 1a971285) removed the 0.21.7-era shield and preceded the
        // 5-minute Murmur-timeout flaps, and the A/B experiment validated the
        // open mic as the load-bearing shield. Muted frames are dropped in the
        // native engine via setMuted(true), so no new voice frames are encoded
        // or transmitted (a single terminator may still be emitted on the mute
        // transition if mid-utterance) — at the cost of the system mic
        // indicator and ~15-25 mA capture drain.
        mNativeEngine.setMuted(muted)
        if (mInitialized) {
            startRecording()
        }
    }

    /**
     * Applies an optimistic self-mute gate to the capture pipeline.
     *
     * Desktop parity: Mumble's AudioInput reads the local desired-state
     * (Global::get().s.bMute) every frame, so the mic cuts on click rather
     * than after the server-echo RTT. The echo remains the model confirm
     * (see ModelHandler); the gate echo in {@link #messageUserState} is
     * ignored once an optimistic value exists.
     *
     * @param muted the effective mute target after central deaf-implies-mute
     *              coercion (see HumlaService.setSelfMuteDeafState); must be
     *              true whenever the user is deafened.
     */
    @Synchronized
    fun setSelfMutedOptimistic(muted: Boolean) {
        mSelfMuted = muted
        mOptimisticSelfMuteActive = true
        if (!mInitialized) return
        updateMuteState(mServerMuted || mSelfMuted || mServerDeafened || mSuppressed)
    }

    fun isInitialized(): Boolean {
        return mInitialized
    }

    fun isPlaying(): Boolean {
        synchronized(mOutput) {
            return mOutput.isPlaying()
        }
    }

    fun getAudioOutput(): AudioOutput {
        return mOutput
    }

    fun getCodec(): HumlaUDPMessageType? {
        return mCodec
    }

    fun recreateEncoder() {
        setCodec(mCodec)
    }

    fun setCodec(codec: HumlaUDPMessageType?) {
        mCodec = codec
    }

    /** Explicit earpiece/speakerphone routing for the voice route. */
    private fun setSpeakerphone(on: Boolean) {
        var audioManager: AudioManager? = null
        try {
            audioManager = mContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        } catch (e: Exception) {
            Log.w(TAG, "VoIP route: no AudioManager (" + e.message + ")")
            return
        }
        if (audioManager == null) return
        try {
            audioManager.isSpeakerphoneOn = on
            Log.i(TAG, "VoIP route: speakerphone " + if (on) "on" else "off")
        } catch (e: Exception) {
            Log.w(TAG, "VoIP route: setSpeakerphoneOn threw (" + e.message + ")")
        }
    }

    fun getAudioStream(): Int {
        return mAudioStream
    }

    fun getAudioSource(): Int {
        return mAudioSource
    }

    fun getSampleRate(): Int {
        return SAMPLE_RATE
    }

    /** Snapshot read; for a guaranteed-consistent pair use {@link #getCurrentBandwidth}. */
    fun getBitrate(): Int {
        synchronized(mBandwidthLock) {
            return mBitrate
        }
    }

    private fun setMaxBandwidth(maxBandwidth: Int) {
        // Seed from construction targets, not live values, so a relaxed or
        // cleared cap restores quality instead of ratcheting (see fields).
        val config = computeEffectiveConfig(mTargetBitrate, mTargetFramesPerPacket, maxBandwidth)
        // Decide and publish under one lock (single main/TCP-serialized
        // writer today; the lock documents it), then fan out and re-drive
        // outside: torn pairs would misreport bandwidth, and callbacks must
        // never run while holding this lock. The no-op check lives inside
        // too so check-then-write cannot interleave.
        val applied: EffectiveAudioConfig
        val oldFramesPerPacket: Int
        val changed: Boolean
        synchronized(mBandwidthLock) {
            changed = config.bitrate != mBitrate || config.framesPerPacket != mFramesPerPacket
            if (!changed) {
                return
            }
            oldFramesPerPacket = mFramesPerPacket
            mBitrate = config.bitrate
            mFramesPerPacket = config.framesPerPacket
            applied = config
        }
        mNativeEngine.setBitrate(applied.bitrate)
        mNativeEngine.setFramesPerPacket(applied.framesPerPacket)
        mLogger.logInfo(
            mContext.getString(
                R.string.audio_max_bandwidth,
                maxBandwidth / 1000, applied.bitrate / 1000,
                applied.framesPerPacket * Constants.FRAME_DURATION_MS
            )
        )
        if (applied.framesPerPacket != oldFramesPerPacket) {
            val listener = mFramesPerPacketListener // volatile read
            if (listener != null) {
                listener.onEffectiveFramesPerPacketChanged(applied.framesPerPacket)
            }
        }
    }

    /** Snapshot read; for a guaranteed-consistent pair use {@link #getCurrentBandwidth}. */
    fun getFramesPerPacket(): Int {
        synchronized(mBandwidthLock) {
            return mFramesPerPacket
        }
    }

    fun setFramesPerPacketListener(listener: FramesPerPacketListener?) {
        mFramesPerPacketListener = listener
    }

    fun getAmplitudeBoost(): Float {
        return mAmplitudeBoost
    }

    fun isHalfDuplex(): Boolean {
        return mHalfDuplex
    }

    /**
     * Selects the voice-call route for confirmed Bluetooth SCO, mirroring
     * handset mode. Set only on confirmed link state (see BluetoothScoManager),
     * never on the raw user toggle: starting playback on the voice-call stream
     * without a live SCO link would misroute audio. Takes effect on the next
     * pipeline creation; live pipelines must be recreated to apply it.
     */
    fun setScoEnabled(scoEnabled: Boolean) {
        mScoActive = scoEnabled
    }

    /**
     * Selects the VoIP triple (voice source/mode/usage) vs the media path.
     * Set only at pipeline creation from the service route policy, mirroring
     * setScoEnabled: live pipelines must be recreated to apply it.
     */
    fun setVoiceRouteEnabled(voiceRoute: Boolean) {
        mVoiceRoute = voiceRoute
    }

    fun setAdaptiveLevelerEnabled(enabled: Boolean) {
        mAdaptiveLevelerEnabled = enabled
        mNativeEngine.setAdaptiveLevelerEnabled(enabled)
    }

    fun isAdaptiveLevelerEnabled(): Boolean {
        return mAdaptiveLevelerEnabled
    }

    fun getCurrentBandwidth(): Int {
        synchronized(mBandwidthLock) {
            return HumlaConnection.calculateAudioBandwidth(mBitrate, mFramesPerPacket)
        }
    }

    @Synchronized
    fun shutdown() {
        // Never hold mInput across shutdown: it joins the capture thread,
        // which needs the AudioInput monitor for release/enableEffects.
        // (Same shape as the mOutput rule below.)
        mInput.shutdown()
        // Never hold mOutput across stopPlaying: it joins the render thread,
        // which needs the AudioOutput monitor each loop to observe shutdown
        // and exit. Holding it here deadlocks disconnect (main in join,
        // render blocked on the monitor). stopPlaying self-synchronizes.
        mOutput.stopPlaying()
        mNativeEngine.destroy()
        mInitialized = false

        mEncodeListener.onTalkingStateChanged(false)
        mOutput.setHalfDuplexMuted(false)
    }

    override fun messageCodecVersion(msg: Mumble.CodecVersion) {
        if (!mInitialized) return

        val codec = HumlaUDPMessageType.UDPVoiceOpus
        if (codec != mCodec) {
            setCodec(codec)
        }
    }

    override fun messageServerSync(msg: Mumble.ServerSync) {
        // Match the sibling guards: post-shutdown delivery must not re-log
        // or re-drive the (destroyed) native engine.
        if (!mInitialized) {
            return
        }
        setMaxBandwidth(if (msg.hasMaxBandwidth()) msg.maxBandwidth else -1)
    }

    override fun messageServerConfig(msg: Mumble.ServerConfig) {
        // Upstream parity (AudioInput::setMaxBandwidth from ServerConfig):
        // a mid-call admin cap change re-degrades the encoder without a
        // reconnect. Same guard as ServerSync above. Absent field is a
        // no-op (murmur always sets it live; only the pre-registration
        // burst omits it, which never reaches this handler).
        if (!mInitialized) {
            return
        }
        if (msg.hasMaxBandwidth()) {
            setMaxBandwidth(msg.maxBandwidth)
        }
    }

    @Synchronized
    override fun messageUserState(msg: Mumble.UserState) {
        if (!mInitialized) return

        if (msg.hasSession() && msg.session == mSession) {
            var changed = false
            if (msg.hasMute() && mServerMuted != msg.mute) {
                mServerMuted = msg.mute
                changed = true
            }
            if (msg.hasSelfMute() && !mOptimisticSelfMuteActive &&
                mSelfMuted != msg.selfMute
            ) {
                mSelfMuted = msg.selfMute
                changed = true
            }
            // Fail-closed murmur parity: a deaf-only broadcast omits self_mute
            // (murmur/Messages.cpp else-if form), but deaf implies mute. This
            // only ever closes the mic, so it is latch-independent and safe
            // under every interleaving.
            if (msg.hasSelfDeaf() && msg.selfDeaf && !mSelfMuted) {
                mSelfMuted = true
                changed = true
            }
            if (msg.hasSuppress() && mSuppressed != msg.suppress) {
                mSuppressed = msg.suppress
                changed = true
            }
            if (msg.hasDeaf() && mServerDeafened != msg.deaf) {
                mServerDeafened = msg.deaf
                changed = true
            }
            if (changed) {
                updateMuteState(mServerMuted || mSelfMuted || mServerDeafened || mSuppressed)
            }
        }
    }

    override fun messageUserRemove(msg: Mumble.UserRemove) {
        super.messageUserRemove(msg)
        synchronized(mOutput) {
            mOutput.removeUser(msg.session)
        }
    }

    override fun messageVoiceData(data: ByteArray, messageType: HumlaUDPMessageType) {
        synchronized(mOutput) {
            mOutput.queueVoiceData(data, messageType)
        }
    }

    override fun messageProtobufAudio(msg: MumbleUDP.Audio) {
        synchronized(mOutput) {
            mOutput.queueProtobufVoiceData(msg)
        }
    }

    override fun messageProtobufPing(msg: MumbleUDP.Ping) {
        // Handled in HumlaConnection
    }

    fun setProtobufUdp(protobufUdp: Boolean) {
        mProtobufUdp = protobufUdp
    }

    fun isProtobufUdp(): Boolean {
        return mProtobufUdp
    }

    override fun onAudioInputReceived(frame: ShortArray?, frameSize: Int) {
        mNativeEngine.processFrame(frame, 0, frameSize)
    }

    override fun onAudioPacketEncoded(
        data: ByteArray?,
        length: Int,
        frames: Int,
        isTerminator: Boolean,
        frameNumber: Long
    ) {
        // length is authoritative; frames is informational from the native
        // engine (actual frames are implicit in length). Zero-length
        // terminators never occur: the native engine encodes silence instead
        // of emitting empty terminators, so length<=0 is safely dropped —
        // with a warning when a terminator is dropped, since that would
        // strand the peer's talking indicator if the native contract changes.
        if (data == null || length <= 0) {
            if (isTerminator && length <= 0 && data != null) {
                Log.w(TAG, "Dropping zero-length terminator; peer talk state may strand")
            }
            return
        }

        synchronized(mPacketBufferLock) {
            if (mProtobufUdp) {
                val audioBuilder = MumbleUDP.Audio.newBuilder()
                if (mTargetId != 0.toByte()) {
                    audioBuilder.target = (mTargetId.toInt() and 0xFF)
                }
                audioBuilder.frameNumber = frameNumber
                audioBuilder.opusData = ByteString.copyFrom(data, 0, length)
                if (isTerminator) {
                    audioBuilder.isTerminator = true
                }

                val protoBytes = audioBuilder.build().toByteArray()
                val totalLen = 1 + protoBytes.size
                if (totalLen <= mProtobufPacketBuffer.size) {
                    mProtobufPacketBuffer[0] = 0x00 // Protobuf Audio header
                    System.arraycopy(protoBytes, 0, mProtobufPacketBuffer, 1, protoBytes.size)
                    mEncodeListener.onAudioEncoded(mProtobufPacketBuffer, totalLen)
                } else {
                    Log.w(TAG, "Dropping oversize protobuf audio packet ($totalLen bytes)")
                }
            } else {
                // Worst case: 1 flag byte + 2 varints (<=10 bytes each) +
                // payload. Pre-check so overflow drops with a log instead of
                // throwing BufferOverflowException to the native thread.
                if (21 + length > mLegacyPacketBuffer.size) {
                    Log.w(TAG, "Dropping oversize legacy audio packet (payload $length bytes)")
                    return
                }
                try {
                    var flags = 0
                    flags = flags or (HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5)
                    flags = flags or (mTargetId.toInt() and 0x1F)

                    mLegacyPacketBuffer[0] = (flags and 0xFF).toByte()
                    mLegacyDataStream.rewind()
                    mLegacyDataStream.skip(1)
                    mLegacyDataStream.writeLong(frameNumber)

                    var header = length.toLong() and ((1 shl 13) - 1).toLong()
                    if (isTerminator) {
                        header = header or (1 shl 13).toLong()
                    }
                    mLegacyDataStream.writeLong(header)
                    mLegacyDataStream.append(data, length)

                    val totalLen = mLegacyDataStream.size()
                    mEncodeListener.onAudioEncoded(mLegacyPacketBuffer, totalLen)
                } catch (e: java.nio.BufferOverflowException) {
                    Log.w(TAG, "Dropping legacy audio packet after buffer overflow", e)
                }
            }
        }
    }

    @Synchronized
    override fun onTalkingStateChanged(isTalking: Boolean, peakEnergy: Float) {
        // Synchronized: concurrent native callbacks must not duplicate or
        // drop transitions via check-then-act on mTalking.
        if (mTalking != isTalking) {
            mTalking = isTalking
            mEncodeListener.onTalkingStateChanged(isTalking)
            if (mHalfDuplex) {
                mOutput.setHalfDuplexMuted(isTalking)
            }
        }
    }

    fun setVoiceTargetId(id: Byte) {
        mTargetId = id
    }

    fun clearVoiceTarget() {
        mTargetId = 0
    }

    fun setPttTalking(talking: Boolean) {
        if (mInputMode is ToggleInputMode) {
            (mInputMode as ToggleInputMode).isTalkingOn = talking
        }
        mNativeEngine.setPttTalking(talking)
    }

    fun setVadThresholds(vadMax: Float, vadMin: Float) {
        mNativeEngine.setVadThresholds(vadMax, vadMin)
    }

    fun setVadSquelchFloor(minDb: Float) {
        mNativeEngine.setVadSquelchFloor(minDb)
    }

    fun getVadSquelchFloor(): Float {
        return mNativeEngine.getVadSquelchFloor()
    }

    /** Degraded effective audio config produced by bandwidth adaptation. Pure value type. */
    class EffectiveAudioConfig(
        @JvmField val bitrate: Int,
        @JvmField val framesPerPacket: Int
    )

    /** Notified when bandwidth adaptation changes the effective frames-per-packet. */
    fun interface FramesPerPacketListener {
        fun onEffectiveFramesPerPacketChanged(framesPerPacket: Int)
    }

    interface AudioEncodeListener {
        fun onAudioEncoded(data: ByteArray, length: Int)
        fun onTalkingStateChanged(talking: Boolean)
    }

    /**
     * A builder to configure and instantiate the audio protocol handler.
     */
    class Builder {
        private var mContext: Context? = null
        private var mLogger: HumlaLogger? = null
        private var mAudioStream: Int = 0
        private var mAudioSource: Int = 0
        private var mTargetBitrate: Int = 0
        private var mTargetFramesPerPacket: Int = Constants.DEFAULT_FRAMES_PER_PACKET
        private var mInputSampleRate: Int = 0
        private var mAmplitudeBoost: Float = 0f
        private var mHalfDuplexEnabled: Boolean = false

        // Plain like neighbors: most fields are programmed on the service
        // main thread, while the route group (sco/voice/source/stream) is
        // written under the HumlaService monitor (fine-grained blocks in
        // updateRouteBuilderLocked, stopScoWithNotify, and onDestroy; the
        // device query itself runs outside it); only the built handler's
        // mScoActive is volatile.
        private var mScoEnabled: Boolean = false

        // Defaults true to preserve step-2a behavior until the service
        // route policy drives it (C2); the service sets this explicitly
        // on every route application either way.
        private var mVoiceRouteEnabled = true
        private var mAdaptiveLevelerEnabled = true
        private var mInputMode: IInputMode? = null
        private var mEncodeListener: AudioEncodeListener? = null
        private var mFramesPerPacketListener: FramesPerPacketListener? = null
        private var mTalkingListener: AudioOutput.AudioOutputListener? = null

        fun setContext(context: Context): Builder {
            mContext = requireNotNull(context, { "context" })
            return this
        }

        fun setLogger(logger: HumlaLogger): Builder {
            mLogger = requireNotNull(logger, { "logger" })
            return this
        }

        fun setAudioStream(audioStream: Int): Builder {
            mAudioStream = audioStream
            return this
        }

        fun setAudioSource(audioSource: Int): Builder {
            mAudioSource = audioSource
            return this
        }

        fun setTargetBitrate(targetBitrate: Int): Builder {
            mTargetBitrate = targetBitrate
            return this
        }

        fun setTargetFramesPerPacket(targetFramesPerPacket: Int): Builder {
            mTargetFramesPerPacket = targetFramesPerPacket
            return this
        }

        fun getTargetFramesPerPacket(): Int {
            return mTargetFramesPerPacket
        }

        /**
         * @deprecated Fixed 48 kHz end to end: AudioInput and the native
         * engine hardcode 48000/480 by design, so this value is accepted
         * and ignored. Retained for API compatibility; do not rely on it.
         */
        @Deprecated("Fixed 48 kHz end to end; accepted and ignored")
        fun setInputSampleRate(inputSampleRate: Int): Builder {
            mInputSampleRate = inputSampleRate
            return this
        }

        fun setAmplitudeBoost(amplitudeBoost: Float): Builder {
            mAmplitudeBoost = amplitudeBoost
            return this
        }

        fun setHalfDuplexEnabled(halfDuplexEnabled: Boolean): Builder {
            mHalfDuplexEnabled = halfDuplexEnabled
            return this
        }

        /**
         * Selects the voice-call route. Pass confirmed SCO link state only
         * (see {@link AudioHandler#setScoEnabled}), never the raw toggle.
         */
        fun setScoEnabled(scoEnabled: Boolean): Builder {
            mScoEnabled = scoEnabled
            return this
        }

        /**
         * Selects the VoIP triple vs the media path. Pass the service
         * route policy value (see {@link AudioHandler#setVoiceRouteEnabled}).
         */
        fun setVoiceRouteEnabled(voiceRouteEnabled: Boolean): Builder {
            mVoiceRouteEnabled = voiceRouteEnabled
            return this
        }

        fun setAdaptiveLevelerEnabled(adaptiveLevelerEnabled: Boolean): Builder {
            mAdaptiveLevelerEnabled = adaptiveLevelerEnabled
            return this
        }

        fun setEncodeListener(encodeListener: AudioEncodeListener): Builder {
            mEncodeListener = requireNotNull(encodeListener, { "encodeListener" })
            return this
        }

        fun setFramesPerPacketListener(listener: FramesPerPacketListener?): Builder {
            mFramesPerPacketListener = listener
            return this
        }

        fun setTalkingListener(talkingListener: AudioOutput.AudioOutputListener?): Builder {
            mTalkingListener = talkingListener
            return this
        }

        fun setInputMode(inputMode: IInputMode): Builder {
            mInputMode = requireNotNull(inputMode, { "inputMode" })
            return this
        }

        @Throws(AudioException::class)
        fun initialize(
            self: User?,
            maxBandwidth: Int,
            codec: HumlaUDPMessageType?,
            targetId: Byte
        ): AudioHandler {
            val handler = AudioHandler(
                requireNotNull(mContext), requireNotNull(mLogger), mAudioStream, mAudioSource,
                mInputSampleRate, mTargetBitrate, mTargetFramesPerPacket, requireNotNull(mInputMode), targetId,
                mAmplitudeBoost, mHalfDuplexEnabled,
                /* preprocessorEnabled */ true, mAdaptiveLevelerEnabled,
                requireNotNull(mEncodeListener), requireNotNull(mTalkingListener)
            )
            handler.setFramesPerPacketListener(mFramesPerPacketListener)
            handler.setScoEnabled(mScoEnabled)
            handler.setVoiceRouteEnabled(mVoiceRouteEnabled)
            handler.initialize(self, maxBandwidth, codec)
            return handler
        }
    }
}
