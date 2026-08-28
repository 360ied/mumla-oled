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

package se.lublin.humla.protocol;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.util.Log;

import com.google.protobuf.ByteString;

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

    private static final String TAG = "AudioHandler";

    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SIZE = SAMPLE_RATE / 100; // 480 samples @ 10ms

    private final Context mContext;
    private final HumlaLogger mLogger;
    private final AudioManager mAudioManager;
    private final AudioInput mInput;
    private final AudioOutput mOutput;
    private final AudioOutput.AudioOutputListener mOutputListener;
    private final AudioEncodeListener mEncodeListener;
    private final NativeAudioInputEngine mNativeEngine;

    private int mSession;
    private HumlaUDPMessageType mCodec;

    private final int mAudioStream;
    private final int mAudioSource;
    private int mBitrate;
    private int mFramesPerPacket;
    private final IInputMode mInputMode;
    private final float mAmplitudeBoost;

    private boolean mInitialized;
    private boolean mMuted;
    private boolean mBluetoothOn;
    private boolean mHalfDuplex;
    private boolean mPreprocessorEnabled;
    private String mEchoCancellationMethod;
    private boolean mTalking;

    private byte mTargetId;
    private boolean mProtobufUdp;

    // Pre-allocated packet buffers for zero heap allocation on audio path
    private final byte[] mProtobufPacketBuffer = new byte[2048];
    private final byte[] mLegacyPacketBuffer = new byte[1024];
    private final PacketBuffer mLegacyDataStream = new PacketBuffer(mLegacyPacketBuffer, 1024);

    public AudioHandler(Context context, HumlaLogger logger, int audioStream, int audioSource,
                        int sampleRate, int targetBitrate, int targetFramesPerPacket,
                        IInputMode inputMode, byte targetId, float amplitudeBoost,
                        boolean bluetoothEnabled, boolean halfDuplexEnabled,
                        boolean preprocessorEnabled, String echoCancellationMethod,
                        AudioEncodeListener encodeListener,
                        AudioOutput.AudioOutputListener outputListener) throws AudioInitializationException {
        mContext = context;
        mLogger = logger;
        mAudioStream = audioStream;
        mBitrate = targetBitrate;
        mFramesPerPacket = sanitizeFramesPerPacket(targetFramesPerPacket);
        mInputMode = inputMode;
        mAmplitudeBoost = amplitudeBoost;
        mBluetoothOn = bluetoothEnabled;
        mHalfDuplex = halfDuplexEnabled;
        mPreprocessorEnabled = preprocessorEnabled;
        mEchoCancellationMethod = echoCancellationMethod != null ? echoCancellationMethod : "none";
        mEncodeListener = encodeListener;
        mOutputListener = outputListener;
        mTalking = false;
        mTargetId = targetId;

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        int actualSource = audioSource;
        if ("system".equalsIgnoreCase(mEchoCancellationMethod)) {
            mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            actualSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        }
        mAudioSource = actualSource;

        int nativeMode = NativeAudioInputEngine.INPUT_MODE_VOICE_ACTIVITY;
        if (mInputMode instanceof ToggleInputMode) {
            nativeMode = NativeAudioInputEngine.INPUT_MODE_PUSH_TO_TALK;
        } else if (mInputMode instanceof ContinuousInputMode) {
            nativeMode = NativeAudioInputEngine.INPUT_MODE_CONTINUOUS;
        }

        mNativeEngine = new NativeAudioInputEngine(
                mBitrate,
                mFramesPerPacket,
                mAmplitudeBoost,
                mPreprocessorEnabled,
                nativeMode,
                this);

        mInput = new AudioInput(this, mAudioSource, mEchoCancellationMethod);
        mOutput = new AudioOutput(mOutputListener);
    }

    private static int sanitizeFramesPerPacket(int fpp) {
        // Opus supports 10ms, 20ms, 40ms, 60ms (1, 2, 4, 6 frames @ 10ms)
        if (fpp == 1 || fpp == 2 || fpp == 4 || fpp == 6) {
            return fpp;
        }
        return 2; // Default 20ms
    }

    public synchronized void initialize(User self, int maxBandwidth, HumlaUDPMessageType codec) throws AudioException {
        if (mInitialized) return;
        mSession = self.getSession();

        setMaxBandwidth(maxBandwidth);
        setCodec(codec);
        setServerMuted(self.isMuted() || self.isLocalMuted() || self.isSuppressed());
        startRecording();

        mOutput.startPlaying(mBluetoothOn ? AudioManager.STREAM_VOICE_CALL : mAudioStream);
        mInitialized = true;
    }

    private void startRecording() throws AudioException {
        synchronized (mInput) {
            if (!mInput.isRecording()) {
                mInput.startRecording();
            } else {
                throw new AudioException("Attempted to start recording while already recording!");
            }
        }
    }

    private void stopRecording() throws AudioException {
        synchronized (mInput) {
            if (mInput.isRecording()) {
                mInput.stopRecording();
            } else {
                throw new AudioException("Attempted to stop recording while not recording!");
            }
        }
    }

    private void setServerMuted(boolean muted) {
        mMuted = muted;
        if (mNativeEngine != null) {
            mNativeEngine.setMuted(muted);
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

    private void setMaxBandwidth(int maxBandwidth) {
        if (maxBandwidth == -1) {
            return;
        }
        int bitrate = mBitrate;
        int framesPerPacket = mFramesPerPacket;

        if (HumlaConnection.calculateAudioBandwidth(bitrate, framesPerPacket) > maxBandwidth) {
            if (framesPerPacket <= 4 && maxBandwidth <= 32000) {
                framesPerPacket = 4;
            } else if (framesPerPacket == 1 && maxBandwidth <= 64000) {
                framesPerPacket = 2;
            } else if (framesPerPacket == 2 && maxBandwidth <= 48000) {
                framesPerPacket = 4;
            }
            while (HumlaConnection.calculateAudioBandwidth(bitrate, framesPerPacket) > maxBandwidth && bitrate > 8000) {
                bitrate -= 1000;
            }
        }
        bitrate = Math.max(8000, bitrate);
        framesPerPacket = sanitizeFramesPerPacket(framesPerPacket);

        if (bitrate != mBitrate || framesPerPacket != mFramesPerPacket) {
            mBitrate = bitrate;
            mFramesPerPacket = framesPerPacket;
            if (mNativeEngine != null) {
                mNativeEngine.setBitrate(mBitrate);
                mNativeEngine.setFramesPerPacket(mFramesPerPacket);
            }
            mLogger.logInfo(mContext.getString(R.string.audio_max_bandwidth,
                    maxBandwidth / 1000, maxBandwidth / 1000, framesPerPacket * 10));
        }
    }

    public int getFramesPerPacket() {
        return mFramesPerPacket;
    }

    public float getAmplitudeBoost() {
        return mAmplitudeBoost;
    }

    public boolean isHalfDuplex() {
        return mHalfDuplex;
    }

    public int getCurrentBandwidth() {
        return HumlaConnection.calculateAudioBandwidth(mBitrate, mFramesPerPacket);
    }

    public synchronized void shutdown() {
        synchronized (mInput) {
            mInput.shutdown();
        }
        synchronized (mOutput) {
            mOutput.stopPlaying();
        }
        if (mNativeEngine != null) {
            mNativeEngine.destroy();
        }
        mInitialized = false;
        mBluetoothOn = false;

        if (mEncodeListener != null) {
            mEncodeListener.onTalkingStateChanged(false);
        }
    }

    @Override
    public void messageCodecVersion(Mumble.CodecVersion msg) {
        if (!mInitialized) return;

        HumlaUDPMessageType codec;
        if (msg.hasOpus() && msg.getOpus()) {
            codec = HumlaUDPMessageType.UDPVoiceOpus;
        } else if (msg.hasBeta() && !msg.getPreferAlpha()) {
            codec = HumlaUDPMessageType.UDPVoiceCELTBeta;
        } else {
            codec = HumlaUDPMessageType.UDPVoiceCELTAlpha;
        }

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

        if (msg.hasSession() && msg.getSession() == mSession &&
                (msg.hasMute() || msg.hasSelfMute() || msg.hasSuppress())) {
            setServerMuted(msg.getMute() || msg.getSelfMute() || msg.getSuppress());
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

        if (mProtobufUdp && (mCodec == null || mCodec == HumlaUDPMessageType.UDPVoiceOpus)) {
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
            HumlaUDPMessageType msgType = (mCodec != null) ? mCodec : HumlaUDPMessageType.UDPVoiceOpus;
            flags |= msgType.ordinal() << 5;
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

    @Override
    public void onTalkingStateChanged(boolean isTalking, float peakEnergy) {
        if (mTalking != isTalking) {
            mTalking = isTalking;
            if (mEncodeListener != null) {
                mEncodeListener.onTalkingStateChanged(isTalking);
            }
            if (mHalfDuplex) {
                mAudioManager.setStreamMute(getAudioStream(), isTalking);
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
        private int mTargetFramesPerPacket;
        private int mInputSampleRate;
        private float mAmplitudeBoost;
        private boolean mBluetoothEnabled;
        private boolean mHalfDuplexEnabled;
        private boolean mPreprocessorEnabled;
        private String mEchoCancellationMethod;
        private IInputMode mInputMode;
        private AudioEncodeListener mEncodeListener;
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

        public Builder setInputSampleRate(int inputSampleRate) {
            mInputSampleRate = inputSampleRate;
            return this;
        }

        public Builder setAmplitudeBoost(float amplitudeBoost) {
            mAmplitudeBoost = amplitudeBoost;
            return this;
        }

        public Builder setBluetoothEnabled(boolean bluetoothEnabled) {
            mBluetoothEnabled = bluetoothEnabled;
            return this;
        }

        public Builder setHalfDuplexEnabled(boolean halfDuplexEnabled) {
            mHalfDuplexEnabled = halfDuplexEnabled;
            return this;
        }

        public Builder setPreprocessorEnabled(boolean preprocessorEnabled) {
            mPreprocessorEnabled = preprocessorEnabled;
            return this;
        }

        public Builder setEchoCancellationMethod(String echoCancellationMethod) {
            mEchoCancellationMethod = echoCancellationMethod;
            return this;
        }

        public Builder setEncodeListener(AudioEncodeListener encodeListener) {
            mEncodeListener = encodeListener;
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
                    mAmplitudeBoost, mBluetoothEnabled, mHalfDuplexEnabled,
                    mPreprocessorEnabled, mEchoCancellationMethod, mEncodeListener, mTalkingListener);
            handler.initialize(self, maxBandwidth, codec);
            return handler;
        }
    }
}
