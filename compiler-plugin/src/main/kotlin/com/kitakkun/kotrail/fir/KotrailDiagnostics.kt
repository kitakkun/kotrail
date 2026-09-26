package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.KotrailRule
import org.jetbrains.kotlin.diagnostics.AbstractSourceElementPositioningStrategy
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory3
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.rendering.DiagnosticParameterRenderer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * A diagnostic that exists at both severities so that each rule's severity can be configured.
 * Every name starts with `KOTRAIL_`, so that a `@Suppress` says where the rule comes from and
 * never meets a compiler or other-plugin diagnostic of the same name. The factory at the rule's
 * default severity carries that base name; the other one carries a `_WARNING` / `_ERROR` suffix,
 * following the compiler's own deprecation diagnostics. [baseName] is what `@Suppress` matches
 * at either severity (see [com.kitakkun.kotrail.fir.reportKotrail]).
 *
 * Every factory carries one parameter more than the diagnostic's own arguments: the project's
 * note, appended to the message. It is empty unless the project set `note` or `note.<rule>`, and
 * the message templates end with the matching placeholder. The [rule] is what
 * [com.kitakkun.kotrail.fir.report] looks the severity and the note up by, so a call site names
 * neither.
 */
class TunableDiagnostic0(
    val rule: KotrailRule,
    val baseName: String,
    val error: KtDiagnosticFactory1<String>,
    val warning: KtDiagnosticFactory1<String>,
) {
    fun at(severity: Severity): KtDiagnosticFactory1<String> = if (severity == Severity.WARNING) warning else error
}

class TunableDiagnostic1<A>(
    val rule: KotrailRule,
    val baseName: String,
    val error: KtDiagnosticFactory2<A, String>,
    val warning: KtDiagnosticFactory2<A, String>,
) {
    fun at(severity: Severity): KtDiagnosticFactory2<A, String> = if (severity == Severity.WARNING) warning else error
}

class TunableDiagnostic2<A, B>(
    val rule: KotrailRule,
    val baseName: String,
    val error: KtDiagnosticFactory3<A, B, String>,
    val warning: KtDiagnosticFactory3<A, B, String>,
) {
    fun at(severity: Severity): KtDiagnosticFactory3<A, B, String> = if (severity == Severity.WARNING) warning else error
}

object KotrailDiagnostics : KtDiagnosticsContainer() {
    private val NAME = SourceElementPositioningStrategies.NAME_IDENTIFIER
    private val WHOLE = SourceElementPositioningStrategies.DEFAULT
    private val REFERENCED_NAME = SourceElementPositioningStrategies.REFERENCED_NAME_BY_QUALIFIED

    // ---- general rules ----

    /** Argument: the name of the backing property (e.g. `_items`). */
    val PREFER_EXPLICIT_BACKING_FIELD = tunable1<KtProperty, String>("PREFER_EXPLICIT_BACKING_FIELD", KotrailRule.PREFER_EXPLICIT_BACKING_FIELD, NAME)

    /** Argument: the name of the backing `var` (e.g. `_count`). */
    val PREFER_PRIVATE_SETTER = tunable1<KtProperty, String>("PREFER_PRIVATE_SETTER", KotrailRule.PREFER_PRIVATE_SETTER, NAME)

    /** Arguments: the parameter name, a description of declared versus read properties. */
    val MODEL_PARAMETER_TOO_WIDE = tunable2<KtParameter, String, String>("MODEL_PARAMETER_TOO_WIDE", KotrailRule.NARROW_MODEL_PARAMETERS, NAME)

    /** Arguments: the function name, the input it returns unchanged (`this` or a parameter name). */
    val PASS_THROUGH_RETURN = tunable2<KtNamedFunction, String, String>("PASS_THROUGH_RETURN", KotrailRule.NO_PASS_THROUGH_RETURN, NAME)

    /** Arguments: the function name, the callee it forwards to. */
    val PASS_THROUGH_FUNCTION = tunable2<KtNamedFunction, String, String>("PASS_THROUGH_FUNCTION", KotrailRule.NO_PASS_THROUGH_FUNCTION, NAME)

    /** Argument: the callable reference that replaces the lambda, e.g. `::transform` or `User::name`. */
    val PREFER_FUNCTION_REFERENCE = tunable1<KtElement, String>("PREFER_FUNCTION_REFERENCE", KotrailRule.PREFER_FUNCTION_REFERENCES, WHOLE)

    /** Arguments: the declaration name, the visibility the policy requires. */
    val VISIBILITY_TOO_WIDE = tunable2<KtElement, String, String>("VISIBILITY_TOO_WIDE", KotrailRule.VISIBILITY_POLICY, NAME)

    /** Arguments: the declaration name, the annotation to add with the policy name, e.g. `@com.acme.Screen (policy 'screens')`. */
    val REQUIRED_ANNOTATION_MISSING = tunable2<KtElement, String, String>("REQUIRED_ANNOTATION_MISSING", KotrailRule.REQUIRED_ANNOTATION, NAME)

    /** Argument: the class name. */
    val DATA_CLASS_IN_PUBLIC_API = tunable1<KtClass, String>("DATA_CLASS_IN_PUBLIC_API", KotrailRule.NO_DATA_CLASS_IN_PUBLIC_API, NAME)

    /** Argument: what was measured, e.g. `72 lines of code (limit 50)`. */
    val FUNCTION_TOO_LONG = tunable1<KtNamedFunction, String>("FUNCTION_TOO_LONG", KotrailRule.FUNCTION_LENGTH, NAME)

    /** Argument: what was measured, e.g. `620 lines of code (limit 500)`; reported on the package directive. */
    val FILE_TOO_LONG = tunable1<KtElement, String>("FILE_TOO_LONG", KotrailRule.FILE_LENGTH, WHOLE)

    /** Arguments: what the file declares (`14 top-level names (3 classes, 9 functions, 2 properties), limit 10`), the fix that fits; reported once on the package directive. */
    val FILE_TOO_FLAT = tunable2<KtElement, String, String>("FILE_TOO_FLAT", KotrailRule.FILE_LENGTH, WHOLE)

    /** Arguments: the name's position (`12 of 14`), the limit; reported on each name past the limit. */
    val TOO_MANY_TOP_LEVEL_DECLARATIONS = tunable2<KtNamedDeclaration, String, String>("TOO_MANY_TOP_LEVEL_DECLARATIONS", KotrailRule.FILE_LENGTH, NAME)

    /** Arguments: what is looped over (`loops over 2 literal elements`), the advice; reported on the looped expression. */
    val LITERAL_LOOP = tunable2<KtElement, String, String>("LITERAL_LOOP", KotrailRule.NO_LITERAL_LOOP, WHOLE)

    /** Argument: how often, e.g. `3 times (limit 2)`. Reported on the outermost `?:` expression. */
    val ELVIS_CHAIN_TOO_LONG = tunable1<KtElement, String>("ELVIS_CHAIN_TOO_LONG", KotrailRule.NULL_CHAIN_LENGTH, WHOLE)

    /** Argument: the property name; reported on a `var` that nothing reassigns. */
    val PREFER_VAL = tunable1<KtProperty, String>("PREFER_VAL", KotrailRule.PREFER_VAL, NAME)

    /** Arguments: the declaration name, the annotation to add (`@JvmSynthetic`, `@get:JvmSynthetic @set:JvmSynthetic`); reported on an internal function or property of a JVM module. */
    val INTERNAL_VISIBLE_TO_JAVA = tunable2<KtElement, String, String>("INTERNAL_VISIBLE_TO_JAVA", KotrailRule.JVM_SYNTHETIC_FOR_INTERNAL, NAME)

    /** Argument: the class name; reported on an internal class of a JVM module, which stays public in bytecode. */
    val INTERNAL_CLASS_VISIBLE_TO_JAVA = tunable1<KtClass, String>("INTERNAL_CLASS_VISIBLE_TO_JAVA", KotrailRule.JVM_SYNTHETIC_FOR_INTERNAL_CLASS, NAME)

    /** Arguments: the live names, the count with the limit; reported on the first statement past the budget. */
    val TOO_MANY_LIVE_VARIABLES = tunable2<KtElement, String, String>("TOO_MANY_LIVE_VARIABLES", KotrailRule.LIVE_VARIABLE_BUDGET, WHOLE)

    /** Arguments: the helper's name, the first declaration that uses it; reported on a private function declared before its first user. */
    val HELPER_BEFORE_FIRST_USE = tunable2<KtNamedFunction, String, String>("HELPER_BEFORE_FIRST_USE", KotrailRule.NARRATIVE_ORDER, NAME)

    /** Argument: the property, or `ThreadLocal()`; reported on a property of a ThreadLocal type, or a bare construction, in a compilation that is unloaded. */
    val THREAD_LOCAL_IN_UNLOADABLE_CODE = tunable1<KtElement, String>("THREAD_LOCAL_IN_UNLOADABLE_CODE", KotrailRule.UNLOADABLE_CODE, WHOLE)

    /** Arguments: what was found (a ThreadLocal, an unscoped registration), `file:line`; reported on the anchor file of the compilation that bundles the module. */
    val OUTBOUND_REFERENCE_IN_BUNDLED_CODE = tunable2<KtElement, String, String>("OUTBOUND_REFERENCE_IN_BUNDLED_CODE", KotrailRule.UNLOADABLE_CODE, WHOLE)

    /** Argument: the registration function; reported on a call from `registrations` with no disposable argument. */
    val UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE = tunable1<KtElement, String>("UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE", KotrailRule.UNLOADABLE_CODE, WHOLE)

    /** Argument: the type; reported on a construction or factory call of a native-backed type inside a loop body or a per-item callback. */
    val NATIVE_ALLOCATION_IN_LOOP = tunable1<KtElement, String>("NATIVE_ALLOCATION_IN_LOOP", KotrailRule.NATIVE_ALLOCATION_IN_LOOP, WHOLE)

    /** Argument: the resource's type; reported on a creation that nothing closes or hands on. */
    val RESOURCE_NOT_CLOSED = tunable1<KtElement, String>("RESOURCE_NOT_CLOSED", KotrailRule.MUST_CLOSE, WHOLE)

    /** Arguments: what creates what (`'decode > newBitmap' creates a 'Bitmap'`), what becomes of it (`returns`, `keeps`, `lets go of`); reported on a call in a loop to a function that allocates. */
    val NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP = tunable2<KtElement, String, String>("NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP", KotrailRule.NATIVE_ALLOCATION_IN_LOOP, WHOLE)

    /** Argument: what was wrapped; reported on a weak reference built from an object nothing else holds. */
    val WEAK_REFERENCE_TO_FRESH_OBJECT = tunable1<KtElement, String>("WEAK_REFERENCE_TO_FRESH_OBJECT", KotrailRule.WEAK_ONLY_REFERENCE, WHOLE)

    /** Arguments: the caught type, the rest of the sentence (what the clause swallows, and what to do); reported on the catch parameter. */
    val CATCH_TOO_BROAD = tunable2<KtElement, String, String>("CATCH_TOO_BROAD", KotrailRule.CATCH_TOO_BROAD, WHOLE)

    /** Arguments: the parameter, how it escapes; reported on the expression through which an unretained parameter is retained. */
    val UNRETAINED_PARAMETER_RETAINED = tunable2<KtElement, String, String>("UNRETAINED_PARAMETER_RETAINED", KotrailRule.UNRETAINED, WHOLE)

    /** Arguments: the class, the required supertype with the policy; reported on a class name. */
    val SUPERTYPE_REQUIRED = tunable2<KtClassOrObject, String, String>("SUPERTYPE_REQUIRED", KotrailRule.REQUIRED_SUPERTYPE, NAME)

    /** Arguments: the referenced package, the policy; reported on the import or the reference. */
    val DEPENDENCY_NOT_ALLOWED = tunable2<KtElement, String, String>("DEPENDENCY_NOT_ALLOWED", KotrailRule.DEPENDENCY_RULES, WHOLE)

    /** Arguments: the call, the names read in its lambda but missing from its keys; reported on the call. */
    val EFFECT_KEY_MISSING = tunable2<KtElement, String, String>("EFFECT_KEY_MISSING", KotrailRule.COMPOSE_REMEMBER_KEYS, WHOLE)

    /** Arguments: the callee and its parameter, the values the passed lambda reads; reported on the argument of a call to a composable that keeps that parameter in a long-lived effect. */
    val EFFECT_CAPTURED_BY_CALLEE = tunable2<KtElement, String, String>("EFFECT_CAPTURED_BY_CALLEE", KotrailRule.COMPOSE_REMEMBER_KEYS, WHOLE)

    /** Arguments: the wait as written, what started the work and how to wait for it properly; reported on a fixed delay that follows a call starting asynchronous work. */
    val DELAY_WAITS_FOR_ASYNC_WORK = tunable2<KtElement, String, String>("DELAY_WAITS_FOR_ASYNC_WORK", KotrailRule.DELAY_FOR_COMPLETION, WHOLE)

    /** Argument: the test's name; reported on a test function that asserts nothing. */
    val TEST_WITHOUT_ASSERTION = tunable1<KtNamedFunction, String>("TEST_WITHOUT_ASSERTION", KotrailRule.TEST_MUST_ASSERT, NAME)

    /** Arguments: the function-typed parameter, the data parameter after it; reported on the function-typed parameter. */
    val CALLBACK_BEFORE_DATA_PARAMETER = tunable2<KtElement, String, String>("CALLBACK_BEFORE_DATA_PARAMETER", KotrailRule.PARAMETER_ORDER, WHOLE)

    /** Argument: the local's name; reported on a local val that only one branch below it uses. */
    val NARROW_LOCAL_SCOPE = tunable1<KtProperty, String>("NARROW_LOCAL_SCOPE", KotrailRule.NARROW_LOCAL_SCOPE, NAME)

    /** Arguments: the local's name, the distance (`9 lines (limit 5)`); reported on a local val declared long before its first use. */
    val LOCAL_DECLARED_TOO_EARLY = tunable2<KtProperty, String, String>("LOCAL_DECLARED_TOO_EARLY", KotrailRule.NARROW_LOCAL_SCOPE, NAME)

    /** Arguments: the expression as written, the idiom to write with its key in parentheses; reported on the expression. */
    val PREFER_IDIOM = tunable2<KtElement, String, String>("PREFER_IDIOM", KotrailRule.PREFER_IDIOM, WHOLE)

    /** Arguments: the branch condition as written (`Cancel`), the form to write (`is Cancel`); reported on the condition. */
    val SEALED_WHEN_BRANCH_STYLE = tunable2<KtElement, String, String>("SEALED_WHEN_BRANCH_STYLE", KotrailRule.SEALED_WHEN_BRANCH_STYLE, WHOLE)

    /** Arguments: the name, the receiver it resolved on with the other one that has the name too; reported on the access. */
    val IMPLICIT_RECEIVER_AMBIGUOUS = tunable2<KtElement, String, String>("IMPLICIT_RECEIVER_AMBIGUOUS", KotrailRule.IMPLICIT_RECEIVERS, WHOLE)

    /** Argument: what was counted, e.g. `3 implicit receivers in scope (limit 2)`; reported on the lambda past the limit. */
    val TOO_MANY_IMPLICIT_RECEIVERS = tunable1<KtElement, String>("TOO_MANY_IMPLICIT_RECEIVERS", KotrailRule.IMPLICIT_RECEIVERS, WHOLE)

    /** Argument: how deep, e.g. `4 times (limit 3)`. Reported on the outermost `?.` expression. */
    val SAFE_CALL_CHAIN_TOO_LONG = tunable1<KtElement, String>("SAFE_CALL_CHAIN_TOO_LONG", KotrailRule.NULL_CHAIN_LENGTH, WHOLE)

    /** Argument: the function the parameter list belongs to. Reported on the comment. */
    val COMMENT_IN_PARAMETER_LIST = tunable1<KtElement, String>("COMMENT_IN_PARAMETER_LIST", KotrailRule.NO_PARAMETER_COMMENTS, WHOLE)

    /** Argument: what was measured, e.g. `7 consecutive comment lines (limit 5)`. */
    val COMMENT_TOO_LONG = tunable1<KtElement, String>("COMMENT_TOO_LONG", KotrailRule.COMMENT_LENGTH, WHOLE)

    /** Argument: the import to add and how to write the reference afterwards. */
    val FQN_REFERENCE = tunable1<KtElement, String>("FQN_REFERENCE", KotrailRule.NO_FQN_REFERENCES, WHOLE)

    /** No arguments; reported on the `else` branch of an exhaustive `when` over a sealed/enum/Boolean subject. */
    val REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN = tunable0<KtElement>("REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN", KotrailRule.NO_REDUNDANT_ELSE, WHOLE)

    /** Argument: the class name. */
    val PREFER_VALUE_CLASS = tunable1<KtClass, String>("PREFER_VALUE_CLASS", KotrailRule.PREFER_VALUE_CLASS, NAME)

    /** Arguments: the callee's fully qualified name, the name of the forbiddenCall entry that matched. */
    val FORBIDDEN_CALL = tunable2<KtElement, String, String>("FORBIDDEN_CALL", KotrailRule.FORBIDDEN_CALL, WHOLE)

    /** Argument: what was found (`TODO()` or `NotImplementedError`). Reported on the call. */
    val UNIMPLEMENTED_CODE = tunable1<KtElement, String>("UNIMPLEMENTED_CODE", KotrailRule.NO_UNIMPLEMENTED, WHOLE)

    /** Arguments: the callee name, then the condition with the argument values that make it false. Reported on the call. */
    val PRECONDITION_VIOLATED = tunable2<KtElement, String, String>("PRECONDITION_VIOLATED", KotrailRule.PRECONDITIONS, WHOLE)

    /** No arguments; reported on the `!!` expression. */
    val NOT_NULL_ASSERTION = tunable0<KtElement>("NOT_NULL_ASSERTION", KotrailRule.NO_NOT_NULL_ASSERTION, WHOLE)

    /** No arguments; reported on a catch clause in a suspend context that swallows CancellationException. */
    val SWALLOWED_CANCELLATION = tunable0<KtElement>("SWALLOWED_CANCELLATION", KotrailRule.NO_SWALLOWED_CANCELLATION, WHOLE)

    /** Argument: the caught variable's name; reported on a catch clause whose body never uses it. */
    val IGNORED_EXCEPTION = tunable1<KtElement, String>("IGNORED_EXCEPTION", KotrailRule.NO_IGNORED_EXCEPTION, WHOLE)

    /** No arguments; reported on a function whose block body is a single `return`. */
    val PREFER_EXPRESSION_BODY = tunable0<KtNamedFunction>("PREFER_EXPRESSION_BODY", KotrailRule.PREFER_EXPRESSION_BODY, NAME)

    /** Argument: the mutable type's name and the read-only type to use instead. */
    val MUTABLE_COLLECTION_IN_PUBLIC_API = tunable1<KtElement, String>("MUTABLE_COLLECTION_IN_PUBLIC_API", KotrailRule.NO_MUTABLE_COLLECTION_IN_PUBLIC_API, WHOLE)

    /** Argument: description of the repeated type and count. */
    val NAMED_ARGUMENTS_REQUIRED = tunable1<KtElement, String>("NAMED_ARGUMENTS_REQUIRED", KotrailRule.NAMED_ARGUMENTS_FOR_REPEATED_TYPES, WHOLE)

    // ---- Compose rules ----

    /** Argument: description of the insets and sides that are declared but not handled. */
    val WINDOW_INSETS_NOT_HANDLED = tunable1<KtNamedFunction, String>("WINDOW_INSETS_NOT_HANDLED", KotrailRule.COMPOSE_WINDOW_INSETS, NAME)

    /** Argument: description of the insets and sides that could not be proven handled. */
    val WINDOW_INSETS_HANDLING_UNVERIFIABLE =
        tunable1<KtNamedFunction, String>("WINDOW_INSETS_HANDLING_UNVERIFIABLE", KotrailRule.COMPOSE_WINDOW_INSETS_UNVERIFIABLE, NAME)

    /** Arguments: the called composable's name, description of the overlapping insets. */
    val WINDOW_INSETS_HANDLED_TWICE = tunable2<KtElement, String, String>("WINDOW_INSETS_HANDLED_TWICE", KotrailRule.COMPOSE_WINDOW_INSETS_HANDLED_TWICE, WHOLE)

    /** Arguments: the local's fully qualified name, where it is read (`read here` or `read in A > B`). Reported on the root function name. */
    val COMPOSITION_LOCAL_NOT_PROVIDED = tunable2<KtNamedFunction, String, String>("COMPOSITION_LOCAL_NOT_PROVIDED", KotrailRule.COMPOSE_COMPOSITION_LOCALS, NAME)

    /** The same, reported on the callee name of an entry-point call such as `setContent { }`. */
    val COMPOSITION_LOCAL_NOT_PROVIDED_AT_ENTRY_POINT = tunable2<KtElement, String, String>("COMPOSITION_LOCAL_NOT_PROVIDED_AT_ENTRY_POINT", KotrailRule.COMPOSE_COMPOSITION_LOCALS, REFERENCED_NAME)

    /** Arguments: the local's name, the keyword to use with delegation (`val` or `var`). */
    val PREFER_STATE_DELEGATION = tunable2<KtProperty, String, String>("PREFER_STATE_DELEGATION", KotrailRule.COMPOSE_STATE_DELEGATION, NAME)

    /** Arguments: the depth at the reported call, the configured limit. */
    val COMPOSABLE_NESTING_TOO_DEEP = tunable2<KtElement, String, String>("COMPOSABLE_NESTING_TOO_DEEP", KotrailRule.COMPOSE_NESTING, WHOLE)

    /** Argument: the parameter name; reported on a trailing non-composable function-type parameter. */
    val COMPOSABLE_TRAILING_CALLBACK = tunable1<KtParameter, String>("COMPOSABLE_TRAILING_CALLBACK", KotrailRule.COMPOSE_NO_TRAILING_CALLBACK, NAME)

    /** Arguments: the function name, the expected casing (`PascalCase` or `camelCase`). */
    val COMPOSABLE_NAMING = tunable2<KtNamedFunction, String, String>("COMPOSABLE_NAMING", KotrailRule.COMPOSE_NAMING, NAME)

    /** Argument: the parameter name; reported on a callback passed to a composable as a trailing lambda. */
    val COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA = tunable1<KtElement, String>("COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA", KotrailRule.COMPOSE_NAMED_CALLBACK_ARGUMENTS, WHOLE)

    /** Argument: the composable's name; reported on a UI composable with no @Preview in its file. */
    val COMPOSABLE_WITHOUT_PREVIEW = tunable1<KtNamedFunction, String>("COMPOSABLE_WITHOUT_PREVIEW", KotrailRule.COMPOSE_PREVIEW_REQUIRED, NAME)

    /** Arguments: what the file declares (`5 distinct non-private composables`), the limit; reported on each composable past the limit. */
    val TOO_MANY_COMPOSABLES_IN_FILE = tunable2<KtNamedFunction, String, String>("TOO_MANY_COMPOSABLES_IN_FILE", KotrailRule.COMPOSE_COMPOSABLES_PER_FILE, NAME)

    /** Argument: the composable's fully qualified name; reported on the package directive of the compilation's anchor file. */
    val COMPOSABLE_NOT_COVERED_BY_PREVIEW = tunable1<KtElement, String>("COMPOSABLE_NOT_COVERED_BY_PREVIEW", KotrailRule.COMPOSE_PREVIEW_COVERAGE, WHOLE)

    /** Arguments: the model class built inline, the preview; reported on the argument of a composable call inside a preview. */
    val PREVIEW_MODEL_BUILT_INLINE = tunable2<KtElement, String, String>("PREVIEW_MODEL_BUILT_INLINE", KotrailRule.COMPOSE_PREVIEW_PARAMETER, WHOLE)

    /** Argument: the package; reported on the anchor file's package directive when a listed package has no UI composable to cover. */
    val PREVIEW_COVERAGE_PACKAGE_EMPTY = tunable1<KtElement, String>("PREVIEW_COVERAGE_PACKAGE_EMPTY", KotrailRule.COMPOSE_PREVIEW_COVERAGE, WHOLE)

    /** Arguments: the offending type, the callee; reported on a call whose type argument is not serializable. */
    val TYPE_NOT_SERIALIZABLE = tunable2<KtElement, String, String>("TYPE_NOT_SERIALIZABLE", KotrailRule.MUST_BE_SERIALIZABLE, WHOLE)

    /** Argument: what is wrong with the modifier parameter. */
    val COMPOSABLE_MODIFIER_PARAMETER = tunable1<KtNamedFunction, String>("COMPOSABLE_MODIFIER_PARAMETER", KotrailRule.COMPOSE_MODIFIER_PARAMETER, NAME)

    /** Argument: the callee's name; reported on the callee of a call that starts work directly in a composable body. */
    val COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION = tunable1<KtElement, String>("COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION", KotrailRule.COMPOSE_NO_SIDE_EFFECT_IN_COMPOSITION, WHOLE)

    /** Argument: the global var (`Session.user`); reported on a read of it during composition. */
    val GLOBAL_VAR_READ_IN_COMPOSITION = tunable1<KtElement, String>("GLOBAL_VAR_READ_IN_COMPOSITION", KotrailRule.COMPOSE_NO_GLOBAL_MUTABLE_STATE, WHOLE)

    /** Arguments: the global var, where it is assigned; reported on an assignment to it in a composable. */
    val GLOBAL_VAR_WRITTEN_IN_COMPOSABLE = tunable2<KtElement, String, String>("GLOBAL_VAR_WRITTEN_IN_COMPOSABLE", KotrailRule.COMPOSE_NO_GLOBAL_MUTABLE_STATE, WHOLE)

    /** Arguments: the parameter with its type (`items: List<Item>`), why the type is unstable; reported on the parameter. */
    val COMPOSABLE_UNSTABLE_PARAMETER = tunable2<KtParameter, String, String>("COMPOSABLE_UNSTABLE_PARAMETER", KotrailRule.COMPOSE_NO_UNSTABLE_PARAMETER, NAME)

    /** Arguments: the parameter with its type, the `Class.property` that is a callback; reported on a UI composable's parameter. */
    val CALLBACK_IN_UI_MODEL = tunable2<KtParameter, String, String>("CALLBACK_IN_UI_MODEL", KotrailRule.COMPOSE_NO_CALLBACK_IN_MODEL, NAME)

    /** Arguments: the literal (abbreviated), the parameter name; reported on a string literal passed to a composable. */
    val COMPOSABLE_HARDCODED_STRING = tunable2<KtElement, String, String>("COMPOSABLE_HARDCODED_STRING", KotrailRule.COMPOSE_NO_HARDCODED_STRING, WHOLE)

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = KotrailDiagnosticRenderers

    // ---- test rules ----

    /** Arguments: the test function name, the minimum number of words a name must have. */
    val TEST_NAME_NOT_DESCRIPTIVE = tunable2<KtNamedFunction, String, String>("TEST_NAME_NOT_DESCRIPTIVE", KotrailRule.TEST_NAMING, NAME)

    /** Argument: the test function name. */
    val TEST_NAME_NOT_IDENTIFIER = tunable1<KtNamedFunction, String>("TEST_NAME_NOT_IDENTIFIER", KotrailRule.TEST_NAMING, NAME)

    /** Argument: the callee's name; reported on a call that waits real time inside a test. */
    val TEST_REAL_TIME_WAIT = tunable1<KtElement, String>("TEST_REAL_TIME_WAIT", KotrailRule.TEST_NO_SLEEP, WHOLE)

    // ---- Kotlin/Native rules ----

    /** Arguments: the operator (`===` or `!==`), the Objective-C class or protocol compared. Reported on the whole comparison. */
    val OBJC_IDENTITY_COMPARISON = tunable2<KtElement, String, String>("OBJC_IDENTITY_COMPARISON", KotrailRule.NATIVE_OBJC_IDENTITY, WHOLE)

    /** Argument: the Objective-C class or protocol the weak reference points to. Reported on the constructor call. */
    val OBJC_WEAK_REFERENCE = tunable1<KtElement, String>("OBJC_WEAK_REFERENCE", KotrailRule.NATIVE_OBJC_IDENTITY, WHOLE)

    /** Arguments: the function, what it lets out and how (`IllegalArgumentException (require)`); reported on an exported function's name. */
    val OBJC_EXPORT_MISSING_THROWS = tunable2<KtNamedFunction, String, String>("OBJC_EXPORT_MISSING_THROWS", KotrailRule.NATIVE_OBJC_THROWS, NAME)

    private inline fun <reified P : KtElement> tunable0(
        name: String,
        rule: KotrailRule,
        strategy: AbstractSourceElementPositioningStrategy,
    ): TunableDiagnostic0 = TunableDiagnostic0(
        rule = rule,
        baseName = PREFIX + name,
        error = KtDiagnosticFactory1(name.withSuffix(Severity.ERROR, rule.defaultSeverity), Severity.ERROR, strategy, P::class, KotrailDiagnosticRenderers),
        warning = KtDiagnosticFactory1(name.withSuffix(Severity.WARNING, rule.defaultSeverity), Severity.WARNING, strategy, P::class, KotrailDiagnosticRenderers),
    )

    private inline fun <reified P : KtElement, A> tunable1(
        name: String,
        rule: KotrailRule,
        strategy: AbstractSourceElementPositioningStrategy,
    ): TunableDiagnostic1<A> = TunableDiagnostic1(
        rule = rule,
        baseName = PREFIX + name,
        error = KtDiagnosticFactory2(name.withSuffix(Severity.ERROR, rule.defaultSeverity), Severity.ERROR, strategy, P::class, KotrailDiagnosticRenderers),
        warning = KtDiagnosticFactory2(name.withSuffix(Severity.WARNING, rule.defaultSeverity), Severity.WARNING, strategy, P::class, KotrailDiagnosticRenderers),
    )

    private inline fun <reified P : KtElement, A, B> tunable2(
        name: String,
        rule: KotrailRule,
        strategy: AbstractSourceElementPositioningStrategy,
    ): TunableDiagnostic2<A, B> = TunableDiagnostic2(
        rule = rule,
        baseName = PREFIX + name,
        error = KtDiagnosticFactory3(name.withSuffix(Severity.ERROR, rule.defaultSeverity), Severity.ERROR, strategy, P::class, KotrailDiagnosticRenderers),
        warning = KtDiagnosticFactory3(name.withSuffix(Severity.WARNING, rule.defaultSeverity), Severity.WARNING, strategy, P::class, KotrailDiagnosticRenderers),
    )

    /** The name every Kotrail diagnostic starts with. */
    const val PREFIX = "KOTRAIL_"

    private fun String.withSuffix(severity: Severity, default: Severity): String =
        if (severity == default) PREFIX + this else "$PREFIX${this}_${severity.name}"
}

object KotrailDiagnosticRenderers : BaseDiagnosticRendererFactory() {
    override val MAP by KtDiagnosticFactoryToRendererMap("Kotrail") { map ->
        map.put1(
            KotrailDiagnostics.PREFER_EXPLICIT_BACKING_FIELD,
            "[Kotrail] This property is exposed through the backing property ''{0}''. " +
                "Use an explicit backing field instead: declare only the public property " +
                "and initialize its ''field'' with the mutable implementation.",
        )
        map.put1(
            KotrailDiagnostics.PREFER_PRIVATE_SETTER,
            "[Kotrail] This property only reads the backing var ''{0}''. Make it the var itself with a " +
                "private setter (var x: T = ...; private set), and write to it directly inside the class.",
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
            KotrailDiagnostics.COMMENT_IN_PARAMETER_LIST,
            "[Kotrail] A comment inside the parameter list of ''{0}'' is shown nowhere the parameter is used and " +
                "comes loose when parameters move. Say it in the KDoc as @param, or above the declaration.",
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
        map.put2(
            KotrailDiagnostics.FORBIDDEN_CALL,
            "[Kotrail] Calling ''{0}'' is forbidden here (forbiddenCall[{1}]).",
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
                "again. The inset padding modifiers consume what they apply, so one of the two is dead, and " +
                "padding(insets.asPaddingValues()) does not consume, so the padding doubles.",
        )
        map.put2(
            KotrailDiagnostics.COMPOSITION_LOCAL_NOT_PROVIDED,
            "[Kotrail] ''{0}'' is {1}, but nothing between this root and the read provides it. " +
                "Provide it with CompositionLocalProvider here or on the way, or give the local a default.",
        )
        map.put2(
            KotrailDiagnostics.COMPOSITION_LOCAL_NOT_PROVIDED_AT_ENTRY_POINT,
            "[Kotrail] ''{0}'' is {1}, but nothing inside this content provides it and nothing above it can. " +
                "Provide it with CompositionLocalProvider inside the content, or give the local a default.",
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
            KotrailDiagnostics.PREVIEW_MODEL_BUILT_INLINE,
            "[Kotrail] ''{1}'' builds a ''{0}'' by hand. Take it as a @PreviewParameter from a PreviewParameterProvider, " +
                "so that the states this composable can show are listed in one place and previewed together.",
        )
        map.put1(
            KotrailDiagnostics.PREVIEW_COVERAGE_PACKAGE_EMPTY,
            "[Kotrail] compose.previewCoverage.packages names ''{0}'', but this compilation sees no UI composable in it: " +
                "check the package name, or that the composables are on this compilation''s classpath or among its sources.",
        )
        map.put1(
            KotrailDiagnostics.COMPOSABLE_NOT_COVERED_BY_PREVIEW,
            "[Kotrail] ''{0}'' has no preview in this compilation: no @Preview function here calls it. Add one, " +
                "or leave it out with compose.previewCoverage.excludeNames.",
        )
        map.put2(
            KotrailDiagnostics.TOO_MANY_COMPOSABLES_IN_FILE,
            "[Kotrail] This file declares {0}; the limit is {1}. Move this one to its own file.",
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
        map.put1(
            KotrailDiagnostics.COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION,
            "[Kotrail] ''{0}'' starts work during composition, which runs again on every recomposition. " +
                "Move it into LaunchedEffect (or another effect), or call it from an event handler.",
        )
        map.put1(
            KotrailDiagnostics.GLOBAL_VAR_READ_IN_COMPOSITION,
            "[Kotrail] ''{0}'' is a plain var outside the composition: Compose does not observe it, so this composable " +
                "shows the value it read first and is never recomposed when it changes. Hold it in a MutableState or a " +
                "StateFlow collected as state, and hand it in as a parameter.",
        )
        map.put2(
            KotrailDiagnostics.GLOBAL_VAR_WRITTEN_IN_COMPOSABLE,
            "[Kotrail] ''{0}'' is assigned {1}: shared state written from a composable is invisible to Compose and to the " +
                "rest of the app. Keep it in a state holder the composable receives, and change it through an event it raises.",
        )
        map.put2(
            KotrailDiagnostics.CALLBACK_IN_UI_MODEL,
            "[Kotrail] ''{0}'' carries a callback: {1} is a function-typed property. A model that holds a lambda is never " +
                "equal to its previous version, so the composable recomposes whenever it is rebuilt, and a preview has to " +
                "invent the callback. Keep the model a value and take the callback as a parameter of the composable.",
        )
        map.put2(
            KotrailDiagnostics.COMPOSABLE_UNSTABLE_PARAMETER,
            "[Kotrail] ''{0}'' has an unstable type: {1}. Compose treats the argument as changed whenever it is " +
                "not the same instance and does not memoize lambdas that capture it. Pass an immutable collection or " +
                "a type marked @Immutable or @Stable, or list the type under compose.noUnstableParameter.stableTypes.",
        )
        map.put2(
            KotrailDiagnostics.COMPOSABLE_HARDCODED_STRING,
            "[Kotrail] ''{0}'' is a hardcoded string passed as ''{1}''. Load it from string resources so that it " +
                "can be translated.",
        )
        map.put1(
            KotrailDiagnostics.TEST_REAL_TIME_WAIT,
            "[Kotrail] ''{0}'' waits real time inside a test, which makes the test slow and flaky. Await the " +
                "condition instead (a latch, a channel, a flow collector); for in-memory coroutine code, run " +
                "the test with runTest so that delay skips virtual time.",
        )
        map.put2(
            KotrailDiagnostics.PASS_THROUGH_FUNCTION,
            "[Kotrail] ''{0}'' only forwards its arguments to ''{1}''. Call ''{1}'' directly, or give this " +
                "function something of its own to do: a default, a conversion, a narrower type.",
        )
        map.put2(
            KotrailDiagnostics.VISIBILITY_TOO_WIDE,
            "[Kotrail] ''{0}'' must be {1}: the project's visibility policy covers this declaration. " +
                "Narrow it, or move it out of the pattern the policy names.",
        )
        map.put2(
            KotrailDiagnostics.REQUIRED_ANNOTATION_MISSING,
            "[Kotrail] ''{0}'' must be annotated with {1}: the project's required-annotation policy covers " +
                "this declaration. Add the annotation, or move it out of the pattern the policy names.",
        )
        map.put1(
            KotrailDiagnostics.DATA_CLASS_IN_PUBLIC_API,
            "[Kotrail] ''{0}'' is a data class in the public API. Its constructor, copy() and componentN() " +
                "become part of the binary contract, so adding a property later breaks every consumer. " +
                "Expose a regular class with an explicit equals/hashCode instead, or make it internal.",
        )
        map.put1(
            KotrailDiagnostics.ELVIS_CHAIN_TOO_LONG,
            "[Kotrail] This expression falls back with ''?:'' {0}; each fallback is one more case the reader " +
                "evaluates in order. Extract the candidates into a function, or, when they are cheap and " +
                "side-effect free, list them with listOfNotNull(...).firstOrNull().",
        )
        map.put1(
            KotrailDiagnostics.SAFE_CALL_CHAIN_TOO_LONG,
            "[Kotrail] This access chains ''?.'' {0}; every link may be null and the result cannot say which one " +
                "was. Bind an intermediate value to a name, or move the traversal into the model.",
        )
        map.put1(
            KotrailDiagnostics.PREFER_VAL,
            "[Kotrail] ''{0}'' is never reassigned. Declare it with ''val'', so that a reader need not watch for it changing.",
        )
        map.put2(
            KotrailDiagnostics.INTERNAL_VISIBLE_TO_JAVA,
            "[Kotrail] ''{0}'' is internal to Kotlin but public to Java: the JVM has no internal. Add {1} so that " +
                "the Java compiler cannot see it.",
        )
        map.put1(
            KotrailDiagnostics.INTERNAL_CLASS_VISIBLE_TO_JAVA,
            "[Kotrail] ''{0}'' is an internal class, which the JVM publishes as a public one; nothing hides a class " +
                "from Java. Keep that in mind, or nest it privately or move it out of the published module.",
        )
        map.put2(
            KotrailDiagnostics.TOO_MANY_LIVE_VARIABLES,
            "[Kotrail] {1} variables are live here: {0}. That is more than a reader holds at once; extract a step " +
                "into a function, or narrow what is declared before this point.",
        )
        map.put1(
            KotrailDiagnostics.THREAD_LOCAL_IN_UNLOADABLE_CODE,
            "[Kotrail] ''{0}'' is a ThreadLocal in a compilation that is unloaded with its class loader: a value set on a " +
                "thread that outlives the plugin keeps the class loader alive. Pass the value along instead.",
        )
        map.put2(
            KotrailDiagnostics.OUTBOUND_REFERENCE_IN_BUNDLED_CODE,
            "[Kotrail] {0} at {1}, in a module bundled into this class loader: it outlives the plugin and keeps the " +
                "class loader alive. Fix it there, scope it to a disposable that goes with the plugin, or suppress it " +
                "there with the reason.",
        )
        map.put1(
            KotrailDiagnostics.UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE,
            "[Kotrail] ''{0}'' registers with something that outlives the plugin and nothing here unregisters it, so the " +
                "class loader stays alive. Pass a disposable that is disposed with the plugin, or unregister in its disposal.",
        )
        map.put1(
            KotrailDiagnostics.RESOURCE_NOT_CLOSED,
            "[Kotrail] This ''{0}'' is created here and neither closed nor handed on: whatever it holds stays held until a " +
                "finalizer runs, if ever. Wrap it in use '{' '}', close it in a finally, or store or return it where its owner will close it.",
        )
        map.put1(
            KotrailDiagnostics.NATIVE_ALLOCATION_IN_LOOP,
            "[Kotrail] ''{0}'' holds native memory that is freed only when a cleaner runs, and this creates one per iteration: " +
                "the heap stays small, the collector rarely runs, and native memory grows unbounded. Reuse one instance " +
                "across iterations, or close each with use '{' '}'.",
        )
        map.put2(
            KotrailDiagnostics.NATIVE_ALLOCATION_THROUGH_CALL_IN_LOOP,
            "[Kotrail] {0} each time it is called and {1} it unclosed, and this calls it once per iteration: its native memory " +
                "is freed only when a cleaner runs, the heap stays small, and native memory grows unbounded. Reuse one instance " +
                "across iterations, close each result with use '{' '}', or have it fill an instance it is given.",
        )
        map.put1(
            KotrailDiagnostics.WEAK_REFERENCE_TO_FRESH_OBJECT,
            "[Kotrail] This weak reference is the only reference to {0}, so it is collected at the next opportunity and " +
                "the reference goes empty. Hold the object strongly somewhere for as long as it should act.",
        )
        map.put2(
            KotrailDiagnostics.CATCH_TOO_BROAD,
            "[Kotrail] Catching ''{0}'' handles every failure alike, {1}",
        )
        map.put2(
            KotrailDiagnostics.UNRETAINED_PARAMETER_RETAINED,
            "[Kotrail] ''{0}'' is declared unretained, but {1}. Keep it only through a weak reference, or drop the annotation.",
        )
        map.put2(
            KotrailDiagnostics.SUPERTYPE_REQUIRED,
            "[Kotrail] ''{0}'' must extend or implement {1}.",
        )
        map.put2(
            KotrailDiagnostics.DEPENDENCY_NOT_ALLOWED,
            "[Kotrail] ''{0}'' is not a package this code may depend on ({1}).",
        )
        map.put2(
            KotrailDiagnostics.EFFECT_KEY_MISSING,
            "[Kotrail] The lambda of ''{0}'' captures {1}: when that changes, the lambda keeps the value it saw first. " +
                "Add it to the keys, or keep it current with rememberUpdatedState (per value, or one lambda that does the work).",
        )
        map.put2(
            KotrailDiagnostics.EFFECT_CAPTURED_BY_CALLEE,
            "[Kotrail] {0} keeps this lambda for the life of its effect, and the lambda reads {1}: when that changes, the " +
                "effect keeps the value it saw first. Keep it current with rememberUpdatedState.",
        )
        map.put2(
            KotrailDiagnostics.DELAY_WAITS_FOR_ASYNC_WORK,
            "[Kotrail] ''{0}'' waits a fixed time for what {1}. Too short and the code after it runs before the work is " +
                "done; too long and it waits for nothing.",
        )
        map.put1(
            KotrailDiagnostics.TEST_WITHOUT_ASSERTION,
            "[Kotrail] ''{0}'' asserts nothing: it passes as long as nothing throws. Assert on the result, or verify an " +
                "interaction.",
        )
        map.put2(
            KotrailDiagnostics.CALLBACK_BEFORE_DATA_PARAMETER,
            "[Kotrail] ''{0}'' is a function-typed parameter declared before ''{1}''. Put the data first and the " +
                "functions that act on it after, so that the signature reads as what it works on, then what it does.",
        )
        map.put2(
            KotrailDiagnostics.HELPER_BEFORE_FIRST_USE,
            "[Kotrail] ''{0}'' is declared before ''{1}'', which is the first to use it. Declare it after, so that " +
                "the file reads from the top down: the story first, the details after.",
        )
        map.put1(
            KotrailDiagnostics.NARROW_LOCAL_SCOPE,
            "[Kotrail] ''{0}'' is only used inside one branch below. Declare it there, so that a reader carries it " +
                "no further than it matters.",
        )
        map.put2(
            KotrailDiagnostics.LOCAL_DECLARED_TOO_EARLY,
            "[Kotrail] ''{0}'' is first used {1} below its declaration. Declare it next to that use, so that a " +
                "reader carries it no further than it matters.",
        )
        map.put2(
            KotrailDiagnostics.PREFER_IDIOM,
            "[Kotrail] Write ''{1}'' instead of ''{0}''.",
        )
        map.put2(
            KotrailDiagnostics.SEALED_WHEN_BRANCH_STYLE,
            "[Kotrail] Write ''{1} ->'' instead of ''{0} ->'', so that every branch of this when over a sealed type " +
                "reads the same way.",
        )
        map.put2(
            KotrailDiagnostics.IMPLICIT_RECEIVER_AMBIGUOUS,
            "[Kotrail] ''{0}'' resolves on {1}. Qualify it so that the reader sees which one is meant.",
        )
        map.put1(
            KotrailDiagnostics.TOO_MANY_IMPLICIT_RECEIVERS,
            "[Kotrail] This lambda puts {0}. Bind the outer receiver to a name, or split the block.",
        )
        map.put1(
            KotrailDiagnostics.FUNCTION_TOO_LONG,
            "[Kotrail] This function is {0}. Split it so that each piece does one thing and has a name.",
        )
        map.put2(
            KotrailDiagnostics.LITERAL_LOOP,
            "[Kotrail] This {0} and the body branches on the element: cases the author already knows, folded into a loop " +
                "the reader has to unfold. {1}.",
        )
        map.put1(
            KotrailDiagnostics.FILE_TOO_LONG,
            "[Kotrail] This file is {0}. Split it: one main type per file, with the helpers only it needs.",
        )
        map.put2(
            KotrailDiagnostics.FILE_TOO_FLAT,
            "[Kotrail] This file declares {0}. A flat list this long says nothing about what belongs together. {1}",
        )
        map.put2(
            KotrailDiagnostics.TOO_MANY_TOP_LEVEL_DECLARATIONS,
            "[Kotrail] Top-level name {0} in this file, past the limit of {1}: move it, with what it needs, to a file of its own.",
        )
        map.put2(
            KotrailDiagnostics.OBJC_IDENTITY_COMPARISON,
            "[Kotrail] ''{0}'' compares Kotlin wrappers, not the {1} objects: Kotlin/Native wraps an Objective-C " +
                "object anew each time it crosses into Kotlin, so two wrappers of one object are not identical. " +
                "Compare with '=='' (isEqual:, pointer equality by default) or compare objcPtr() addresses.",
        )
        map.put1(
            KotrailDiagnostics.OBJC_WEAK_REFERENCE,
            "[Kotrail] A WeakReference to {0} tracks the Kotlin wrapper, which is collected while the Objective-C " +
                "object lives on, so it answers null for a live object. Hold the object strongly, or hold its " +
                "objcPtr() address.",
        )
        map.put2(
            KotrailDiagnostics.OBJC_EXPORT_MISSING_THROWS,
            "[Kotrail] ''{0}'' can throw {1} and declares no `@Throws`: an exception that reaches Swift or Objective-C " +
                "undeclared terminates the app. Declare it with `@Throws(...::class)` so that it arrives as an NSError, or handle it here.",
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

    /**
     * Every message ends with the diagnostic's base name in parentheses, which is what a
     * `@Suppress` takes and what a rule is looked up by, and then with the project's note. The
     * note is the last parameter of every factory; it is empty, or already prefixed with a space,
     * so a message without one reads exactly as it did before the parameter existed.
     */
    private fun KtDiagnosticFactoryToRendererMap.put0(diagnostic: TunableDiagnostic0, message: String) {
        val renderer: DiagnosticParameterRenderer<String> = CommonRenderers.STRING
        val template = "$message (${diagnostic.baseName}){0}"
        put(diagnostic.error, template, renderer)
        put(diagnostic.warning, template, renderer)
    }

    private fun KtDiagnosticFactoryToRendererMap.put1(diagnostic: TunableDiagnostic1<String>, message: String) {
        val renderer: DiagnosticParameterRenderer<String> = CommonRenderers.STRING
        val template = "$message (${diagnostic.baseName}){1}"
        put(diagnostic.error, template, renderer, renderer)
        put(diagnostic.warning, template, renderer, renderer)
    }

    private fun KtDiagnosticFactoryToRendererMap.put2(diagnostic: TunableDiagnostic2<String, String>, message: String) {
        val renderer: DiagnosticParameterRenderer<String> = CommonRenderers.STRING
        val template = "$message (${diagnostic.baseName}){2}"
        put(diagnostic.error, template, renderer, renderer, renderer)
        put(diagnostic.warning, template, renderer, renderer, renderer)
    }
}
