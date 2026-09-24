package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNullableNothing
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Rules for a compilation whose classes are loaded through a class loader of their own and
 * dropped later: a host plugin loaded per plugin and replaced on hot reload, an IDE plugin the
 * platform unloads. The loader can go only when nothing outside it refers to its classes or
 * instances, so the hazard is an outbound reference: a plugin object handed to something that
 * outlives the plugin. State that stays inside the loader (an `object` cache of the plugin's own
 * values) goes with it and is not reported.
 *
 * ```kotlin
 * val cache: ThreadLocal<Buffer> = ThreadLocal()                 // reported: a platform thread keeps the value
 * Runtime.getRuntime().addShutdownHook(thread)                   // reported: registered, never unregistered
 * bus.connect().subscribe(TOPIC, listener)                       // reported: connect() without a parent disposable
 * bus.connect(parentDisposable).subscribe(TOPIC, listener)       // fine: scoped to the plugin's lifetime
 * ```
 *
 * Off by default: nothing here matters to an ordinary library. Switched on for the compilations
 * that are unloaded, through `kotrail { compilation("main") { } }` on those modules or their
 * own configuration file, and for every module bundled into the same class loader.
 */
object UnloadableCodeChecker {
    private val THREAD_LOCAL = ClassId(FqName("java.lang"), Name.identifier("ThreadLocal"))

    /** A property of a `ThreadLocal` type, static or not, local or not: a value set on a platform thread outlives the plugin. */
    object ThreadLocalChecker : FirPropertyChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirProperty) {
            if (!context.session.kotrailConfig.isEnabled(KotrailRule.UNLOADABLE_CODE)) return
            val source = declaration.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            if (!declaration.returnTypeRef.coneType.isSubtypeOf(THREAD_LOCAL, context.session)) return
            reportKotrail(source, KotrailDiagnostics.THREAD_LOCAL_IN_UNLOADABLE_CODE, declaration.name.asString())
        }
    }

    /**
     * A registration on something that outlives the plugin, from `registrations`, without a
     * `Disposable` (or another `disposableTypes` type) among its arguments to undo it with; and
     * a `ThreadLocal` constructed where no property declares it.
     */
    object RegistrationChecker : FirFunctionCallChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirFunctionCall) {
            val config = context.session.kotrailConfig
            if (!config.isEnabled(KotrailRule.UNLOADABLE_CODE)) return
            val source = expression.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            val session = context.session
            val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return

            if (callee is FirConstructorSymbol && expression.resolvedType.isSubtypeOf(THREAD_LOCAL, session)) {
                // `val local: ThreadLocal<T> = ThreadLocal()` is reported once, on the property.
                if (context.containingElements.any { it is FirProperty && it.initializer === expression }) return
                reportKotrail(source, KotrailDiagnostics.THREAD_LOCAL_IN_UNLOADABLE_CODE, "ThreadLocal()")
                return
            }

            val settings = config.unloadableCode
            val name = callee.callableId?.asSingleFqName()?.asString() ?: return
            if (settings.registrations.none { Glob(it).matches(name) }) return
            val disposables = settings.disposableTypes.map { ClassId.topLevel(FqName(it)) }
            val scoped = expression.argumentList.arguments.any { argument -> disposables.any { argument.resolvedType.isSubtypeOf(it, session) } }
            if (scoped) return
            reportKotrail(source, KotrailDiagnostics.UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE, name)
        }
    }

    private fun ConeKotlinType.isSubtypeOf(classId: ClassId, session: FirSession): Boolean {
        val expanded = fullyExpandedType(session)
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return false
        val superType = classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = true)
        return !expanded.isNullableNothing && AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
    }
}
