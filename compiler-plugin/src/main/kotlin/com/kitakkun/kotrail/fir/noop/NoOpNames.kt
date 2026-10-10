package com.kitakkun.kotrail.fir.noop

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

object NoOpNames {
    val INFERRED_NO_OP_WHEN = ClassId(FqName("com.kitakkun.kotrail.noop"), Name.identifier("InferredNoOpWhen"))
    val CONDITIONS_PARAM: Name = Name.identifier("conditions")

    private val DELAY = CallableId(FqName("kotlinx.coroutines"), Name.identifier("delay"))
    private val DURATION = ClassId(FqName("kotlin.time"), Name.identifier("Duration"))
    private const val YIELD_HINT = "To let other coroutines run, call yield()."

    /**
     * A library function known to return at once for some arguments, with no body to read it from:
     * the function by id and the classes of its value parameters, to tell overloads apart; the
     * conditions in the precondition language, with its parameter names; the sentence the
     * diagnostic ends with, or an empty one.
     */
    class KnownNoOp(val callableId: CallableId, val parameterTypes: List<ClassId>, val conditions: List<String>, val hint: String)

    /**
     * `delay` returns before suspending when the time is not positive (`if (timeMillis <= 0)
     * return`, and the `Duration` form passes 0 for a duration that is not positive), so it neither lets
     * other coroutines run nor checks for cancellation.
     */
    val KNOWN_FUNCTIONS: List<KnownNoOp> = listOf(
        KnownNoOp(DELAY, listOf(StandardClassIds.Long), listOf("timeMillis <= 0"), YIELD_HINT),
        KnownNoOp(DELAY, listOf(DURATION), listOf("duration <= Duration.ZERO"), YIELD_HINT),
    )
}
