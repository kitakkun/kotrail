package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.fir.checkers.CommentLengthChecker
import com.kitakkun.kotrail.fir.checkers.ForbiddenCallChecker
import com.kitakkun.kotrail.fir.preconditions.checkers.PreconditionChecker
import com.kitakkun.kotrail.fir.preconditions.checkers.PreconditionWarmup
import com.kitakkun.kotrail.fir.checkers.UnimplementedCodeChecker
import com.kitakkun.kotrail.fir.checkers.IgnoredExceptionChecker
import com.kitakkun.kotrail.fir.checkers.MutableCollectionInPublicApiChecker
import com.kitakkun.kotrail.fir.checkers.NamedArgumentsChecker
import com.kitakkun.kotrail.fir.checkers.NarrowModelParametersChecker
import com.kitakkun.kotrail.fir.checkers.NoFqnReferences
import com.kitakkun.kotrail.fir.checkers.NotNullAssertionChecker
import com.kitakkun.kotrail.fir.checkers.PassThroughReturnChecker
import com.kitakkun.kotrail.fir.checkers.PreferExplicitBackingFieldChecker
import com.kitakkun.kotrail.fir.checkers.PreferExpressionBodyChecker
import com.kitakkun.kotrail.fir.checkers.PreferFunctionReferenceChecker
import com.kitakkun.kotrail.fir.checkers.PreferValueClassChecker
import com.kitakkun.kotrail.fir.checkers.RedundantElseChecker
import com.kitakkun.kotrail.fir.checkers.SwallowedCancellationChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableModifierParameterChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableNamedCallbackArgumentsChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableNamingChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableNestingChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposablePreviewRequiredChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposablesPerFileChecker
import com.kitakkun.kotrail.fir.checkers.MustBeSerializableChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableTrailingCallbackChecker
import com.kitakkun.kotrail.fir.compose.checkers.PreferStateDelegationChecker
import com.kitakkun.kotrail.fir.compose.insets.checkers.HandlesWindowInsetsContractChecker
import com.kitakkun.kotrail.fir.compose.insets.checkers.WindowInsetsHandledTwiceChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirAnonymousFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirCheckNotNullCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirResolvedQualifierChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTryExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirWhenExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.type.FirTypeRefChecker
import org.jetbrains.kotlin.fir.analysis.checkers.type.TypeCheckers
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension

class KotrailCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers: DeclarationCheckers = KotrailDeclarationCheckers
    override val expressionCheckers: ExpressionCheckers = KotrailExpressionCheckers
    override val typeCheckers: TypeCheckers = KotrailTypeCheckers
}

object KotrailDeclarationCheckers : DeclarationCheckers() {
    override val fileCheckers: Set<FirFileChecker> = setOf(
        CommentLengthChecker,
        ComposablePreviewRequiredChecker,
        ComposablesPerFileChecker,
    )
    override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(
        PreconditionWarmup.ClassChecker,
        PreferValueClassChecker,
    )
    override val propertyCheckers: Set<FirPropertyChecker> = setOf(
        PreferExplicitBackingFieldChecker,
        PreferStateDelegationChecker,
    )
    override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker> = setOf(
        PreconditionWarmup.FunctionChecker,
        HandlesWindowInsetsContractChecker,
        ComposableNestingChecker,
        NarrowModelParametersChecker,
        PassThroughReturnChecker,
        PreferExpressionBodyChecker,
        ComposableTrailingCallbackChecker,
        ComposableNamingChecker,
        ComposableModifierParameterChecker,
    )
    override val callableDeclarationCheckers: Set<FirCallableDeclarationChecker> = setOf(
        MutableCollectionInPublicApiChecker,
    )
    override val anonymousFunctionCheckers: Set<FirAnonymousFunctionChecker> = setOf(
        PreferFunctionReferenceChecker,
    )
}

object KotrailExpressionCheckers : ExpressionCheckers() {
    override val functionCallCheckers: Set<FirFunctionCallChecker> = setOf(
        WindowInsetsHandledTwiceChecker,
        ForbiddenCallChecker,
        PreconditionChecker,
        UnimplementedCodeChecker,
        NamedArgumentsChecker,
        ComposableNamedCallbackArgumentsChecker,
        MustBeSerializableChecker,
    )
    override val resolvedQualifierCheckers: Set<FirResolvedQualifierChecker> = setOf(
        NoFqnReferences.QualifierChecker,
    )
    override val qualifiedAccessExpressionCheckers: Set<FirQualifiedAccessExpressionChecker> = setOf(
        NoFqnReferences.CallableChecker,
    )
    override val whenExpressionCheckers: Set<FirWhenExpressionChecker> = setOf(
        RedundantElseChecker,
    )
    override val checkNotNullCallCheckers: Set<FirCheckNotNullCallChecker> = setOf(
        NotNullAssertionChecker,
    )
    override val tryExpressionCheckers: Set<FirTryExpressionChecker> = setOf(
        SwallowedCancellationChecker,
        IgnoredExceptionChecker,
    )
}

object KotrailTypeCheckers : TypeCheckers() {
    override val typeRefCheckers: Set<FirTypeRefChecker> = setOf(
        NoFqnReferences.TypeChecker,
    )
}
