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

package se.lublin.mumla.drawable;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import se.lublin.mumla.R;

/**
 * A drawable containing a circular bitmap in the style of @drawable/outline_circle_talking_off.
 * Created by andrew on 19/10/14.
 */
public class CircleDrawable extends Drawable {
    public static final int STROKE_WIDTH_DP = 1;
    private Resources mResources;
    private Bitmap mBitmap;
    private Paint mPaint;
    private Paint mStrokePaint;
    private ConstantState mConstantState;
    private final RectF mImageRect = new RectF();
    private final RectF mStrokeRect = new RectF();

    public CircleDrawable(Resources resources, Bitmap bitmap) {
        mResources = resources;
        mBitmap = bitmap;

        mPaint = new Paint();
        mPaint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        mPaint.setDither(true);
        mPaint.setAntiAlias(true);

        mStrokePaint = new Paint();
        mStrokePaint.setDither(true);
        mStrokePaint.setAntiAlias(true);
        mStrokePaint.setColor(resources.getColor(R.color.ripple_talk_state_disabled));
        float strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                STROKE_WIDTH_DP, resources.getDisplayMetrics());
        mStrokePaint.setStrokeWidth(strokeWidth);
        mStrokePaint.setStyle(Paint.Style.STROKE);

        mConstantState = new CircleConstantState(mResources, mBitmap);
    }

    private static final class CircleConstantState extends ConstantState {
        private final Resources mResources;
        private final Bitmap mBitmap;

        CircleConstantState(Resources resources, Bitmap bitmap) {
            mResources = resources;
            mBitmap = bitmap;
        }

        @Override
        public Drawable newDrawable() {
            return new CircleDrawable(mResources, mBitmap);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CircleConstantState that = (CircleConstantState) o;
            return java.util.Objects.equals(mBitmap, that.mBitmap);
        }

        @Override
        public int hashCode() {
            return mBitmap != null ? mBitmap.hashCode() : 0;
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);

        RectF bitmapRect = new RectF(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
        Matrix matrix = new Matrix();
        matrix.setRectToRect(bitmapRect, new RectF(bounds), Matrix.ScaleToFit.CENTER);
        mPaint.getShader().setLocalMatrix(matrix);
    }

    @Override
    public void draw(Canvas canvas) {
        mImageRect.set(getBounds());
        mStrokeRect.set(getBounds());
        // Default stroke drawing is both inset and outset.
        mStrokeRect.inset(mStrokePaint.getStrokeWidth()/2,
                         mStrokePaint.getStrokeWidth()/2);

        canvas.drawOval(mImageRect, mPaint);
        canvas.drawOval(mStrokeRect, mStrokePaint);
    }

    @Override
    public void setAlpha(int alpha) {

    }

    @Override
    public void setColorFilter(ColorFilter cf) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.UNKNOWN;
    }

    @Override
    public ConstantState getConstantState() {
        return mConstantState;
    }
}
