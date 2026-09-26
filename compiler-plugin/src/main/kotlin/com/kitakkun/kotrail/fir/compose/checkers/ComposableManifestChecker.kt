@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.compose.checkers

import com.kitakkun.kotrail.fir.compose.ComposableManifest
import com.kitakkun.kotrail.fir.compose.emitsUi
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.compose.isPreview
import com.kitakkun.kotrail.fir.kotrailConfig
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isUnit

/**
 * Writes, for every file of a compilation that names a `composablesDir`, the public and internal
 * UI composables the file declares (see [ComposableManifest]). Not a rule: it reports nothing and
 * runs whatever rules are on, so that a `preview` compilation can hold `main` to
 * `compose.previewCoverage` without `main` opting in.
 */
object ComposableManifestChecker : FirFileChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val config = context.session.kotrailConfig
        val directory = config.composablesDir ?: return
        val path = declaration.sourceFile?.path ?: return
        val session = context.session
        val functions = mutableListOf<FirNamedFunction>()
        collect(declaration.declarations, functions)
        val entries = functions.mapNotNull { function ->
            val visibility = when (function.visibility) {
                Visibilities.Public -> "public"
                Visibilities.Internal -> "internal"
                else -> return@mapNotNull null
            }
            if (!function.symbol.isComposable(session) || !function.returnTypeRef.coneType.isUnit) return@mapNotNull null
            if (function.isExpect || function.symbol.isPreview(session)) return@mapNotNull null
            if (!function.symbol.emitsUi(session, config.compose.nonUiPackages)) return@mapNotNull null
            val callableId = function.symbol.callableId
            ComposableManifest.Entry(callableId.asSingleFqName().asString(), callableId.packageName.asString(), visibility)
        }
        // What this file's previews call, so that an incremental build that skips the file still counts them.
        val previewCallees = functions.filter { it.symbol.isPreview(session) }.flatMapTo(HashSet()) { function ->
            ComposablePreviewCoverageChecker.calleesOf(function).map { it.asSingleFqName().asString() }
        }
        ComposableManifest.write(directory, path, entries, previewCallees)
    }

    private fun collect(declarations: List<FirDeclaration>, into: MutableList<FirNamedFunction>) {
        for (declaration in declarations) {
            when (declaration) {
                is FirNamedFunction -> into += declaration
                is FirRegularClass -> collect(declaration.declarations, into)
                else -> {}
            }
        }
    }
}
