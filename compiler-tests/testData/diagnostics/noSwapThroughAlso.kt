// KOTRAIL_CONFIG: rules.noSwapThroughAlso=on
class Bitmap : AutoCloseable {
    override fun close() {}
}

class Holder {
    var value: Bitmap? = null
}

fun log(message: Any?) {}

var shared: Bitmap? = null

class FrameSlot {
    private val lock = Any()
    private var ready: Bitmap? = null
    private var showingKeptFrame = true
    private var frameCounter = 0
    private var onReset: () -> Unit = {}

    // Reported: returns the previous ready while the block overwrites it; dropping the also would leak the old bitmap.
    fun publish(frame: Bitmap) {
        val replaced = synchronized(lock) {
            ready.<!KOTRAIL_SWAP_THROUGH_ALSO!>also<!> {
                ready = frame
                showingKeptFrame = false
                frameCounter++
            }
        }
        replaced?.close()
    }

    // Reported: the same through an explicit this.
    fun swapExplicit(frame: Bitmap): Bitmap? = this.ready.<!KOTRAIL_SWAP_THROUGH_ALSO!>also<!> { ready = frame }

    // Reported: apply returns its receiver as well.
    fun swapApply(frame: Bitmap): Bitmap? {
        frameCounter++
        return ready.<!KOTRAIL_SWAP_THROUGH_ALSO!>apply<!> { ready = frame }
    }

    // Reported: run hands its block the previous value while the block reassigns the variable.
    fun swapRun(frame: Bitmap): Int {
        return ready.<!KOTRAIL_SWAP_THROUGH_ALSO!>run<!> {
            ready = frame
            frameCounter
        }
    }

    // Reported: let does the same with it.
    fun swapLet(frame: Bitmap) {
        ready.<!KOTRAIL_SWAP_THROUGH_ALSO!>let<!> {
            it?.close()
            ready = frame
        }
    }

    // Reported: closes the old value and clears the variable in one expression.
    fun clear() {
        ready.<!KOTRAIL_SWAP_THROUGH_ALSO!>also<!> {
            it?.close()
            ready = null
        }
    }

    // Reported: a safe call takes the value and clears it.
    fun take(): Bitmap? = ready?.<!KOTRAIL_SWAP_THROUGH_ALSO!>also<!> { ready = null }

    // Reported: the assignment sits in an inline lambda inside the block, which runs before also returns.
    fun swapNested(frame: Bitmap): Bitmap? = ready.<!KOTRAIL_SWAP_THROUGH_ALSO!>also<!> {
        repeat(1) { ready = frame }
    }

    // Reported: a desugared assignment counts.
    fun bump(): Int = frameCounter.<!KOTRAIL_SWAP_THROUGH_ALSO!>also<!> { frameCounter += 1 }

    // Not reported: the block reassigns another variable.
    fun count(): Bitmap? = ready.also { frameCounter++ }

    // Not reported: a lambda stored for later assigns the variable only when it runs.
    fun deferReset(): Bitmap? = ready.also { onReset = { ready = null } }

    // Not reported: a local function assigns the variable only when called.
    fun localFunction(): Bitmap? = ready.also {
        fun reset() {
            ready = null
        }
        log(::reset)
    }
}

fun locals(next: Bitmap) {
    var current: Bitmap? = null
    // Reported: a local variable.
    val old = current.<!KOTRAIL_SWAP_THROUGH_ALSO!>also<!> { current = next }
    old?.close()

    var x: Bitmap? = next
    var y: Bitmap? = null
    var counter = 0
    val result = x

    // Not reported: the block only reads.
    result.also { log(it) }

    // Not reported: the block assigns a variable that is not the receiver.
    x.also { counter++ }
    x.also { listOf(x).forEach { y = it } }
    log(y)
    log(counter)
}

// Reported: a top-level property.
fun swapShared(next: Bitmap): Bitmap? = shared.<!KOTRAIL_SWAP_THROUGH_ALSO!>also<!> { shared = next }

// Not reported: read through another object, which this rule leaves alone.
fun throughHolder(holder: Holder, next: Bitmap): Bitmap? = holder.value.also { holder.value = next }

// Not reported: the receiver is a call, not a variable.
fun throughCall(holder: Holder, next: Bitmap): Bitmap? = holderOf(holder).value.also { holder.value = next }

fun holderOf(holder: Holder): Holder = holder

/* GENERATED_FIR_TAGS: additiveExpression, assignment, callableReference, classDeclaration, functionDeclaration,
functionalType, incrementDecrementExpression, integerLiteral, lambdaLiteral, localFunction, localProperty, nullableType,
override, propertyDeclaration, safeCall, thisExpression */
