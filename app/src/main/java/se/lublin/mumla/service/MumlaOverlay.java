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

package se.lublin.mumla.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.channel.ChannelAdapter;

/**
 * A minimal onscreen floating voice HUD displaying active channel members.
 */
public class MumlaOverlay {
    private static final String TAG = MumlaOverlay.class.getName();

    private static final String PREF_OVERLAY_POS_X = "overlay_hud_pos_x";
    private static final String PREF_OVERLAY_POS_Y = "overlay_hud_pos_y";

    private final HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onUserTalkStateUpdated(IUser user) {
            if (mChannelAdapter != null && user != null && user.getChannel() != null
                    && user.getChannel().equals(mService.getSessionChannel())) {
                mChannelAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (mChannelAdapter != null && user != null && user.getChannel() != null
                    && user.getChannel().equals(mService.getSessionChannel())) {
                mChannelAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onUserConnected(IUser user) {
            if (mChannelAdapter != null && user != null && user.getChannel() != null
                    && user.getChannel().equals(mService.getSessionChannel())) {
                mChannelAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onUserRemoved(IUser user, String reason) {
            if (mChannelAdapter != null) {
                mChannelAdapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onUserJoinedChannel(IUser user, IChannel newChannel, IChannel oldChannel) {
            int selfSession;
            try {
                selfSession = mService.getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserJoinedChannel: " + e);
                return;
            }

            if (mChannelAdapter != null) {
                if (user.getSession() == selfSession) {
                    mChannelAdapter.setChannel(mService.getSessionChannel());
                } else if (mService.getSessionChannel() != null && (
                        (newChannel != null && newChannel.getId() == mService.getSessionChannel().getId()) ||
                        (oldChannel != null && oldChannel.getId() == mService.getSessionChannel().getId()))) {
                    mChannelAdapter.notifyDataSetChanged();
                }
            }
        }

        @Override
        public void onConnected() {
            if (mChannelAdapter != null) {
                mChannelAdapter.setChannel(mService.getSessionChannel());
            }
        }

        @Override
        public void onDisconnected(HumlaException e) {
            if (mChannelAdapter != null) {
                mChannelAdapter.setChannel(null);
            }
        }
    };

    private final MumlaService mService;
    private final WindowManager mWindowManager;
    private final View mOverlayView;
    private final RecyclerView mOverlayList;
    private final WindowManager.LayoutParams mOverlayParams;

    private ChannelAdapter mChannelAdapter;
    private boolean mShown = false;

    private float mInitialTouchX;
    private float mInitialTouchY;
    private int mInitialParamX;
    private int mInitialParamY;

    public MumlaOverlay(MumlaService service) {
        mService = service;
        mWindowManager = (WindowManager) mService.getSystemService(Context.WINDOW_SERVICE);
        mOverlayView = View.inflate(service, R.layout.overlay, null);
        mOverlayList = mOverlayView.findViewById(R.id.overlay_list);
        mOverlayList.setLayoutManager(new LinearLayoutManager(service));

        mOverlayView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (!mShown) {
                    return;
                }
                DisplayMetrics dm = mService.getResources().getDisplayMetrics();
                int viewWidth = mOverlayView.getWidth();
                int viewHeight = mOverlayView.getHeight();
                if (viewWidth > 0 && viewHeight > 0) {
                    int maxX = Math.max(0, dm.widthPixels - viewWidth);
                    int maxY = Math.max(0, dm.heightPixels - viewHeight);
                    int clampedX = Math.max(0, Math.min(mOverlayParams.x, maxX));
                    int clampedY = Math.max(0, Math.min(mOverlayParams.y, maxY));
                    if (clampedX != mOverlayParams.x || clampedY != mOverlayParams.y) {
                        mOverlayParams.x = clampedX;
                        mOverlayParams.y = clampedY;
                        try {
                            mWindowManager.updateViewLayout(mOverlayView, mOverlayParams);
                        } catch (IllegalArgumentException e) {
                            Log.d(TAG, "exception updating overlay layout on layout change: " + e);
                        }
                    }
                }
            }
        });

        OverlayLayout overlayLayout = (OverlayLayout) mOverlayView;
        final int touchSlop = ViewConfiguration.get(service).getScaledTouchSlop();

        overlayLayout.setOnDispatchTouchEventListener(new OverlayLayout.OnDispatchTouchEventListener() {
            private boolean mIsDragging = false;

            @Override
            public boolean onDispatchTouchEvent(MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mInitialParamX = mOverlayParams.x;
                        mInitialParamY = mOverlayParams.y;
                        mInitialTouchX = event.getRawX();
                        mInitialTouchY = event.getRawY();
                        mIsDragging = false;
                        return false;

                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - mInitialTouchX;
                        float deltaY = event.getRawY() - mInitialTouchY;
                        if (!mIsDragging && Math.hypot(deltaX, deltaY) > touchSlop) {
                            mIsDragging = true;
                        }

                        if (mIsDragging) {
                            int newX = (int) (mInitialParamX + deltaX);
                            int newY = (int) (mInitialParamY + deltaY);

                            DisplayMetrics dm = mService.getResources().getDisplayMetrics();
                            int viewWidth = mOverlayView.getWidth() > 0 ? mOverlayView.getWidth() : (int) (120 * dm.density);
                            int viewHeight = mOverlayView.getHeight() > 0 ? mOverlayView.getHeight() : (int) (60 * dm.density);
                            int maxX = Math.max(0, dm.widthPixels - viewWidth);
                            int maxY = Math.max(0, dm.heightPixels - viewHeight);

                            mOverlayParams.x = Math.max(0, Math.min(newX, maxX));
                            mOverlayParams.y = Math.max(0, Math.min(newY, maxY));
                            if (mShown) {
                                mWindowManager.updateViewLayout(mOverlayView, mOverlayParams);
                            }
                            return true;
                        }
                        return false;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (mIsDragging) {
                            savePosition();
                            mIsDragging = false;
                            return true;
                        }
                        break;
                }
                return false;
            }
        });

        mOverlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        mOverlayParams.gravity = Gravity.TOP | Gravity.START;
        mOverlayParams.windowAnimations = android.R.style.Animation_Dialog;
    }

    private void restorePosition() {
        DisplayMetrics dm = mService.getResources().getDisplayMetrics();
        int defaultX = (int) (24 * dm.density);
        int defaultY = (int) (80 * dm.density);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mService);
        int savedX = prefs.getInt(PREF_OVERLAY_POS_X, defaultX);
        int savedY = prefs.getInt(PREF_OVERLAY_POS_Y, defaultY);

        int maxX = Math.max(0, dm.widthPixels - (int) (120 * dm.density));
        int maxY = Math.max(0, dm.heightPixels - (int) (60 * dm.density));

        mOverlayParams.x = Math.max(0, Math.min(savedX, maxX));
        mOverlayParams.y = Math.max(0, Math.min(savedY, maxY));
    }

    private void savePosition() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mService);
        prefs.edit()
                .putInt(PREF_OVERLAY_POS_X, mOverlayParams.x)
                .putInt(PREF_OVERLAY_POS_Y, mOverlayParams.y)
                .apply();
    }

    public boolean isShown() {
        return mShown;
    }

    public void show() {
        if (mShown) {
            return;
        }
        restorePosition();
        mChannelAdapter = new ChannelAdapter(mService, mService.getSessionChannel());
        mOverlayList.setAdapter(mChannelAdapter);
        mService.registerObserver(mObserver);
        try {
            mWindowManager.addView(mOverlayView, mOverlayParams);
            mShown = true;
        } catch (Exception e) {
            Log.e(TAG, "exception showing overlay: " + e);
            mService.unregisterObserver(mObserver);
            mOverlayList.setAdapter(null);
            mChannelAdapter = null;
            mShown = false;
        }
    }

    public void hide() {
        if (!mShown) {
            return;
        }
        mShown = false;
        mService.unregisterObserver(mObserver);
        mOverlayList.setAdapter(null);
        mChannelAdapter = null;
        try {
            mWindowManager.removeView(mOverlayView);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "exception removing overlay view: " + e);
        }
    }

    public void setPushToTalkShown(boolean showPtt) {
        // No-op in minimal HUD mode
    }
}
