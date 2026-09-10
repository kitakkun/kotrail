package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.NarrowModelParametersScope
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.declaredProperties
import org.jetbrains.kotlin.fir.declarations.utils.isData
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.references.toResolvedValueParameterSymbol
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.Name

/**
 * Reports a data-class parameter when the function reads only a few of its properties and
 * leaves more than the configured number unused:
 *
 * ```kotlin
 * @Composable fun UserCard(user: User)   // reads user.name and user.avatarUrl out of ten
 * ```
 *
 * The function should take the values it needs (or a smaller model). A parameter that is used as
 * a whole (passed on, copied, compared, destructured, used as a receiver of anything but a
 * property read) is not reported because narrowing would change the program.
 */
object NarrowModelParametersChecker : FirSimpleFunctionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NARROW_MODEL_PARAMETERS)) return
        val settings = config.narrowModelParameters
        val session = context.session
        if (settings.scope == NarrowModelParametersScope.COMPOSABLES && !declaration.symbol.isComposable(session)) return
        if (declaration.source?.kind is KtFakeSourceElementKind) return
        if (declaration.isOverride || declaration.isExpect) return
        val body = declaration.body ?: return

        for (parameter in declaration.valueParameters) {
            val source = parameter.source ?: continue
            val classSymbol = parameter.returnTypeRef.coneType.toRegularClassSymbol(session) ?: continue
            if (!classSymbol.isData) continue
            val properties = classSymbol.declaredProperties(session).map { it.name }.toSet()
            if (properties.size <= settings.maxUnusedProperties) continue

            val usage = ParameterUsage(parameter.symbol, properties)
            body.accept(usage)
            if (usage.usedAsWhole) continue
            val unused = properties - usage.readProperties
            if (unused.size <= settings.maxUnusedProperties) continue

            val details = "${classSymbol.classId.shortClassName} declares ${properties.size} properties, but only " +
                describe(usage.readProperties) + " ${if (usage.readProperties.size == 1) "is" else "are"} read here; " +
                "${unused.size} unused (limit ${settings.maxUnusedProperties})"
            reportKotrail(
                source,
                KotrailDiagnostics.MODEL_PARAMETER_TOO_WIDE,
                parameter.name.asString(),
                details,
            )
        }
    }

    private fun describe(names: Set<Name>): String =
        if (names.isEmpty()) "none" else names.joinToString(", ") { it.asString() }

    /** Collects `param.property` reads and detects any other use of the parameter. */
    private class ParameterUsage(
        private val parameter: FirValueParameterSymbol,
        private val properties: Set<Name>,
    ) : FirVisitorVoid() {
        val readProperties = mutableSetOf<Name>()
        var usedAsWhole: Boolean = false
            private set

        override fun visitElement(element: FirElement) {
            if (usedAsWhole) return
            element.acceptChildren(this)
        }

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            if (usedAsWhole) return
            val receiver = propertyAccessExpression.explicitReceiver?.unwrapSmartCast()
            if (receiver.refersToParameter()) {
                val property = propertyAccessExpression.calleeReference.toResolvedPropertySymbol()?.name
                if (property != null && property in properties) {
                    readProperties += property
                    return
                }
                usedAsWhole = true
                return
            }
            if (propertyAccessExpression.refersToParameter()) {
                usedAsWhole = true
                return
            }
            propertyAccessExpression.acceptChildren(this)
        }

        private fun FirExpression?.refersToParameter(): Boolean =
            this is FirPropertyAccessExpression && calleeReference.toResolvedValueParameterSymbol() == parameter

        private fun FirExpression.unwrapSmartCast(): FirExpression =
            if (this is FirSmartCastExpression) originalExpression.unwrapSmartCast() else this
    }
}
