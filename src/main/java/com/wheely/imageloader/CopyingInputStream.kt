package com.wheely.imageloader

import java.io.InputStream
import java.io.OutputStream

class CopyingInputStream(
    private val src: InputStream,
    private val output: OutputStream,
    private val onBytesWritten: (Long) -> Unit
) : InputStream() {

    private var bytesWritten: Long = 0
        set(value) {
            field = value
            onBytesWritten(value)
        }

    override fun read(): Int =
        src.read().also {
            if (it != -1) {
                output.write(it)
                bytesWritten++
            }
        }

    override fun read(b: ByteArray): Int =
        src.read(b).also {
            if (it > 0) {
                output.write(b, 0, it)
                bytesWritten += it
            }
        }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        src.read(b, off, len).also {
            if (it > 0) {
                output.write(b, off, it)
                bytesWritten += it
            }
        }

    @Suppress("ConvertTryFinallyToUseCall")
    override fun close() {
        try {
            src.close()
        } finally {
            output.close()
        }
    }

    fun copyTail() {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = src.read(buffer)
        while (bytes >= 0) {
            output.write(buffer, 0, bytes)
            bytesWritten += bytes
            bytes = src.read(buffer)
        }
    }
}
