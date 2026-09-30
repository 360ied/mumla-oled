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

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import se.lublin.humla.model.IChannel;
import se.lublin.humla.model.IUser;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.channel.ChannelAdapter;

/**
 * A minimal onscreen floating voice HUD displaying active channel members.
 */
public class MumlaOverlay {
    private static final String TAG = MumlaOverlay.class.getName();

    /** Extra padding between the pinned overlay and the system bars. */
    private static final int EDGE_GUTTER_DP = 8;
    /** Horizontal offset of the pinned overlay from the screen edge. */
    private static final int SIDE_MARGIN_DP = 16;
    /** Fallback pinned top margin when no status-bar height is known. */
    private static final int DEFAULT_TOP_MARGIN_DP = 40;
    /** Fallback pinned bottom margin when no navigation-bar height is known. */
    private static final int DEFAULT_BOTTOM_MARGIN_DP = 56;
    /** Default floating-overlay position used when nothing is stored yet. */
    private static final int DEFAULT_POS_X_DP = 24;
    /** Default floating-overlay position used when nothing is stored yet. */
    private static final int DEFAULT_POS_Y_DP = 80;
    /** Size estimate used to clamp stored floating positions before layout. */
    private static final int ESTIMATED_WIDTH_DP = 120;
    /** Size estimate used to clamp stored floating positions before layout. */
    private static final int ESTIMATED_HEIGHT_DP = 60;

    private final HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onUserTalkStateUpdated(IUser user) {
            if (mChannelAdapter != null && user != null && user.getChannel() != null
                    && user.getChannel().equals(mService.getSessionChannel())) {
                mChannelAdapter.updateUserState(user, mOverlayList);
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (mChannelAdapter != null && user != null && user.getChannel() != null
                    && user.getChannel().equals(mService.getSessionChannel())) {
                mChannelAdapter.updateUserState(user, mOverlayList);
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
                if (user != null) {
                    mChannelAdapter.removeUser(user.getSession());
                }
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

            if (mChannelAdapter != null && user != null) {
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
                mChannelAdapter.clearAvatarCache();
            }
        }
    };

    private final MumlaService mService;
    private final Settings mSettings;
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
        mSettings = Settings.getInstance(service);
        mWindowManager = (WindowManager) mService.getSystemService(Context.WINDOW_SERVICE);
        mOverlayView = View.inflate(service, R.layout.overlay, null);
        mOverlayList = mOverlayView.findViewById(R.id.overlay_list);
        mOverlayList.setLayoutManager(new LinearLayoutManager(service));
        mOverlayList.setItemAnimator(null);

        mOverlayView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (!mShown || mSettings.isOverlayPinned()) {
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
                if (mSettings.isOverlayPinned()) {
                    return false;
                }
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
                            int viewWidth = mOverlayView.getWidth() > 0 ? mOverlayView.getWidth() : (int) (ESTIMATED_WIDTH_DP * dm.density);
                            int viewHeight = mOverlayView.getHeight() > 0 ? mOverlayView.getHeight() : (int) (ESTIMATED_HEIGHT_DP * dm.density);
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
        mOverlayParams.windowAnimations = 0;
        applyLayoutParameters();
    }

    private void applyLayoutParameters() {
        DisplayMetrics dm = mService.getResources().getDisplayMetrics();
        if (mSettings.isOverlayPinned()) {
            mOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            mOverlayParams.gravity = mSettings.getOverlayGravity();
            String placement = mSettings.getOverlayPlacement();
            // Single WindowMetrics query per layout pass; the snapshot is shared by
            // all margin resolvers below instead of re-querying per edge.
            Insets insets = getSystemBarInsets();
            mOverlayParams.x = getSideMargin(dm, insets, placement);
            boolean isTop = Settings.OVERLAY_PLACEMENT_TOP_LEFT.equals(placement)
                    || Settings.OVERLAY_PLACEMENT_TOP_RIGHT.equals(placement);
            mOverlayParams.y = isTop ? getTopMargin(dm, insets) : getBottomMargin(dm, insets);
        } else {
            mOverlayParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            mOverlayParams.gravity = Gravity.TOP | Gravity.LEFT;
            restorePosition();
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private Insets getSystemBarInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = mWindowManager.getCurrentWindowMetrics();
            // Ignoring visibility (rather than getInsets) bakes hidden bars into the
            // pinned margins so the overlay never slides under a transient system bar
            // when bars reappear (e.g. swipe-in gesture navigation).
            return metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        }
        return null;
    }

    /**
     * Pure margin resolution, extracted for unit testing without a {@link WindowManager}.
     *
     * @param insetPx live system-bar inset for the edge, or 0 when unavailable
     * @param barHeightPx legacy resource height ({@code status_bar_height} /
     *     {@code navigation_bar_height}), or 0 when unknown
     * @param defaultDp hardcoded fallback when neither source yields a height
     * @param density display density
     */
    static int resolveEdgeMarginPx(int insetPx, int barHeightPx, int defaultDp, float density) {
        if (insetPx > 0) {
            return insetPx + (int) (EDGE_GUTTER_DP * density);
        }
        if (barHeightPx > 0) {
            return barHeightPx + (int) (EDGE_GUTTER_DP * density);
        }
        return (int) (defaultDp * density);
    }

    /**
     * Pure side-margin resolution: keeps the pinned overlay clear of side cutouts
     * and gesture bars on either edge instead of using a fixed offset.
     *
     * @param sideInsetPx live left/right inset for the pinned side, or 0
     * @param density display density
     */
    static int resolveSideMarginPx(int sideInsetPx, float density) {
        if (sideInsetPx > 0) {
            return sideInsetPx + (int) (EDGE_GUTTER_DP * density);
        }
        return (int) (SIDE_MARGIN_DP * density);
    }

    private int getSideMargin(DisplayMetrics dm, Insets insets, String placement) {
        int sideInset = 0;
        if (insets != null) {
            boolean isLeft = Settings.OVERLAY_PLACEMENT_TOP_LEFT.equals(placement)
                    || Settings.OVERLAY_PLACEMENT_BOTTOM_LEFT.equals(placement);
            sideInset = isLeft ? insets.left : insets.right;
        }
        return resolveSideMarginPx(Math.max(0, sideInset), dm.density);
    }

    private int getTopMargin(DisplayMetrics dm, Insets insets) {
        int insetTop = insets != null ? Math.max(0, insets.top) : 0;
        if (insetTop > 0) {
            return resolveEdgeMarginPx(insetTop, 0, DEFAULT_TOP_MARGIN_DP, dm.density);
        }
        int statusBarHeight = 0;
        int resourceId = mService.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = mService.getResources().getDimensionPixelSize(resourceId);
        }
        return resolveEdgeMarginPx(0, statusBarHeight, DEFAULT_TOP_MARGIN_DP, dm.density);
    }

    private int getBottomMargin(DisplayMetrics dm, Insets insets) {
        int insetBottom = insets != null ? Math.max(0, insets.bottom) : 0;
        if (insetBottom > 0) {
            return resolveEdgeMarginPx(insetBottom, 0, DEFAULT_BOTTOM_MARGIN_DP, dm.density);
        }
        int navBarHeight = 0;
        int resourceId = mService.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            navBarHeight = mService.getResources().getDimensionPixelSize(resourceId);
        }
        return resolveEdgeMarginPx(0, navBarHeight, DEFAULT_BOTTOM_MARGIN_DP, dm.density);
    }

    private void restorePosition() {
        DisplayMetrics dm = mService.getResources().getDisplayMetrics();
        int defaultX = (int) (DEFAULT_POS_X_DP * dm.density);
        int defaultY = (int) (DEFAULT_POS_Y_DP * dm.density);

        int savedX = mSettings.getOverlayPosX(defaultX);
        int savedY = mSettings.getOverlayPosY(defaultY);

        int maxX = Math.max(0, dm.widthPixels - (int) (ESTIMATED_WIDTH_DP * dm.density));
        int maxY = Math.max(0, dm.heightPixels - (int) (ESTIMATED_HEIGHT_DP * dm.density));

        mOverlayParams.x = Math.max(0, Math.min(savedX, maxX));
        mOverlayParams.y = Math.max(0, Math.min(savedY, maxY));
    }

    private void savePosition() {
        if (mSettings.isOverlayPinned()) {
            return;
        }
        mSettings.setOverlayPosition(mOverlayParams.x, mOverlayParams.y);
    }

    public boolean isShown() {
        return mShown;
    }

    public void updatePosition() {
        applyLayoutParameters();
        if (mShown) {
            try {
                mWindowManager.updateViewLayout(mOverlayView, mOverlayParams);
            } catch (Exception e) {
                Log.d(TAG, "exception updating overlay layout: " + e);
            }
        }
    }

    public void show() {
        if (mShown) {
            return;
        }
        applyLayoutParameters();
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
        if (mChannelAdapter != null) {
            mChannelAdapter.clearAvatarCache();
        }
        mChannelAdapter = null;
        try {
            mWindowManager.removeView(mOverlayView);
        } catch (Exception e) {
            Log.d(TAG, "exception removing overlay view: " + e);
        }
    }

    public void setPushToTalkShown(boolean showPtt) {
        // No-op in minimal HUD mode
    }
}
