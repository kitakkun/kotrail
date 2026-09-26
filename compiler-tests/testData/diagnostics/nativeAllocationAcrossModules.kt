// IGNORE_DEXING
// The library's helper creates a Bitmap per call and lets it out; the metadata the plugin writes
// tells the module that calls it in a loop.

// MODULE: lib
// KOTRAIL_CONFIG: rules.nativeAllocationInLoop=on
// FILE: Decoder.kt
package lib.decode

import org.jetbrains.skia.Bitmap

class Frame(val bytes: ByteArray)

// Recorded as @InferredNativeAllocation(["Bitmap"]) for callers.
fun decode(frame: Frame): Bitmap = Bitmap().apply { allocPixels() }

// Closes what it creates: nothing recorded.
fun measure(frame: Frame): Int = Bitmap().use { frame.bytes.size }

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.nativeAllocationInLoop=on
// FILE: Player.kt
package app

import lib.decode.Frame
import lib.decode.decode
import lib.decode.measure
import org.jetbrains.skia.Bitmap

fun publish(bitmap: Bitmap) {}

fun play(frames: List<Frame>) {
    // Reported through the library's metadata.
    for (frame in frames) {
        publish(<!KOTRAIL_NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP!>decode(frame)<!>)
    }
    // Not reported: the library helper closes its own, and this one closes the result.
    for (frame in frames) {
        measure(frame)
        decode(frame).use { }
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, forLoop, functionDeclaration, lambdaLiteral, localProperty, primaryConstructor,
propertyDeclaration */
