package com.wheely.imageloader

import android.graphics.Bitmap
import com.wheely.coreandroid.ui.Height
import com.wheely.coreandroid.ui.Width

sealed class ImageResponse {
    data class Progress(val progress: Float) : ImageResponse()
    data class SizeDetected(val width: Width, val height: Height) : ImageResponse()
    data class Image(val bitmap: Bitmap) : ImageResponse()
}
