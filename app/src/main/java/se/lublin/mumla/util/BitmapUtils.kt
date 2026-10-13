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
