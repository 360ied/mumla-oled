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

package se.lublin.mumla.service

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import java.util.Collections
import se.lublin.mumla.R

/**
 * A push-to-talk hot corner overlay in an area of the screen specified by {@link MumlaHotCorner#getGravity()}.
 */
class MumlaHotCorner(context: Context?, gravity: Int, listener: MumlaHotCornerListener?) : View.OnTouchListener {
    private val mWindowManager: WindowManager
    private val mContext: Context
    private val mView: View
    private val mIconView: ImageView?
    private var mShown: Boolean = false
    private val mListener: MumlaHotCornerListener
    private val mParams: WindowManager.LayoutParams

    init {
        if (context == null) {
            throw IllegalArgumentException("Context must not be null.")
        }
        if (listener == null) {
            throw IllegalArgumentException("A MumlaHotCornerListener must be assigned.")
        }
        val inflater = LayoutInflater.from(context)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager?
        if (windowManager == null) {
            throw IllegalStateException("WindowManager unavailable.")
        }
        mWindowManager = windowManager
        mContext = context
        mView = inflater.inflate(R.layout.ptt_corner, null, false)
        mIconView = mView.findViewById(R.id.hot_corner_icon)
        mView.setOnTouchListener(this)
        mListener = listener

        mView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            private var mLastWidth = 0
            private var mLastHeight = 0

            override fun onLayoutChange(
                v: View?, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                val width = right - left
                val height = bottom - top
                if (width > 0 && height > 0) {
                    if (width != mLastWidth || height != mLastHeight) {
                        mLastWidth = width
                        mLastHeight = height
                        refreshGestureExclusion()
                    }
                }
            }
        })

        mParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        mParams.gravity = gravity
    }

    /**
     * Updates the hot corner with any new settings applied, recalculating the layout parameters.
     * Does nothing if the hot corner is not shown.
     */
    private fun updateLayout() {
        if (!isShown()) return
        try {
            mWindowManager.updateViewLayout(mView, mParams)
        } catch (e: RuntimeException) {
            // updateViewLayout can throw BadTokenException, SecurityException, or
            // IllegalStateException after rotation or token loss, not just IAE.
            Log.d(TAG, "exception updating hot corner layout", e)
        }
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        // NPE-exact: the original dereferenced event unconditionally.
        val e = event ?: throw NullPointerException("event")
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mView.isPressed = true
                if (mIconView != null) {
                    mIconView.alpha = 1.0f
                }
                mListener.onHotCornerDown()
                return true
            }
            MotionEvent.ACTION_UP -> {
                mView.isPressed = false
                if (mIconView != null) {
                    mIconView.alpha = if (mView.isActivated) 1.0f else 0.85f
                }
                mListener.onHotCornerUp()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mView.isPressed = false
                if (mIconView != null) {
                    mIconView.alpha = if (mView.isActivated) 1.0f else 0.85f
                }
                mListener.onHotCornerCancel()
                return true
            }
            else -> return false
        }
    }

    fun updateTalkState(talking: Boolean) {
        mView.isActivated = talking
        if (mIconView != null) {
            mIconView.alpha = if (talking || mView.isPressed) 1.0f else 0.85f
        }
    }

    fun setShown(shown: Boolean) {
        if (shown == mShown) {
            return
        }
        if (shown) {
            if (!android.provider.Settings.canDrawOverlays(mContext)) {
                val showSetting = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + mContext.packageName)
                )
                showSetting.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                try {
                    mContext.startActivity(showSetting)
                    Toast.makeText(mContext, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    // OEM builds without the overlay-permission activity: no
                    // settings screen exists, so no grant toast either.
                    Log.e(TAG, "Failed to open overlay permission settings", e)
                }
                return
            }
            try {
                mWindowManager.addView(mView, mParams)
                mShown = true
                // A re-shown view lands on a fresh ViewRoot with empty exclusion
                // rects while the size-gated listener still holds stale mLast
                // dimensions and skips; reapply past the pending layout (D1).
                refreshGestureExclusionDeferred()
            } catch (e: Exception) {
                Log.e(TAG, "exception adding hot corner view", e)
                mShown = false
            }
        } else {
            try {
                mWindowManager.removeView(mView)
            } catch (e: RuntimeException) {
                // removeView can throw past IAE when the host is dying.
                Log.d(TAG, "exception removing hot corner view", e)
            }
            mShown = false
        }
    }

    /**
     * Reapplies the system gesture exclusion rects without checking for a
     * dimension change. Rotation invalidates exclusion rects while the hot
     * corner view has a fixed size, so the size-gated layout listener alone
     * would skip the reapplication after rotation (ODD-05).
     * Does nothing when the hot corner is not shown, or when the
     * view has no size yet. The view is never null after construction.
     */
    fun refreshGestureExclusion() {
        if (!mShown) {
            return
        }
        val width = mView.width
        val height = mView.height
        if (width > 0 && height > 0) {
            mView.systemGestureExclusionRects = Collections.singletonList(Rect(0, 0, width, height))
        }
    }

    /**
     * Defers {@link #refreshGestureExclusion()} past the in-flight layout pass.
     * Call this from configuration-change paths where getWidth/getHeight may
     * still hold pre-layout values; the posted runnable reapplies once the
     * view has its post-rotation size (ODD-05).
     */
    fun refreshGestureExclusionDeferred() {
        if (!mShown) {
            return
        }
        if (!mView.post { refreshGestureExclusion() }) {
            // View detached (rotation/disconnect race): the posted runnable will
            // never run, so try immediately; a stale-size reapply still beats a
            // silently cleared exclusion.
            refreshGestureExclusion()
        }
    }

    fun isShown(): Boolean {
        return mShown
    }

    fun setGravity(gravity: Int) {
        mParams.gravity = gravity
        updateLayout()
    }

    fun getGravity(): Int {
        return mParams.gravity
    }

    interface MumlaHotCornerListener {
        fun onHotCornerDown()
        fun onHotCornerUp()
        fun onHotCornerCancel()
    }

    companion object {
        private const val TAG = "MumlaHotCorner"
    }
}
