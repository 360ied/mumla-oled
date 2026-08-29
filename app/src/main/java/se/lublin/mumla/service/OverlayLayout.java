/*
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
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Custom root container for the overlay HUD that intercepts touch events
 * before child views (such as RecyclerView) consume them.
 */
public class OverlayLayout extends FrameLayout {

    public interface OnDispatchTouchEventListener {
        boolean onDispatchTouchEvent(MotionEvent event);
    }

    private OnDispatchTouchEventListener mDispatchTouchEventListener;

    public OverlayLayout(@NonNull Context context) {
        super(context);
    }

    public OverlayLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public OverlayLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setOnDispatchTouchEventListener(OnDispatchTouchEventListener listener) {
        mDispatchTouchEventListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mDispatchTouchEventListener != null && mDispatchTouchEventListener.onDispatchTouchEvent(ev)) {
            return true;
        }
        boolean handled = super.dispatchTouchEvent(ev);
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        return handled;
    }
}
