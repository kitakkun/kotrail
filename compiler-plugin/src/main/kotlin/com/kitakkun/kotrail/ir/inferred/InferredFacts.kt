package com.kitakkun.kotrail.ir.inferred

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.effects.effectCaptureService
import com.kitakkun.kotrail.fir.compose.insets.WindowInsetsNames
import com.kitakkun.kotrail.fir.compose.insets.windowInsetsHandlingService
import com.kitakkun.kotrail.fir.compose.locals.CompositionLocalNames
import com.kitakkun.kotrail.fir.compose.locals.compositionLocalService
import com.kitakkun.kotrail.fir.concurrency.asyncWorkService
import com.kitakkun.kotrail.fir.inferred.InferredFactService
import com.kitakkun.kotrail.fir.memory.nativeAllocationService
import com.kitakkun.kotrail.fir.noop.NoOpNames
import com.kitakkun.kotrail.fir.noop.noOpService
import com.kitakkun.kotrail.fir.preconditions.PreconditionNames
import com.kitakkun.kotrail.fir.preconditions.preconditionService
import com.kitakkun.kotrail.inferred.InferredFactNames
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * The inferred facts the plugin writes as metadata, each the IR side of a FIR
 * [InferredFactService]: its facts-file name, its annotation, which declarations, and the arrays
 * the service encodes. The FIR side computed and cached the fact while bodies were available; here it is
 * only read back through the FIR declaration attached to the IR one.
 */
object InferredFacts {
    /** The facts, with the rules that switch each on; the writer takes the ones with any of their rules enabled. */
    val functionFacts: List<InferredFunctionFact> = listOf(WindowInsets, CompositionLocals, Preconditions, NoOpWhen, EffectCapture, StartsAsyncWork, NativeAllocation)
    val propertyFacts: List<InferredPropertyFact> = listOf(RequiredCompositionLocal, CompositionLocalGetter)
    val classFacts: List<InferredClassFact> = listOf(ClassPreconditions)

    private fun <S : org.jetbrains.kotlin.fir.symbols.FirBasedSymbol<*>, T> InferredFactService<S, T>.arraysFor(symbol: S): List<List<String>>? = encode(of(symbol))

    private fun List<Name>.strings(): List<String> = map { it.asString() }

    /** `@InferredWindowInsetsHandling` on a composable without a declared `@HandlesWindowInsets` contract. */
    object WindowInsets : InferredFunctionFact {
        override val name: String = InferredFactNames.WINDOW_INSETS
        override val rules = listOf(KotrailRule.COMPOSE_WINDOW_INSETS)
        override val annotation: ClassId = WindowInsetsNames.INFERRED_WINDOW_INSETS_HANDLING
        override val parameters = listOf("handled")
        override fun applies(declaration: IrSimpleFunction): Boolean =
            declaration.hasAnnotation(WindowInsetsNames.COMPOSABLE) && !declaration.hasAnnotation(WindowInsetsNames.HANDLES_WINDOW_INSETS)
        override fun arrays(declaration: IrSimpleFunction, fir: FirNamedFunction): List<List<String>>? =
            fir.moduleData.session.windowInsetsHandlingService.arraysFor(fir.symbol)
    }

    /** `@InferredCompositionLocals` on a composable that reads or provides a local. */
    object CompositionLocals : InferredFunctionFact {
        override val name: String = InferredFactNames.COMPOSITION_LOCALS
        override val rules = listOf(KotrailRule.COMPOSE_COMPOSITION_LOCALS)
        override val annotation: ClassId = CompositionLocalNames.INFERRED_COMPOSITION_LOCALS
        override val parameters = listOf("reads", "provides")
        override fun applies(declaration: IrSimpleFunction): Boolean = declaration.hasAnnotation(ComposeNames.COMPOSABLE)
        override fun arrays(declaration: IrSimpleFunction, fir: FirNamedFunction): List<List<String>>? =
            fir.moduleData.session.compositionLocalService.arraysFor(fir.symbol)
    }

    /** The same annotation on a property whose getter is composable: a reader too. */
    object CompositionLocalGetter : InferredPropertyFact {
        override val name: String = InferredFactNames.COMPOSITION_LOCAL_GETTER
        override val rules = listOf(KotrailRule.COMPOSE_COMPOSITION_LOCALS)
        override val annotation: ClassId = CompositionLocalNames.INFERRED_COMPOSITION_LOCALS
        override val parameters = listOf("reads", "provides")
        override fun applies(declaration: IrProperty): Boolean = declaration.getter?.hasAnnotation(ComposeNames.COMPOSABLE) == true
        override fun arrays(declaration: IrProperty, fir: FirProperty): List<List<String>>? =
            fir.moduleData.session.compositionLocalService.getters.arraysFor(fir.symbol)
    }

    /** `@InferredRequiredCompositionLocal`, a marker, on a local whose default throws. */
    object RequiredCompositionLocal : InferredPropertyFact {
        override val name: String = InferredFactNames.REQUIRED_COMPOSITION_LOCAL
        override val rules = listOf(KotrailRule.COMPOSE_COMPOSITION_LOCALS)
        override val annotation: ClassId = CompositionLocalNames.INFERRED_REQUIRED_COMPOSITION_LOCAL
        override val parameters: List<String> = emptyList()
        override fun arrays(declaration: IrProperty, fir: FirProperty): List<List<String>>? =
            if (fir.moduleData.session.compositionLocalService.isRequiredBySource(fir.symbol)) emptyList() else null
    }

    /** `@InferredPreconditions` on a function whose leading `require` / `check` calls fit the precondition language. */
    object Preconditions : InferredFunctionFact {
        override val name: String = InferredFactNames.PRECONDITIONS
        override val rules = listOf(KotrailRule.PRECONDITIONS)
        override val annotation: ClassId = PreconditionNames.INFERRED_PRECONDITIONS
        override val parameters = listOf("conditions")
        override fun arrays(declaration: IrSimpleFunction, fir: FirNamedFunction): List<List<String>>? =
            fir.moduleData.session.preconditionService.functions.arraysFor(fir.symbol)
    }

    /** The same on a class, for the `init` blocks that check its primary constructor's parameters. */
    object ClassPreconditions : InferredClassFact {
        override val name: String = InferredFactNames.CLASS_PRECONDITIONS
        override val rules = listOf(KotrailRule.PRECONDITIONS)
        override val annotation: ClassId = PreconditionNames.INFERRED_PRECONDITIONS
        override val parameters = listOf("conditions")
        override fun arrays(declaration: IrClass, fir: FirRegularClass): List<List<String>>? =
            fir.moduleData.session.preconditionService.classes.arraysFor(fir.symbol)
    }

    /** `@InferredNoOpWhen` on a function whose opening `if (condition) return` guards fit the precondition language. */
    object NoOpWhen : InferredFunctionFact {
        override val name: String = InferredFactNames.NO_OP_WHEN
        override val rules = listOf(KotrailRule.NO_OP_CALL)
        override val annotation: ClassId = NoOpNames.INFERRED_NO_OP_WHEN
        override val parameters = listOf("conditions")
        override fun arrays(declaration: IrSimpleFunction, fir: FirNamedFunction): List<List<String>>? =
            fir.moduleData.session.noOpService.arraysFor(fir.symbol)
    }

    /** `@InferredEffectCapture` on a composable that keeps some parameters for the life of an effect. */
    object EffectCapture : InferredFunctionFact {
        override val name: String = InferredFactNames.EFFECT_CAPTURE
        override val rules = listOf(KotrailRule.COMPOSE_REMEMBER_KEYS)
        override val annotation: ClassId = ComposeNames.INFERRED_EFFECT_CAPTURE
        override val parameters = listOf("captured")
        override fun applies(declaration: IrSimpleFunction): Boolean = declaration.hasAnnotation(ComposeNames.COMPOSABLE)
        override fun arrays(declaration: IrSimpleFunction, fir: FirNamedFunction): List<List<String>>? =
            fir.moduleData.session.effectCaptureService.arraysFor(fir.symbol)
    }

    /** `@InferredStartsAsyncWork`, a marker, on a non-suspending function that starts work its caller cannot wait for. */
    object StartsAsyncWork : InferredFunctionFact {
        override val name: String = InferredFactNames.STARTS_ASYNC_WORK
        override val rules = listOf(KotrailRule.DELAY_FOR_COMPLETION)
        override val annotation: ClassId = com.kitakkun.kotrail.fir.concurrency.AsyncWorkService.INFERRED_STARTS_ASYNC_WORK
        override val parameters: List<String> = emptyList()
        override fun applies(declaration: IrSimpleFunction): Boolean = !declaration.isSuspend
        override fun arrays(declaration: IrSimpleFunction, fir: FirNamedFunction): List<List<String>>? =
            fir.moduleData.session.asyncWorkService.arraysFor(fir.symbol)
    }

    /** `@InferredNativeAllocation` on a function that creates a native-backed object and lets it out unclosed. */
    object NativeAllocation : InferredFunctionFact {
        override val name: String = InferredFactNames.NATIVE_ALLOCATION
        override val rules = listOf(KotrailRule.NATIVE_ALLOCATION_IN_LOOP)
        override val annotation: ClassId = com.kitakkun.kotrail.fir.memory.NativeAllocationService.INFERRED_NATIVE_ALLOCATION
        override val parameters = listOf("types", "path")
        override fun arrays(declaration: IrSimpleFunction, fir: FirNamedFunction): List<List<String>>? =
            fir.moduleData.session.nativeAllocationService.arraysFor(fir.symbol)
    }
}
