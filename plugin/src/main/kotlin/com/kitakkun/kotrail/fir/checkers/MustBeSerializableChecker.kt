package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * Turns "this type must be serializable with kotlinx.serialization" into a compile-time check:
 *
 * ```kotlin
 * val filter = rememberSerializable { Filter() }          // Filter is not @Serializable -> reported
 * fun <@MustBeSerializable T> persist(value: T)            // every call site's T is checked
 * fun send(@MustBeSerializable payload: Any)               // the argument's type is checked
 * ```
 *
 * A `serializer<T>()` lookup on a type without a serializer fails at runtime; here it fails when
 * the call is compiled. Contracts come from two places: callables listed in
 * `serialization.requiredFor` (every type argument of the call is checked, unless an explicit
 * `KSerializer` argument is passed) and `@MustBeSerializable` on the callee's type parameters or
 * value parameters.
 *
 * A type is serializable when it is a primitive, `String`, `Char`, `Unit`, `Duration`, an enum,
 * an array, `Pair`/`Triple` or a standard collection whose type arguments are serializable, a
 * class annotated with `@Serializable`, or a type parameter (checked at its own call sites).
 */
object MustBeSerializableChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
    private val SERIALIZABLE = ClassId(FqName("kotlinx.serialization"), Name.identifier("Serializable"))
    private val K_SERIALIZER = ClassId(FqName("kotlinx.serialization"), Name.identifier("KSerializer"))
    private val MUST_BE_SERIALIZABLE = ClassId(FqName("com.kitakkun.kotrail.serialization"), Name.identifier("MustBeSerializable"))

    private val BUILTIN_SERIALIZABLE: Set<ClassId> = StandardClassIds.primitiveTypes + StandardClassIds.unsignedTypes + setOf(
        StandardClassIds.String,
        StandardClassIds.Unit,
        StandardClassIds.Array,
        ClassId(FqName("kotlin"), Name.identifier("Pair")),
        ClassId(FqName("kotlin"), Name.identifier("Triple")),
        StandardClassIds.List,
        StandardClassIds.Set,
        StandardClassIds.Map,
        StandardClassIds.Collection,
        StandardClassIds.Iterable,
        StandardClassIds.MutableList,
        StandardClassIds.MutableSet,
        StandardClassIds.MutableMap,
        StandardClassIds.MutableCollection,
        ClassId(FqName("kotlin.time"), Name.identifier("Duration")),
    ) + StandardClassIds.primitiveArrayTypeByElementType.values + StandardClassIds.unsignedArrayTypeByElementType.values

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.MUST_BE_SERIALIZABLE)) return
        val source = expression.source ?: return
        if (source.kind is KtFakeSourceElementKind) return
        val session = context.session
        val callee = expression.calleeReference.toResolvedFunctionSymbol() ?: return
        val calleeName = callee.displayName() ?: return

        val typesToCheck = mutableListOf<ConeKotlinType>()

        if (calleeName in config.serialization.requiredFor && !passesSerializer(expression, session)) {
            expression.typeArguments.mapNotNullTo(typesToCheck) { (it as? FirTypeProjectionWithVariance)?.typeRef?.coneType }
        }
        callee.typeParameterSymbols.forEachIndexed { index, typeParameter ->
            if (typeParameter.hasAnnotation(MUST_BE_SERIALIZABLE, session)) {
                (expression.typeArguments.getOrNull(index) as? FirTypeProjectionWithVariance)?.typeRef?.coneType?.let(typesToCheck::add)
            }
        }
        expression.resolvedArgumentMapping?.forEach { (argument, parameter) ->
            if (parameter.symbol.hasAnnotation(MUST_BE_SERIALIZABLE, session)) typesToCheck += argument.resolvedType
        }
        if (typesToCheck.isEmpty()) return

        val severity = config.severity(KotrailRule.MUST_BE_SERIALIZABLE)
        for (type in typesToCheck.distinct()) {
            if (isSerializable(type, session)) continue
            reporter.reportOn(source, KotrailDiagnostics.TYPE_NOT_SERIALIZABLE.at(severity), type.renderReadable(), calleeName)
        }
    }

    private fun FirFunctionSymbol<*>.displayName(): String? = when (this) {
        is FirNamedFunctionSymbol -> callableId?.asSingleFqName()?.asString()
        is FirConstructorSymbol -> callableId?.classId?.asSingleFqName()?.asString()
        else -> null
    }

    /** An explicit `KSerializer` argument means the caller took responsibility for serialization. */
    private fun passesSerializer(call: FirFunctionCall, session: FirSession): Boolean =
        call.resolvedArgumentMapping?.keys?.any { it.resolvedType.fullyExpandedType(session).classId == K_SERIALIZER } == true

    private fun isSerializable(type: ConeKotlinType, session: FirSession): Boolean {
        if (type is ConeTypeParameterType) return true
        val expanded = type.fullyExpandedType(session)
        if (expanded !is ConeClassLikeType) return false
        val classId = expanded.classId ?: return false
        if (classId in BUILTIN_SERIALIZABLE) {
            return expanded.typeArguments.all { projection -> projection.type?.let { isSerializable(it, session) } ?: true }
        }
        val symbol = expanded.toRegularClassSymbol(session) ?: return false
        if (symbol.classKind == ClassKind.ENUM_CLASS) return true
        if (symbol.hasAnnotation(SERIALIZABLE, session)) {
            return expanded.typeArguments.all { projection -> projection.type?.let { isSerializable(it, session) } ?: true }
        }
        return false
    }
}
