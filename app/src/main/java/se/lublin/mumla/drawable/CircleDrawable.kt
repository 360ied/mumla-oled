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

package se.lublin.mumla.drawable

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import java.util.Objects
import se.lublin.mumla.R

/**
 * A drawable containing a circular bitmap in the style of @drawable/outline_circle_talking_off.
 */
class CircleDrawable(resources: Resources, bitmap: Bitmap) : Drawable() {
    private val mResources: Resources = resources
    private val mBitmap: Bitmap = bitmap
    private val mPaint: Paint = Paint()
    private val mStrokePaint: Paint = Paint()
    private val mConstantState: ConstantState = CircleConstantState(resources, bitmap)
    private val mImageRect = RectF()
    private val mStrokeRect = RectF()

    init {
        mPaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        mPaint.isDither = true
        mPaint.isAntiAlias = true

        mStrokePaint.isDither = true
        mStrokePaint.isAntiAlias = true
        mStrokePaint.color = ResourcesCompat.getColor(resources, R.color.ripple_talk_state_disabled, null)
        val strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            STROKE_WIDTH_DP.toFloat(), resources.displayMetrics
        )
        mStrokePaint.strokeWidth = strokeWidth
        mStrokePaint.style = Paint.Style.STROKE
    }

    private class CircleConstantState(resources: Resources, bitmap: Bitmap) : ConstantState() {
        private val mResources: Resources = resources
        private val mBitmap: Bitmap = bitmap

        override fun newDrawable(): Drawable {
            return CircleDrawable(mResources, mBitmap)
        }

        override fun newDrawable(res: Resources?): Drawable {
            return CircleDrawable(res ?: mResources, mBitmap)
        }

        override fun getChangingConfigurations(): Int {
            return 0
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            other as CircleConstantState
            // Resources included: the same bitmap under different density/
            // theme resources renders a different stroke, so the states are
            // not interchangeable (newDrawable(res) exists for exactly this).
            return Objects.equals(mBitmap, other.mBitmap) &&
                Objects.equals(mResources, other.mResources)
        }

        override fun hashCode(): Int {
            return 31 * mBitmap.hashCode() + mResources.hashCode()
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)

        val bitmapRect = RectF(0f, 0f, mBitmap.width.toFloat(), mBitmap.height.toFloat())
        val matrix = Matrix()
        matrix.setRectToRect(bitmapRect, RectF(bounds), Matrix.ScaleToFit.CENTER)
        // NPE-exact: the original dereferenced getShader() unconditionally.
        val shader = mPaint.shader ?: throw NullPointerException("shader")
        shader.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) {
        mImageRect.set(bounds)
        mStrokeRect.set(bounds)
        // Default stroke drawing is both inset and outset.
        mStrokeRect.inset(
            mStrokePaint.strokeWidth / 2,
            mStrokePaint.strokeWidth / 2
        )

        canvas.drawOval(mImageRect, mPaint)
        canvas.drawOval(mStrokeRect, mStrokePaint)
    }

    override fun setAlpha(alpha: Int) {
        mPaint.alpha = alpha
        mStrokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(cf: ColorFilter?) {
        mPaint.colorFilter = cf
        mStrokePaint.colorFilter = cf
        invalidateSelf()
    }

    override fun getOpacity(): Int {
        return PixelFormat.UNKNOWN
    }

    override fun getConstantState(): ConstantState {
        return mConstantState
    }

    companion object {
        const val STROKE_WIDTH_DP = 1
    }
}
