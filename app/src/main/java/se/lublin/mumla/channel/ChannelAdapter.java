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

package se.lublin.mumla.channel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.TalkState;
import se.lublin.mumla.R;
import se.lublin.mumla.drawable.CircleDrawable;

/**
 * Modern RecyclerView adapter to display channel members in the overlay HUD.
 */
public final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    private final Context mContext;
    private IChannel mChannel;

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView stateIcon;
        final TextView userName;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            stateIcon = itemView.findViewById(R.id.user_row_state);
            userName = itemView.findViewById(R.id.user_row_name);
        }
    }

    public ChannelAdapter(Context context, IChannel channel) {
        mContext = context;
        mChannel = channel;
        setHasStableIds(true);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(mContext).inflate(R.layout.overlay_user_row, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (mChannel == null || mChannel.getUsers() == null || position < 0 || position >= mChannel.getUsers().size()) {
            return;
        }
        IUser user = mChannel.getUsers().get(position);
        if (user == null) {
            return;
        }

        holder.userName.setText(user.getName());
        holder.stateIcon.setImageDrawable(getTalkStateDrawable(user));
    }

    @Override
    public int getItemCount() {
        return (mChannel != null && mChannel.getUsers() != null) ? mChannel.getUsers().size() : 0;
    }

    @Override
    public long getItemId(int position) {
        if (mChannel != null && mChannel.getUsers() != null && position >= 0 && position < mChannel.getUsers().size()) {
            IUser user = mChannel.getUsers().get(position);
            if (user != null) {
                return user.getSession();
            }
        }
        return RecyclerView.NO_ID;
    }

    private Drawable getTalkStateDrawable(IUser user) {
        if (user.isSelfDeafened()) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_deafened);
        } else if (user.isDeafened()) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_server_deafened);
        } else if (user.isSelfMuted()) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_muted);
        } else if (user.isMuted()) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_server_muted);
        } else if (user.isSuppressed()) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_suppressed);
        } else if (user.getTalkState() == TalkState.TALKING
                || user.getTalkState() == TalkState.SHOUTING
                || user.getTalkState() == TalkState.WHISPERING) {
            return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_talking_on);
        } else {
            if (user.getTexture() != null && user.getTexture().length > 0) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(user.getTexture(), 0, user.getTexture().length);
                if (bitmap != null) {
                    return new CircleDrawable(mContext.getResources(), bitmap);
                }
            }
        }
        return AppCompatResources.getDrawable(mContext, R.drawable.outline_circle_talking_off);
    }

    public void setChannel(IChannel channel) {
        mChannel = channel;
        notifyDataSetChanged();
    }

    public void notifyUserChanged(IUser user) {
        if (mChannel == null || mChannel.getUsers() == null || user == null) {
            return;
        }
        int index = mChannel.getUsers().indexOf(user);
        if (index >= 0) {
            notifyItemChanged(index);
        } else {
            notifyDataSetChanged();
        }
    }

    public IChannel getChannel() {
        return mChannel;
    }
}
