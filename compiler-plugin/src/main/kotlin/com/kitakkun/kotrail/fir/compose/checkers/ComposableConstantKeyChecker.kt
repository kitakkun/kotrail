package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.compat.qualifierClassId
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.fix.FixBuilder
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.text

/**
 * The constant-key half of `compose.rememberKeys`: asks that the keys of `remember`,
 * `LaunchedEffect` and their kin list only values that can change while the composable lives:
 *
 * ```kotlin
 * class Screen(private val navigator: Navigator) {
 *     @Composable
 *     fun Content(id: String) {
 *         LaunchedEffect(navigator) { navigator.observe() }         // reported: navigator never changes
 *         val text = remember(Registry, id) { Registry.lookup(id) } // reported: Registry
 *         LaunchedEffect(Unit) { load() }                            // fine: the idiom for "once"
 *     }
 * }
 * ```
 *
 * A reader takes "it is a key" as "it changes, and the block runs again when it does". A value
 * that never changes in the key list breaks that reading, and hides which inputs really restart
 * the block. [ComposableRememberKeysChecker] reports the other half under the same rule, a
 * changing value that is not a key: together they make the key list exactly the set of changing
 * inputs.
 *
 * A key is constant, and reported, when it is a final, non-delegated `val` without a custom
 * getter that is a member of the enclosing class read through `this`, a member of an `object` or
 * companion, or declared at the top level (constants included); an `object`, a companion or an
 * enum entry; or a literal other than a Boolean. A local is never constant, a remembered object
 * included: it keeps its identity only while its `remember` keeps the keys it has, and an effect
 * keyed on it restarts, letting go of the old object, once that `remember` gains a key. `Unit`
 * and Boolean literals are the idiom for "once" and stay quiet.
 * Key arguments are those of the parameters named `key1`, `key2`, ..., `keys` or `inputs`, which
 * is how the Compose runtime names them; a `saver` or `initialValue` is not a key.
 */
object ComposableConstantKeyChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val KEY_PARAMETER = Regex("key\\d*|keys|inputs")

    /** One key argument: the expression as analyzed, and where it is written. */
    private class Key(val expression: FirExpression, val source: KtSourceElement)

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val session = context.session
        val config = session.kotrailConfig
        if (!config.isEnabled(KotrailRule.COMPOSE_REMEMBER_KEYS)) return
        val callSource = expression.source ?: return
        if (callSource.kind is KtFakeSourceElementKind) return
        val function = context.containingElements.lastOrNull { it is FirNamedFunction } as? FirNamedFunction ?: return
        if (!function.symbol.isComposable(session)) return
        val keyedFunctions = config.compose.rememberKeysFunctions
        val callee = expression.calleeReference.toResolvedNamedFunctionSymbol() ?: return
        if (callee.callableId.asSingleFqName().asString() !in keyedFunctions) return

        var fixable = true
        val keys = ArrayList<Key>()
        for ((argument, parameter) in expression.resolvedArgumentMapping.orEmpty()) {
            if (!KEY_PARAMETER.matches(parameter.name.asString())) continue
            val elements = if (argument is FirVarargArgumentsExpression) argument.arguments else listOf(argument)
            for (element in elements) {
                // A spread array hides its keys, and its written form starts before the expression's source.
                if (element is FirSpreadArgumentExpression) {
                    fixable = false
                    continue
                }
                val unwrapped = element.unwrapped()
                val source = unwrapped.source ?: element.source ?: continue
                if (source.kind is KtFakeSourceElementKind) continue
                keys += Key(unwrapped, source)
            }
        }
        if (keys.isEmpty()) return
        keys.sortBy { it.source.startOffset }
        val redundant = keys.map { isConstant(session, it.expression) }
        if (redundant.none { it }) return

        val builder = FixBuilder.over(callSource)
        // A named key (`key1 = x`) cannot be dropped without renaming the ones after it.
        if (builder == null || keys.any { builder.previousSignificantChar(it.source.startOffset) == '=' }) fixable = false
        for ((index, key) in keys.withIndex()) {
            if (!redundant[index]) continue
            val text = key.source.text?.toString()?.trim() ?: continue
            val fix = if (fixable && builder != null) removal(builder, keys, redundant, index) else emptyList()
            reportKotrail(key.source, KotrailDiagnostics.REDUNDANT_KEY, text, fix)
        }
    }

    /**
     * The edit that drops key [index]. The edits of the keys of one call never overlap, so that
     * `kotrailFix` applies them all: when every key is redundant, the first becomes `Unit` and the
     * others go with the comma before them; a key in a leading run of redundant keys goes with
     * everything up to the next key; any other key goes with the comma before it. A last key
     * followed by a trailing comma on the same line takes that comma along, so that
     * `remember(a, b, ) {` becomes `remember(a) {`, not `remember(a, ) {`.
     */
    private fun removal(builder: FixBuilder, keys: List<Key>, redundant: List<Boolean>, index: Int): List<FixEdit> {
        val key = keys[index].source
        if (redundant.all { it }) {
            if (index == 0) return listOf(builder.replaceRange(key.startOffset, key.endOffset, "Unit"))
            return listOf(builder.replaceRange(keys[index - 1].source.endOffset, endWithTrailingComma(builder, keys, index), ""))
        }
        if ((0..index).all { redundant[it] }) {
            return listOf(builder.replaceRange(key.startOffset, keys[index + 1].source.startOffset, ""))
        }
        return listOf(builder.replaceRange(keys[index - 1].source.endOffset, endWithTrailingComma(builder, keys, index), ""))
    }

    /** The end of key [index], extended to the closing parenthesis when it is the last key and only a same-line trailing comma follows. */
    private fun endWithTrailingComma(builder: FixBuilder, keys: List<Key>, index: Int): Int {
        val end = keys[index].source.endOffset
        if (index != keys.lastIndex) return end
        var offset = end
        var sawComma = false
        while (true) {
            val char = builder.charAt(offset) ?: return end
            when {
                char == '\n' -> return end
                char.isWhitespace() -> offset++
                char == ',' && !sawComma -> {
                    sawComma = true
                    offset++
                }
                char == ')' -> return if (sawComma) offset else end
                else -> return end
            }
        }
    }

    /** Whether [expression], written as a key, keeps its value for as long as the composable lives. */
    private fun isConstant(session: FirSession, expression: FirExpression): Boolean = when (expression) {
        // `true`, `false`: the idiom for "once", like `Unit`.
        is FirLiteralExpression -> expression.value !is Boolean
        is FirResolvedQualifier -> expression.resolvedToCompanionObject || expression.isObject(session)
        is FirPropertyAccessExpression -> when (val symbol = expression.calleeReference.toResolvedCallableSymbol()) {
            is FirEnumEntrySymbol -> true
            is FirPropertySymbol -> !symbol.isLocal && isConstantMember(expression, symbol)
            else -> false
        }
        else -> false
    }

    private fun FirResolvedQualifier.isObject(session: FirSession): Boolean {
        val classId = qualifierClassId ?: return false
        val qualified = session.symbolProvider.getClassLikeSymbolByClassId(classId) as? FirRegularClassSymbol ?: return false
        return qualified.classKind == ClassKind.OBJECT && qualified.classId != StandardClassIds.Unit
    }

    /**
     * A plain final `val` read through `this` of a class or object, through an `object` or
     * companion, or declared at the top level: nothing reassigns it, and no getter recomputes it.
     */
    private fun isConstantMember(access: FirPropertyAccessExpression, symbol: FirPropertySymbol): Boolean {
        if (!symbol.isVal || symbol.hasDelegate || symbol.receiverParameterSymbol != null) return false
        if (symbol.modality != Modality.FINAL) return false
        if (symbol.getterSymbol?.isDefault == false) return false
        return when (val receiver = access.dispatchReceiver?.unwrapped()) {
            null -> access.explicitReceiver == null
            is FirThisReceiverExpression -> receiver.calleeReference.boundSymbol is FirClassSymbol<*>
            is FirResolvedQualifier -> true
            else -> false
        }
    }

    private fun FirExpression.unwrapped(): FirExpression = when (this) {
        is FirWrappedArgumentExpression -> expression.unwrapped()
        is FirSmartCastExpression -> originalExpression.unwrapped()
        else -> this
    }
}
