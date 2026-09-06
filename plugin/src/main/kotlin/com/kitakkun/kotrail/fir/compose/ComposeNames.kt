package com.kitakkun.kotrail.fir.compose

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Compose runtime and UI names shared by every Compose rule. */
object ComposeNames {
    val COMPOSABLE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("Composable"))
    val MODIFIER = ClassId(FqName("androidx.compose.ui"), Name.identifier("Modifier"))
    val PREVIEW = ClassId(FqName("androidx.compose.ui.tooling.preview"), Name.identifier("Preview"))
}

fun FirBasedSymbol<*>.isComposable(session: FirSession): Boolean = hasAnnotation(ComposeNames.COMPOSABLE, session)
