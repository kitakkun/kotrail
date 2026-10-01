@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailNoExternalMutation
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestFunction
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirVariableAssignmentChecker
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReceiverParameterSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Reports a mutation of another object's mutable state from code outside the class that owns it:
 *
 * ```kotlin
 * class Scene { val capturing = mutableStateOf(false); fun render() { ... } }
 *
 * class CaptureTool(private val scene: Scene) {
 *     fun capture() {
 *         scene.capturing.value = true      // reported: Scene's state, changed by CaptureTool
 *         try { ... } finally { scene.capturing.value = false }
 *     }
 * }
 * ```
 *
 * When two callers each flip the same flag around their own work, nothing says which
 * transitions are legal or what happens when they overlap; the answer is scattered over every
 * caller. A method on the owner (`scene.captureWhile { ... }`) puts every transition in one
 * place, where it can be guarded and tested.
 *
 * A write is `owner.state.value = x` on a property whose type is one of `types` (subtypes
 * included: `MutableState`, `MutableStateFlow`, `MutableSharedFlow` by default), a call of
 * `update`, `emit`, `tryEmit`, `compareAndSet`, `getAndUpdate` or `updateAndGet` on such a
 * property, an assignment to a delegated `var x by mutableStateOf()`, and, when `plainVars` is
 * true (it is false by default), an assignment to a plain `var` member of another class
 * (`owner.count = 3`, `owner.count += 1`, `owner.count++`) whose setter is public or internal.
 * The owner is the class or object that declares the property; a property of a companion object
 * belongs to the class around it.
 *
 * Quiet: a write lexically inside the owner (its members, companion, nested and inner classes,
 * and lambdas and local functions in them); a write through `this` of a class, which includes a
 * subclass changing what it inherited; locals and top-level properties (`compose.noGlobalMutableState`
 * covers global state); an owner declared in the same source file as the write (a component
 * writing its own state holder; a `private` top-level class is always this case); for a plain `var`, an owner with no functions of its own, data class or not, a
 * bag of values with no behavior to add a method to (a state holder is reported on every owner:
 * a flag flipped from outside is the bug whatever else the class declares); for a plain `var`, an
 * owner from another module or a library, whose API is what its authors chose (a state holder of
 * one of `types` is reported whatever module declares it: a class that exposes a
 * `MutableStateFlow` to other modules is the case that matters most); a test function and a file
 * in a test source set. An object the function is still configuring is its own, not someone
 * else's state: the receiver of an `apply`, `also`, `run`, `with` or `let` on a call result
 * (`Config().apply { name = "x" }`), and a local initialized by a constructor call. An extension
 * function declared outside the owner is outside it, even when it writes through its receiver.
 */
object NoExternalMutationChecker {
    private val UPDATE_CALLS = setOf("update", "emit", "tryEmit", "compareAndSet", "getAndUpdate", "updateAndGet")
    private val SCOPE_FUNCTIONS = setOf("kotlin.apply", "kotlin.also", "kotlin.run", "kotlin.with", "kotlin.let")

    object Assignments : FirVariableAssignmentChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirVariableAssignment) {
            val config = context.session.kotrailConfig
            if (!config.isEnabled(KotrailRule.NO_EXTERNAL_MUTATION)) return
            val settings = config.noExternalMutation
            // `x += 1` and `x++` assign through a reference to the original access.
            val lValue = when (val value = expression.lValue) {
                is FirDesugaredAssignmentValueReferenceExpression -> value.expressionRef.value
                else -> value
            } as? FirPropertyAccessExpression ?: return
            val symbol = lValue.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return
            val session = context.session

            // `owner.state.value = x`: the write is to `state`, whatever declares `value`.
            val holder = lValue.explicitReceiver?.unwrapSmartCast() as? FirPropertyAccessExpression
            if (holder != null && holder.resolvedType.isStateHolder(session, settings)) {
                reportIfOutside(expression.source ?: lValue.source, holder, session, plainVar = false)
                return
            }
            if (lValue.dispatchReceiver?.resolvedType?.isStateHolder(session, settings) == true) return
            val isStateDelegate = symbol.hasDelegate &&
                symbol.resolvedDelegateType()?.isStateHolder(session, settings) == true
            if (!settings.plainVars && !isStateDelegate) return
            if (!symbol.isVar) return
            val setterVisibility = symbol.setterSymbol?.visibility ?: symbol.visibility
            if (setterVisibility != Visibilities.Public && setterVisibility != Visibilities.Internal) return
            reportIfOutside(expression.source ?: lValue.source, lValue, session, plainVar = !isStateDelegate)
        }
    }

    object Calls : FirFunctionCallChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirFunctionCall) {
            val config = context.session.kotrailConfig
            if (!config.isEnabled(KotrailRule.NO_EXTERNAL_MUTATION)) return
            if (expression.calleeReference.name.asString() !in UPDATE_CALLS) return
            val holder = expression.explicitReceiver?.unwrapSmartCast() as? FirPropertyAccessExpression ?: return
            val session = context.session
            if (!holder.resolvedType.isStateHolder(session, config.noExternalMutation)) return
            reportIfOutside(expression.source, holder, session, plainVar = false)
        }
    }

    /**
     * Reports the write at [source] to the member [access] names unless the code at hand is inside
     * its owner. [plainVar] is a write to a plain `var` rather than to a state holder: only those
     * are limited to owners compiled in this module.
     */
    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportIfOutside(source: KtSourceElement?, access: FirPropertyAccessExpression, session: FirSession, plainVar: Boolean) {
        if (source == null) return
        val property = access.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return
        val owner = ownerOf(property, session) ?: return
        if (plainVar && owner.origin != FirDeclarationOrigin.Source) return
        if (plainVar && owner.isBag()) return
        if (owner.isDeclaredInWriterFile(session)) return
        val receiver = access.dispatchReceiver?.unwrapSmartCast()
        if (receiver.isThisOfClass()) return
        if (receiver != null && isFreshObject(receiver)) return
        if (context.containingDeclarations.any { it is FirClassSymbol<*> && it.classId == owner.classId }) return
        if (isInTest(session)) return
        val name = access.source?.text?.toString() ?: property.name.asString()
        reportKotrail(source, KotrailDiagnostics.MUTATED_OUTSIDE_OWNER, name, owner.classId.shortClassName.asString())
    }

    /** The class or object that declares [property], a companion standing for the class around it; null for a local or top-level one. */
    private fun ownerOf(property: FirPropertySymbol, session: FirSession): FirRegularClassSymbol? {
        val declaring = property.containingClassLookupTag()?.toRegularClassSymbol(session) ?: return null
        if (!declaring.isCompanion) return declaring
        val outer = declaring.classId.outerClassId ?: return declaring
        return session.symbolProvider.getClassLikeSymbolByClassId(outer) as? FirRegularClassSymbol ?: declaring
    }

    /**
     * A class with no functions of its own, data class or not: a bag of values with no behavior to
     * add a method to. The members generated for a data class are not its own.
     */
    private fun FirRegularClassSymbol.isBag(): Boolean = declarationSymbols.none { member ->
        member is FirNamedFunctionSymbol && member.source?.kind !is KtFakeSourceElementKind
    }

    /**
     * Whether this class is declared in the file the write is in. A holder and the code that drives
     * it (a component writing its own state holder, a presenter feeding a file-private one) are one
     * unit when they share a file: the reader sees both. A `private` top-level class is always this
     * case, since only its own file can write it.
     */
    context(context: CheckerContext)
    private fun FirRegularClassSymbol.isDeclaredInWriterFile(session: FirSession): Boolean {
        if (origin != FirDeclarationOrigin.Source || classId.isLocal) return false
        val writerFile = context.containingFileSymbol ?: return false
        return session.firProvider.getFirClassifierContainerFileIfAny(this)?.symbol == writerFile
    }

    /**
     * Whether [receiver] is an object the function is still configuring: the receiver or parameter
     * of a scope function called on a call result (`Config().apply { name = "x" }`,
     * `with(Config()) { ... }`), or a local initialized by a constructor call.
     */
    context(context: CheckerContext)
    private fun isFreshObject(receiver: FirExpression): Boolean {
        val bound = when (receiver) {
            is FirThisReceiverExpression -> receiver.calleeReference.boundSymbol
            is FirPropertyAccessExpression -> receiver.calleeReference.toResolvedCallableSymbol()
            else -> null
        }
        if (bound is FirPropertySymbol && bound.isLocal) {
            val initializer = bound.resolvedInitializer?.unwrapSmartCast() as? FirFunctionCall ?: return false
            return initializer.calleeReference.toResolvedCallableSymbol() is FirConstructorSymbol
        }
        val lambda = when (bound) {
            is FirReceiverParameterSymbol -> bound.containingDeclarationSymbol as? FirAnonymousFunctionSymbol
            is FirValueParameterSymbol -> bound.containingDeclarationSymbol as? FirAnonymousFunctionSymbol
            else -> null
        } ?: return false
        val call = context.containingElements.lastOrNull { element ->
            element is FirFunctionCall && element.argumentList.arguments.any {
                (it.unwrapArgument() as? FirAnonymousFunctionExpression)?.anonymousFunction?.symbol == lambda
            }
        } as? FirFunctionCall ?: return false
        val callee = call.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString()
        if (callee !in SCOPE_FUNCTIONS) return false
        val target = if (callee == "kotlin.with") call.argumentList.arguments.firstOrNull()?.unwrapArgument() else call.explicitReceiver
        return target?.unwrapSmartCast() is FirFunctionCall
    }

    private fun FirExpression?.isThisOfClass(): Boolean =
        this is FirThisReceiverExpression && calleeReference.boundSymbol is FirClassSymbol<*>

    context(context: CheckerContext)
    private fun isInTest(session: FirSession): Boolean {
        val path = context.containingFileSymbol?.sourceFile?.path
        if (path != null && isTestSourcePath(path)) return true
        val annotations = session.kotrailConfig.test.annotations
        return context.containingDeclarations.any { it is FirNamedFunctionSymbol && it.isTestFunction(session, annotations) }
    }

    private fun FirPropertySymbol.resolvedDelegateType(): ConeKotlinType? = delegate?.resolvedType

    private fun FirExpression.unwrapSmartCast(): FirExpression = if (this is FirSmartCastExpression) originalExpression else this

    private fun ConeKotlinType.isStateHolder(session: FirSession, settings: KotrailNoExternalMutation): Boolean {
        val expanded = fullyExpandedType(session)
        if (expanded.isNullableNothing) return false
        return settings.types.any { name ->
            val classId = ClassId.topLevel(FqName(name))
            val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return@any false
            val superType = classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
            AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
        }
    }
}
