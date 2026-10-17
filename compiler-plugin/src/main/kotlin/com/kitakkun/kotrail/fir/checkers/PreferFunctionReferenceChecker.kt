@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.compat.qualifierClassId
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.ReferenceForm
import com.kitakkun.kotrail.fir.ClassNameInFile
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.fix.FixBuilder
import com.kitakkun.kotrail.fir.compose.ComposeNames
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.classNameInFile
import com.kitakkun.kotrail.fir.fqNameMatchesAny
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.recordNameProbe
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirAnonymousFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.declaredFunctions
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassId
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.dispatchReceiverClassLookupTagOrNull
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionCallOrigin
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.references.toResolvedPropertySymbol
import org.jetbrains.kotlin.fir.references.toResolvedValueParameterSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.scopes.getFunctions
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirSyntheticPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.isSuspendOrKSuspendFunctionType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Asks for a callable reference where a lambda only forwards its parameters:
 *
 * ```kotlin
 * list.map { transform(it) }        // -> list.map(::transform)        (topLevel)
 * button.onClick { vm.submit() }    // -> button.onClick(vm::submit)   (bound)
 * list.map { it.name }              // -> list.map(User::name)         (typeQualified)
 * ```
 *
 * Only reported when the reference is guaranteed to resolve to the same call: parameters are
 * forwarded in order without extras or omissions, the callee has no varargs, type parameters,
 * or same-named overloads, the receiver (if any) is `this`, a `val`, an object, or the first
 * lambda parameter, and neither side is `@Composable` or mismatched on `suspend`. A callee
 * that cannot be referenced at all is skipped: a member extension, a function or property with
 * context parameters, a property where a suspend function is expected, or a property that shares
 * its name with a function on the same receiver. A Java synthetic property (`file.absolutePath`)
 * is referenced through its getter (`File::getAbsolutePath`), the only form the language accepts.
 * The forms a project wants are selected with `preferFunctionReferences.forms`.
 *
 * Some lambdas read better than the reference and are left alone: a call written with operator
 * syntax (`{ -it }`, `{ a + b }`, `{ it in set }`, `{ list[it] }`), which names no function and
 * would become `Int::unaryMinus`; a constructor call; a call to a top-level function named like
 * the class it returns or a supertype of that class (`{ MutableInteractionSource() }`,
 * `{ Job(it) }` for a `CompletableJob`), which reads as one; and a callee whose fully qualified
 * name matches `preferFunctionReferences.ignoredFunctions`, a Java synthetic property by its
 * getter's name (`java.io.File.getAbsolutePath`).
 *
 * A type-qualified reference names the class the way the file can write it: a nested class with
 * its outer classes (`Sheet.Line::text`), an outermost class imported under an alias by the
 * alias. A class the file only met through inference (`spans.map { it.beat }` over a type from
 * another package) is not reachable by its simple name; the fix then imports it, and when that
 * name already means something else in the file nothing is reported.
 */
object PreferFunctionReferenceChecker : FirAnonymousFunctionChecker(MppCheckerKind.Common) {
    /** A reference to suggest, and the class the file has to import for it, if any. */
    private class Suggestion(val form: ReferenceForm, val text: String, val import: FqName?)

    /** How the file writes a class before `::`, and the class it has to import for that, if any. */
    private class TypeName(val text: String, val import: FqName?)

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirAnonymousFunction) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.PREFER_FUNCTION_REFERENCES)) return
        if (!declaration.isLambda) return
        val source = declaration.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        if (declaration.receiverParameter != null) return

        val session = context.session
        val lambdaType = declaration.typeRef.coneType
        if (lambdaType.customAnnotations.any { it.toAnnotationClassId(session) == ComposeNames.COMPOSABLE }) return
        val lambdaSuspends = lambdaType.isSuspendOrKSuspendFunctionType(session)

        val statement = declaration.body?.statements?.singleOrNull() ?: return
        val expression = when (statement) {
            is FirReturnExpression -> if (statement.target.labeledElement === declaration) statement.result else return
            is FirExpression -> statement
            else -> return
        }.unwrapSmartCast()
        val parameters = declaration.valueParameters.map { it.symbol }

        val suggestion = when (expression) {
            is FirFunctionCall -> referenceForCall(expression, parameters, lambdaSuspends, session)
            is FirPropertyAccessExpression -> referenceForProperty(expression, parameters, lambdaSuspends, session)
            else -> null
        } ?: return
        if (suggestion.form !in config.preferFunctionReferences.forms) return
        val fix = fixFor(declaration, source, suggestion.text)
        val edits = when (val import = suggestion.import) {
            null -> fix
            // Without its import the reference does not resolve, so the fix goes only with it.
            else -> importEdit(import)?.let { fix + it } ?: emptyList()
        }
        reportKotrail(source, KotrailDiagnostics.PREFER_FUNCTION_REFERENCE, suggestion.text, edits)
    }

    context(context: CheckerContext)
    private fun importEdit(fqName: FqName): FixEdit? {
        val file = context.containingFileSymbol?.fir ?: return null
        return FixBuilder.over(file.source)?.addImport(file, fqName)
    }

    /**
     * How the file writes [owner] before `::`: a nested class by its outer classes, headed by the
     * name the file has for the outermost one (see [classNameInFile]), which may need an import,
     * and a local class by its own name. `null` when the outermost class's simple name means
     * something else in the file, so that no reference to it can be written without qualifying
     * it by package.
     */
    context(context: CheckerContext)
    private fun typeNameOf(owner: FirRegularClassSymbol, session: FirSession): TypeName? {
        val classId = owner.classId
        if (classId.isLocal) return TypeName(owner.name.asString(), null)
        val file = context.containingFileSymbol?.fir ?: return null
        val outermost = classId.outermostClassId
        val nested = classId.relativeClassName.pathSegments().drop(1).joinToString("") { ".${it.asString()}" }
        return when (val name = classNameInFile(outermost, file, session)) {
            is ClassNameInFile.Reachable -> TypeName(name.name.asString() + nested, null)
            ClassNameInFile.Importable -> TypeName(outermost.shortClassName.asString() + nested, outermost.asSingleFqName())
            ClassNameInFile.Taken -> null
        }
    }

    /**
     * The edit that puts the reference where the lambda was. A trailing lambda moves into the
     * parentheses: `map { f(it) }` becomes `map(::f)`, `zip(b) { a, c -> g(a, c) }` becomes
     * `zip(b, ::g)`. A lambda already inside parentheses, named or not, or one that is not an
     * argument of the enclosing call at all (`val f: (Int) -> Int = { g(it) }` in a trailing
     * lambda, `remember { { g() } }`), is replaced in place.
     */
    context(context: CheckerContext)
    private fun fixFor(declaration: FirAnonymousFunction, lambda: KtSourceElement, reference: String): List<FixEdit> {
        val inPlace = listOf(FixBuilder.replace(lambda, reference))
        val call = context.containingElements.lastOrNull { it is FirFunctionCall } as? FirFunctionCall ?: return inPlace
        val argument = call.arguments.firstOrNull {
            (it.unwrapArgument() as? FirAnonymousFunctionExpression)?.anonymousFunction === declaration
        } ?: return inPlace
        val callSource = call.source ?: return inPlace
        val argumentSource = argument.source ?: lambda
        if (argumentSource.startOffset < callSource.startOffset || lambda.endOffset > callSource.endOffset) return inPlace
        val builder = FixBuilder.over(callSource) ?: return inPlace
        // A labeled lambda (`step@{ f(it) }`) starts at its label, which neither source covers.
        val argumentStart = labelStart(builder, minOf(argumentSource.startOffset, lambda.startOffset))
        val precedingEnd = builder.previousSignificantEnd(argumentStart)
        return when (builder.charAt(precedingEnd - 1)) {
            '(', ',', '=' -> listOf(builder.replaceRange(argumentStart, lambda.endOffset, reference))
            ')' -> {
                val closing = precedingEnd - 1
                if (builder.previousSignificantChar(closing) == '(') listOf(builder.replaceRange(closing, lambda.endOffset, "$reference)"))
                else listOf(builder.replaceRange(closing, lambda.endOffset, ", $reference)"))
            }
            // A trailing lambda after the callee: the parentheses take its place, whitespace included.
            else -> listOf(builder.replaceRange(precedingEnd, lambda.endOffset, "($reference)"))
        }
    }

    /** The start of the `label@` before the lambda at [lambdaStart], or [lambdaStart] when it has none. */
    private fun labelStart(builder: FixBuilder, lambdaStart: Int): Int {
        val at = builder.previousSignificantEnd(lambdaStart) - 1
        if (builder.charAt(at) != '@') return lambdaStart
        var start = at
        while (builder.charAt(start - 1)?.let { it.isLetterOrDigit() || it == '_' } == true) start--
        return if (start < at) start else lambdaStart
    }

    context(context: CheckerContext)
    private fun referenceForCall(
        call: FirFunctionCall,
        parameters: List<FirValueParameterSymbol>,
        lambdaSuspends: Boolean,
        session: FirSession,
    ): Suggestion? {
        // `{ block() }` on a function-typed value has no named callee to reference.
        if (call is FirImplicitInvokeCall) return null
        if (call.origin == FirFunctionCallOrigin.Operator) return null
        val callee = call.calleeReference.toResolvedFunctionSymbol() ?: return null
        if (callee.fqNameMatchesAny(session.kotrailConfig.preferFunctionReferences.ignoredFunctions)) return null
        if (callee.typeParameterSymbols.isNotEmpty()) return null
        if (callee.valueParameterSymbols.any { it.isVararg }) return null
        if (callee.isSuspend != lambdaSuspends) return null
        if (callee.isComposable(session)) return null
        // A member that is also an extension cannot be referenced: the language prohibits it.
        if (callee.dispatchReceiverType != null && callee.receiverParameterSymbol != null) return null
        // The language rejects a reference to a declaration with context parameters.
        if (callee.contextParameterSymbols.isNotEmpty()) return null
        if (hasOverloads(callee, session)) return null
        if (isNamedLikeItsType(callee, session)) return null
        val arguments = call.arguments.map { it.unwrapArgument().unwrapSmartCast() }
        if (arguments.size != callee.valueParameterSymbols.size) return null

        val name = when (callee) {
            is FirNamedFunctionSymbol -> callee.name.asString()
            is FirConstructorSymbol -> callee.callableId.className?.shortName()?.asString() ?: return null
            else -> return null
        }
        val receiver = call.explicitReceiver?.unwrapSmartCast()
        val receiverParameter = receiver?.asLambdaParameter()?.takeIf { it in parameters }

        return when {
            // { it.f(a) } -> Type::f, the receiver being the first lambda parameter.
            receiverParameter != null -> {
                if (parameters.firstOrNull() != receiverParameter) return null
                if (!forwardsInOrder(arguments, parameters.drop(1))) return null
                val owner = receiver.resolvedType.toRegularClassSymbol(session)?.takeIf { it.typeParameterSymbols.isEmpty() }
                    ?: return null
                val typeName = typeNameOf(owner, session) ?: return null
                Suggestion(ReferenceForm.TYPE_QUALIFIED, "${typeName.text}::$name", typeName.import)
            }
            receiver == null -> {
                if (!forwardsInOrder(arguments, parameters)) return null
                // An extension function called on an implicit receiver cannot be referenced as `::f`.
                if (callee.receiverParameterSymbol != null) return null
                Suggestion(ReferenceForm.TOP_LEVEL, "::$name", null)
            }
            else -> {
                if (!forwardsInOrder(arguments, parameters)) return null
                val prefix = stableReceiverText(receiver) ?: return null
                Suggestion(ReferenceForm.BOUND, "$prefix::$name", null)
            }
        }
    }

    context(context: CheckerContext)
    private fun referenceForProperty(
        access: FirPropertyAccessExpression,
        parameters: List<FirValueParameterSymbol>,
        lambdaSuspends: Boolean,
        session: FirSession,
    ): Suggestion? {
        // A property reference is never a suspend function, so it cannot stand in for a suspend lambda.
        if (lambdaSuspends) return null
        val receiver = access.explicitReceiver?.unwrapSmartCast() ?: return null
        val receiverParameter = receiver.asLambdaParameter() ?: return null
        if (parameters.size != 1 || parameters.single() != receiverParameter) return null
        val property = access.calleeReference.toResolvedPropertySymbol() ?: return null
        if (property.isLocal) return null
        if (property.contextParameterSymbols.isNotEmpty()) return null
        val ignoredFunctions = session.kotrailConfig.preferFunctionReferences.ignoredFunctions
        val ignored = when (property) {
            is FirSyntheticPropertySymbol -> ignoredFunctions.any { it.matches(property.getterId.asSingleFqName().asString()) }
            else -> property.fqNameMatchesAny(ignoredFunctions)
        }
        if (ignored) return null
        val owner = receiver.resolvedType.toRegularClassSymbol(session)?.takeIf { it.typeParameterSymbols.isEmpty() }
            ?: return null
        val scope = owner.unsubstitutedScope(session, context.scopeSession, withForcedTypeCalculator = false, memberRequiredPhase = null)
        // Asked by hand, the receiver's scope and the packages are recorded for incremental
        // compilation: a function of that name added or removed in another file compiles this file
        // again.
        val ownerFqName = owner.classId.asSingleFqName()
        val memberName = if (property is FirSyntheticPropertySymbol) {
            // A Java getter is only referenceable as a function: `File::getAbsolutePath`, not `File::absolutePath`.
            val getterName = property.getterId.callableName
            recordNameProbe(getterName, ownerFqName, access.source)
            if (scope.getFunctions(getterName).size > 1) return null
            getterName
        } else {
            // `Type::name` is ambiguous when a function of the same name exists on the receiver or as a
            // same-package extension, which is how an Objective-C property and its getter method arrive.
            recordNameProbe(property.name, ownerFqName, access.source)
            if (scope.getFunctions(property.name).isNotEmpty()) return null
            val packages = setOfNotNull(owner.classId.packageFqName, property.callableId?.packageName)
            packages.forEach { recordNameProbe(property.name, it, access.source) }
            if (packages.any { session.symbolProvider.getTopLevelFunctionSymbols(it, property.name).isNotEmpty() }) return null
            property.name
        }
        val typeName = typeNameOf(owner, session) ?: return null
        return Suggestion(ReferenceForm.TYPE_QUALIFIED, "${typeName.text}::${memberName.asString()}", typeName.import)
    }

    private fun forwardsInOrder(arguments: List<FirExpression>, parameters: List<FirValueParameterSymbol>): Boolean =
        arguments.size == parameters.size &&
            arguments.zip(parameters).all { (argument, parameter) -> argument.asLambdaParameter() == parameter }

    private fun FirExpression.asLambdaParameter(): FirValueParameterSymbol? =
        (this as? FirPropertyAccessExpression)?.calleeReference?.toResolvedValueParameterSymbol()

    /**
     * `this`, an object or companion, or a plain `val` / parameter; anything else could be
     * re-evaluated differently. An object or companion is written as the call spells it
     * (`Outer.Obj`, `lib.music.Tempo`), which already resolves in this file; a qualifier that
     * names a package alone has no value to bind.
     */
    private fun stableReceiverText(receiver: FirExpression): String? = when (receiver) {
        is FirThisReceiverExpression -> "this"
        is FirResolvedQualifier -> receiver.qualifierClassId?.let { FixBuilder.textOf(receiver.source)?.filterNot(Char::isWhitespace) }
        is FirPropertyAccessExpression -> {
            if (receiver.explicitReceiver != null) return null
            when (val variable = receiver.calleeReference.toResolvedVariableSymbol()) {
                is FirValueParameterSymbol -> variable.name.asString()
                is FirPropertySymbol -> if (variable.isVal) variable.name.asString() else null
                else -> null
            }
        }
        else -> null
    }

    private fun hasOverloads(callee: FirFunctionSymbol<*>, session: FirSession): Boolean {
        return when (callee) {
            // Constructor references need the class's constructor list; treated as ambiguous for now.
            is FirConstructorSymbol -> true
            is FirNamedFunctionSymbol -> {
                val owner = callee.dispatchReceiverClassLookupTagOrNull()?.toSymbol(session) as? FirClassSymbol<*>
                if (owner != null) {
                    owner.declaredFunctions(session).count { it.name == callee.name } > 1
                } else {
                    val id = callee.callableId
                    session.symbolProvider.getTopLevelFunctionSymbols(id.packageName, id.callableName).size > 1
                }
            }
            else -> true
        }
    }

    /**
     * Whether [callee] is a top-level function named like the class it is declared to return, or
     * like a supertype of that class, nullability aside: `MutableInteractionSource()`,
     * `CoroutineScope(context)`, `Job(parent)`, which returns a `CompletableJob`. Such a call reads
     * as a constructor call.
     */
    private fun isNamedLikeItsType(callee: FirFunctionSymbol<*>, session: FirSession): Boolean {
        if (callee !is FirNamedFunctionSymbol) return false
        if (callee.dispatchReceiverType != null || callee.receiverParameterSymbol != null) return false
        val returned = callee.resolvedReturnType.toRegularClassSymbol(session) ?: return false
        if (returned.name == callee.name) return true
        return lookupSuperTypes(returned, lookupInterfaces = true, deep = true, useSiteSession = session)
            .any { it.lookupTag.name == callee.name }
    }

    private fun FirExpression.unwrapSmartCast(): FirExpression =
        if (this is FirSmartCastExpression) originalExpression.unwrapSmartCast() else this
}
