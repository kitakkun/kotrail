package org.jetbrains.skia

import org.jetbrains.skia.impl.Managed

class Bitmap : Managed() {
    fun allocPixels() {}

    /** Encodes the pixels; null when they cannot be encoded. */
    fun encodeToData(): Data? = Data()
}
