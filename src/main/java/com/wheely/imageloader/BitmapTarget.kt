package com.wheely.imageloader

import android.graphics.Bitmap
import com.wheely.coreandroid.platformextensions.DrawableRes

interface BitmapTarget {
    fun onLoadingStarted(src: Any, request: ImageLoadRequest) {}
    fun onLoadingSucceeded(src: Any, request: ImageLoadRequest, bitmap: Bitmap)
    fun onLoadingFailed(e: Throwable, request: ImageLoadRequest) {}
    fun setPlaceHolder(resId: DrawableRes) {}
    fun isAlreadyLoaded(src: Any): Boolean = false
    fun clear() {}
}
