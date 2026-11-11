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
import java.util.Objects
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.StateFlow
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
import se.lublin.humla.voice.VoicePolicy
import se.lublin.humla.net.ProtobufMessages
import se.lublin.humla.state.UserInfo
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

        private const val TAG = "AudioHandler"

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
            if (ProtobufMessages.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth) {
                if (degradedFpp <= 4 && maxBandwidth <= 32000) {
                    degradedFpp = 4
                } else if (degradedFpp == 1 && maxBandwidth <= 64000) {
                    degradedFpp = 2
                } else if (degradedFpp == 2 && maxBandwidth <= 48000) {
                    degradedFpp = 4
                }
                while (ProtobufMessages.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth && degradedBitrate > 8000) {
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
    private var mVoiceRoute = true

    private var mBitrate: Int

    private var mFramesPerPacket: Int

    /**
     * Construction-time targets that bandwidth adaptation degrades from and
     * restores to (upstream parity: AudioInput reseeds from settings on
     * every adjustBandwidth). Seeding from the live degraded values would
     * ratchet quality down permanently after a transient cap.
     */
    private val mTargetBitrate: Int
    private val mTargetFramesPerPacket: Int

    private val mFramesPerPacketListener = java.util.concurrent.atomic.AtomicReference<FramesPerPacketListener?>(null)
    private val mInputMode: IInputMode
    private val mAmplitudeBoost: Float

    private var mInitialized: Boolean = false

    private var mSelfMuted: Boolean = false

    private var mServerMuted: Boolean = false

    /**
     * Moderator-applied deafening, tracked separately so a deaf-only packet
     * (murmur's else-if broadcast form omits the paired mute) still closes
     * the gate. Mirrors the admin-mute coercion in UserTransitions.
     */
    private var mServerDeafened: Boolean = false

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
    private var mOptimisticSelfMuteActive: Boolean = false
    private val mHalfDuplex: Boolean

    private var mScoActive: Boolean = false

    // TODO(ODD-21-followup): collapse this now-constant flag; the Builder
    // hardcodes preprocessor-on and no production path can reach off.
    private val mPreprocessorEnabled: Boolean
    private var mAdaptiveLevelerEnabled: Boolean

    private var mTalking: Boolean = false

    private var mTargetId: Byte = 0

    private var mProtobufUdp: Boolean = false

    private val policyRef = AtomicReference<StateFlow<VoicePolicy>?>(null)
    private var lastPolicyTransmit: Boolean? = null
    private var lastPolicyPtt: Boolean? = null
    private var lastPolicyBitrate: Int? = null
    private var lastPolicyFpp: Int? = null
    private var lastPolicyVoiceTarget: Int? = null

    // Pre-allocated packet buffers for zero heap allocation on audio path.
    // (Step 4) No locks remain: gate fields are confined to the session
    // work-dispatcher plus main-thread optimistic updates and the single
    // voice-control owner; every update recomputes the composite from
    // current fields and the native setter early-returns on no-change, so
    // the last update converges. See updateMuteState/applyInterimPolicy.
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

    /** Destroys capture + native engine made owned before a later init step failed.
     *
     * Pre-publication today, so uncontended. */
    private fun destroyInputAndEngine() {
        // Best-effort teardown must never mask the original failure, fatal
        // or otherwise: Throwable guards throughout, original rethrown.
        run {
            try {
                mInput.shutdown()
            } catch (ignored: Throwable) {
            }
            try {
                mNativeEngine.destroy()
            } catch (ignored: Throwable) {
            }
        }
    }

    @Throws(AudioException::class)
    fun initialize(self: UserInfo?, maxBandwidth: Int, codec: HumlaUDPMessageType?) {
        // Field setup under A; engine fan-out (bandwidth + mute) outside A:
        // setMaxBandwidth takes E and updateMuteState takes E plus a bounded
        // join, while the capture thread needs A for onTalkingStateChanged.
        if (self == null) return
        run {
            if (mInitialized) return
            mSession = self.session

            setCodec(codec)
            mSelfMuted = self.selfMuted
            mServerMuted = self.muted
            mServerDeafened = self.deafened
            mSuppressed = self.suppressed
            // Flag before fan-out (previously after startPlaying): the mute
            // and bandwidth paths are thread-safe via their own locks and
            // re-application is idempotent, so a server message arriving
            // during the blocking startPlaying window below would apply
            // instead of dropping on the !mInitialized guard.
            // Defense-in-depth: this instance is unpublished until initialize
            // returns, so no such message can arrive in-tree today.
            mInitialized = true
        }

        setMaxBandwidth(maxBandwidth)

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
            // Unflag: the pipeline never went live (see the early flag above).
            run {
                mInitialized = false
            }
            destroyInputAndEngine()
            throw t
        }
        // The stream int is route intent on the voice route (see field):
        // earpiece vs speakerphone. Set explicitly both ways — the flag is
        // global AudioManager state and a speaker-then-earpiece recreate
        // must clear it; media routes clear it for hygiene (voice-call
        // routing ignores it there, but no stale flag may leak across).
        setSpeakerphone(mVoiceRoute && !mScoActive && mAudioStream != AudioManager.STREAM_VOICE_CALL)
        updateMuteState()
    }

    private fun startRecording() {
        // No outer hold on mInput: AudioInput entry points self-synchronize
        // and join outside their monitor; holding it here would re-pin the
        // lock across startRecording's bounded join.
        if (!mInput.isRecording()) {
            mInput.startRecording()
        }
    }

    /**
     * Applies the composite mute gate to the capture pipeline.
     *
     * Ownership (step 4 interim): this synchronous gate seeds init and
     * answers server/model transitions; the per-frame [applyInterimPolicy]
     * below is the live owner once a policy flow is attached, converging on
     * the same composite. Interim staleness is bounded by one collector
     * pass (session state + settings combine on a single dispatcher).
     */
    private fun updateMuteState() {
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
        val muted: Boolean
        run {
            run {
                if (!mInitialized) return
                muted = mServerMuted || mSelfMuted || mServerDeafened || mSuppressed
            }
            mNativeEngine.setMuted(muted)
            // Re-gate recording: shutdown flags mInitialized=false before
            // its join, so a mute racing disconnect cannot resurrect capture
            // after teardown (same check-then-act shape as startRecording
            // itself).
            run {
                if (!mInitialized) return
            }
            // Deliberately held across startRecording's bounded stale join
            // (≤200 ms). No deadlock — a stale capture needs only
            // short-lived engine access — at most concurrent mute
            // updates serialize behind teardown of a dead capture thread.
            startRecording()
        }
    }

    /**
     * Applies an optimistic self-mute gate to the capture pipeline.
     *
     * Desktop parity: Mumble's AudioInput reads the local desired-state
     * (Global::get().s.bMute) every frame, so the mic cuts on click rather
     * than after the server-echo RTT. The echo remains the model confirm
     * (see UserTransitions); the gate echo in {@link #messageUserState} is
     * ignored once an optimistic value exists.
     *
     * @param muted the effective mute target after central deaf-implies-mute
     *              coercion (see HumlaService.setSelfMuteDeafState); must be
     *              true whenever the user is deafened.
     */
    fun setSelfMutedOptimistic(muted: Boolean) {
        run {
            mSelfMuted = muted
            mOptimisticSelfMuteActive = true
            if (!mInitialized) return
        }
        updateMuteState()
    }

    fun isInitialized(): Boolean {
        return mInitialized
    }

    fun isPlaying(): Boolean {
        run {
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
        run {
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
        run {
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
            val listener = mFramesPerPacketListener.get()
            if (listener != null) {
                listener.onEffectiveFramesPerPacketChanged(applied.framesPerPacket)
            }
        }
    }

    /** Snapshot read; for a guaranteed-consistent pair use {@link #getCurrentBandwidth}. */
    fun getFramesPerPacket(): Int {
        run {
            return mFramesPerPacket
        }
    }

    fun setFramesPerPacketListener(listener: FramesPerPacketListener?) {
        mFramesPerPacketListener.set(listener)
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
        require(!mInitialized) { "setScoEnabled requires pipeline recreation" }
        mScoActive = scoEnabled
    }

    /**
     * Selects the VoIP triple (voice source/mode/usage) vs the media path.
     * Set only at pipeline creation from the service route policy, mirroring
     * setScoEnabled: live pipelines must be recreated to apply it.
     */
    fun setVoiceRouteEnabled(voiceRoute: Boolean) {
        require(!mInitialized) { "setVoiceRouteEnabled requires pipeline recreation" }
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
        run {
            return ProtobufMessages.calculateAudioBandwidth(mBitrate, mFramesPerPacket)
        }
    }

    fun shutdown() {
        // Flag + join + destroy: the capture path re-checks the flag after
        // the join, and a mute racing disconnect cannot resurrect capture
        // after teardown. mInput/mOutput self-synchronize their joins.
        // Talking state resets for hygiene on the discarded instance; the disconnect notification
        // fires only on a real true→false edge (a repeat false would only
        // re-trigger downstream model lookups and UI refreshes).
        val wasTalking: Boolean
        run {
            run {
                mInitialized = false
                wasTalking = mTalking
                mTalking = false
                policyRef.set(null)
                lastPolicyTransmit = null
                lastPolicyPtt = null
                lastPolicyBitrate = null
                lastPolicyFpp = null
                lastPolicyVoiceTarget = null
            }
            mInput.shutdown()
            mOutput.stopPlaying()
            mNativeEngine.destroy()
            // Belt-and-braces: stopPlaying() already clears this after
            // joining the render thread; kept explicit so the disconnect
            // state does not depend on that callee-side effect.
            mOutput.setHalfDuplexMuted(false)
        }
        if (wasTalking) {
            mEncodeListener.onTalkingStateChanged(false)
        }
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

    override fun messageUserState(msg: Mumble.UserState) {
        // Fields under `this`, gate outside it: updateMuteState takes E plus
        // a bounded join while the capture thread needs `this` (server-echo
        // path; see docs/mute-tap-deadlock).
        run {
            if (!mInitialized) return

            if (!msg.hasSession() || msg.session != mSession) return
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
            if (!changed) return
        }
        updateMuteState()
    }

    override fun messageUserRemove(msg: Mumble.UserRemove) {
        super.messageUserRemove(msg)
        run {
            mOutput.removeUser(msg.session)
        }
    }

    override fun messageVoiceData(data: ByteArray, messageType: HumlaUDPMessageType) {
        run {
            mOutput.queueVoiceData(data, messageType)
        }
    }

    override fun messageProtobufAudio(msg: MumbleUDP.Audio) {
        run {
            mOutput.queueProtobufVoiceData(msg)
        }
    }

    override fun messageProtobufPing(msg: MumbleUDP.Ping) {
        // Handled in Transport (tunnelled voice bypasses the UDP path)
    }

    fun setProtobufUdp(protobufUdp: Boolean) {
        mProtobufUdp = protobufUdp
    }

    fun isProtobufUdp(): Boolean {
        return mProtobufUdp
    }

    fun setPolicyFlow(flow: StateFlow<VoicePolicy>) {
        policyRef.set(flow)
    }

    override fun onAudioInputReceived(frame: ShortArray?, frameSize: Int) {
        applyInterimPolicy()
        mNativeEngine.processFrame(frame, 0, frameSize)
    }

    private fun applyInterimPolicy() {
        val flow = policyRef.get() ?: return
        val policy = try {
            flow.value
        } catch (_: Exception) {
            return
        }
        if (lastPolicyTransmit != policy.transmitAllowed) {
            lastPolicyTransmit = policy.transmitAllowed
            try {
                mNativeEngine.setMuted(!policy.transmitAllowed)
            } catch (_: Exception) {
            }
        }
        if (lastPolicyPtt != policy.pttPressed) {
            lastPolicyPtt = policy.pttPressed
            try {
                mNativeEngine.setPttTalking(policy.pttPressed)
            } catch (_: Exception) {
            }
        }
        if (lastPolicyBitrate != policy.bitrate || lastPolicyFpp != policy.framesPerPacket) {
            lastPolicyBitrate = policy.bitrate
            lastPolicyFpp = policy.framesPerPacket
            try {
                mNativeEngine.setBitrate(policy.bitrate)
            } catch (_: Exception) {
            }
            try {
                mNativeEngine.setFramesPerPacket(policy.framesPerPacket)
            } catch (_: Exception) {
            }
        }
        if (lastPolicyVoiceTarget != policy.voiceTarget) {
            lastPolicyVoiceTarget = policy.voiceTarget
            try {
                setVoiceTargetId(policy.voiceTarget.coerceIn(0, 31).toByte())
            } catch (_: Exception) {
            }
        }
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

        run {
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
                    val flags = (HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5) or
                        (mTargetId.toInt() and 0x1F)

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

    override fun onTalkingStateChanged(isTalking: Boolean, peakEnergy: Float) {
        // Check-and-set under the lock (concurrent native callbacks must not
        // duplicate or drop transitions), fan out outside it: the encode
        // listener and mOutput each take locks of their own, and the capture
        // thread must never hold `this` across them.
        val changed: Boolean
        run {
            changed = mTalking != isTalking
            if (changed) {
                mTalking = isTalking
            }
        }
        if (changed) {
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

        // Plain like neighbors: all fields are programmed on the single
        // voice-control dispatcher before initialize; the built handler is
        // then confined to session callbacks, the capture thread, and the
        // main-thread optimistic setters.
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
            mContext = Objects.requireNonNull(context, "context")
            return this
        }

        fun setLogger(logger: HumlaLogger): Builder {
            mLogger = Objects.requireNonNull(logger, "logger")
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
            mEncodeListener = Objects.requireNonNull(encodeListener, "encodeListener")
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
            mInputMode = Objects.requireNonNull(inputMode, "inputMode")
            return this
        }

        @Throws(AudioException::class)
        fun initialize(
            self: UserInfo?,
            maxBandwidth: Int,
            codec: HumlaUDPMessageType?,
            targetId: Byte
        ): AudioHandler {
            val handler = AudioHandler(
                requireNotNull(mContext), requireNotNull(mLogger), mAudioStream, mAudioSource,
                mInputSampleRate, mTargetBitrate, mTargetFramesPerPacket, requireNotNull(mInputMode), targetId,
                mAmplitudeBoost, mHalfDuplexEnabled,
                /* preprocessorEnabled */ true, mAdaptiveLevelerEnabled,
                requireNotNull(mEncodeListener),
                // Intentional fail-fast (no original counterpart): the original
                // passed a null talking listener through to a later NPE; every
                // in-tree caller sets it, so this only trips misuse.
                requireNotNull(mTalkingListener)
            )
            handler.setFramesPerPacketListener(mFramesPerPacketListener)
            handler.setScoEnabled(mScoEnabled)
            handler.setVoiceRouteEnabled(mVoiceRouteEnabled)
            handler.initialize(self, maxBandwidth, codec)
            return handler
        }
    }
}
