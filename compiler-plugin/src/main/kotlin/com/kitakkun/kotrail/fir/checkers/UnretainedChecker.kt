package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.FirThisReference
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Holds a function to the contract of its `@Unretained` parameters: the function may read the
 * parameter, call it and register callbacks on it, but must not keep it alive past the call,
 * except through a weak reference.
 *
 * ```kotlin
 * fun register(@Unretained job: Job, name: String) {
 *     roots[name] = job                          // reported: stored in a property
 *     roots[name] = WeakReference(job)           // fine
 *     job.invokeOnCompletion { roots.remove(name) }   // fine: registered on the parameter itself
 *     scope.launch { track(job) }                // reported: captured by a lambda that outlives the call
 * }
 * ```
 *
 * Whether a reference should be weak is a matter of intended lifetime, which no analysis can
 * read off the code; once the intent is written down on the parameter, keeping to it is
 * decidable. The analysis is intraprocedural: the parameter and its aliases (locals assigned
 * from it, the receiver or `it` of `also`/`apply`/`let`/`run`/`with`) escape when stored in a
 * non-local property, passed to a callee whose parameter is not `@Unretained` (a callee that
 * cannot be seen counts as retaining, so a finding can be a false positive but not a false
 * negative), captured by a lambda that is not inlined into the call, captured by an anonymous
 * object, a local class or a local function (`object : WeakReference<T> { override fun get() =
 * referent }` retains the referent strongly), or returned. Calling a method on the parameter, or
 * handing it a lambda, is not an escape. The annotation travels in metadata, so a library's
 * contract is honored by its callers.
 */
object UnretainedChecker : FirBasicDeclarationChecker(MppCheckerKind.Common) {
    private val SCOPE_FUNCTIONS = setOf("kotlin.also", "kotlin.apply", "kotlin.let", "kotlin.run", "kotlin.with")
    private val ALLOWED_CALLEES = setOf(
        "kotlin.Any.equals", "kotlin.Any.hashCode", "kotlin.Any.toString",
        "kotlin.require", "kotlin.check", "kotlin.error", "kotlin.checkNotNull", "kotlin.requireNotNull",
        "kotlin.io.println", "kotlin.io.print",
    )
    private val DECLARES_PROPERTY = Regex("""(^|\s)(val|var)\s""")

    /** Standard-library functions that build a value out of their arguments: the parameter ends up inside that value. */
    private val VALUE_BUILDERS = setOf(
        "kotlin.to", "kotlin.collections.plus", "kotlin.collections.listOf", "kotlin.collections.setOf", "kotlin.collections.mapOf",
        "kotlin.collections.mutableListOf", "kotlin.collections.mutableSetOf", "kotlin.collections.mutableMapOf",
        "kotlin.collections.arrayListOf", "kotlin.collections.hashMapOf", "kotlin.collections.hashSetOf", "kotlin.arrayOf",
        "kotlin.Pair", "kotlin.Triple",
    )

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirDeclaration) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.UNRETAINED)) return
        val function = declaration as? FirFunction ?: return
        if (function !is FirNamedFunction && function !is FirConstructor) return
        if (function.source?.kind is KtFakeSourceElementKind) return
        val session = context.session
        val annotations = config.unretained.annotations.map { classId(it) }
        val weakTypes = config.unretained.weakTypes.map { classId(it) }

        for (parameter in function.valueParameters) {
            if (annotations.none { parameter.symbol.hasAnnotation(it, session) }) continue
            val name = parameter.name.asString()
            val parameterSource = parameter.source
            // A `val` or `var` constructor parameter is a property: the class retains it from the start.
            if (function is FirConstructor && parameterSource != null && DECLARES_PROPERTY.containsMatchIn(parameterSource.text ?: "")) {
                reportKotrail(parameterSource, KotrailDiagnostics.UNRETAINED_PARAMETER_RETAINED, name, "it is declared as a property of the class")
                continue
            }
            val body = function.body ?: continue
            val finder = EscapeFinder(session, function, parameter, annotations, weakTypes)
            body.accept(finder)
            for ((source, how) in finder.escapes) {
                reportKotrail(source, KotrailDiagnostics.UNRETAINED_PARAMETER_RETAINED, name, how)
            }
        }
    }

    private fun classId(fqn: String): ClassId = ClassId.topLevel(FqName(fqn))

    /**
     * Walks a function body tracking the parameter and its aliases, collecting every place one
     * of them is retained.
     */
    private class EscapeFinder(
        private val session: FirSession,
        private val function: FirFunction,
        parameter: FirValueParameter,
        private val annotations: List<ClassId>,
        private val weakTypes: List<ClassId>,
    ) : FirVisitorVoid() {
        val escapes = mutableListOf<Pair<KtSourceElement, String>>()
        private val aliases = mutableSetOf<FirBasedSymbol<*>>(parameter.symbol)

        /** Lambdas whose body runs within the call: an inline parameter's, or one handed to the parameter itself. */
        private val transparentLambdas = mutableSetOf<FirAnonymousFunction>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitProperty(property: FirProperty) {
            val initializer = property.initializer
            if (initializer != null && initializer.isAliasRead()) {
                if (property.isLocal) aliases += property.symbol
                else property.source?.let { escapes += it to "it is stored in property '${property.name.asString()}'" }
            }
            property.acceptChildren(this)
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            val value = variableAssignment.rValue
            if (value.isAliasRead()) {
                val lValue = when (val target = variableAssignment.lValue) {
                    is FirDesugaredAssignmentValueReferenceExpression -> target.expressionRef.value
                    else -> target
                }
                val symbol = (lValue as? FirQualifiedAccessExpression)?.calleeReference?.toResolvedCallableSymbol()
                when {
                    symbol is FirPropertySymbol && symbol.isLocal -> aliases += symbol
                    symbol is FirPropertySymbol -> variableAssignment.source?.let { escapes += it to "it is stored in property '${symbol.name.asString()}'" }
                    else -> variableAssignment.source?.let { escapes += it to "it is stored" }
                }
            }
            variableAssignment.acceptChildren(this)
        }

        override fun visitReturnExpression(returnExpression: FirReturnExpression) {
            if (returnExpression.target.labeledElement === function && returnExpression.result.isAliasRead()) {
                returnExpression.result.source?.let { escapes += it to "it is returned" }
            }
            returnExpression.acceptChildren(this)
        }

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedCallableSymbol() as? FirFunctionSymbol<*>
            val fqn = callee?.callableId?.asSingleFqName()?.asString()
            val receiverIsAlias = functionCall.explicitReceiver?.isAliasRead() == true

            if (fqn in SCOPE_FUNCTIONS) {
                // `with(alias) { }` takes the value as its first argument; the others as the receiver.
                val scoped = if (fqn == "kotlin.with") functionCall.argumentList.arguments.firstOrNull()?.isAliasRead() == true else receiverIsAlias
                val lambda = functionCall.argumentList.arguments.lastOrNull()?.lambda()
                if (lambda != null) {
                    transparentLambdas += lambda
                    if (scoped) {
                        lambda.receiverParameter?.symbol?.let { aliases += it }
                        lambda.valueParameters.firstOrNull()?.symbol?.let { aliases += it }
                    }
                }
                functionCall.acceptChildren(this)
                return
            }

            val mapping = functionCall.resolvedArgumentMapping
            val arguments = functionCall.argumentList.arguments
            for ((index, argument) in arguments.withIndex()) {
                val parameterSymbol: FirValueParameterSymbol? = mapping?.get(argument)?.symbol ?: callee?.valueParameterSymbols?.getOrNull(index)
                val lambda = argument.lambda()
                if (lambda != null) {
                    // The lambda runs within the call when the callee inlines it, or it is handed to the parameter itself.
                    val inlined = callee != null && callee.isInline && parameterSymbol != null && !parameterSymbol.isCrossinline && !parameterSymbol.isNoinline
                    if (inlined || receiverIsAlias) transparentLambdas += lambda
                    continue
                }
                if (!argument.isAliasRead()) continue
                if (callee is FirConstructorSymbol && weakTypes.any { callee.resolvedReturnType.isSubtypeOf(it) }) continue
                if (fqn in ALLOWED_CALLEES) continue
                if (parameterSymbol != null && annotations.any { parameterSymbol.hasAnnotation(it, session) }) continue
                // A constructor is named by its class: `Pair(a, b)`, not `kotlin.Pair.<init>`.
                val builder = if (callee is FirConstructorSymbol) callee.callableId.classId?.asSingleFqName()?.asString() else fqn
                val how = when {
                    builder in VALUE_BUILDERS -> "it is put into a value built with '${builder!!.substringAfterLast('.')}'"
                    else -> "it is passed to '${fqn ?: "an unresolved function"}', which does not declare its parameter unretained"
                }
                argument.source?.let { escapes += it to how }
            }
            functionCall.acceptChildren(this)
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            if (anonymousFunction in transparentLambdas) {
                anonymousFunction.acceptChildren(this)
                return
            }
            // A lambda that may run after the call: any read of the parameter inside it keeps the parameter alive.
            captured(anonymousFunction, "it is captured by a lambda that outlives the call")
        }

        // An object, a local class or a local function is a value of its own: whatever it reads, it retains.
        override fun visitAnonymousObject(anonymousObject: FirAnonymousObject) {
            captured(anonymousObject, "it is captured by an object that outlives the call")
        }

        override fun visitRegularClass(regularClass: FirRegularClass) {
            captured(regularClass, "it is captured by a local class that outlives the call")
        }

        override fun visitNamedFunction(namedFunction: FirNamedFunction) {
            captured(namedFunction, "it is captured by a local function that outlives the call")
        }

        /** Records the first read of an alias inside [declaration] as an escape with [how]; the declaration is not walked further. */
        private fun captured(declaration: FirElement, how: String) {
            val read = FirstAliasRead().also { declaration.acceptChildren(it) }.found ?: return
            read.source?.let { escapes += it to how }
        }

        private fun FirExpression.lambda(): FirAnonymousFunction? = when (this) {
            is FirAnonymousFunctionExpression -> anonymousFunction
            is FirWrappedArgumentExpression -> expression.lambda()
            else -> null
        }

        private fun FirExpression.isAliasRead(): Boolean = when (this) {
            is FirSmartCastExpression -> originalExpression.isAliasRead()
            is FirWrappedArgumentExpression -> expression.isAliasRead()
            is FirThisReceiverExpression -> (calleeReference as? FirThisReference)?.boundSymbol?.let { bound -> aliases.any { it == bound } } == true
            is FirPropertyAccessExpression -> calleeReference.toResolvedCallableSymbol()?.let { callee -> aliases.any { it == callee } } == true
            else -> false
        }

        private fun ConeKotlinType.isSubtypeOf(classId: ClassId): Boolean {
            val expanded = fullyExpandedType(session)
            val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
            val superType = classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
            return !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
        }

        /** The first read of an alias inside a lambda, lambdas nested in it included. */
        private inner class FirstAliasRead : FirVisitorVoid() {
            var found: FirExpression? = null

            override fun visitElement(element: FirElement) {
                if (found != null) return
                element.acceptChildren(this)
            }

            override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
                if (found == null && propertyAccessExpression.isAliasRead()) found = propertyAccessExpression
                else propertyAccessExpression.acceptChildren(this)
            }

            override fun visitThisReceiverExpression(thisReceiverExpression: FirThisReceiverExpression) {
                if (found == null && thisReceiverExpression.isAliasRead()) found = thisReceiverExpression
            }
        }
    }
}
