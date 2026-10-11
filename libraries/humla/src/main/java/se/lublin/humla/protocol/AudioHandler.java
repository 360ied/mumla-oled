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

package se.lublin.humla.protocol;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import com.google.protobuf.ByteString;

import se.lublin.humla.Constants;
import se.lublin.humla.R;
import se.lublin.humla.audio.AudioInput;
import se.lublin.humla.audio.AudioOutput;
import se.lublin.humla.audio.NativeAudioInputEngine;
import se.lublin.humla.audio.inputmode.ActivityInputMode;
import se.lublin.humla.audio.inputmode.ContinuousInputMode;
import se.lublin.humla.audio.inputmode.IInputMode;
import se.lublin.humla.audio.inputmode.ToggleInputMode;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.exception.AudioInitializationException;
import se.lublin.humla.model.User;
import se.lublin.humla.net.HumlaConnection;
import se.lublin.humla.net.HumlaUDPMessageType;
import se.lublin.humla.net.PacketBuffer;
import se.lublin.humla.protobuf.Mumble;
import se.lublin.humla.protobuf.MumbleUDP;
import se.lublin.humla.util.HumlaLogger;
import se.lublin.humla.util.HumlaNetworkListener;

/**
 * Modern Audio Protocol Handler.
 *
 * Bridges network audio messages to low-latency capture and playback.
 * Powered natively by NativeAudioInputEngine with RNNoise DSP, Pre-Speech Ring Buffer (80ms),
 * Dual-Threshold Hysteresis VAD, Soft-Knee Saturation Limiter, and Mandatory Hard CBR Opus.
 */
public class AudioHandler extends HumlaNetworkListener
        implements AudioInput.AudioInputListener, NativeAudioInputEngine.AudioInputEngineListener {

    // Canonical 48 kHz / 480-sample (10 ms) framing shared with AudioInput
    // and the native engine: keep all three in agreement (no single source;
    // JNI and the capture layer each need their own constants).
    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SIZE = SAMPLE_RATE / 100; // 480 samples @ 10ms
    private static final String TAG = "AudioHandler";

    private final Context mContext;
    private final HumlaLogger mLogger;
    private final AudioInput mInput;
    private final AudioOutput mOutput;
    private final AudioOutput.AudioOutputListener mOutputListener;
    private final AudioEncodeListener mEncodeListener;
    private final NativeAudioInputEngine mNativeEngine;

    private volatile int mSession;
    private HumlaUDPMessageType mCodec;

    // Route intent, not a literal stream: on the voice route, VOICE_CALL
    // selects earpiece, anything else speakerphone (see initialize()).
    // On the media route the value is ignored for output (always MUSIC).
    private final int mAudioStream;
    private final int mAudioSource;
    /**
     * Voice route (VoIP triple) vs media route. Set only at pipeline
     * creation from the service route policy, mirroring setScoEnabled:
     * live pipelines are recreated to apply changes.
     */
    private volatile boolean mVoiceRoute = true;
    private volatile int mBitrate;
    private volatile int mFramesPerPacket;
    /**
     * Construction-time targets that bandwidth adaptation degrades from and
     * restores to (upstream parity: AudioInput reseeds from settings on
     * every adjustBandwidth). Seeding from the live degraded values would
     * ratchet quality down permanently after a transient cap.
     */
    private final int mTargetBitrate;
    private final int mTargetFramesPerPacket;
    private volatile FramesPerPacketListener mFramesPerPacketListener;
    private final IInputMode mInputMode;
    private final float mAmplitudeBoost;

    private volatile boolean mInitialized;
    private volatile boolean mSelfMuted;
    private volatile boolean mServerMuted;
    /**
     * Moderator-applied deafening, tracked separately so a deaf-only packet
     * (murmur's else-if broadcast form omits the paired mute) still closes
     * the gate. Mirrors User.setDeafened coercion; like the rest of the gate
     * it is read and written under synchronized(this).
     */
    private volatile boolean mServerDeafened;
    private volatile boolean mSuppressed;
    /**
     * Once the client has expressed a desired self-mute, the gate follows
     * only that: echoes are delayed copies of our own in-order requests and
     * carry no newer information, so a stale echo must never move the gate
     * (rapid mute-unmute-mute would otherwise transiently reopen the mic
     * while the UI shows muted). No reset is needed: the handler instance is
     * per-connection, and intra-connection pipeline recreations re-seed the
     * gate from the optimistically updated User in {@link #initialize}.
     */
    private volatile boolean mOptimisticSelfMuteActive;
    private final boolean mHalfDuplex;
    private volatile boolean mScoActive;
    // TODO(ODD-21-followup): collapse this now-constant flag; the Builder
    // hardcodes preprocessor-on and no production path can reach off.
    private final boolean mPreprocessorEnabled;
    private boolean mAdaptiveLevelerEnabled;
    private volatile boolean mTalking;

    private volatile byte mTargetId;
    private volatile boolean mProtobufUdp;

    // Pre-allocated packet buffers for zero heap allocation on audio path
    private final Object mPacketBufferLock = new Object();
    private final byte[] mProtobufPacketBuffer = new byte[2048];
    private final byte[] mLegacyPacketBuffer = new byte[1024];
    private final PacketBuffer mLegacyDataStream = new PacketBuffer(mLegacyPacketBuffer, 1024);

    public AudioHandler(Context context, HumlaLogger logger, int audioStream, int audioSource,
                        int sampleRate, int targetBitrate, int targetFramesPerPacket,
                        IInputMode inputMode, byte targetId, float amplitudeBoost,
                        boolean halfDuplexEnabled,
                        boolean preprocessorEnabled,
                        AudioEncodeListener encodeListener,
                        AudioOutput.AudioOutputListener outputListener) throws AudioInitializationException {
        this(context, logger, audioStream, audioSource, sampleRate, targetBitrate, targetFramesPerPacket,
                inputMode, targetId, amplitudeBoost, halfDuplexEnabled,
                preprocessorEnabled, true, encodeListener, outputListener);
    }

    public AudioHandler(Context context, HumlaLogger logger, int audioStream, int audioSource,
                        int sampleRate, int targetBitrate, int targetFramesPerPacket,
                        IInputMode inputMode, byte targetId, float amplitudeBoost,
                        boolean halfDuplexEnabled,
                        boolean preprocessorEnabled, boolean adaptiveLevelerEnabled,
                        AudioEncodeListener encodeListener,
                        AudioOutput.AudioOutputListener outputListener) throws AudioInitializationException {
        mContext = context;
        mLogger = logger;
        mAudioStream = audioStream;
        mBitrate = targetBitrate;
        mFramesPerPacket = sanitizeFramesPerPacket(targetFramesPerPacket);
        mTargetBitrate = targetBitrate;
        mTargetFramesPerPacket = sanitizeFramesPerPacket(targetFramesPerPacket);
        mInputMode = inputMode;
        mAmplitudeBoost = amplitudeBoost;
        mHalfDuplex = halfDuplexEnabled;
        mPreprocessorEnabled = preprocessorEnabled;
        mAdaptiveLevelerEnabled = adaptiveLevelerEnabled;
        mEncodeListener = encodeListener;
        mOutputListener = outputListener;
        mTalking = false;
        mTargetId = targetId;
        mAudioSource = audioSource;

        int nativeMode = NativeAudioInputEngine.INPUT_MODE_VOICE_ACTIVITY;
        if (mInputMode instanceof ToggleInputMode) {
            nativeMode = NativeAudioInputEngine.INPUT_MODE_PUSH_TO_TALK;
        } else if (mInputMode instanceof ContinuousInputMode) {
            nativeMode = NativeAudioInputEngine.INPUT_MODE_CONTINUOUS;
        }

        byte[] modelData = null;
        if (mPreprocessorEnabled) {
            NativeAudioInputEngine.loadRnnoiseModel(context);
            modelData = NativeAudioInputEngine.getCachedRnnoiseModel();
        }

        mNativeEngine = new NativeAudioInputEngine(
                mBitrate,
                mFramesPerPacket,
                mAmplitudeBoost,
                mPreprocessorEnabled,
                mAdaptiveLevelerEnabled,
                nativeMode,
                modelData,
                this);

        // Anything below owns system handles (native engine above, mic
        // below): clean up on failure so a failed connect never abandons
        // a live native handle or a held mic.
        try {
            if (mInputMode instanceof ActivityInputMode) {
                ActivityInputMode actMode = (ActivityInputMode) mInputMode;
                mNativeEngine.setVadThresholds(actMode.getVadMax(), actMode.getVadMin());
            }

            AudioInput input = new AudioInput(this, mAudioSource);
            try {
                mOutput = new AudioOutput(mContext, mOutputListener);
            } catch (RuntimeException e) {
                // Output construction failed after capture was already
                // holding the mic: shut it down before propagating.
                try {
                    input.shutdown();
                } catch (Exception ignored) {
                }
                throw e;
            }
            mInput = input;
        } catch (AudioInitializationException | RuntimeException e) {
            // mInput was never assigned (its construction threw): only the
            // native engine above needs destroying. (destroyInputAndEngine
            // reads mInput and so must not run inside the constructor.)
            try {
                mNativeEngine.destroy();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    /** Destroys capture + native engine made owned before a later init step failed. */
    private void destroyInputAndEngine() {
        try {
            if (mInput != null) {
                mInput.shutdown();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mNativeEngine != null) {
                mNativeEngine.destroy();
            }
        } catch (Exception ignored) {
        }
    }

    private static int sanitizeFramesPerPacket(int fpp) {
        return Constants.sanitizeFramesPerPacket(fpp);
    }

    public synchronized void initialize(User self, int maxBandwidth, HumlaUDPMessageType codec) throws AudioException {
        if (mInitialized || self == null) return;
        mSession = self.getSession();

        setMaxBandwidth(maxBandwidth);
        setCodec(codec);
        mSelfMuted = self.isSelfMuted();
        mServerMuted = self.isMuted();
        mServerDeafened = self.isDeafened();
        mSuppressed = self.isSuppressed();
        boolean isMuted = mSelfMuted || mServerMuted || mServerDeafened || mSuppressed;

        try {
            mOutput.startPlaying((mVoiceRoute || mScoActive) ? AudioManager.STREAM_VOICE_CALL
                    : AudioManager.STREAM_MUSIC);
        } catch (AudioException | RuntimeException e) {
            // The constructor owns a native engine + capture (AudioRecord +
            // HW effects): destroy the partial pipeline before rethrow so
            // the caller never abandons a half-built handler holding the mic.
            // RuntimeException is caught too: an unchecked throw from
            // startPlaying (e.g. SecurityException) must not skip teardown.
            destroyInputAndEngine();
            throw e;
        }
        // The stream int is route intent on the voice route (see field):
        // earpiece vs speakerphone. Set explicitly both ways — the flag is
        // global AudioManager state and a speaker-then-earpiece recreate
        // must clear it; media routes clear it for hygiene (voice-call
        // routing ignores it there, but no stale flag may leak across).
        setSpeakerphone(mVoiceRoute && !mScoActive && mAudioStream != AudioManager.STREAM_VOICE_CALL);
        mInitialized = true;
        updateMuteState(isMuted);
    }

    private void startRecording() {
        // No outer hold on mInput: AudioInput entry points self-synchronize
        // and join outside their monitor; holding it here would re-pin the
        // lock across startRecording's bounded join.
        if (!mInput.isRecording()) {
            mInput.startRecording();
        }
    }

    private synchronized void updateMuteState(boolean muted) {
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
        if (mNativeEngine != null) {
            mNativeEngine.setMuted(muted);
        }
        if (mInput != null && mInitialized) {
            startRecording();
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
    public synchronized void setSelfMutedOptimistic(boolean muted) {
        mSelfMuted = muted;
        mOptimisticSelfMuteActive = true;
        if (!mInitialized) return;
        updateMuteState(mServerMuted || mSelfMuted || mServerDeafened || mSuppressed);
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public boolean isPlaying() {
        synchronized (mOutput) {
            return mOutput.isPlaying();
        }
    }

    public AudioOutput getAudioOutput() {
        return mOutput;
    }

    public HumlaUDPMessageType getCodec() {
        return mCodec;
    }

    public void recreateEncoder() {
        setCodec(mCodec);
    }

    public void setCodec(HumlaUDPMessageType codec) {
        mCodec = codec;
    }

    /** Explicit earpiece/speakerphone routing for the voice route. */
    private void setSpeakerphone(boolean on) {
        AudioManager audioManager = null;
        try {
            audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        } catch (Exception e) {
            Log.w(TAG, "VoIP route: no AudioManager (" + e.getMessage() + ")");
            return;
        }
        if (audioManager == null) return;
        try {
            audioManager.setSpeakerphoneOn(on);
            Log.i(TAG, "VoIP route: speakerphone " + (on ? "on" : "off"));
        } catch (Exception e) {
            Log.w(TAG, "VoIP route: setSpeakerphoneOn threw (" + e.getMessage() + ")");
        }
    }

    public int getAudioStream() {
        return mAudioStream;
    }

    public int getAudioSource() {
        return mAudioSource;
    }

    public int getSampleRate() {
        return SAMPLE_RATE;
    }

    public int getBitrate() {
        return mBitrate;
    }

    /**
     * Pure bandwidth-degradation decision: does not call Android APIs, so JVM tests can cover it.
     * Do not add logging, native calls, or context access here.
     */
    static EffectiveAudioConfig computeEffectiveConfig(int bitrate, int framesPerPacket, int maxBandwidth) {
        if (maxBandwidth == -1) {
            return new EffectiveAudioConfig(bitrate, sanitizeFramesPerPacket(framesPerPacket));
        }
        int degradedBitrate = bitrate;
        int degradedFpp = framesPerPacket;

        // fpp==6 is already the largest packet: no growth branch applies by
        // design, so only the bitrate loop below degrades (pinned by test).
        if (HumlaConnection.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth) {
            if (degradedFpp <= 4 && maxBandwidth <= 32000) {
                degradedFpp = 4;
            } else if (degradedFpp == 1 && maxBandwidth <= 64000) {
                degradedFpp = 2;
            } else if (degradedFpp == 2 && maxBandwidth <= 48000) {
                degradedFpp = 4;
            }
            while (HumlaConnection.calculateAudioBandwidth(degradedBitrate, degradedFpp) > maxBandwidth && degradedBitrate > 8000) {
                degradedBitrate -= 1000;
            }
        }
        degradedBitrate = Math.max(8000, degradedBitrate);
        degradedFpp = sanitizeFramesPerPacket(degradedFpp);
        return new EffectiveAudioConfig(degradedBitrate, degradedFpp);
    }

    private void setMaxBandwidth(int maxBandwidth) {
        // Seed from construction targets, not live values, so a relaxed or
        // cleared cap restores quality instead of ratcheting (see fields).
        EffectiveAudioConfig config = computeEffectiveConfig(mTargetBitrate, mTargetFramesPerPacket, maxBandwidth);
        if (config.bitrate == mBitrate && config.framesPerPacket == mFramesPerPacket) {
            return;
        }
        int oldFramesPerPacket = mFramesPerPacket;
        mBitrate = config.bitrate;
        mFramesPerPacket = config.framesPerPacket;
        if (mNativeEngine != null) {
            mNativeEngine.setBitrate(mBitrate);
            mNativeEngine.setFramesPerPacket(mFramesPerPacket);
        }
        mLogger.logInfo(mContext.getString(R.string.audio_max_bandwidth,
                maxBandwidth / 1000, mBitrate / 1000, mFramesPerPacket * Constants.FRAME_DURATION_MS));
        if (config.framesPerPacket != oldFramesPerPacket) {
            FramesPerPacketListener listener = mFramesPerPacketListener; // volatile read
            if (listener != null) {
                listener.onEffectiveFramesPerPacketChanged(config.framesPerPacket);
            }
        }
    }

    public int getFramesPerPacket() {
        return mFramesPerPacket;
    }

    public void setFramesPerPacketListener(FramesPerPacketListener listener) {
        mFramesPerPacketListener = listener;
    }

    public float getAmplitudeBoost() {
        return mAmplitudeBoost;
    }

    public boolean isHalfDuplex() {
        return mHalfDuplex;
    }

    /**
     * Selects the voice-call route for confirmed Bluetooth SCO, mirroring
     * handset mode. Set only on confirmed link state (see BluetoothScoManager),
     * never on the raw user toggle: starting playback on the voice-call stream
     * without a live SCO link would misroute audio. Takes effect on the next
     * pipeline creation; live pipelines must be recreated to apply it.
     */
    public void setScoEnabled(boolean scoEnabled) {
        mScoActive = scoEnabled;
    }

    /**
     * Selects the VoIP triple (voice source/mode/usage) vs the media path.
     * Set only at pipeline creation from the service route policy, mirroring
     * setScoEnabled: live pipelines must be recreated to apply it.
     */
    public void setVoiceRouteEnabled(boolean voiceRoute) {
        mVoiceRoute = voiceRoute;
    }

    public void setAdaptiveLevelerEnabled(boolean enabled) {
        mAdaptiveLevelerEnabled = enabled;
        if (mNativeEngine != null) {
            mNativeEngine.setAdaptiveLevelerEnabled(enabled);
        }
    }

    public boolean isAdaptiveLevelerEnabled() {
        return mAdaptiveLevelerEnabled;
    }

    public int getCurrentBandwidth() {
        return HumlaConnection.calculateAudioBandwidth(mBitrate, mFramesPerPacket);
    }

    public synchronized void shutdown() {
        // Never hold mInput across shutdown: it joins the capture thread,
        // which needs the AudioInput monitor for release/enableEffects.
        // (Same shape as the mOutput rule below.)
        mInput.shutdown();
        // Never hold mOutput across stopPlaying: it joins the render thread,
        // which needs the AudioOutput monitor each loop to observe shutdown
        // and exit. Holding it here deadlocks disconnect (main in join,
        // render blocked on the monitor). stopPlaying self-synchronizes.
        mOutput.stopPlaying();
        if (mNativeEngine != null) {
            mNativeEngine.destroy();
        }
        mInitialized = false;

        if (mEncodeListener != null) {
            mEncodeListener.onTalkingStateChanged(false);
        }
        if (mOutput != null) {
            mOutput.setHalfDuplexMuted(false);
        }
    }

    @Override
    public void messageCodecVersion(Mumble.CodecVersion msg) {
        if (!mInitialized) return;

        HumlaUDPMessageType codec = HumlaUDPMessageType.UDPVoiceOpus;
        if (codec != mCodec) {
            setCodec(codec);
        }
    }

    @Override
    public void messageServerSync(Mumble.ServerSync msg) {
        setMaxBandwidth(msg.hasMaxBandwidth() ? msg.getMaxBandwidth() : -1);
    }

    @Override
    public synchronized void messageUserState(Mumble.UserState msg) {
        if (!mInitialized) return;

        if (msg.hasSession() && msg.getSession() == mSession) {
            boolean changed = false;
            if (msg.hasMute() && mServerMuted != msg.getMute()) {
                mServerMuted = msg.getMute();
                changed = true;
            }
            if (msg.hasSelfMute() && !mOptimisticSelfMuteActive
                    && mSelfMuted != msg.getSelfMute()) {
                mSelfMuted = msg.getSelfMute();
                changed = true;
            }
            // Fail-closed murmur parity: a deaf-only broadcast omits self_mute
            // (murmur/Messages.cpp else-if form), but deaf implies mute. This
            // only ever closes the mic, so it is latch-independent and safe
            // under every interleaving.
            if (msg.hasSelfDeaf() && msg.getSelfDeaf() && !mSelfMuted) {
                mSelfMuted = true;
                changed = true;
            }
            if (msg.hasSuppress() && mSuppressed != msg.getSuppress()) {
                mSuppressed = msg.getSuppress();
                changed = true;
            }
            if (msg.hasDeaf() && mServerDeafened != msg.getDeaf()) {
                mServerDeafened = msg.getDeaf();
                changed = true;
            }
            if (changed) {
                updateMuteState(mServerMuted || mSelfMuted || mServerDeafened || mSuppressed);
            }
        }
    }

    @Override
    public void messageUserRemove(Mumble.UserRemove msg) {
        super.messageUserRemove(msg);
        synchronized (mOutput) {
            mOutput.removeUser(msg.getSession());
        }
    }

    @Override
    public void messageVoiceData(byte[] data, HumlaUDPMessageType messageType) {
        synchronized (mOutput) {
            mOutput.queueVoiceData(data, messageType);
        }
    }

    @Override
    public void messageProtobufAudio(MumbleUDP.Audio msg) {
        synchronized (mOutput) {
            mOutput.queueProtobufVoiceData(msg);
        }
    }

    @Override
    public void messageProtobufPing(MumbleUDP.Ping msg) {
        // Handled in HumlaConnection
    }

    public void setProtobufUdp(boolean protobufUdp) {
        mProtobufUdp = protobufUdp;
    }

    public boolean isProtobufUdp() {
        return mProtobufUdp;
    }

    @Override
    public void onAudioInputReceived(short[] frame, int frameSize) {
        if (mNativeEngine != null) {
            mNativeEngine.processFrame(frame, 0, frameSize);
        }
    }

    @Override
    public void onAudioPacketEncoded(byte[] data, int length, int frames, boolean isTerminator, long frameNumber) {
        // length is authoritative; frames is informational from the native
        // engine (actual frames are implicit in length). Zero-length
        // terminators never occur: the native engine encodes silence instead
        // of emitting empty terminators, so length<=0 is safely dropped —
        // with a warning when a terminator is dropped, since that would
        // strand the peer's talking indicator if the native contract changes.
        if (data == null || length <= 0 || mEncodeListener == null) {
            if (isTerminator && length <= 0 && data != null && mEncodeListener != null) {
                Log.w(TAG, "Dropping zero-length terminator; peer talk state may strand");
            }
            return;
        }

        synchronized (mPacketBufferLock) {
            if (mProtobufUdp) {
                MumbleUDP.Audio.Builder audioBuilder = MumbleUDP.Audio.newBuilder();
                if (mTargetId != 0) {
                    audioBuilder.setTarget(mTargetId & 0xFF);
                }
                audioBuilder.setFrameNumber(frameNumber);
                audioBuilder.setOpusData(ByteString.copyFrom(data, 0, length));
                if (isTerminator) {
                    audioBuilder.setIsTerminator(true);
                }

                byte[] protoBytes = audioBuilder.build().toByteArray();
                int totalLen = 1 + protoBytes.length;
                if (totalLen <= mProtobufPacketBuffer.length) {
                    mProtobufPacketBuffer[0] = 0x00; // Protobuf Audio header
                    System.arraycopy(protoBytes, 0, mProtobufPacketBuffer, 1, protoBytes.length);
                    mEncodeListener.onAudioEncoded(mProtobufPacketBuffer, totalLen);
                } else {
                    Log.w(TAG, "Dropping oversize protobuf audio packet (" + totalLen + " bytes)");
                }
            } else {
                // Worst case: 1 flag byte + 2 varints (<=10 bytes each) +
                // payload. Pre-check so overflow drops with a log instead of
                // throwing BufferOverflowException to the native thread.
                if (21 + length > mLegacyPacketBuffer.length) {
                    Log.w(TAG, "Dropping oversize legacy audio packet (payload " + length + " bytes)");
                    return;
                }
                try {
                    int flags = 0;
                    flags |= HumlaUDPMessageType.UDPVoiceOpus.ordinal() << 5;
                    flags |= mTargetId & 0x1F;

                    mLegacyPacketBuffer[0] = (byte) (flags & 0xFF);
                    mLegacyDataStream.rewind();
                    mLegacyDataStream.skip(1);
                    mLegacyDataStream.writeLong(frameNumber);

                    long header = length & ((1 << 13) - 1);
                    if (isTerminator) {
                        header |= (1 << 13);
                    }
                    mLegacyDataStream.writeLong(header);
                    mLegacyDataStream.append(data, length);

                    int totalLen = mLegacyDataStream.size();
                    mEncodeListener.onAudioEncoded(mLegacyPacketBuffer, totalLen);
                } catch (java.nio.BufferOverflowException e) {
                    Log.w(TAG, "Dropping legacy audio packet after buffer overflow", e);
                }
            }
        }
    }

    @Override
    public synchronized void onTalkingStateChanged(boolean isTalking, float peakEnergy) {
        // Synchronized: concurrent native callbacks must not duplicate or
        // drop transitions via check-then-act on mTalking.
        if (mTalking != isTalking) {
            mTalking = isTalking;
            if (mEncodeListener != null) {
                mEncodeListener.onTalkingStateChanged(isTalking);
            }
            if (mHalfDuplex && mOutput != null) {
                mOutput.setHalfDuplexMuted(isTalking);
            }
        }
    }

    public void setVoiceTargetId(byte id) {
        mTargetId = id;
    }

    public void clearVoiceTarget() {
        mTargetId = 0;
    }

    public void setPttTalking(boolean talking) {
        if (mInputMode instanceof ToggleInputMode) {
            ((ToggleInputMode) mInputMode).setTalkingOn(talking);
        }
        if (mNativeEngine != null) {
            mNativeEngine.setPttTalking(talking);
        }
    }

    public void setVadThresholds(float vadMax, float vadMin) {
        if (mNativeEngine != null) {
            mNativeEngine.setVadThresholds(vadMax, vadMin);
        }
    }

    public void setVadSquelchFloor(float minDb) {
        if (mNativeEngine != null) {
            mNativeEngine.setVadSquelchFloor(minDb);
        }
    }

    public float getVadSquelchFloor() {
        if (mNativeEngine != null) {
            return mNativeEngine.getVadSquelchFloor();
        }
        return NativeAudioInputEngine.DEFAULT_SQUELCH_MIN_DB;
    }

    /** Degraded effective audio config produced by bandwidth adaptation. Pure value type. */
    public static final class EffectiveAudioConfig {
        public final int bitrate;
        public final int framesPerPacket;

        public EffectiveAudioConfig(int bitrate, int framesPerPacket) {
            this.bitrate = bitrate;
            this.framesPerPacket = framesPerPacket;
        }
    }

    /** Notified when bandwidth adaptation changes the effective frames-per-packet. */
    @FunctionalInterface
    public interface FramesPerPacketListener {
        void onEffectiveFramesPerPacketChanged(int framesPerPacket);
    }

    public interface AudioEncodeListener {
        void onAudioEncoded(byte[] data, int length);
        void onTalkingStateChanged(boolean talking);
    }

    /**
     * A builder to configure and instantiate the audio protocol handler.
     */
    public static class Builder {
        private Context mContext;
        private HumlaLogger mLogger;
        private int mAudioStream;
        private int mAudioSource;
        private int mTargetBitrate;
        private int mTargetFramesPerPacket = Constants.DEFAULT_FRAMES_PER_PACKET;
        private int mInputSampleRate;
        private float mAmplitudeBoost;
        private boolean mHalfDuplexEnabled;
        // Plain like neighbors: most fields are programmed on the service
        // main thread, while the route group (sco/voice/source/stream) is
        // written under the HumlaService monitor (fine-grained blocks in
        // updateRouteBuilderLocked, stopScoWithNotify, and onDestroy; the
        // device query itself runs outside it); only the built handler's
        // mScoActive is volatile.
        private boolean mScoEnabled;
        // Defaults true to preserve step-2a behavior until the service
        // route policy drives it (C2); the service sets this explicitly
        // on every route application either way.
        private boolean mVoiceRouteEnabled = true;
        private boolean mAdaptiveLevelerEnabled = true;
        private IInputMode mInputMode;
        private AudioEncodeListener mEncodeListener;
        private FramesPerPacketListener mFramesPerPacketListener;
        private AudioOutput.AudioOutputListener mTalkingListener;

        public Builder setContext(Context context) {
            mContext = java.util.Objects.requireNonNull(context, "context");
            return this;
        }

        public Builder setLogger(HumlaLogger logger) {
            mLogger = java.util.Objects.requireNonNull(logger, "logger");
            return this;
        }

        public Builder setAudioStream(int audioStream) {
            mAudioStream = audioStream;
            return this;
        }

        public Builder setAudioSource(int audioSource) {
            mAudioSource = audioSource;
            return this;
        }

        public Builder setTargetBitrate(int targetBitrate) {
            mTargetBitrate = targetBitrate;
            return this;
        }

        public Builder setTargetFramesPerPacket(int targetFramesPerPacket) {
            mTargetFramesPerPacket = targetFramesPerPacket;
            return this;
        }

        public int getTargetFramesPerPacket() {
            return mTargetFramesPerPacket;
        }

        public Builder setInputSampleRate(int inputSampleRate) {
            mInputSampleRate = inputSampleRate;
            return this;
        }

        public Builder setAmplitudeBoost(float amplitudeBoost) {
            mAmplitudeBoost = amplitudeBoost;
            return this;
        }

        public Builder setHalfDuplexEnabled(boolean halfDuplexEnabled) {
            mHalfDuplexEnabled = halfDuplexEnabled;
            return this;
        }

        /**
         * Selects the voice-call route. Pass confirmed SCO link state only
         * (see {@link AudioHandler#setScoEnabled}), never the raw toggle.
         */
        public Builder setScoEnabled(boolean scoEnabled) {
            mScoEnabled = scoEnabled;
            return this;
        }

        /**
         * Selects the VoIP triple vs the media path. Pass the service
         * route policy value (see {@link AudioHandler#setVoiceRouteEnabled}).
         */
        public Builder setVoiceRouteEnabled(boolean voiceRouteEnabled) {
            mVoiceRouteEnabled = voiceRouteEnabled;
            return this;
        }

        public Builder setAdaptiveLevelerEnabled(boolean adaptiveLevelerEnabled) {
            mAdaptiveLevelerEnabled = adaptiveLevelerEnabled;
            return this;
        }

        public Builder setEncodeListener(AudioEncodeListener encodeListener) {
            mEncodeListener = java.util.Objects.requireNonNull(encodeListener, "encodeListener");
            return this;
        }

        public Builder setFramesPerPacketListener(FramesPerPacketListener listener) {
            mFramesPerPacketListener = listener;
            return this;
        }

        public Builder setTalkingListener(AudioOutput.AudioOutputListener talkingListener) {
            mTalkingListener = talkingListener;
            return this;
        }

        public Builder setInputMode(IInputMode inputMode) {
            mInputMode = java.util.Objects.requireNonNull(inputMode, "inputMode");
            return this;
        }

        public AudioHandler initialize(User self, int maxBandwidth, HumlaUDPMessageType codec, byte targetId)
                throws AudioException {
            AudioHandler handler = new AudioHandler(mContext, mLogger, mAudioStream, mAudioSource,
                    mInputSampleRate, mTargetBitrate, mTargetFramesPerPacket, mInputMode, targetId,
                    mAmplitudeBoost, mHalfDuplexEnabled,
                    /* preprocessorEnabled */ true, mAdaptiveLevelerEnabled,
                    mEncodeListener, mTalkingListener);
            handler.setFramesPerPacketListener(mFramesPerPacketListener);
            handler.setScoEnabled(mScoEnabled);
            handler.setVoiceRouteEnabled(mVoiceRouteEnabled);
            handler.initialize(self, maxBandwidth, codec);
            return handler;
        }
    }
}
