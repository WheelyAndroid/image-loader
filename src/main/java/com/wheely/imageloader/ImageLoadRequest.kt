package com.wheely.imageloader

import android.graphics.Bitmap
import com.wheely.coreandroid.platformextensions.DrawableRes
import com.wheely.coreandroid.ui.Height
import com.wheely.coreandroid.ui.Width

interface ImageLoadRequest {
    fun cancel()
    fun into(target: BitmapTarget)
    var onLoaded: ((Bitmap) -> Unit)?
    var onAlreadyLoaded: (() -> Unit)?
    var onSizeDetected: ((Width, Height) -> Unit)?
    var onProgress: ((Float) -> Unit)?
    var targetSize: Pair<Width, Height>?
    var onError: ((Throwable) -> Unit)?
    var onSetPlaceHolder: (() -> Unit)?
    var withETag: Boolean
    var useMemoryCache: Boolean
    var placeHolder: DrawableRes?
    var force: Boolean
    val src: Any?
}
