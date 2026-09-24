@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.emitsUi
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
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
 * or through type arguments (`List<UserRow>`, `Map<Id, UserRow>`), and every project class
 * reachable from it through its properties, including inherited ones. A function-typed property
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
        if (declaration.body == null || !declaration.symbol.emitsUi(session, config.compose.nonUiPackages)) return

        val search = CallbackSearch(session, config.compose.allowComposableSlots)
        for (parameter in declaration.valueParameters) {
            val type = parameter.returnTypeRef.coneType
            if (type.isSomeFunctionType(session)) continue
            val path = search.callbackIn(type, path = emptyList()) ?: continue
            val source = parameter.source ?: continue
            reportKotrail(source, KotrailDiagnostics.CALLBACK_IN_UI_MODEL, "${parameter.name.asString()}: ${type.renderReadable()}", path)
        }
    }

    /** Walks the project classes reachable from a type and finds the first function-typed property. */
    private class CallbackSearch(private val session: FirSession, private val allowComposableSlots: Boolean) {
        private val visited = mutableSetOf<FirRegularClassSymbol>()

        /**
         * `Class.property` of the first callback reachable from [type], or, for one that sits in a type
         * argument with no property on the way (`Pair<() -> Unit, String>`), the type that carries it.
         */
        fun callbackIn(type: ConeKotlinType, path: List<String>, holder: String? = null): String? {
            val expanded = type.fullyExpandedType(session)
            if (expanded.isSomeFunctionType(session)) {
                val isSlot = expanded.customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }
                if (isSlot && allowComposableSlots) return null
                return if (path.isNotEmpty()) path.joinToString(".") else "a type argument of ${holder ?: expanded.renderReadable()}"
            }
            if (expanded !is ConeClassLikeType) return null
            for (argument in expanded.typeArguments) {
                val argumentType = (argument as? ConeKotlinTypeProjection)?.type ?: continue
                callbackIn(argumentType, path, holder ?: expanded.renderReadable())?.let { return it }
            }
            val classSymbol = expanded.toRegularClassSymbol(session) ?: return null
            return callbackIn(classSymbol)
        }

        private fun callbackIn(classSymbol: FirRegularClassSymbol): String? {
            if (!classSymbol.origin.fromSource) return null
            if (classSymbol.classKind != ClassKind.CLASS && classSymbol.classKind != ClassKind.INTERFACE) return null
            if (!visited.add(classSymbol)) return null
            val className = classSymbol.classId.shortClassName.asString()
            for (property in classSymbol.declarationSymbols.filterIsInstance<FirPropertySymbol>()) {
                if (property.receiverParameterSymbol != null) continue
                callbackIn(property.resolvedReturnType, listOf(className, property.name.asString()))?.let { return it }
            }
            for (superType in classSymbol.resolvedSuperTypes) {
                val superClass = superType.toRegularClassSymbol(session) ?: continue
                callbackIn(superClass)?.let { return it }
            }
            return null
        }
    }
}
