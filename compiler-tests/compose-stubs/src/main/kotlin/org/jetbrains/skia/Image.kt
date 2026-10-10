package org.jetbrains.skia

import org.jetbrains.skia.impl.Managed

/** Stands in for Skia's Image: decoded pixels behind a native handle, made by a companion factory. */
class Image : Managed() {
    companion object {
        fun makeFromEncoded(bytes: ByteArray): Image = Image()
    }
}
