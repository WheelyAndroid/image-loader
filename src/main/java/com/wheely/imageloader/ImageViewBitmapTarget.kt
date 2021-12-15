package com.wheely.imageloader

import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.ImageView
import com.wheely.coreandroid.platformextensions.DrawableRes

class ImageViewBitmapTarget(private val imageView: ImageView) : BitmapTarget {

    companion object {
        fun clear(imageView: ImageView, recycle: Boolean = true) {
            ImageLoaderTag.from(imageView)?.run {
                imageView.setImageDrawable(null)
                clear(recycle)
                imageView.setTag(R.id.image_loader_tag, null)
            }
        }

        fun clear(viewGroup: ViewGroup) {
            (0 until viewGroup.childCount).map { viewGroup.getChildAt(it) }
                .forEach { view ->
                    when (view) {
                        is ImageView ->
                            clear(view, true)
                        is ViewGroup ->
                            clear(view)
                        else ->
                            Unit
                    }
                }
        }
    }

    override fun onLoadingStarted(src: Any, request: ImageLoadRequest) {
        imageView.setTag(
            R.id.image_loader_tag,
            ImageLoaderTag(
                request = request,
                bitmap = null,
                isCachedInMemory = false,
                src = src
            )
        )
    }

    override fun onLoadingSucceeded(src: Any, request: ImageLoadRequest, bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        imageView.setTag(
            R.id.image_loader_tag,
            ImageLoaderTag(
                request = null,
                bitmap = bitmap,
                isCachedInMemory = request.useMemoryCache,
                src = src
            )
        )
    }

    override fun onLoadingFailed(e: Throwable, request: ImageLoadRequest) {
        imageView.setTag(R.id.image_loader_tag, null)
    }

    override fun setPlaceHolder(resId: DrawableRes) {
        imageView.setImageResource(resId)
    }

    override fun isAlreadyLoaded(src: Any): Boolean =
        ImageLoaderTag.from(imageView)?.let {
            it.src == src && it.bitmap != null
        } == true

    override fun clear() {
        clear(imageView, ImageLoaderTag.from(imageView)?.isCachedInMemory == false)
    }
}

class ImageLoaderTag(
    val request: ImageLoadRequest?,
    val bitmap: Bitmap?,
    val isCachedInMemory: Boolean,
    val src: Any
) {

    companion object {
        fun from(imageView: ImageView): ImageLoaderTag? =
            imageView.getTag(R.id.image_loader_tag)
                .let { it as? ImageLoaderTag }
    }

    fun clear(shouldRecycle: Boolean) {
        request?.cancel()
        if (shouldRecycle) bitmap?.recycle()
    }
}

fun ImageLoadRequest.into(imageView: ImageView) {
    into(ImageViewBitmapTarget(imageView))
}

fun ImageLoader.Companion.clear(imageView: ImageView) {
    clear(ImageViewBitmapTarget(imageView))
}

fun ImageLoader.Companion.clear(viewGroup: ViewGroup) {
    ImageViewBitmapTarget.clear(viewGroup)
}
