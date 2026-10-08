/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.humla.model;

import com.google.protobuf.ByteString;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class User implements IUser, Comparable<User> {

    private int mSession;
    private int mId = -1;
    private String mName;
    private String mComment;
    private ByteString mCommentHash;
    private ByteString mTexture;
    private ByteString mTextureHash;
    private String mHash;

    private boolean mMuted;
    private boolean mDeafened;
    private boolean mSuppressed;

    // Volatile: the optimistic self-mute path writes these on the main thread
    // while the server-echo path writes them on the TCP reader thread, and
    // UI readers observe them lock-free (mirrors mLocalMuted below).
    private volatile boolean mSelfMuted;
    private volatile boolean mSelfDeafened;

    private boolean mPrioritySpeaker;
    private boolean mRecording;

    private Channel mChannel;

    private volatile TalkState mTalkState = TalkState.PASSIVE;

    private final Set<Integer> mListeningChannels = new CopyOnWriteArraySet<Integer>();

    // Local state
    private volatile boolean mLocalMuted;
    private boolean mLocalIgnored;

    public User() {

    }

    public User(int session, String name) {
        mSession = session;
        mName = name;
    }

    @Override
    public int getSession() {
        return mSession;
    }

    @Override
    public Channel getChannel() {
        return mChannel;
    }

    /**
     * Changes the user's channel, removing them from their last channel (if set).
     * @param channel The user's new channel.
     */
    public void setChannel(Channel channel) {
        if (mChannel != null)
            mChannel.removeUser(this);

        mChannel = channel;

        if (mChannel != null)
            mChannel.addUser(this);
    }

    @Override
    public int getUserId() {
        return mId;
    }

    public void setUserId(int mId) {
        this.mId = mId;
    }

    @Override
    public String getName() {
        return mName;
    }

    public void setName(String mName) {
        this.mName = mName;
    }

    @Override
    public String getComment() {
        return mComment;
    }

    public void setComment(String mComment) {
        this.mComment = mComment;
    }

    @Override
    public byte[] getCommentHash() {
        return mCommentHash != null ? mCommentHash.toByteArray() : null;
    }

    public void setCommentHash(ByteString commentHash) {
        mCommentHash = commentHash;
    }

    @Override
    public byte[] getTexture() {
        return mTexture != null ? mTexture.toByteArray() : null;
    }

    public void setTexture(ByteString texture) {
        mTexture = texture;
    }

    @Override
    public byte[] getTextureHash() {
        return mTextureHash != null ? mTextureHash.toByteArray() : null;
    }

    public void setTextureHash(ByteString textureHash) {
        mTextureHash = textureHash;
    }

    @Override
    public boolean hasTexture() {
        return mTexture != null && !mTexture.isEmpty();
    }

    @Override
    public int getTextureCacheKey() {
        if (mTexture != null && !mTexture.isEmpty()) {
            int h = mTexture.hashCode();
            return h != 0 ? h : 1;
        } else if (mTextureHash != null && !mTextureHash.isEmpty()) {
            int h = mTextureHash.hashCode();
            return h != 0 ? h : 1;
        }
        return 0;
    }

    @Override
    public String getHash() {
        return mHash;
    }

    public void setHash(String mHash) {
        this.mHash = mHash;
    }

    @Override
    public boolean isMuted() {
        return mMuted;
    }

    public void setMuted(boolean mMuted) {
        this.mMuted = mMuted;
    }

    @Override
    public boolean isDeafened() {
        return mDeafened;
    }

    public void setDeafened(boolean mDeafened) {
        this.mDeafened = mDeafened;
    }

    @Override
    public boolean isSuppressed() {
        return mSuppressed;
    }

    public void setSuppressed(boolean mSuppressed) {
        this.mSuppressed = mSuppressed;
    }

    @Override
    public boolean isSelfMuted() {
        return mSelfMuted;
    }

    public void setSelfMuted(boolean mSelfMuted) {
        this.mSelfMuted = mSelfMuted;
        // Desktop parity (ClientUser::setSelfMute): unmute implies undeafen.
        // murmur coerces the same way server-side; mirroring it here keeps
        // single-field packets (e.g. a deaf-only initial-state broadcast,
        // which omits self_mute) from leaving an incoherent mute=false,
        // deaf=true pair behind.
        if (!mSelfMuted)
            mSelfDeafened = false;
    }

    @Override
    public boolean isSelfDeafened() {
        return mSelfDeafened;
    }

    public void setSelfDeafened(boolean mSelfDeafened) {
        this.mSelfDeafened = mSelfDeafened;
        // Desktop parity (ClientUser::setSelfDeaf): deaf implies mute.
        if (mSelfDeafened)
            mSelfMuted = true;
    }

    @Override
    public boolean isPrioritySpeaker() {
        return mPrioritySpeaker;
    }

    public void setPrioritySpeaker(boolean mPrioritySpeaker) {
        this.mPrioritySpeaker = mPrioritySpeaker;
    }

    @Override
    public boolean isRecording() {
        return mRecording;
    }

    public void setRecording(boolean mRecording) {
        this.mRecording = mRecording;
    }

    @Override
    public boolean isLocalMuted() {
        return mLocalMuted;
    }

    public void setLocalMuted(boolean mLocalMuted) {
        this.mLocalMuted = mLocalMuted;
    }

    @Override
    public boolean isLocalIgnored() {
        return mLocalIgnored;
    }

    public void setLocalIgnored(boolean localIgnored) {
        mLocalIgnored = localIgnored;
    }

    @Override
    public TalkState getTalkState() {
        return mTalkState;
    }

    public void setTalkState(TalkState mTalkState) {
        this.mTalkState = mTalkState;
    }

    @Override
    public Set<Integer> getListeningChannels() {
        return Collections.unmodifiableSet(mListeningChannels);
    }

    public void addListeningChannel(int channelId) {
        mListeningChannels.add(channelId);
    }

    public void removeListeningChannel(int channelId) {
        mListeningChannels.remove(channelId);
    }

    @Override
    public boolean isListeningTo(int channelId) {
        return mListeningChannels.contains(channelId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        User user = (User) o;

        if (mSession != user.mSession) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return mSession;
    }

    @Override
    public int compareTo(User another) {
        if (mName == null && another.getName() == null) {
            return Integer.compare(mSession, another.getSession());
        }
        if (mName == null) {
            return -1;
        }
        if (another.getName() == null) {
            return 1;
        }
        int nameCmp = mName.compareToIgnoreCase(another.getName());
        if (nameCmp != 0) {
            return nameCmp;
        }
        return Integer.compare(mSession, another.getSession());
    }
}
