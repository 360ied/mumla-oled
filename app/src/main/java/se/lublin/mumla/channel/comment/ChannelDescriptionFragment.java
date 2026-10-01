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

package se.lublin.mumla.channel.comment;

import android.os.Bundle;

import androidx.annotation.NonNull;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.model.IChannel;
import se.lublin.humla.util.HumlaObserver;

/**
 * Created by andrew on 03/03/14.
 */
public class ChannelDescriptionFragment extends AbstractCommentFragment {

    public static final String ARG_CHANNEL = "channel";

    @Override
    public void requestComment(final IHumlaService service) {
        if (!service.isConnected())
            return;
        HumlaObserver observer = new HumlaObserver() {
            @Override
            public void onChannelStateUpdated(IChannel channel) {
                if (channel.getId() == getChannelId() &&
                        channel.getDescription() != null) {
                    loadComment(channel.getDescription());
                    service.unregisterObserver(this);
                }
            }
        };
        trackCommentObserver(service, observer);
        service.registerObserver(observer);
        service.HumlaSession().requestChannelDescription(getChannelId());
    }

    @Override
    public void editComment(IHumlaService service, String comment) {
        throw new UnsupportedOperationException("Channel description editing is not supported");
    }

    private int getChannelId() {
        return requireIntArgument(requireArguments(), ARG_CHANNEL);
    }

    @Override
    protected void validateArguments(@NonNull Bundle args) {
        super.validateArguments(args);
        requireIntArgument(args, ARG_CHANNEL);
        if (args.getBoolean(AbstractCommentFragment.ARG_EDITING, false)) {
            throw new IllegalStateException("ChannelDescriptionFragment does not support editing");
        }
    }
}
