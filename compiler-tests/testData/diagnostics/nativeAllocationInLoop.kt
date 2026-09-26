// KOTRAIL_CONFIG: rules.nativeAllocationInLoop=on
import java.nio.ByteBuffer
import org.jetbrains.skia.Bitmap

class Frame(val bytes: ByteArray)

fun draw(bitmap: Bitmap) {}
fun publish(bitmap: Bitmap) {}

class Decoder(private val frames: List<Frame>) {
    private var latest: Bitmap? = null

    fun decodeAll() {
        // Reported: one native-backed object per iteration, never closed.
        for (frame in frames) {
            val bitmap = <!KOTRAIL_NATIVE_ALLOCATION_IN_LOOP!>Bitmap()<!>.apply { allocPixels() }
            publish(bitmap)
        }

        // Reported: a per-item callback is a loop too.
        frames.forEach { publish(<!KOTRAIL_NATIVE_ALLOCATION_IN_LOOP!>Bitmap()<!>) }

        // Reported: a factory of native memory in a while loop.
        var index = 0
        while (index < frames.size) {
            val buffer = <!KOTRAIL_NATIVE_ALLOCATION_IN_LOOP!>ByteBuffer.allocateDirect(1024)<!>
            buffer.put(frames[index].bytes)
            index++
        }

        // Reported: keeping the newest in a property still drops the previous one unclosed.
        for (frame in frames) {
            latest = <!KOTRAIL_NATIVE_ALLOCATION_IN_LOOP!>Bitmap()<!>
        }

        // Not reported: closed on every path by use, directly or through a scope function.
        for (frame in frames) {
            Bitmap().use { draw(it) }
            Bitmap().apply { allocPixels() }.use { draw(it) }
        }

        // Not reported: one instance outside the loop, reused.
        val reused = Bitmap()
        for (frame in frames) {
            reused.allocPixels()
            draw(reused)
        }
        reused.close()
    }

    // Reported: the allocation sits in a helper the loop calls, directly or two calls away.
    fun decodeThroughHelpers() {
        for (frame in frames) {
            publish(<!KOTRAIL_NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP!>decode(frame)<!>)
            publish(<!KOTRAIL_NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP!>decodeTwice(frame)<!>)
        }
        frames.forEach { publish(<!KOTRAIL_NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP!>decode(it)<!>) }

        // Not reported: the helper's result is closed here, or the helper closes what it creates itself,
        // or the helper returns an instance it did not create.
        for (frame in frames) {
            decode(frame).use { draw(it) }
            drawDecoded(frame)
            drawAndClose(frame)
            publish(current())
        }
    }

    fun decode(frame: Frame): Bitmap = Bitmap().apply { allocPixels() }

    fun decodeTwice(frame: Frame): Bitmap = decode(frame)

    fun drawDecoded(frame: Frame) {
        Bitmap().use { draw(it) }
    }

    fun drawAndClose(frame: Frame) {
        val bitmap = Bitmap().apply { allocPixels() }
        draw(bitmap)
        bitmap.close()
    }

    fun current(): Bitmap = latest ?: Bitmap().also { latest = it }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, comparisonExpression, flexibleType, forLoop, functionDeclaration,
incrementDecrementExpression, integerLiteral, javaFunction, lambdaLiteral, localProperty, nullableType,
primaryConstructor, propertyDeclaration, whileLoop */
