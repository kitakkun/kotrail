package com.kitakkun.kotrail.fir.inferred

import com.kitakkun.kotrail.compat.NamedFunctionChecker
import com.kitakkun.kotrail.fir.compose.effects.effectCaptureService
import com.kitakkun.kotrail.fir.compose.insets.windowInsetsHandlingService
import com.kitakkun.kotrail.fir.compose.locals.compositionLocalService
import com.kitakkun.kotrail.fir.concurrency.asyncWorkService
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.memory.nativeAllocationService
import com.kitakkun.kotrail.fir.preconditions.preconditionService
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol

/**
 * Not a rule: computes every inferred fact for every source declaration while FIR bodies are
 * available, so that the IR metadata writer, which runs after Fir2Ir has released them, only
 * reads cached results. Each fact is warmed only when its rule is on.
 */
object InferredFactWarmup {
    /** The facts about functions, in the order their metadata is written. */
    fun functionFacts(session: FirSession): List<InferredFactService<FirNamedFunctionSymbol, *>> = listOf(
        session.windowInsetsHandlingService,
        session.compositionLocalService,
        session.preconditionService.functions,
        session.effectCaptureService,
        session.asyncWorkService,
        session.nativeAllocationService,
    )

    object FunctionChecker : NamedFunctionChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirNamedFunction) {
            if (declaration.source?.kind is KtFakeSourceElementKind) return
            val config = context.session.kotrailConfig
            for (fact in functionFacts(context.session)) {
                if (config.isEnabled(fact.rule)) fact.warm(declaration.symbol)
            }
        }
    }

    object ClassChecker : FirRegularClassChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirRegularClass) {
            val classes = context.session.preconditionService.classes
            if (context.session.kotrailConfig.isEnabled(classes.rule)) classes.warm(declaration.symbol)
        }
    }

    object PropertyChecker : FirPropertyChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirProperty) {
            val locals = context.session.compositionLocalService
            if (!context.session.kotrailConfig.isEnabled(locals.rule)) return
            locals.isRequiredBySource(declaration.symbol)
            locals.getters.warm(declaration.symbol)
        }
    }
}
