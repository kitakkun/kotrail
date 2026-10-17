package org.jetbrains.skia

import org.jetbrains.skia.impl.Managed

/** Stands in for Skia's Image: decoded pixels behind a native handle, made by a companion factory. */
class Image : Managed() {
    val width: Int = 0
    val height: Int = 0

    /** Encodes the pixels; null when they cannot be encoded. */
    fun encodeToData(): Data? = Data()

    companion object {
        fun makeFromEncoded(bytes: ByteArray): Image = Image()
        fun makeFromBitmap(bitmap: Bitmap): Image = Image()
    }
}
