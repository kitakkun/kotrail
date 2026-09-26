package org.jetbrains.skia

import org.jetbrains.skia.impl.Managed

/** Stands in for Skia's Data: encoded bytes behind a native buffer. */
class Data : Managed() {
    val bytes: ByteArray = ByteArray(0)
}
