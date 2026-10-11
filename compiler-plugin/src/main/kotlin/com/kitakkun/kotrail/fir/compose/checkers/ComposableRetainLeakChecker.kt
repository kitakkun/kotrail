@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.holdsItsArguments
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.isOfThisModule
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.hasBackingField
import org.jetbrains.kotlin.fir.declarations.utils.isDelegatedProperty
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Keeps a Context, a View and anything marked `@DoNotRetain` out of `retain`:
 *
 * ```kotlin
 * class Holder(val view: View)
 *
 * @Composable
 * fun Screen(context: Context, view: View, id: String) {
 *     val a = retain { context }                 // reported: android.content.Context
 *     val b = retain { Holder(view) }            // reported: Holder.view (android.view.View)
 *     val c = retain { mutableStateOf(view) }    // reported: MutableState<View> (android.view.View)
 *     val d = retain(view) { Cache() }           // reported on the key
 *     val e = retain(id) { Cache() }             // fine
 * }
 * ```
 *
 * A retained value survives its composable leaving the composition (a back stack entry, collapsed
 * content, an Activity recreated for a configuration change) and keeps everything it references;
 * the keys it was retained with are kept as long as it is. The Compose runtime's own KDoc says to
 * never retain an Android Context or an object that references one, a View included, directly or
 * indirectly, and to mark any other class that must not be retained with `@DoNotRetain`.
 *
 * The calls are those of the `functions` setting (`retain`, `RetainedEffect` and a store
 * registry's `LocalRetainedValuesStoreProvider` by default). Their keys are the arguments of the
 * parameters named `key1`, `key2`, ..., `keys` or `inputs`; their value is the function-typed
 * argument whose return type is the call's own (`calculation` of `retain`); a lambda's value is
 * its last expression when that is more specific (`retain<Any> { context }` retains a Context). A
 * value or a key is reported when its type is one of `leakTypes` (a Context or a View by default),
 * a subclass of one, or a class annotated `@DoNotRetain` or extending one, or holds one: what a
 * project class stores (its properties with a backing field or a delegate, private ones included,
 * and those of its project superclasses), and the type arguments of a library class (a collection's
 * elements, `MutableState<View>`, `StateFlow<View>`, `Lazy<Context>`) other than one of
 * `nonRetainingTypes` (a weak or a soft reference, a class, a comparator, a reflection handle). A
 * project's own generic class is walked with its type arguments in place of its type parameters. A
 * library class without type arguments ends the walk: what it holds is not visible from its type.
 *
 * A value that is a `RememberObserver` but not a `RetainObserver` is reported too: `retain` throws
 * for it.
 */
object ComposableRetainLeakChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val KEY_PARAMETER = Regex("key\\d*|keys|inputs")
    private val DO_NOT_RETAIN = ClassId(FqName("androidx.compose.runtime.annotation"), Name.identifier("DoNotRetain"))
    private val REMEMBER_OBSERVER = ClassId(FqName("androidx.compose.runtime"), Name.identifier("RememberObserver"))
    private val RETAIN_OBSERVER = ClassId(FqName("androidx.compose.runtime.retain"), Name.identifier("RetainObserver"))

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val session = context.session
        val config = session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_RETAIN_LEAK)) return
        val callSource = expression.source ?: return
        if (callSource.kind is KtFakeSourceElementKind) return
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (callee.callableId.asSingleFqName().asString() !in config.compose.retainFunctions) return
        if (!isInComposition(session)) return

        val search = LeakSearch(session, config.compose.retainLeakTypes.toSet(), config.compose.retainNonRetainingTypes.toSet())
        for ((argument, parameter) in expression.resolvedArgumentMapping.orEmpty()) {
            if (KEY_PARAMETER.matches(parameter.name.asString())) {
                val elements = if (argument is FirVarargArgumentsExpression) argument.arguments else listOf(argument)
                for (element in elements) {
                    val key = element.unwrapArgument()
                    val source = key.source ?: element.source ?: continue
                    if (source.kind is KtFakeSourceElementKind) continue
                    val held = search.leakIn(key.resolvedType) ?: continue
                    reportKotrail(source, KotrailDiagnostics.RETAINED_LEAK, "This key of ${callee.name.asString()}", held)
                }
                continue
            }
            if (!parameter.isValueOf(callee, session)) continue
            val calculation = argument.unwrapArgument()
            val source = calculation.source ?: argument.source ?: continue
            if (source.kind is KtFakeSourceElementKind) continue
            val value = calculation.resultType(session) ?: continue
            search.leakIn(value)?.let { held -> reportKotrail(source, KotrailDiagnostics.RETAINED_LEAK, "This retained value", held) }
            if (value.isRememberObserverOnly(session)) {
                reportKotrail(source, KotrailDiagnostics.RETAINED_REMEMBER_OBSERVER, value.renderReadable())
            }
        }
    }

    /**
     * Whether the call runs in composition: in a `@Composable` function or getter, or in a lambda
     * of a `@Composable` function type (`setContent { }`, a content slot). A lambda of any other type
     * belongs to the function around it, as an inline one (`forEach { }`) does.
     */
    context(context: CheckerContext)
    private fun isInComposition(session: FirSession): Boolean {
        val elements = context.containingElements
        for (index in elements.indices.reversed()) {
            when (val element = elements[index]) {
                is FirAnonymousFunction -> {
                    val call = elements.subList(0, index).lastOrNull { it is FirFunctionCall } as? FirFunctionCall ?: continue
                    val parameter = call.resolvedArgumentMapping.orEmpty().entries
                        .firstOrNull { (argument, _) -> (argument.unwrapArgument() as? FirAnonymousFunctionExpression)?.anonymousFunction === element }
                        ?.value ?: continue
                    if (parameter.returnTypeRef.coneType.isComposableFunctionType(session)) return true
                }
                is FirNamedFunction -> return element.symbol.isComposable(session)
                is FirPropertyAccessor -> return element.symbol.isComposable(session)
                else -> {}
            }
        }
        return false
    }

    private fun ConeKotlinType.isComposableFunctionType(session: FirSession): Boolean =
        customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }

    /** Whether this parameter takes the retained value: a function type whose result the call returns, other than `Unit`. */
    private fun FirValueParameter.isValueOf(callee: FirNamedFunctionSymbol, session: FirSession): Boolean {
        val returned = callee.resolvedReturnType
        if (returned.isUnit) return false
        val type = returnTypeRef.coneType.fullyExpandedType(session)
        if (!type.isSomeFunctionType(session)) return false
        return (type.typeArguments.lastOrNull() as? ConeKotlinTypeProjection)?.type == returned
    }

    /**
     * What a function-typed argument (a lambda, a reference) produces: its function type's result,
     * or a lambda's last expression when that is more specific. `T` of `retain<Any> { context }` is
     * `Any`, and the value retained is a Context.
     */
    private fun FirExpression.resultType(session: FirSession): ConeKotlinType? {
        val type = resolvedType.fullyExpandedType(session)
        if (!type.isSomeFunctionType(session)) return null
        val declared = (type.typeArguments.lastOrNull() as? ConeKotlinTypeProjection)?.type ?: return null
        val produced = (this as? FirAnonymousFunctionExpression)?.lastExpression()?.resolvedType ?: return declared
        return if (AbstractTypeChecker.isSubtypeOf(session.typeContext, produced, declared)) produced else declared
    }

    /** The last expression of a lambda's body: the result of the return its body ends in, or its last statement. */
    private fun FirAnonymousFunctionExpression.lastExpression(): FirExpression? {
        val last = anonymousFunction.body?.statements?.lastOrNull() ?: return null
        return (last as? FirReturnExpression)?.result ?: last as? FirExpression
    }

    /** Whether a value of this type is a `RememberObserver` but not a `RetainObserver`, which `retain` throws for. */
    private fun ConeKotlinType.isRememberObserverOnly(session: FirSession): Boolean {
        val classSymbol = fullyExpandedType(session).toRegularClassSymbol(session) ?: return false
        return classSymbol.isOrExtends(REMEMBER_OBSERVER, session) && !classSymbol.isOrExtends(RETAIN_OBSERVER, session)
    }

    private fun FirRegularClassSymbol.isOrExtends(classId: ClassId, session: FirSession): Boolean =
        this.classId == classId || lookupSuperTypes(this, lookupInterfaces = true, deep = true, useSiteSession = session).any { it.lookupTag.classId == classId }

    /** Walks what a value holds, and finds the first thing that must not be retained. */
    private class LeakSearch(
        private val session: FirSession,
        private val leakTypes: Set<String>,
        private val nonRetainingTypes: Set<String>,
    ) {
        /** Classes on the current path, so that a class reached again inside itself is not entered again. */
        private val walking = mutableSetOf<FirRegularClassSymbol>()

        /** Classes already walked, with the type arguments they were walked under. */
        private val walked = mutableSetOf<Pair<FirRegularClassSymbol, List<ConeTypeProjection>>>()

        /**
         * What a value of [type] holds that must not be retained, for the message: the type itself
         * (`android.content.Context`, `@DoNotRetain Session`, `AppContext (android.content.Context)`),
         * the property that holds it (`Holder.view (android.view.View)`), the generic whose type
         * argument it is (`List<View> (android.view.View)`, `MutableState<View> (android.view.View)`),
         * or both (`StateFlow<Holder> (Holder.view: android.view.View)`).
         */
        fun leakIn(type: ConeKotlinType): String? {
            walking.clear()
            walked.clear()
            return leakIn(type, path = null, holder = null)
        }

        /**
         * [path] is the property that stores [type] (`Holder.view`), when the walk came through one;
         * [holder] is the outermost library generic the walk entered before any property (`StateFlow<Holder>`).
         */
        private fun leakIn(type: ConeKotlinType, path: String?, holder: String?): String? {
            val expanded = type.fullyExpandedType(session).lowerBoundIfFlexible()
            // What a lambda captures is not in its type.
            if (expanded.isSomeFunctionType(session)) return null
            if (expanded !is ConeClassLikeType) return null
            val classSymbol = expanded.toRegularClassSymbol(session) ?: return null
            forbidden(classSymbol)?.let { (reason, isItself) ->
                return when {
                    holder != null && path != null -> "$holder ($path: $reason)"
                    path != null -> "$path ($reason)"
                    holder != null -> "$holder ($reason)"
                    isItself -> reason
                    else -> "${type.lowerBoundIfFlexible().renderReadable()} ($reason)"
                }
            }
            if (classSymbol.isOfThisModule(session)) return leakInMembers(classSymbol, expanded, holder)
            return leakInArguments(classSymbol, type, expanded, path, holder)
        }

        /**
         * Why [classSymbol] must not be retained, and whether that is the class itself rather than
         * a supertype: it is one of [leakTypes] or extends one, or it or a supertype is `@DoNotRetain`.
         */
        private fun forbidden(classSymbol: FirRegularClassSymbol): Pair<String, Boolean>? {
            reason(classSymbol)?.let { return it to true }
            for (superType in lookupSuperTypes(classSymbol, lookupInterfaces = true, deep = true, useSiteSession = session)) {
                val superSymbol = superType.toRegularClassSymbol(session) ?: continue
                reason(superSymbol)?.let { return it to false }
            }
            return null
        }

        private fun reason(classSymbol: FirRegularClassSymbol): String? {
            val fqName = classSymbol.classId.asSingleFqName().asString()
            if (fqName in leakTypes) return fqName
            if (classSymbol.hasAnnotation(DO_NOT_RETAIN, session)) return "@DoNotRetain ${classSymbol.classId.relativeClassName.asString()}"
            return null
        }

        /**
         * The first leak among the type arguments of a library class. What it stores is not visible
         * from its members, and a generic holds what its type arguments are: a collection its
         * elements, `MutableState<View>` and `StateFlow<View>` their value, `Lazy<Context>` what it
         * computes. One of [nonRetainingTypes] (a weak or a soft reference, `KClass<View>`,
         * `Comparator<View>`) holds nothing, and a star projection or a type parameter holds
         * nothing known.
         */
        private fun leakInArguments(
            classSymbol: FirRegularClassSymbol,
            type: ConeKotlinType,
            expanded: ConeClassLikeType,
            path: String?,
            holder: String?,
        ): String? {
            if (expanded.typeArguments.isEmpty()) return null
            if (classSymbol.isNonRetaining()) return null
            val via = holder ?: if (path == null) type.lowerBoundIfFlexible().renderReadable() else null
            for (argument in expanded.typeArguments) {
                val argumentType = (argument as? ConeKotlinTypeProjection)?.type ?: continue
                leakIn(argumentType, path, via)?.let { return it }
            }
            return null
        }

        private fun FirRegularClassSymbol.isNonRetaining(): Boolean =
            classId.asSingleFqName().asString() in nonRetainingTypes ||
                lookupSuperTypes(this, lookupInterfaces = true, deep = true, useSiteSession = session)
                    .any { it.lookupTag.classId.asSingleFqName().asString() in nonRetainingTypes }

        /** The first leak among what a project class stores, its superclasses' fields included. */
        private fun leakInMembers(classSymbol: FirRegularClassSymbol, type: ConeClassLikeType, holder: String?): String? {
            if (classSymbol.classKind != ClassKind.CLASS) return null
            if (classSymbol in walking || !walked.add(classSymbol to type.typeArguments.toList())) return null
            walking += classSymbol
            try {
                val arguments = classSymbol.typeParameterSymbols.zip(type.typeArguments)
                    .mapNotNull { (parameter, argument) -> (argument as? ConeKotlinTypeProjection)?.let { parameter to it.type } }
                    .toMap()
                val substitutor = substitutorByMap(arguments, session)
                val className = classSymbol.classId.shortClassName.asString()
                for (property in classSymbol.declarationSymbols.filterIsInstance<FirPropertySymbol>()) {
                    if (property.receiverParameterSymbol != null) continue
                    if (!property.hasBackingField && !property.isDelegatedProperty) continue
                    val propertyType = substitutor.substituteOrSelf(property.resolvedReturnType)
                    leakIn(propertyType, path = "$className.${property.name.asString()}", holder)?.let { return it }
                }
                for (superType in classSymbol.resolvedSuperTypes) {
                    val substituted = substitutor.substituteOrSelf(superType)
                    // An interface stores nothing, and its type arguments say what it handles
                    // (`Comparator<View>`); a collection interface's are still its elements, as
                    // when it is implemented by delegation.
                    if (substituted.isInterface() && !substituted.holdsItsArguments()) continue
                    leakIn(substituted, path = null, holder)?.let { return it }
                }
                return null
            } finally {
                walking -= classSymbol
            }
        }

        private fun ConeKotlinType.isInterface(): Boolean =
            fullyExpandedType(session).toRegularClassSymbol(session)?.classKind == ClassKind.INTERFACE
    }
}
