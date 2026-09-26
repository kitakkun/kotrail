package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.fir.checkers.CommentLengthChecker
import com.kitakkun.kotrail.fir.checkers.ParameterCommentChecker
import com.kitakkun.kotrail.fir.checkers.ForbiddenCallChecker
import com.kitakkun.kotrail.fir.checkers.FileLengthChecker
import com.kitakkun.kotrail.fir.checkers.FunctionLengthChecker
import com.kitakkun.kotrail.fir.preconditions.checkers.PreconditionChecker
import com.kitakkun.kotrail.fir.preconditions.checkers.PreconditionWarmup
import com.kitakkun.kotrail.fir.checkers.UnimplementedCodeChecker
import com.kitakkun.kotrail.fir.checkers.RequiredAnnotationChecker
import com.kitakkun.kotrail.fir.checkers.VisibilityPolicyChecker
import com.kitakkun.kotrail.fir.native.checkers.ObjCIdentityChecker
import com.kitakkun.kotrail.fir.native.checkers.ObjCThrowsChecker
import com.kitakkun.kotrail.fir.native.checkers.ObjCWeakReferenceChecker
import com.kitakkun.kotrail.fir.checkers.IgnoredExceptionChecker
import com.kitakkun.kotrail.fir.checkers.MutableCollectionInPublicApiChecker
import com.kitakkun.kotrail.fir.checkers.NoDataClassInPublicApiChecker
import com.kitakkun.kotrail.fir.checkers.NamedArgumentsChecker
import com.kitakkun.kotrail.fir.checkers.NarrowModelParametersChecker
import com.kitakkun.kotrail.fir.checkers.NoFqnReferences
import com.kitakkun.kotrail.fir.checkers.NotNullAssertionChecker
import com.kitakkun.kotrail.fir.checkers.PassThroughFunctionChecker
import com.kitakkun.kotrail.fir.checkers.PassThroughReturnChecker
import com.kitakkun.kotrail.fir.checkers.PreferExplicitBackingFieldChecker
import com.kitakkun.kotrail.fir.checkers.PreferExpressionBodyChecker
import com.kitakkun.kotrail.fir.checkers.PreferFunctionReferenceChecker
import com.kitakkun.kotrail.fir.checkers.PreferValueClassChecker
import com.kitakkun.kotrail.fir.checkers.RedundantElseChecker
import com.kitakkun.kotrail.fir.checkers.SealedWhenBranchStyleChecker
import com.kitakkun.kotrail.fir.checkers.PreferValChecker
import com.kitakkun.kotrail.fir.checkers.NarrowLocalScopeChecker
import com.kitakkun.kotrail.fir.checkers.JvmSyntheticForInternalChecker
import com.kitakkun.kotrail.fir.checkers.LiveVariableBudgetChecker
import com.kitakkun.kotrail.fir.checkers.NarrativeOrderChecker
import com.kitakkun.kotrail.fir.checkers.CatchTooBroadChecker
import com.kitakkun.kotrail.fir.checkers.DelayForCompletionChecker
import com.kitakkun.kotrail.fir.checkers.DependencyRulesChecker
import com.kitakkun.kotrail.fir.checkers.AsyncWorkRecorder
import com.kitakkun.kotrail.fir.checkers.NativeAllocationInLoopChecker
import com.kitakkun.kotrail.fir.checkers.NoLiteralLoopChecker
import com.kitakkun.kotrail.fir.checkers.ParameterOrderChecker
import com.kitakkun.kotrail.fir.checkers.RequiredSupertypeChecker
import com.kitakkun.kotrail.fir.checkers.UnretainedChecker
import com.kitakkun.kotrail.fir.checkers.WeakOnlyReferenceChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableRememberKeysChecker
import com.kitakkun.kotrail.fir.test.checkers.TestMustAssertChecker
import com.kitakkun.kotrail.fir.checkers.UnloadableCodeChecker
import com.kitakkun.kotrail.fir.checkers.EmptinessIdiomChecker
import com.kitakkun.kotrail.fir.checkers.SizeComparisonIdiomChecker
import com.kitakkun.kotrail.fir.checkers.NegationIdiomChecker
import com.kitakkun.kotrail.fir.checkers.NullOrEmptyIdiomChecker
import com.kitakkun.kotrail.fir.checkers.ChainIdiomChecker
import com.kitakkun.kotrail.fir.checkers.ConfiguredCallIdiomChecker
import com.kitakkun.kotrail.fir.checkers.ElvisIdiomChecker
import com.kitakkun.kotrail.fir.checkers.SwallowedCancellationChecker
import com.kitakkun.kotrail.fir.checkers.ElvisChainChecker
import com.kitakkun.kotrail.fir.checkers.ImplicitReceiverDepthChecker
import com.kitakkun.kotrail.fir.checkers.AmbiguousImplicitReceiverChecker
import com.kitakkun.kotrail.fir.checkers.SafeCallChainChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableModifierParameterChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableNamedCallbackArgumentsChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableNamingChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableNestingChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableManifestChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableCallbackInModelChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableGlobalMutableStateChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposablePreviewCoverageChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposablePreviewParameterChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposablePreviewRequiredChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposablesPerFileChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableHardcodedStringChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableSideEffectChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableUnstableParameterChecker
import com.kitakkun.kotrail.fir.test.checkers.TestSleepChecker
import com.kitakkun.kotrail.fir.checkers.MustBeSerializableChecker
import com.kitakkun.kotrail.fir.compose.checkers.ComposableTrailingCallbackChecker
import com.kitakkun.kotrail.fir.compose.checkers.PreferStateDelegationChecker
import com.kitakkun.kotrail.fir.compose.insets.checkers.HandlesWindowInsetsContractChecker
import com.kitakkun.kotrail.fir.compose.locals.checkers.CompositionLocalEntryPointChecker
import com.kitakkun.kotrail.fir.compose.locals.checkers.CompositionLocalPropertyWarmup
import com.kitakkun.kotrail.fir.compose.locals.checkers.CompositionLocalRootChecker
import com.kitakkun.kotrail.fir.test.checkers.TestNamingChecker
import com.kitakkun.kotrail.fir.compose.insets.checkers.WindowInsetsHandledTwiceChecker
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirAnonymousFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirCallableDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirFileChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirRegularClassChecker
import com.kitakkun.kotrail.compat.CompatDeclarationCheckers
import com.kitakkun.kotrail.compat.NamedFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBasicExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBooleanOperatorExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirCheckNotNullCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirElvisExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirEqualityOperatorCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirSafeCallExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirBlockChecker
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

object KotrailDeclarationCheckers : CompatDeclarationCheckers() {
    override val basicDeclarationCheckers: Set<FirBasicDeclarationChecker> = setOf(
        VisibilityPolicyChecker,
        RequiredAnnotationChecker,
        JvmSyntheticForInternalChecker,
        ParameterOrderChecker,
        UnretainedChecker,
    )
    override val fileCheckers: Set<FirFileChecker> = setOf(
        // First, so that a file's fix record is fresh before any rule reports on the file.
        FixRecordChecker,
        UnloadableCodeChecker.RecordChecker,
        ComposableManifestChecker,
        UnloadableCodeChecker.BundledChecker,
        DependencyRulesChecker,
        CommentLengthChecker,
        NarrativeOrderChecker.FileChecker,
        ParameterCommentChecker,
        ComposablePreviewRequiredChecker,
        ComposablePreviewCoverageChecker,
        ComposablePreviewParameterChecker,
        ComposablesPerFileChecker,
        FileLengthChecker,
    )
    override val regularClassCheckers: Set<FirRegularClassChecker> = setOf(
        PreconditionWarmup.ClassChecker,
        NarrativeOrderChecker.ClassChecker,
        RequiredSupertypeChecker,
        PreferValueClassChecker,
        NoDataClassInPublicApiChecker,
    )
    override val propertyCheckers: Set<FirPropertyChecker> = setOf(
        PreferValChecker,
        UnloadableCodeChecker.ThreadLocalChecker,
        NarrowLocalScopeChecker,
        PreferExplicitBackingFieldChecker,
        PreferStateDelegationChecker,
        CompositionLocalPropertyWarmup,
    )
    override val namedFunctionCheckersCompat: Set<NamedFunctionChecker> = setOf(
        PreconditionWarmup.FunctionChecker,
        HandlesWindowInsetsContractChecker,
        CompositionLocalRootChecker,
        ComposableNestingChecker,
        NarrowModelParametersChecker,
        PassThroughReturnChecker,
        PassThroughFunctionChecker,
        PreferExpressionBodyChecker,
        ComposableTrailingCallbackChecker,
        ComposableCallbackInModelChecker,
        ComposableNamingChecker,
        ComposableModifierParameterChecker,
        TestNamingChecker,
        TestSleepChecker,
        TestMustAssertChecker,
        FunctionLengthChecker,
        LiveVariableBudgetChecker,
        AsyncWorkRecorder,
        ComposableSideEffectChecker,
        ComposableGlobalMutableStateChecker,
        ObjCThrowsChecker,
        NativeAllocationInLoopChecker.Recorder,
        ComposableUnstableParameterChecker,
    )
    override val callableDeclarationCheckers: Set<FirCallableDeclarationChecker> = setOf(
        MutableCollectionInPublicApiChecker,
    )
    override val anonymousFunctionCheckers: Set<FirAnonymousFunctionChecker> = setOf(
        PreferFunctionReferenceChecker,
        ImplicitReceiverDepthChecker,
    )
}

object KotrailExpressionCheckers : ExpressionCheckers() {
    override val equalityOperatorCallCheckers: Set<FirEqualityOperatorCallChecker> = setOf(
        ObjCIdentityChecker,
        EmptinessIdiomChecker,
    )
    override val basicExpressionCheckers: Set<FirBasicExpressionChecker> = setOf(
        SizeComparisonIdiomChecker,
    )
    override val booleanOperatorExpressionCheckers: Set<FirBooleanOperatorExpressionChecker> = setOf(
        NullOrEmptyIdiomChecker,
    )
    override val blockCheckers: Set<FirBlockChecker> = setOf(
        NoLiteralLoopChecker.ForLoops,
    )
    override val functionCallCheckers: Set<FirFunctionCallChecker> = setOf(
        NoLiteralLoopChecker.Calls,
        ObjCWeakReferenceChecker,
        CompositionLocalEntryPointChecker,
        WindowInsetsHandledTwiceChecker,
        ForbiddenCallChecker,
        UnloadableCodeChecker.RegistrationChecker,
        NativeAllocationInLoopChecker,
        DelayForCompletionChecker,
        WeakOnlyReferenceChecker,
        ComposableRememberKeysChecker,
        PreconditionChecker,
        UnimplementedCodeChecker,
        NamedArgumentsChecker,
        ComposableNamedCallbackArgumentsChecker,
        ComposableHardcodedStringChecker,
        MustBeSerializableChecker,
        NegationIdiomChecker,
        ConfiguredCallIdiomChecker,
    )
    override val resolvedQualifierCheckers: Set<FirResolvedQualifierChecker> = setOf(
        NoFqnReferences.QualifierChecker,
    )
    override val qualifiedAccessExpressionCheckers: Set<FirQualifiedAccessExpressionChecker> = setOf(
        NoFqnReferences.CallableChecker,
        AmbiguousImplicitReceiverChecker,
        ChainIdiomChecker,
    )
    override val elvisExpressionCheckers: Set<FirElvisExpressionChecker> = setOf(
        ElvisChainChecker,
    )
    override val safeCallExpressionCheckers: Set<FirSafeCallExpressionChecker> = setOf(
        SafeCallChainChecker,
    )
    override val whenExpressionCheckers: Set<FirWhenExpressionChecker> = setOf(
        RedundantElseChecker,
        SealedWhenBranchStyleChecker,
        ElvisIdiomChecker,
    )
    override val checkNotNullCallCheckers: Set<FirCheckNotNullCallChecker> = setOf(
        NotNullAssertionChecker,
    )
    override val tryExpressionCheckers: Set<FirTryExpressionChecker> = setOf(
        CatchTooBroadChecker,
        SwallowedCancellationChecker,
        IgnoredExceptionChecker,
    )
}

object KotrailTypeCheckers : TypeCheckers() {
    override val typeRefCheckers: Set<FirTypeRefChecker> = setOf(
        NoFqnReferences.TypeChecker,
    )
}
