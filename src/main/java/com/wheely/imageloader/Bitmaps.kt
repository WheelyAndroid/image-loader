package com.wheely.imageloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.wheely.coreandroid.platformextensions.Px
import com.wheely.coreandroid.ui.Height
import com.wheely.coreandroid.ui.Width
import java.io.File

object Bitmaps {
    /**
     * https://developer.android.com/topic/performance/graphics/load-bitmap.html
     */
    fun calculateInSampleSize(imageSize: Pair<Width, Height>, targetSize: Pair<Width, Height>?): Int {
        val (reqWidth, reqHeight) = targetSize ?: return 1
        val (width, height) = imageSize
        if (width == 0 || height == 0) return 1

        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {

            val halfHeight = height / 2
            val halfWidth = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Resize a [Bitmap] in one allocation
     *
     * https://developer.android.com/topic/performance/graphics/load-bitmap.html
     */
    @JvmStatic
    fun loadScaled(file: File, reqWidth: Px, reqHeight: Px): Bitmap {

        // First decode with inJustDecodeBounds=true to check dimensions
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, opts)

        // Calculate inSampleSize
        opts.inSampleSize = calculateInSampleSize(opts.run { outWidth to outHeight }, reqWidth to reqHeight)

        // Decode bitmap with inSampleSize set
        opts.inJustDecodeBounds = false

        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    fun rotateByFileExif(file: File, bitmap: Bitmap): Bitmap {
        val rotation = ExifInterface(file.absolutePath).run {
            when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 ->
                    90f
                ExifInterface.ORIENTATION_ROTATE_180 ->
                    180f
                ExifInterface.ORIENTATION_ROTATE_270 ->
                    270f
                else ->
                    null
            }
        }
        return if (rotation != null) {
            val matrix = Matrix()
            matrix.postRotate(rotation)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { bitmap.recycle() }
        } else {
            bitmap
        }
    }
}
