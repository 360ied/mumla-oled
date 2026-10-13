/*
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

package se.lublin.mumla.service

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Custom root container for the overlay HUD that intercepts touch events
 * before child views (such as RecyclerView) consume them.
 */
class OverlayLayout : FrameLayout {

    interface OnDispatchTouchEventListener {
        fun onDispatchTouchEvent(event: MotionEvent): Boolean
    }

    private var mDispatchTouchEventListener: OnDispatchTouchEventListener? = null

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setOnDispatchTouchEventListener(listener: OnDispatchTouchEventListener?) {
        mDispatchTouchEventListener = listener
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // NPE-exact before any use: the original dereferenced ev unconditionally,
        // and the framework must never receive null.
        val event = ev ?: throw NullPointerException("ev")
        val listener = mDispatchTouchEventListener
        if (listener != null && listener.onDispatchTouchEvent(event)) {
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Intentional drag-detection claim: the overlay must see the
            // whole gesture, so DOWN is consumed even when no child handled
            // it (otherwise the gesture stream would never start).
            return true
        }
        return handled
    }
}
