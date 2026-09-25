package org.jetbrains.skia.impl

/** A stand-in for Skiko's Managed: a native-backed object freed by a cleaner unless closed. */
abstract class Managed : AutoCloseable {
    override fun close() {}
}
