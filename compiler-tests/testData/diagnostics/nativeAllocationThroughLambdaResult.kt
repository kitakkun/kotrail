// KOTRAIL_CONFIG: rules.nativeAllocationInLoop=on
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

class Size(val width: Int, val height: Int)

class FrameStore {
    private val lock = Any()
    private val reentrantLock = ReentrantLock()
    private var ready: Bitmap? = null
    private var front: Bitmap? = null
    private var closed = false

    // Not reported: the Image is the value of the `synchronized` lambda, kept in a local and closed by `use`.
    fun newestPng(): ByteArray? {
        val newest = synchronized(lock) {
            if (closed) return null
            Image.makeFromBitmap(ready ?: front ?: return null)
        }
        return newest.use { image -> image.encodeToData()?.use(Data::bytes) }
    }

    // Not reported: the same through `withLock`, `run` and `with`, whose value is also their lambda's.
    fun newestPngWithLock(): ByteArray? {
        val newest = reentrantLock.withLock { Image.makeFromBitmap(ready ?: return null) }
        return newest.use { image -> image.encodeToData()?.use(Data::bytes) }
    }

    fun newestPngThroughRun(): ByteArray? {
        val newest = run { Image.makeFromBitmap(front ?: return null) }
        return newest.use { image -> image.encodeToData()?.use(Data::bytes) }
    }

    fun newestPngThroughWith(): ByteArray? {
        val newest = with(front ?: return null) { Image.makeFromBitmap(this) }
        return newest.use { image -> image.encodeToData()?.use(Data::bytes) }
    }

    // Not reported: the lambda's value is closed by `use` on the `synchronized` call itself.
    fun newestPngClosedInPlace(): ByteArray? =
        synchronized(lock) { Image.makeFromBitmap(ready ?: return null) }.use { image -> image.encodeToData()?.use(Data::bytes) }

    // Not reported: the PNG is decoded and closed inside `let`.
    fun newestSize(): Size? = newestPng()?.let { png -> Image.makeFromEncoded(png).use { Size(it.width, it.height) } }

    // Reported: the Image leaves through the `synchronized` lambda unclosed.
    fun newestImage(): Image? = synchronized(lock) { Image.makeFromBitmap(ready ?: return null) }

    fun newestImageKept(): Image? {
        val newest = synchronized(lock) { Image.makeFromBitmap(front ?: return null) }
        return newest
    }

    fun poll() {
        while (newestSize()?.width != 12) {
            newestPng()
            newestPngWithLock()
            newestPngThroughRun()
            newestPngThroughWith()
            newestPngClosedInPlace()
        }
        while (true) {
            <!KOTRAIL_NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP!>newestImage()<!>
            <!KOTRAIL_NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP!>newestImageKept()<!>
            // Not reported: closed by `use` here.
            newestImage()?.use { it.width }
        }
    }
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, elvisExpression, equalityExpression, functionDeclaration,
ifExpression, integerLiteral, javaFunction, lambdaLiteral, localProperty, nullableType, primaryConstructor,
propertyDeclaration, safeCall, thisExpression, whileLoop */
