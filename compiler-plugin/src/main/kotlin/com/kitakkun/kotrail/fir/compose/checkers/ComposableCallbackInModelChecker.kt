package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.ModelSurface
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.emitsUi
import com.kitakkun.kotrail.fir.compose.holdsItsArguments
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.isOfThisModule
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.fromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.isData
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.fir.types.renderReadable

/**
 * Keeps callbacks out of the models a UI composable takes:
 *
 * ```kotlin
 * data class UserRow(val name: String, val onClick: () -> Unit)
 * @Composable fun UserList(rows: List<UserRow>)                       // reported: UserRow.onClick
 *
 * data class UserRow(val id: Long, val name: String)
 * @Composable fun UserList(rows: List<UserRow>, onClick: (Long) -> Unit)  // fine
 * ```
 *
 * A lambda that captures anything is a new instance every time it is created, so a model that
 * holds one is never equal to its previous version: Compose recomposes the composable whenever
 * the model is rebuilt, whatever it shows, and the stability inference does not see it, since
 * function types count as stable. The model also stops being a plain value: a preview or a
 * screenshot test has to invent callbacks to build one, and "what to show" is tied to "what to
 * do". State goes down as values; events go up through the composable's own parameters, or one
 * `onAction: (Action) -> Unit`.
 *
 * A model is a class the project declares that a UI composable takes as a parameter, directly
 * or as the element of a collection, an array, a `Pair` or a `Triple` (`List<UserRow>`,
 * `Map<Id, UserRow>`), and every project class reachable from it the same way through its
 * non-private properties, inherited ones included, and the private primary-constructor properties
 * of a data class, which its `equals` compares. That is the surface the composable reads and
 * Compose compares; any other private property, and the element type of a `Flow`, a `Channel`, a
 * `StateFlow` or any other generic class from the classpath, is not part of it. A project's own
 * generic class is walked with its type arguments in place of its type parameters, so its
 * arguments count where its non-private properties carry them. A function-typed property
 * anywhere in that graph is reported on the composable's parameter, naming the property. A
 * `@Composable` slot in a model (`cell: @Composable (Row) -> Unit` of a table column) is what such
 * a model is for and is allowed by default; `allowComposableSlots: false` reports it too.
 */
object ComposableCallbackInModelChecker : NamedFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_NO_CALLBACK_IN_MODEL)) return
        if (declaration.source?.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isExpect) return
        val session = context.session
        if (!declaration.symbol.isComposable(session)) return
        if (!declaration.returnTypeRef.coneType.isUnit) return
        if (declaration.symbol.isPreview(session)) return
        if (declaration.body == null || !declaration.symbol.emitsUi(session)) return

        for (parameter in declaration.valueParameters) {
            val type = parameter.returnTypeRef.coneType
            if (type.isSomeFunctionType(session)) continue
            val search = CallbackSearch(session, config.compose.allowComposableSlots)
            val path = search.callbackIn(type, path = emptyList(), holder = null) ?: continue
            val source = parameter.source ?: continue
            reportKotrail(source, KotrailDiagnostics.CALLBACK_IN_UI_MODEL, "${parameter.name.asString()}: ${type.renderReadable()}", path)
        }
    }

    /** Walks what a composable can observe of a type and finds the first function-typed property. */
    private class CallbackSearch(private val session: FirSession, private val allowComposableSlots: Boolean) {
        /** Classes on the current path, so that a class reached again inside itself is not entered again. */
        private val walking = mutableSetOf<FirRegularClassSymbol>()

        /** Classes already walked, with the type arguments they were walked under. */
        private val walked = mutableSetOf<Pair<FirRegularClassSymbol, List<ConeTypeProjection>>>()

        /**
         * `Class.property` of the first callback reachable from [type], or, for one that sits in a type
         * argument with no property on the way (`Pair<() -> Unit, String>`), the type that carries it.
         * The type arguments of a container are its elements and are walked; those of a project class
         * replace its type parameters in its properties; those of any other class are not looked at.
         */
        fun callbackIn(type: ConeKotlinType, path: List<String>, holder: String?): String? {
            val expanded = type.fullyExpandedType(session)
            if (expanded.isSomeFunctionType(session)) {
                val isSlot = expanded.customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }
                if (isSlot && allowComposableSlots) return null
                return if (path.isNotEmpty()) path.joinToString(".") else "a type argument of ${holder ?: expanded.renderReadable()}"
            }
            if (expanded !is ConeClassLikeType) return null
            if (type.holdsItsArguments() || expanded.holdsItsArguments()) {
                for (argument in expanded.typeArguments) {
                    val argumentType = (argument as? ConeKotlinTypeProjection)?.type ?: continue
                    callbackIn(argumentType, path, holder ?: expanded.renderReadable())?.let { return it }
                }
                return null
            }
            val classSymbol = expanded.toRegularClassSymbol(session) ?: return null
            return callbackIn(classSymbol, expanded)
        }

        private fun callbackIn(classSymbol: FirRegularClassSymbol, type: ConeClassLikeType): String? {
            // A project class: of this module, also when an incremental build loaded it from the classes directory.
            if (!classSymbol.isOfThisModule(session)) return null
            if (!ModelSurface.isModelKind(classSymbol)) return null
            if (classSymbol in walking || !walked.add(classSymbol to type.typeArguments.toList())) return null
            walking += classSymbol
            try {
                val arguments = classSymbol.typeParameterSymbols.zip(type.typeArguments)
                    .mapNotNull { (parameter, argument) -> (argument as? ConeKotlinTypeProjection)?.let { parameter to it.type } }
                    .toMap()
                val substitutor = substitutorByMap(arguments, session)
                val className = classSymbol.classId.shortClassName.asString()
                // The surface the plugin metadata sums up for incremental compilation (see ModelSurface): what is read
                // here must be part of it.
                val compared = ModelSurface.comparedNames(classSymbol)
                for (property in ModelSurface.properties(classSymbol)) {
                    if (property.receiverParameterSymbol != null) continue
                    // A data class compares every property of its primary constructor, private ones included.
                    val isCompared = classSymbol.isData && (property.fromPrimaryConstructor || property.name in compared)
                    if (Visibilities.isPrivate(property.visibility) && !isCompared) continue
                    val propertyType = substitutor.substituteOrSelf(property.resolvedReturnType)
                    callbackIn(propertyType, listOf(className, property.name.asString()), holder = null)?.let { return it }
                }
                for (superType in classSymbol.resolvedSuperTypes) {
                    val substituted = substitutor.substituteOrSelf(superType)
                    // A class that implements a function type defines its own equality; it is not a captured lambda.
                    if (substituted.isSomeFunctionType(session)) continue
                    callbackIn(substituted, path = emptyList(), holder = null)?.let { return it }
                }
                return null
            } finally {
                walking -= classSymbol
            }
        }
    }
}
