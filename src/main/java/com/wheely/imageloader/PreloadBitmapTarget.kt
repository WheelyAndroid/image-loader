package com.wheely.imageloader

import android.graphics.Bitmap

object PreloadBitmapTarget : BitmapTarget {

    private enum class Status { LOADING, LOADED }

    private val registry = mutableMapOf<Any, Status>()

    override fun onLoadingStarted(src: Any, request: ImageLoadRequest) {
        registry[src] = Status.LOADING
    }

    override fun onLoadingSucceeded(src: Any, request: ImageLoadRequest, bitmap: Bitmap) {
        registry[src] = Status.LOADED
    }

    override fun onLoadingFailed(e: Throwable, request: ImageLoadRequest) {
        request.src?.let {
            registry.remove(it)
        }
    }

    override fun isAlreadyLoaded(src: Any): Boolean =
        registry[src] == Status.LOADED
}

fun ImageLoadRequest.preload() =
    into(PreloadBitmapTarget)
