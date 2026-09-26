package com.kitakkun.kotrail.fir.compose

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.toAnnotationClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Compose runtime and UI names shared by every Compose rule. */
object ComposeNames {
    val COMPOSABLE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("Composable"))
    val MODIFIER = ClassId(FqName("androidx.compose.ui"), Name.identifier("Modifier"))
    val PREVIEW = ClassId(FqName("androidx.compose.ui.tooling.preview"), Name.identifier("Preview"))
    val PREVIEW_PARAMETER = ClassId(FqName("androidx.compose.ui.tooling.preview"), Name.identifier("PreviewParameter"))
    val INFERRED_EFFECT_CAPTURE = ClassId(FqName("com.kitakkun.kotrail.compose.effects"), Name.identifier("InferredEffectCapture"))
}

fun FirBasedSymbol<*>.isComposable(session: FirSession): Boolean = hasAnnotation(ComposeNames.COMPOSABLE, session)

/**
 * Whether this function is a preview: annotated with `@Preview` directly, or with a multipreview
 * annotation, which is any annotation class that itself carries `@Preview`.
 */
fun FirNamedFunctionSymbol.isPreview(session: FirSession): Boolean {
    if (hasAnnotation(ComposeNames.PREVIEW, session)) return true
    return resolvedAnnotationsWithClassIds.any { annotation ->
        annotation.toAnnotationClassLikeSymbol(session)?.hasAnnotation(ComposeNames.PREVIEW, session) == true
    }
}
