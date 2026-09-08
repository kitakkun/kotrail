package com.kitakkun.kotrail.fir

import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.rendering.DiagnosticParameterRenderer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * A diagnostic that exists at both severities so that each rule's severity can be configured.
 * The factory at the default severity carries the bare name; the other one carries a
 * `_WARNING` / `_ERROR` suffix, following the compiler's own deprecation diagnostics.
 */
class TunableDiagnostic0(val error: KtDiagnosticFactory0, val warning: KtDiagnosticFactory0) {
    fun at(severity: Severity): KtDiagnosticFactory0 = if (severity == Severity.WARNING) warning else error
}

class TunableDiagnostic1<A>(val error: KtDiagnosticFactory1<A>, val warning: KtDiagnosticFactory1<A>) {
    fun at(severity: Severity): KtDiagnosticFactory1<A> = if (severity == Severity.WARNING) warning else error
}

class TunableDiagnostic2<A, B>(val error: KtDiagnosticFactory2<A, B>, val warning: KtDiagnosticFactory2<A, B>) {
    fun at(severity: Severity): KtDiagnosticFactory2<A, B> = if (severity == Severity.WARNING) warning else error
}

object KotrailDiagnostics : KtDiagnosticsContainer() {
    private val NAME = SourceElementPositioningStrategies.NAME_IDENTIFIER
    private val WHOLE = SourceElementPositioningStrategies.DEFAULT

    // ---- general rules ----

    /** Argument: the name of the backing property (e.g. `_items`). */
    val PREFER_EXPLICIT_BACKING_FIELD = tunable1<KtProperty, String>("PREFER_EXPLICIT_BACKING_FIELD", Severity.ERROR, NAME)

    /** Arguments: the parameter name, a description of declared versus read properties. */
    val MODEL_PARAMETER_TOO_WIDE = tunable2<KtParameter, String, String>("MODEL_PARAMETER_TOO_WIDE", Severity.ERROR, NAME)

    /** Arguments: the function name, the input it returns unchanged (`this` or a parameter name). */
    val PASS_THROUGH_RETURN = tunable2<KtNamedFunction, String, String>("PASS_THROUGH_RETURN", Severity.ERROR, NAME)

    /** Argument: the callable reference that replaces the lambda, e.g. `::transform` or `User::name`. */
    val PREFER_FUNCTION_REFERENCE = tunable1<KtElement, String>("PREFER_FUNCTION_REFERENCE", Severity.ERROR, WHOLE)

    /** Argument: what was measured, e.g. `7 consecutive comment lines (limit 5)`. */
    val COMMENT_TOO_LONG = tunable1<KtElement, String>("COMMENT_TOO_LONG", Severity.ERROR, WHOLE)

    /** Argument: the import to add and how to write the reference afterwards. */
    val FQN_REFERENCE = tunable1<KtElement, String>("FQN_REFERENCE", Severity.ERROR, WHOLE)

    /** No arguments; reported on the `else` branch of an exhaustive `when` over a sealed/enum/Boolean subject. */
    val REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN = tunable0<KtElement>("REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN", Severity.ERROR, WHOLE)

    /** Argument: the class name. */
    val PREFER_VALUE_CLASS = tunable1<KtClass, String>("PREFER_VALUE_CLASS", Severity.ERROR, NAME)

    /** Argument: the fully qualified name of the forbidden callable. */
    val FORBIDDEN_CALL = tunable1<KtElement, String>("FORBIDDEN_CALL", Severity.ERROR, WHOLE)

    /** Argument: what was found (`TODO()` or `NotImplementedError`). Reported on the call. */
    val UNIMPLEMENTED_CODE = tunable1<KtElement, String>("UNIMPLEMENTED_CODE", Severity.ERROR, WHOLE)

    /** Arguments: the callee name, then the condition with the argument values that make it false. Reported on the call. */
    val PRECONDITION_VIOLATED = tunable2<KtElement, String, String>("PRECONDITION_VIOLATED", Severity.ERROR, WHOLE)

    /** No arguments; reported on the `!!` expression. */
    val NOT_NULL_ASSERTION = tunable0<KtElement>("NOT_NULL_ASSERTION", Severity.ERROR, WHOLE)

    /** No arguments; reported on a catch clause in a suspend context that swallows CancellationException. */
    val SWALLOWED_CANCELLATION = tunable0<KtElement>("SWALLOWED_CANCELLATION", Severity.ERROR, WHOLE)

    /** Argument: the caught variable's name; reported on a catch clause whose body never uses it. */
    val IGNORED_EXCEPTION = tunable1<KtElement, String>("IGNORED_EXCEPTION", Severity.ERROR, WHOLE)

    /** No arguments; reported on a function whose block body is a single `return`. */
    val PREFER_EXPRESSION_BODY = tunable0<KtNamedFunction>("PREFER_EXPRESSION_BODY", Severity.ERROR, NAME)

    /** Argument: the mutable type's name and the read-only type to use instead. */
    val MUTABLE_COLLECTION_IN_PUBLIC_API = tunable1<KtElement, String>("MUTABLE_COLLECTION_IN_PUBLIC_API", Severity.ERROR, WHOLE)

    /** Argument: description of the repeated type and count. */
    val NAMED_ARGUMENTS_REQUIRED = tunable1<KtElement, String>("NAMED_ARGUMENTS_REQUIRED", Severity.ERROR, WHOLE)

    // ---- Compose rules ----

    /** Argument: description of the insets and sides that are declared but not handled. */
    val WINDOW_INSETS_NOT_HANDLED = tunable1<KtNamedFunction, String>("WINDOW_INSETS_NOT_HANDLED", Severity.ERROR, NAME)

    /** Argument: description of the insets and sides that could not be proven handled. */
    val WINDOW_INSETS_HANDLING_UNVERIFIABLE =
        tunable1<KtNamedFunction, String>("WINDOW_INSETS_HANDLING_UNVERIFIABLE", Severity.WARNING, NAME)

    /** Arguments: the called composable's name, description of the overlapping insets. */
    val WINDOW_INSETS_HANDLED_TWICE = tunable2<KtElement, String, String>("WINDOW_INSETS_HANDLED_TWICE", Severity.WARNING, WHOLE)

    /** Arguments: the local's name, the keyword to use with delegation (`val` or `var`). */
    val PREFER_STATE_DELEGATION = tunable2<KtProperty, String, String>("PREFER_STATE_DELEGATION", Severity.ERROR, NAME)

    /** Arguments: the depth at the reported call, the configured limit. */
    val COMPOSABLE_NESTING_TOO_DEEP = tunable2<KtElement, String, String>("COMPOSABLE_NESTING_TOO_DEEP", Severity.ERROR, WHOLE)

    /** Argument: the parameter name; reported on a trailing non-composable function-type parameter. */
    val COMPOSABLE_TRAILING_CALLBACK = tunable1<KtParameter, String>("COMPOSABLE_TRAILING_CALLBACK", Severity.ERROR, NAME)

    /** Arguments: the function name, the expected casing (`PascalCase` or `camelCase`). */
    val COMPOSABLE_NAMING = tunable2<KtNamedFunction, String, String>("COMPOSABLE_NAMING", Severity.ERROR, NAME)

    /** Argument: the parameter name; reported on a callback passed to a composable as a trailing lambda. */
    val COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA = tunable1<KtElement, String>("COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA", Severity.ERROR, WHOLE)

    /** Argument: the composable's name; reported on a UI composable with no @Preview in its file. */
    val COMPOSABLE_WITHOUT_PREVIEW = tunable1<KtNamedFunction, String>("COMPOSABLE_WITHOUT_PREVIEW", Severity.ERROR, NAME)

    /** Arguments: how many composables the file declares, the limit; reported on each composable past the limit. */
    val TOO_MANY_COMPOSABLES_IN_FILE = tunable2<KtNamedFunction, String, String>("TOO_MANY_COMPOSABLES_IN_FILE", Severity.ERROR, NAME)

    /** Arguments: the offending type, the callee; reported on a call whose type argument is not serializable. */
    val TYPE_NOT_SERIALIZABLE = tunable2<KtElement, String, String>("TYPE_NOT_SERIALIZABLE", Severity.ERROR, WHOLE)

    /** Argument: what is wrong with the modifier parameter. */
    val COMPOSABLE_MODIFIER_PARAMETER = tunable1<KtNamedFunction, String>("COMPOSABLE_MODIFIER_PARAMETER", Severity.ERROR, NAME)

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = KotrailDiagnosticRenderers

    // ---- test rules ----

    /** Arguments: the test function name, the minimum number of words a name must have. */
    val TEST_NAME_NOT_DESCRIPTIVE = tunable2<KtNamedFunction, String, String>("TEST_NAME_NOT_DESCRIPTIVE", Severity.ERROR, NAME)

    /** Argument: the test function name. */
    val TEST_NAME_NOT_IDENTIFIER = tunable1<KtNamedFunction, String>("TEST_NAME_NOT_IDENTIFIER", Severity.ERROR, NAME)

    private inline fun <reified P : KtElement> tunable0(
        name: String,
        default: Severity,
        strategy: AbstractSourceElementPositioningStrategy,
    ): TunableDiagnostic0 = TunableDiagnostic0(
        error = KtDiagnosticFactory0(name.withSuffix(Severity.ERROR, default), Severity.ERROR, strategy, P::class, KotrailDiagnosticRenderers),
        warning = KtDiagnosticFactory0(name.withSuffix(Severity.WARNING, default), Severity.WARNING, strategy, P::class, KotrailDiagnosticRenderers),
    )

    private inline fun <reified P : KtElement, A> tunable1(
        name: String,
        default: Severity,
        strategy: AbstractSourceElementPositioningStrategy,
    ): TunableDiagnostic1<A> = TunableDiagnostic1(
        error = KtDiagnosticFactory1(name.withSuffix(Severity.ERROR, default), Severity.ERROR, strategy, P::class, KotrailDiagnosticRenderers),
        warning = KtDiagnosticFactory1(name.withSuffix(Severity.WARNING, default), Severity.WARNING, strategy, P::class, KotrailDiagnosticRenderers),
    )

    private inline fun <reified P : KtElement, A, B> tunable2(
        name: String,
        default: Severity,
        strategy: AbstractSourceElementPositioningStrategy,
    ): TunableDiagnostic2<A, B> = TunableDiagnostic2(
        error = KtDiagnosticFactory2(name.withSuffix(Severity.ERROR, default), Severity.ERROR, strategy, P::class, KotrailDiagnosticRenderers),
        warning = KtDiagnosticFactory2(name.withSuffix(Severity.WARNING, default), Severity.WARNING, strategy, P::class, KotrailDiagnosticRenderers),
    )

    private fun String.withSuffix(severity: Severity, default: Severity): String =
        if (severity == default) this else "${this}_${severity.name}"
}

object KotrailDiagnosticRenderers : BaseDiagnosticRendererFactory() {
    override val MAP by KtDiagnosticFactoryToRendererMap("Kotrail") { map ->
        map.put1(
            KotrailDiagnostics.PREFER_EXPLICIT_BACKING_FIELD,
            "[Kotrail] This property is exposed through the backing property ''{0}''. " +
                "Use an explicit backing field instead: declare only the public property " +
                "and initialize its ''field'' with the mutable implementation.",
        )
        map.put2(
            KotrailDiagnostics.MODEL_PARAMETER_TOO_WIDE,
            "[Kotrail] Parameter ''{0}'' is wider than this function needs: {1}. " +
                "Pass the values that are read, or a smaller model.",
        )
        map.put2(
            KotrailDiagnostics.PASS_THROUGH_RETURN,
            "[Kotrail] ''{0}'' returns ''{1}'' unchanged on every path, so the caller already has the result. " +
                "Return Unit, or return something the function actually computes.",
        )
        map.put1(
            KotrailDiagnostics.PREFER_FUNCTION_REFERENCE,
            "[Kotrail] This lambda only forwards its parameters. Use the callable reference {0} instead.",
        )
        map.put1(
            KotrailDiagnostics.COMMENT_TOO_LONG,
            "[Kotrail] Comment is too long: {0}. Say less, move the explanation into names and structure, " +
                "or use KDoc for documentation.",
        )
        map.put1(
            KotrailDiagnostics.FQN_REFERENCE,
            "[Kotrail] Fully qualified reference; {0} instead.",
        )
        map.put0(
            KotrailDiagnostics.REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN,
            "[Kotrail] This ''when'' is already exhaustive; the ''else'' branch can never run and would hide a " +
                "missing branch when a new case is added. Remove it.",
        )
        map.put1(
            KotrailDiagnostics.PREFER_VALUE_CLASS,
            "[Kotrail] ''{0}'' wraps a single value. Declare it as ''@JvmInline value class'' instead of a data class.",
        )
        map.put1(
            KotrailDiagnostics.FORBIDDEN_CALL,
            "[Kotrail] Calling ''{0}'' is forbidden by the project configuration.",
        )
        map.put1(
            KotrailDiagnostics.UNIMPLEMENTED_CODE,
            "[Kotrail] {0} is a placeholder for code that has not been written. Implement it before this build ships.",
        )
        map.put2(
            KotrailDiagnostics.PRECONDITION_VIOLATED,
            "[Kotrail] {0} requires {1}. This call would fail at runtime.",
        )
        map.put0(
            KotrailDiagnostics.NOT_NULL_ASSERTION,
            "[Kotrail] Do not use ''!!''. Handle the null case with ''?.'', ''?:'', ''requireNotNull'', or a smart cast.",
        )
        map.put0(
            KotrailDiagnostics.SWALLOWED_CANCELLATION,
            "[Kotrail] This catch clause swallows CancellationException inside a suspend context, which stops " +
                "cancellation from propagating. Rethrow it (''if (e is CancellationException) throw e'') or catch a " +
                "narrower type.",
        )
        map.put1(
            KotrailDiagnostics.IGNORED_EXCEPTION,
            "[Kotrail] The caught exception ''{0}'' is never used. Handle it, rethrow it, or explain the swallow " +
                "with a narrower catch.",
        )
        map.put0(
            KotrailDiagnostics.PREFER_EXPRESSION_BODY,
            "[Kotrail] This function body is a single ''return''. Use an expression body (''= ...'').",
        )
        map.put1(
            KotrailDiagnostics.MUTABLE_COLLECTION_IN_PUBLIC_API,
            "[Kotrail] Public API exposes a mutable collection type: {0}.",
        )
        map.put1(
            KotrailDiagnostics.NAMED_ARGUMENTS_REQUIRED,
            "[Kotrail] {0}. Name the arguments so that their order cannot be mixed up.",
        )
        map.put1(
            KotrailDiagnostics.WINDOW_INSETS_NOT_HANDLED,
            "[Kotrail] This composable declares that it handles window insets, but the following are not handled " +
                "in its body or in the composables it calls: {0}.",
        )
        map.put1(
            KotrailDiagnostics.WINDOW_INSETS_HANDLING_UNVERIFIABLE,
            "[Kotrail] Could not verify that this composable handles the following window insets because an " +
                "insets expression is not statically known: {0}. Use the WindowInsets companion properties, " +
                "only(), union(), add(), and exclude() so that the contract can be checked.",
        )
        map.put2(
            KotrailDiagnostics.WINDOW_INSETS_HANDLED_TWICE,
            "[Kotrail] ''{0}'' already handles {1} internally; the Modifier passed to it applies the same insets " +
                "again, which doubles the padding.",
        )
        map.put2(
            KotrailDiagnostics.PREFER_STATE_DELEGATION,
            "[Kotrail] ''{0}'' is a State that is only used through .value. Delegate it instead: " +
                "''{1} {0} by ...'' and use ''{0}'' directly.",
        )
        map.put2(
            KotrailDiagnostics.COMPOSABLE_NESTING_TOO_DEEP,
            "[Kotrail] Composable calls are nested {0} levels deep here; the limit is {1}. " +
                "Extract this subtree into its own composable.",
        )
        map.put1(
            KotrailDiagnostics.COMPOSABLE_TRAILING_CALLBACK,
            "[Kotrail] ''{0}'' is a callback in the trailing position. The trailing lambda of a composable is " +
                "read as its content slot; move the callback before the optional parameters (or make a @Composable " +
                "content parameter the last one).",
        )
        map.put2(
            KotrailDiagnostics.COMPOSABLE_NAMING,
            "[Kotrail] Composable ''{0}'' should be named in {1}: composables that emit UI (return Unit) use " +
                "PascalCase, composables that return a value use camelCase.",
        )
        map.put1(
            KotrailDiagnostics.COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA,
            "[Kotrail] ''{0}'' is a callback, but it is passed as a trailing lambda, which reads as the composable''s " +
                "content slot. Pass it as a named argument (''{0} = ...'').",
        )
        map.put1(
            KotrailDiagnostics.COMPOSABLE_WITHOUT_PREVIEW,
            "[Kotrail] ''{0}'' has no @Preview in this file. Add a preview composable next to it that calls ''{0}'', " +
                "or suppress this diagnostic when the composable cannot be previewed.",
        )
        map.put2(
            KotrailDiagnostics.TOO_MANY_COMPOSABLES_IN_FILE,
            "[Kotrail] This file declares {0} non-private composables; the limit is {1}. Move this one to its own file.",
        )
        map.put2(
            KotrailDiagnostics.TYPE_NOT_SERIALIZABLE,
            "[Kotrail] ''{0}'' is not serializable, but ''{1}'' serializes its state and would fail at runtime. " +
                "Annotate the class with @Serializable, or pass an explicit serializer.",
        )
        map.put1(
            KotrailDiagnostics.COMPOSABLE_MODIFIER_PARAMETER,
            "[Kotrail] Modifier parameter convention: {0}.",
        )
        map.put2(
            KotrailDiagnostics.TEST_NAME_NOT_DESCRIPTIVE,
            "[Kotrail] ''{0}'' does not say what this test verifies. Name it with a backticked sentence of at " +
                "least {1} words, for example `returns an empty list when nothing matches`.",
        )
        map.put1(
            KotrailDiagnostics.TEST_NAME_NOT_IDENTIFIER,
            "[Kotrail] ''{0}'' is not a plain identifier. This compilation is configured for targets that reject " +
                "method names with spaces, so name the test in camelCase.",
        )
    }

    private fun KtDiagnosticFactoryToRendererMap.put0(diagnostic: TunableDiagnostic0, message: String) {
        put(diagnostic.error, message)
        put(diagnostic.warning, message)
    }

    private fun KtDiagnosticFactoryToRendererMap.put1(diagnostic: TunableDiagnostic1<String>, message: String) {
        put(diagnostic.error, message, CommonRenderers.STRING)
        put(diagnostic.warning, message, CommonRenderers.STRING)
    }

    private fun KtDiagnosticFactoryToRendererMap.put2(diagnostic: TunableDiagnostic2<String, String>, message: String) {
        val renderer: DiagnosticParameterRenderer<String> = CommonRenderers.STRING
        put(diagnostic.error, message, renderer, renderer)
        put(diagnostic.warning, message, renderer, renderer)
    }
}
