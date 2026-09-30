// KOTRAIL_CONFIG: rules.catchTooBroad=on
import kotlinx.coroutines.CancellationException

class State<T>(var value: T)
class Canvas
class RenderFailed(cause: Throwable) : Exception(cause)

val failure = State<Throwable?>(null)

fun draw() {}
fun report(e: Throwable) {}
fun prepare() {}

// Records the failure, then rethrows it: every failure of the body reaches the caller recorded.
fun render() {
    try {
        draw()
    } catch (e: Throwable) {
        failure.value = e
        throw e
    }
}

// The same as an expression body, rethrowing wrapped, after letting cancellation through unrecorded.
fun renderWrapped(canvas: Canvas) = try {
    draw()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    report(e)
    throw RenderFailed(e)
}

// Does not record: it only rethrows.
fun rethrowOnly() {
    try {
        draw()
    } catch (e: Throwable) {
        throw e
    }
}

// Does not record what `prepare()` throws: the try does not cover the whole body.
fun preparedRender() {
    prepare()
    try {
        draw()
    } catch (e: Throwable) {
        failure.value = e
        throw e
    }
}

class Scene {
    fun render(canvas: Canvas) {
        try {
            draw()
        } catch (e: Throwable) {
            report(e)
            throw e
        }
    }
}

fun boundaries(scene: Scene?, canvas: Canvas) {
    // Not reported: render() has recorded the failure before rethrowing it.
    try {
        render()
    } catch (e: Throwable) {
    }

    // Not reported: the same for a member called through a safe call, with an unnamed parameter.
    try {
        scene?.render(canvas)
    } catch (_: Throwable) {
        // render() has recorded the failure.
    }

    // Not reported: an expression body that records and rethrows wrapped.
    try {
        renderWrapped(canvas)
    } catch (e: Exception) {
    }

    // Reported: the callee does not record.
    try {
        rethrowOnly()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Throwable<!>) {
    }

    // Reported: the callee does work outside its try.
    try {
        preparedRender()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Throwable<!>) {
    }

    // Reported: the try body is more than the recording call.
    try {
        prepare()
        render()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Throwable<!>) {
    }

    // Reported: the exemption covers an empty Throwable or Exception clause only.
    try {
        render()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Error<!>) {
    }
}

suspend fun suspendingBoundaries() {
    // Not reported: cancellation is let through before the empty clause.
    try {
        render()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
    }

    // Reported: in a coroutine the empty clause would swallow cancellation.
    try {
        render()
    } catch (<!KOTRAIL_CATCH_TOO_BROAD!>e: Throwable<!>) {
    }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, functionDeclaration, localProperty, nullableType,
primaryConstructor, propertyDeclaration, safeCall, suspend, tryExpression, typeParameter, unnamedLocalVariable */
