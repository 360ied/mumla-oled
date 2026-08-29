/*
 * Copyright (C) 2014 Andrew Comminos
 * Copyright (C) 2026 Mumla OLED Contributors
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
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.Collections;

import se.lublin.mumla.R;

/**
 * A push-to-talk hot corner overlay in an area of the screen specified by {@link MumlaHotCorner#getGravity()}.
 */
public class MumlaHotCorner implements View.OnTouchListener {
    private static final String TAG = "MumlaHotCorner";

    private WindowManager mWindowManager;
    private Context mContext;
    private View mView;
    private ImageView mIconView;
    private boolean mShown;
    private MumlaHotCornerListener mListener;
    private WindowManager.LayoutParams mParams;

    public MumlaHotCorner(Context context, int gravity, MumlaHotCornerListener listener) {
        if (listener == null) {
            throw new NullPointerException("A MumlaHotCornerListener must be assigned.");
        }
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mContext = context;
        mView = inflater.inflate(R.layout.ptt_corner, null, false);
        mIconView = mView.findViewById(R.id.hot_corner_icon);
        mView.setOnTouchListener(this);
        mListener = listener;

        mView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    int width = right - left;
                    int height = bottom - top;
                    if (width > 0 && height > 0) {
                        mView.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, width, height)));
                    }
                }
            }
        });

        mParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        mParams.gravity = gravity;
    }

    /**
     * Updates the hot corner with any new settings applied, recalculating the layout parameters.
     * Does nothing if the hot corner is not shown.
     */
    private void updateLayout() {
        if (!isShown()) return;
        try {
            mWindowManager.updateViewLayout(mView, mParams);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "exception updating hot corner layout: " + e);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setVisualActive(true);
                mListener.onHotCornerDown();
                return true;
            case MotionEvent.ACTION_UP:
                setVisualActive(false);
                mListener.onHotCornerUp();
                return true;
            case MotionEvent.ACTION_CANCEL:
                setVisualActive(false);
                mListener.onHotCornerCancel();
                return true;
            default:
                return false;
        }
    }

    public void setVisualActive(boolean active) {
        if (mView != null) {
            mView.setBackgroundResource(active
                    ? R.drawable.hot_corner_background_active
                    : R.drawable.hot_corner_background);
        }
        if (mIconView != null) {
            mIconView.setAlpha(active ? 1.0f : 0.85f);
        }
    }

    public void updateTalkState(boolean talking) {
        setVisualActive(talking);
    }

    public void setShown(boolean shown) {
        if (shown == mShown) {
            return;
        }
        if (shown) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(mContext)) {
                    Intent showSetting = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + mContext.getPackageName()));
                    showSetting.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(showSetting);
                    Toast.makeText(mContext, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            try {
                mWindowManager.addView(mView, mParams);
                mShown = true;
            } catch (Exception e) {
                Log.e(TAG, "exception adding hot corner view: " + e);
                mShown = false;
            }
        } else {
            try {
                mWindowManager.removeView(mView);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "exception removing hot corner view: " + e);
            }
            mShown = false;
        }
    }

    public boolean isShown() {
        return mShown;
    }

    public void setGravity(int gravity) {
        mParams.gravity = gravity;
        updateLayout();
    }

    public int getGravity() {
        return mParams.gravity;
    }

    public interface MumlaHotCornerListener {
        void onHotCornerDown();
        void onHotCornerUp();
        void onHotCornerCancel();
    }
}
