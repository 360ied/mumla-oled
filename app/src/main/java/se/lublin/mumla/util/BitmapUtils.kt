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

package se.lublin.mumla.util

import android.graphics.Bitmap

object BitmapUtils {
    @JvmStatic
    fun resizeKeepingAspect(image: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        require(maxWidth > 0 && maxHeight > 0) {
            "maxWidth/maxHeight must be positive: $maxWidth x $maxHeight"
        }
        val width = image.width
        val height = image.height

        if (width < maxWidth && height < maxHeight) {
            return image
        }

        val ratioBitmap = width.toFloat() / height.toFloat()
        val ratioMax = maxWidth.toFloat() / maxHeight.toFloat()

        var finalWidth = maxWidth
        var finalHeight = maxHeight
        if (ratioMax > ratioBitmap) {
            finalWidth = (maxHeight.toFloat() * ratioBitmap).toInt()
        } else {
            finalHeight = (maxWidth.toFloat() / ratioBitmap).toInt()
        }
        // Degenerate ratios floor to 0, which createScaledBitmap rejects.
        finalWidth = Math.max(1, finalWidth)
        finalHeight = Math.max(1, finalHeight)

        return Bitmap.createScaledBitmap(image, finalWidth, finalHeight, true)
    }
}
