package com.wheely.imageloader

import android.content.ContentResolver.SCHEME_CONTENT
import android.content.ContentResolver.SCHEME_FILE
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.wheely.coreandroid.platformextensions.DrawableRes
import com.wheely.coreandroid.platformextensions.inTransaction
import com.wheely.coreandroid.ui.Height
import com.wheely.coreandroid.ui.Width
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.headersContentLength
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

typealias ETag = String

class ImageLoader(
    private val client: OkHttpClient,
    private val context: Context
) {

    companion object {
        fun clear(target: BitmapTarget) {
            target.clear()
        }

        private val executor = Executors.newCachedThreadPool()
        private val handler = Handler(Looper.getMainLooper())
    }

    private val cacheRegistry =
        context.getSharedPreferences("image_cache_registry", Context.MODE_PRIVATE)

    private val memoryCache by lazy {
        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes as LruCache takes an
        // int in its constructor.
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()

        // Use 1/8th of the available memory for this memory cache.
        val cacheSize = maxMemory / 8
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                // The cache size will be measured in kilobytes rather than
                // number of items.
                return bitmap.allocationByteCount / 1024
            }
        }
    }

    fun load(src: String?): ImageLoadRequest = loadInternal(ImageLoadRequestImpl(src))

    fun load(uri: Uri?): ImageLoadRequest = loadInternal(ImageLoadRequestImpl(uri))

    fun load(file: File?): ImageLoadRequest = loadInternal(ImageLoadRequestImpl(file))

    fun load(url: HttpUrl?): ImageLoadRequest = loadInternal(ImageLoadRequestImpl(url))

    private fun setPlaceHolder(request: ImageLoadRequestImpl) {
        request.target?.clear()
        request.placeHolder?.let { request.target?.setPlaceHolder(it) }
        request.onSetPlaceHolder?.invoke()
    }

    private fun loadInternal(request: ImageLoadRequestImpl): ImageLoadRequest {
        val src = request.src
        val execute: () -> Unit = {
            if (src == null) {
                setPlaceHolder(request)
            } else {
                request.target?.run {
                    setPlaceHolder(request)
                    onLoadingStarted(src, request)
                }
                val onResult: (Result<ImageResponse>) -> Unit = { result ->
                    handleResult(src, request, result)
                }
                val future = executor.submit { getBitmap(src, request, onResult) }
                request.onCancel = { future.cancel(true) }
            }
        }
        request.onExecute = execute
        return request
    }

    private fun handleResult(src: Any, request: ImageLoadRequestImpl, result: Result<ImageResponse>) {
        if (Thread.currentThread().isInterrupted) return
        handler.post {
            result.fold(
                onSuccess = {
                    when (it) {
                        is ImageResponse.Progress ->
                            request.onProgress?.invoke(it.progress)
                        is ImageResponse.SizeDetected ->
                            request.onSizeDetected?.invoke(it.width, it.height)
                        is ImageResponse.Image -> {
                            request.onLoaded?.invoke(it.bitmap)
                            request.target?.onLoadingSucceeded(src, request, it.bitmap)
                        }
                    }
                },
                onFailure = {
                    request.target?.onLoadingFailed(it, request)
                    request.onError?.invoke(it)
                    setPlaceHolder(request)
                }
            )
        }
    }

    private fun getBitmap(src: Any, request: ImageLoadRequestImpl, onResult: (Result<ImageResponse>) -> Unit) {
        try {
            when (src) {
                is Uri -> getFromUri(src, request, onResult)
                is String -> getFromUri(Uri.parse(src), request, onResult)
                is File -> getFromFile(src, request, onResult)
                is HttpUrl -> getFromHttp(src, request, onResult)
                else -> error("unsupported type")
            }
        } catch (e: Throwable) {
            onResult(Result.failure(e))
        }
    }

    private fun makeRegistryKey(url: HttpUrl, eTag: ETag?) = "$url;${eTag.orEmpty()}"

    private fun getFromUri(src: Uri, request: ImageLoadRequestImpl, onResult: (Result<ImageResponse>) -> Unit) {
        when (src.scheme) {
            SCHEME_FILE ->
                getFromFile(File(requireNotNull(src.path)), request, onResult)
            SCHEME_CONTENT ->
                getFromContent(src, onResult)
            "https", "http" ->
                getFromHttp(src.toString().toHttpUrl(), request, onResult)
            else ->
                error("unsupported scheme")
        }
    }

    private fun getFromHttp(httpUrl: HttpUrl, request: ImageLoadRequestImpl, onResult: (Result<ImageResponse>) -> Unit) {
        if (!tryLoadFromCache(httpUrl, request, onResult)) {
            tryDownloadFromUrl(httpUrl, request, onResult)
        }
    }

    private fun getFromFile(file: File, request: ImageLoadRequestImpl, onResult: (Result<ImageResponse>) -> Unit) {
        request.useMemoryCache.let {
            if (it) tryGetFromMemoryCache(file.absolutePath) else null
        }?.let { bitmap ->
            onResult(Result.success(ImageResponse.Image(bitmap)))
        } ?: run {
            val (w, h) = getImageSize(file)
            if (w > 0 && h > 0) onResult(Result.success(ImageResponse.SizeDetected(w, h)))
            val bitmap = decode(file, w to h, request.targetSize)
            if (request.useMemoryCache) {
                bitmap.putToMemoryCache(file.absolutePath)
            }
            onResult(Result.success(ImageResponse.Image(bitmap)))
        }
    }

    private fun getFromContent(uri: Uri, onResult: (Result<ImageResponse>) -> Unit) {
        context.contentResolver.openInputStream(uri)?.let { inputStream ->
            val file = tryGetFromFileCache(uri.toString())
                ?: File(context.cacheDir, UUID.randomUUID().toString()).also {
                    it.registerInFileCache(uri.toString())
                }
            inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath).let { b ->
                Bitmaps.rotateByFileExif(file, b)
            }
            onResult(Result.success(ImageResponse.Image(bitmap)))
        } ?: error("empty content")
    }

    private fun tryDownloadFromUrl(
        httpUrl: HttpUrl,
        request: ImageLoadRequestImpl,
        onResult: (Result<ImageResponse>) -> Unit
    ) {
        val httpRequest = Request.Builder().get().url(httpUrl).build()
        val httpResponse = client.newCall(httpRequest).execute()
        if (httpResponse.isSuccessful) {
            val size = httpResponse.headersContentLength()
            val eTag = if (request.withETag) httpResponse.header("ETag") else null
            httpResponse.body?.source()?.use {
                val file = File(context.cacheDir, UUID.randomUUID().toString())
                file.registerInFileCache(makeRegistryKey(httpUrl, eTag))
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                CopyingInputStream(it.inputStream(), file.outputStream()) { bytesCopied ->
                    if (size > 0) {
                        val progress = bytesCopied.toFloat() / size.toFloat()
                        onResult(Result.success(ImageResponse.Progress(progress)))
                    }
                }.use { wrappedInput ->
                    BitmapFactory.decodeStream(wrappedInput, null, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) {
                        onResult(Result.success(ImageResponse.SizeDetected(opts.outWidth, opts.outHeight)))
                    }
                    wrappedInput.copyTail()
                }
                val bitmap = decode(file, opts.outWidth to opts.outHeight, request.targetSize)
                if (request.useMemoryCache) {
                    bitmap.putToMemoryCache(makeRegistryKey(httpUrl, eTag))
                }
                onResult(Result.success(ImageResponse.Image(bitmap)))
            } ?: throw IllegalStateException("response with no body")
        } else {
            val message = httpResponse.body?.string().orEmpty()
            throw HttpException(httpResponse.code, message)
        }
    }

    fun tryGetETag(httpUrl: HttpUrl): ETag? =
        Request.Builder().head().url(httpUrl).build()
            .let { client.newCall(it) }
            .execute().run {
                if (!isSuccessful) {
                    throw HttpException(code, body?.string().orEmpty())
                }
                header("ETag")
            }

    private fun decode(
        file: File,
        imageSize: Pair<Width, Height>,
        targetSize: Pair<Width, Height>?
    ): Bitmap =
        if (targetSize != null) {
            decodeSampledBitmap(file, imageSize, targetSize)
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }

    private fun decodeSampledBitmap(
        file: File,
        imageSize: Pair<Width, Height>,
        targetSize: Pair<Width, Height>?
    ): Bitmap =
        BitmapFactory.Options().run {
            // Calculate inSampleSize
            inSampleSize = Bitmaps.calculateInSampleSize(imageSize, targetSize)
            // Decode bitmap with inSampleSize set
            inJustDecodeBounds = false
            BitmapFactory.decodeFile(file.absolutePath, this)
        }

    private fun tryLoadFromCache(
        httpUrl: HttpUrl,
        request: ImageLoadRequestImpl,
        onResult: (Result<ImageResponse>) -> Unit
    ): Boolean {
        val eTag = if (request.withETag) tryGetETag(httpUrl) else null
        if (request.useMemoryCache) {
            tryGetFromMemoryCache(makeRegistryKey(httpUrl, eTag))?.let { bitmap ->
                onResult(Result.success(ImageResponse.Image(bitmap)))
                return true
            }
        }

        val file = tryGetFromFileCache(makeRegistryKey(httpUrl, eTag)) ?: return false
        return try {
            val (w, h) = getImageSize(file)
            if (w > 0 && h > 0) onResult(Result.success(ImageResponse.SizeDetected(w, h)))
            val bitmap = decode(file, w to h, request.targetSize)
            if (request.useMemoryCache) {
                bitmap.putToMemoryCache(makeRegistryKey(httpUrl, eTag))
            }
            onResult(Result.success(ImageResponse.Image(bitmap)))
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun getImageSize(file: File): Pair<Width, Height> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath)
        return opts.outWidth to opts.outHeight
    }

    private fun tryGetFromMemoryCache(key: String) =
        memoryCache.get(key)?.takeIf { !it.isRecycled }

    private fun Bitmap.putToMemoryCache(key: String) {
        memoryCache.put(key, this)
    }

    private fun File.registerInFileCache(key: String) {
        cacheRegistry.inTransaction { putString(key, name) }
    }

    private fun tryGetFromFileCache(key: String): File? =
        cacheRegistry.getString(key, null)
            ?.let { File(context.cacheDir, it) }
            ?.takeIf { it.exists() }

    fun clearFileCache() {
        cacheRegistry.all.forEach { (_, value) ->
            if (value is String) {
                File(context.cacheDir, value).delete()
            }
        }
        cacheRegistry.inTransaction { clear() }
    }
}

private class ImageLoadRequestImpl(override val src: Any?) : ImageLoadRequest {

    override var onLoaded: ((Bitmap) -> Unit)? = null
    override var onAlreadyLoaded: (() -> Unit)? = null
    override var onSizeDetected: ((Width, Height) -> Unit)? = null
    override var onProgress: ((Float) -> Unit)? = null
    override var targetSize: Pair<Width, Height>? = null
    override var onError: ((Throwable) -> Unit)? = null
    override var onSetPlaceHolder: (() -> Unit)? = null
    override var withETag: Boolean = false
    override var useMemoryCache: Boolean = false
    override var placeHolder: DrawableRes? = null
    override var force: Boolean = false
    var target: BitmapTarget? = null

    var onCancel: (() -> Unit)? = null
    var onExecute: (() -> Unit)? = null

    override fun cancel() {
        onCancel?.invoke()
    }

    override fun into(target: BitmapTarget) {
        if (!force && src != null && target.isAlreadyLoaded(src)) {
            onAlreadyLoaded?.invoke()
            return
        }
        this.target = target
        onExecute?.invoke()
    }
}
