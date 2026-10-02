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

    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SIZE = SAMPLE_RATE / 100; // 480 samples @ 10ms

    private final Context mContext;
    private final HumlaLogger mLogger;
    private final AudioInput mInput;
    private final AudioOutput mOutput;
    private final AudioOutput.AudioOutputListener mOutputListener;
    private final AudioEncodeListener mEncodeListener;
    private final NativeAudioInputEngine mNativeEngine;

    private volatile int mSession;
    private HumlaUDPMessageType mCodec;

    private final int mAudioStream;
    private final int mAudioSource;
    private volatile int mBitrate;
    private volatile int mFramesPerPacket;
    private volatile FramesPerPacketListener mFramesPerPacketListener;
    private final IInputMode mInputMode;
    private final float mAmplitudeBoost;

    private volatile boolean mInitialized;
    private volatile boolean mSelfMuted;
    private volatile boolean mServerMuted;
    private volatile boolean mSuppressed;
    private boolean mHalfDuplex;
    private volatile boolean mScoActive;
    private boolean mPreprocessorEnabled;
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

        if (mInputMode instanceof ActivityInputMode) {
            ActivityInputMode actMode = (ActivityInputMode) mInputMode;
            mNativeEngine.setVadThresholds(actMode.getVadMax(), actMode.getVadMin());
        }

        mInput = new AudioInput(this, mAudioSource);
        mOutput = new AudioOutput(mContext, mOutputListener);
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
        mSuppressed = self.isSuppressed();
        boolean isMuted = mSelfMuted || mServerMuted || mSuppressed;

        mOutput.startPlaying(mScoActive ? AudioManager.STREAM_VOICE_CALL : mAudioStream);
        mInitialized = true;
        updateMuteState(isMuted);
    }

    private void startRecording() {
        synchronized (mInput) {
            if (!mInput.isRecording()) {
                mInput.startRecording();
            }
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
        EffectiveAudioConfig config = computeEffectiveConfig(mBitrate, mFramesPerPacket, maxBandwidth);
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
     * without a live SCO link would misroute audio.
     */
    public void setScoEnabled(boolean scoEnabled) {
        mScoActive = scoEnabled;
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
        synchronized (mInput) {
            mInput.shutdown();
        }
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
    public void messageUserState(Mumble.UserState msg) {
        if (!mInitialized) return;

        if (msg.hasSession() && msg.getSession() == mSession) {
            boolean changed = false;
            if (msg.hasMute()) {
                mServerMuted = msg.getMute();
                changed = true;
            }
            if (msg.hasSelfMute()) {
                mSelfMuted = msg.getSelfMute();
                changed = true;
            }
            if (msg.hasSuppress()) {
                mSuppressed = msg.getSuppress();
                changed = true;
            }
            if (changed) {
                updateMuteState(mServerMuted || mSelfMuted || mSuppressed);
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
        if (data == null || length <= 0 || mEncodeListener == null) {
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
                }
            } else {
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
            }
        }
    }

    @Override
    public void onTalkingStateChanged(boolean isTalking, float peakEnergy) {
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
        // Plain like neighbors: the builder is configured single-threaded on
        // the service main thread; only the built handler's mScoActive is volatile.
        private boolean mScoEnabled;
        private boolean mPreprocessorEnabled;
        private boolean mAdaptiveLevelerEnabled = true;
        private IInputMode mInputMode;
        private AudioEncodeListener mEncodeListener;
        private FramesPerPacketListener mFramesPerPacketListener;
        private AudioOutput.AudioOutputListener mTalkingListener;

        public Builder setContext(Context context) {
            mContext = context;
            return this;
        }

        public Builder setLogger(HumlaLogger logger) {
            mLogger = logger;
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

        public Builder setPreprocessorEnabled(boolean preprocessorEnabled) {
            mPreprocessorEnabled = preprocessorEnabled;
            return this;
        }

        public Builder setAdaptiveLevelerEnabled(boolean adaptiveLevelerEnabled) {
            mAdaptiveLevelerEnabled = adaptiveLevelerEnabled;
            return this;
        }

        public Builder setEncodeListener(AudioEncodeListener encodeListener) {
            mEncodeListener = encodeListener;
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
            mInputMode = inputMode;
            return this;
        }

        public AudioHandler initialize(User self, int maxBandwidth, HumlaUDPMessageType codec, byte targetId)
                throws AudioException {
            AudioHandler handler = new AudioHandler(mContext, mLogger, mAudioStream, mAudioSource,
                    mInputSampleRate, mTargetBitrate, mTargetFramesPerPacket, mInputMode, targetId,
                    mAmplitudeBoost, mHalfDuplexEnabled,
                    mPreprocessorEnabled, mAdaptiveLevelerEnabled,
                    mEncodeListener, mTalkingListener);
            handler.setFramesPerPacketListener(mFramesPerPacketListener);
            handler.setScoEnabled(mScoEnabled);
            handler.initialize(self, maxBandwidth, codec);
            return handler;
        }
    }
}
