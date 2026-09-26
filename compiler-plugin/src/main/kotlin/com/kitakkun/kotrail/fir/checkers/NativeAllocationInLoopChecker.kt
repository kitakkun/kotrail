package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.memory.NativeAllocationService
import com.kitakkun.kotrail.fir.memory.nativeAllocationService
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol

/**
 * Keeps native-backed objects from being created once per iteration:
 *
 * ```kotlin
 * for (frame in frames) {
 *     val bitmap = Bitmap().apply { allocPixels(info) }      // reported: 12 MB of native memory per frame
 *     publish(bitmap.asComposeImageBitmap())
 * }
 * frames.forEach { Bitmap().use { draw(it) } }              // fine: closed each time
 * val bitmap = Bitmap()                                      // fine: one instance, reused
 * for (frame in frames) { bitmap.install(frame) }
 * ```
 *
 * A Skia `Bitmap`, a direct `ByteBuffer` and their kin are tiny on the heap and large off it,
 * and the native part is freed only when a cleaner runs after the collector has found the
 * wrapper. In a loop the wrappers never add up to enough heap pressure for a collection, so the
 * native memory grows without bound: one decode loop with a 512 MB heap reached 189 GB. What
 * counts as such an object is `nativeAllocation.types` (subtypes included) and
 * `nativeAllocation.factories`; a loop is a `for`, `while` or `do` body, or the lambda of a
 * function in `nativeAllocation.callbacks`, which runs once per item or frame. An allocation
 * that is the receiver of `use { }` (directly, or through `apply`/`also`/`let`/`run`) is
 * closed on every path and is left alone. An allocation whose result is kept in a property
 * declared outside the loop is still reported: the previous instance is dropped unclosed.
 *
 * The allocation may sit inside a function the loop calls: `publish(decode(frame))` where
 * `decode` builds a `Bitmap` and returns it is the same leak one function away, and is reported
 * at the call with the path to the allocation. [NativeAllocationService] decides what a
 * function lets out, in this module from its body and across modules through the
 * `@InferredNativeAllocation` metadata the plugin writes. A function that creates the object
 * and closes it itself (`use { }`, or `close()` on the local) lets nothing out.
 */
object NativeAllocationInLoopChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val USE = NativeAllocationService.USE
    private val SCOPE_FUNCTIONS = NativeAllocationService.SCOPE_FUNCTIONS

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NATIVE_ALLOCATION_IN_LOOP)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val settings = config.nativeAllocation
        val allocation = context.session.nativeAllocationService.allocationOf(expression) ?: return
        if (!context.isPerIteration(settings.callbacks)) return
        if (context.isClosedByUse(expression)) return
        if (allocation.path.isEmpty()) {
            reportKotrail(source, KotrailDiagnostics.NATIVE_ALLOCATION_IN_LOOP, allocation.type)
        } else {
            reportKotrail(source, KotrailDiagnostics.NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP, "'${allocation.renderPath()}' creates a '${allocation.type}'", allocation.fate)
        }
    }

    /** Warms the allocation analysis for every non-private function, so that the IR metadata writer only reads cached results. */
    object Recorder : NamedFunctionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirNamedFunction) {
            if (!context.session.kotrailConfig.isEnabled(KotrailRule.NATIVE_ALLOCATION_IN_LOOP)) return
            if (declaration.body == null || declaration.visibility == Visibilities.Private) return
            context.session.nativeAllocationService.allocates(declaration.symbol)
        }
    }

    /** Whether the current expression sits in a loop body or in the lambda of a per-item callback. */
    private fun CheckerContext.isPerIteration(callbacks: List<String>): Boolean {
        val elements = containingElements
        for ((index, element) in elements.withIndex()) {
            when (element) {
                is FirWhileLoop, is FirDoWhileLoop -> return true
                is FirAnonymousFunction -> {
                    val call = elements.take(index).lastOrNull { it is FirFunctionCall } as? FirFunctionCall ?: continue
                    val name = call.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString() ?: continue
                    if (name in callbacks) return true
                }
                else -> {}
            }
        }
        return false
    }

    /**
     * Whether [allocation] is the receiver of a `use` call, directly or through a chain of scope
     * functions whose receiver it is: `Bitmap().apply { }.use { }`.
     */
    private fun CheckerContext.isClosedByUse(allocation: FirFunctionCall): Boolean {
        var current: FirElement = allocation
        val elements = containingElements
        for (parent in elements.asReversed()) {
            if (parent === current) continue
            val call = parent as? FirFunctionCall ?: return false
            if (call.explicitReceiver?.unwrapped() !== current) return false
            val name = call.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString() ?: return false
            if (name in USE) return true
            if (name !in SCOPE_FUNCTIONS) return false
            current = call
        }
        return false
    }

    private fun FirExpression.unwrapped(): FirExpression = (this as? FirSmartCastExpression)?.originalExpression ?: this
}
