package com.kitakkun.kotrail.fir.compose.live

import com.kitakkun.kotrail.fir.compose.effects.EffectCaptureAnalysis
import com.kitakkun.kotrail.fir.compose.effects.EffectCaptureAnalysis.unwrapArgument
import com.kitakkun.kotrail.fir.compose.isComposable
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirComponentCall
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedNamedFunctionSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.constructClassLikeType
import org.jetbrains.kotlin.fir.types.isNothingOrNullableNothing
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.text
import org.jetbrains.kotlin.types.AbstractTypeChecker

/**
 * Values that change in place while keeping their identity, and the `remember` calls that copy
 * them: shared by the `compose.liveValueRemembered` checker and by [IdentityKeyedParametersService].
 *
 * A live value is a snapshot collection (`SnapshotStateList`, `SnapshotStateMap`,
 * `SnapshotStateSet`) or a `State`. `remember` compares its keys by identity (`equals`), and its
 * calculation is not observed by the snapshot system, so a calculation that copies or derives
 * from a live value keeps what it saw first: the key, the same object every time, never changes.
 * Only `derivedStateOf { }`, whose reads are tracked, follows the contents.
 *
 * What a calculation reads is collected from the parts of its body that run while it runs: the
 * body itself and the lambdas an inline function runs in place (`filter { }`, `let { }`), not a
 * lambda kept for later (an `onClick`, `lazy { }`), `derivedStateOf { }` or `snapshotFlow { }`, an
 * anonymous object, a local class or function. The initial value handed to `mutableStateOf(...)`
 * and the other state factories, and the arguments of a constructor, are seeds taken once on
 * purpose, as `compose.rememberKeys` treats them, and are left out.
 */
internal object LiveValueAnalysis {
    /** The calls whose calculation is remembered under its keys, by fully qualified name. */
    private val REMEMBER_FUNCTIONS = setOf("androidx.compose.runtime.remember", "androidx.compose.runtime.saveable.rememberSaveable")

    /** How the Compose runtime names the key parameters of `remember` (`key1`, `keys`) and `rememberSaveable` (`inputs`). */
    private val KEY_PARAMETER = Regex("key\\d+|keys|inputs")

    private val RUNTIME = FqName("androidx.compose.runtime")
    private val SNAPSHOTS = FqName("androidx.compose.runtime.snapshots")
    private val SNAPSHOT_COLLECTIONS = listOf("SnapshotStateList", "SnapshotStateMap", "SnapshotStateSet").map { ClassId(SNAPSHOTS, Name.identifier(it)) }
    private val STATE = ClassId(RUNTIME, Name.identifier("State"))
    private val VALUE = Name.identifier("value")

    /** State factories of the runtime: what they are handed, receiver or arguments, is the initial content, taken once. */
    private val SEEDS = setOf(
        "mutableStateOf", "mutableIntStateOf", "mutableLongStateOf", "mutableFloatStateOf", "mutableDoubleStateOf",
        "mutableStateListOf", "mutableStateMapOf", "mutableStateSetOf", "toMutableStateList", "toMutableStateMap",
    )

    /** Members that read only how many elements a collection holds: a key made of them does not change when one is replaced. */
    private val SIZE_PROPERTIES = setOf("size", "lastIndex", "indices").mapTo(HashSet(), Name::identifier)

    /** The same as calls, without arguments: `count { }` with a predicate reads the elements. */
    private val SIZE_FUNCTIONS = setOf("isEmpty", "isNotEmpty", "count").mapTo(HashSet(), Name::identifier)

    /** Calls whose lambda observes what it reads. */
    private val OBSERVING = setOf("derivedStateOf", "snapshotFlow")

    /** How long a value's text may be in a message before it is cut. */
    private const val MAX_TEXT = 40

    /** How many local `val`s are followed back to what initialized them. */
    private const val MAX_ALIAS_DEPTH = 8

    /** A `remember` or `rememberSaveable` call: the expressions of its keys, and its calculation. */
    class RememberCall(val keys: List<FirExpression>, val calculation: FirAnonymousFunction)

    /**
     * A live value a calculation reads: what identifies it against the keys, what to call it, where it
     * is read, and whether only the size or emptiness of a collection is read there ([sizeOnly]).
     */
    class LiveRead(val identity: String, val name: String, val offset: Int, val sizeOnly: Boolean)

    /** [call] as a remembered calculation with its keys, or null when it is not one (or its calculation is not a literal lambda). */
    fun rememberCall(call: FirFunctionCall): RememberCall? {
        val callee = call.calleeReference.toResolvedNamedFunctionSymbol() ?: return null
        if (callee.callableId.asSingleFqName().asString() !in REMEMBER_FUNCTIONS) return null
        val calculation = (call.argumentList.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression)?.anonymousFunction ?: return null
        val keys = ArrayList<FirExpression>()
        for ((argument, parameter) in call.resolvedArgumentMapping.orEmpty()) {
            if (!KEY_PARAMETER.matches(parameter.name.asString())) continue
            val unwrapped = argument.unwrapArgument()
            if (unwrapped is FirVarargArgumentsExpression) unwrapped.arguments.mapTo(keys) { it.unwrapArgument() } else keys += unwrapped
        }
        return RememberCall(keys, calculation)
    }

    /**
     * The live values [call]'s calculation reads that its keys do not change with, one per value,
     * in source order. A key covers a value when it reads it too: `query` for a delegated `query`,
     * `queryState.value` for `queryState.value`, a read of the contents (`items.toList()`) for a
     * snapshot collection. A key that reads only the size or emptiness of a collection
     * (`items.size`, `items.isEmpty()`) covers only reads of the same: an element replaced in place
     * (`items[i] = updated`) leaves the size as it was, and a copy of the contents goes stale under
     * it. The `State` object or the collection itself as a key covers nothing: its identity does
     * not change when what it holds does. A keyless calculation that is one bare read of a `State`
     * ([isBareStateRead]) reads nothing stale: it takes the initial value.
     */
    fun staleReads(session: FirSession, call: RememberCall): List<LiveRead> {
        // `remember { state.value }` takes the value once, on purpose: the initial one.
        if (call.keys.isEmpty() && isBareStateRead(session, call.calculation)) return emptyList()
        val keyReads = call.keys.flatMap { key -> liveReads(session, Uses.of(key, root = null)) }
        val covered = keyReads.filterNot { it.sizeOnly }.mapTo(HashSet()) { it.identity }
        val sizeCovered = keyReads.filter { it.sizeOnly }.mapTo(HashSet()) { it.identity }
        return liveReads(session, Uses.of(call.calculation, root = call.calculation))
            .filter { it.identity !in covered && !(it.sizeOnly && it.identity in sizeCovered) }
            .distinctBy { it.identity }
            .sortedBy { it.offset }
    }

    /**
     * The parameters of [function], a composable, whose identity keys a remembered copy, each with
     * the composable that holds that `remember`: null when [function] does itself (a key of a
     * `remember` or `rememberSaveable` in its body is the parameter, or a local `val` initialized
     * with it, and the calculation reads its contents), or, when the parameter is handed as it is
     * to a composable whose [identityKeyedOf] names the parameter it is handed to, the holder that
     * entry names, else that composable. A `remember` of its own wins over a callee's. Only
     * parameters of a type a live value can be passed as (a collection, `Any`, a type parameter, a
     * `State`) are looked at; what a caller passes for a `String` cannot change in place.
     */
    fun identityKeyedParameters(
        session: FirSession,
        function: FirNamedFunction,
        identityKeyedOf: (FirNamedFunctionSymbol) -> Map<String, String?>,
    ): Map<String, String?> {
        val body = function.body ?: return emptyMap()
        val candidates = function.valueParameters.filter { canHoldLive(session, it.returnTypeRef.coneType) }.mapTo(HashSet()) { it.symbol }
        if (candidates.isEmpty()) return emptyMap()
        val found = HashMap<FirValueParameterSymbol, String?>()
        body.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                element.acceptChildren(this)
            }

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                val remember = rememberCall(functionCall)
                if (remember != null) {
                    val keyed = remember.keys.mapNotNullTo(HashSet()) { parameterOf(it, candidates, depth = 0) }
                    if (keyed.isNotEmpty()) {
                        for (use in Uses.of(remember.calculation, root = remember.calculation).contents) {
                            parameterOf(use, candidates, depth = 0)?.takeIf { it in keyed }?.let { found[it] = null }
                        }
                    }
                } else {
                    val callee = functionCall.calleeReference.toResolvedNamedFunctionSymbol()
                    if (callee != null && callee.isComposable(session)) {
                        val calleeKeyed = identityKeyedOf(callee)
                        for ((argument, parameter) in functionCall.resolvedArgumentMapping.orEmpty()) {
                            val name = parameter.name.asString()
                            if (name !in calleeKeyed) continue
                            val holder = calleeKeyed[name] ?: callee.name.asString()
                            parameterOf(argument, candidates, depth = 0)?.let { if (it !in found) found[it] = holder }
                        }
                    }
                }
                functionCall.acceptChildren(this)
            }
        })
        return function.valueParameters.filter { it.symbol in found }.associateTo(LinkedHashMap()) { it.name.asString() to found[it.symbol] }
    }

    /** Whether [type] is a snapshot collection or a `State`: a value that changes in place. */
    fun isLiveType(session: FirSession, type: ConeKotlinType): Boolean =
        SNAPSHOT_COLLECTIONS.any { isSubtypeOf(session, type, it) } || EffectCaptureAnalysis.isState(session, type)

    /**
     * The live value [expression] may evaluate to, or null when it holds none. That is [expression]
     * itself when it is one: by its static type, or, under a wider declared type, a local `val`
     * initialized with one (`val items: List<Tx> = remember { mutableStateListOf() }`), a
     * `remember` whose calculation returns one, or a property whose getter returns a stored live
     * property of the same class (`val history: List<Run> get() = runs`). For an `if`, a `when`,
     * an elvis or a `try` (a block by its last expression), it is the first branch that holds one:
     * `if (redact) transactions.map(::redact) else transactions` hands the live list on whenever
     * `redact` is false.
     */
    fun liveValueOf(session: FirSession, expression: FirExpression): FirExpression? = liveValue(session, expression, depth = 0) { isLiveType(session, it) }

    /** The text of [expression] on one line, cut to a length a message can carry. */
    fun shortText(expression: FirExpression): String? {
        val text = expression.source?.text?.toString()?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        return if (text.length <= MAX_TEXT) text else text.take(MAX_TEXT - 3) + "..."
    }

    /**
     * The live values among [uses]: a snapshot collection whose contents are read, a property
     * delegated to a `State`, and the `value` of a `State`, each reached from something declared
     * outside the code walked (a value created inside a calculation is fresh, not stale).
     */
    private fun liveReads(session: FirSession, uses: Uses): List<LiveRead> {
        val reads = ArrayList<LiveRead>()
        fun add(expression: FirExpression, sizeOnly: Boolean) {
            val root = rootSymbol(expression) ?: return
            if (root in uses.declaredInside) return
            val name = shortText(expression) ?: return
            reads += LiveRead(path(expression) ?: name, name, expression.source?.startOffset ?: Int.MAX_VALUE, sizeOnly)
        }
        for (expression in uses.contents) {
            if (holds(session, expression, depth = 0) { type -> SNAPSHOT_COLLECTIONS.any { isSubtypeOf(session, type, it) } }) {
                add(expression, sizeOnly = expression in uses.sizeReads)
            }
        }
        for (access in uses.properties) {
            if (isStateRead(session, access)) add(access, sizeOnly = false)
        }
        return reads
    }

    /** Whether [access] reads a property delegated to a `State` (`query`) or the `value` of a `State`. */
    private fun isStateRead(session: FirSession, access: FirPropertyAccessExpression): Boolean {
        val symbol = access.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return false
        val delegatedToState = symbol.hasDelegate && symbol.delegate?.resolvedType?.let { EffectCaptureAnalysis.isState(session, it) } == true
        val stateValue = symbol.name == VALUE && access.dispatchReceiver?.resolvedType?.let { EffectCaptureAnalysis.isState(session, it) } == true
        return delegatedToState || stateValue
    }

    /**
     * Whether [calculation] is one read of a `State` with nothing done to it: `remember { state.value }`,
     * or `remember { count }` over a delegated `count`. Without keys, that takes the initial value
     * once, on purpose; `remember { state.value.length }` derives from it and goes stale.
     */
    private fun isBareStateRead(session: FirSession, calculation: FirAnonymousFunction): Boolean {
        val statement = calculation.body?.statements?.singleOrNull() ?: return false
        val access = returnedValue(statement)?.unwrapped() as? FirPropertyAccessExpression ?: return false
        return isStateRead(session, access)
    }

    private fun holds(session: FirSession, expression: FirExpression, depth: Int, isLive: (ConeKotlinType) -> Boolean): Boolean =
        liveValue(session, expression, depth, isLive) != null

    private fun liveValue(session: FirSession, expression: FirExpression, depth: Int, isLive: (ConeKotlinType) -> Boolean): FirExpression? {
        val unwrapped = expression.unwrapped()
        val branches = branchesOf(unwrapped)
        if (branches != null && depth < MAX_ALIAS_DEPTH) {
            branches.firstNotNullOfOrNull { liveValue(session, it, depth + 1, isLive) }?.let { return it }
        }
        if (isLive(unwrapped.resolvedType)) return expression
        if (branches != null || depth >= MAX_ALIAS_DEPTH) return null
        val origin = originOf(unwrapped) ?: return null
        return expression.takeIf { holds(session, origin, depth + 1, isLive) }
    }

    /** The values an `if`, a `when`, an elvis or a `try` may evaluate to, a block's by its last expression; null for anything else. */
    private fun branchesOf(expression: FirExpression): List<FirExpression>? = when (expression) {
        is FirWhenExpression -> expression.branches.mapNotNull { resultOf(it.result) }
        is FirElvisExpression -> listOf(expression.lhs, expression.rhs)
        is FirTryExpression -> listOfNotNull(resultOf(expression.tryBlock)) + expression.catches.mapNotNull { resultOf(it.block) }
        is FirBlock -> listOfNotNull(resultOf(expression))
        else -> null
    }

    private fun resultOf(block: FirBlock): FirExpression? = block.statements.lastOrNull() as? FirExpression

    /**
     * Where the value of [expression] comes from, when it can be followed: the initializer of a
     * local `val`, what a `remember`'s calculation returns, or the stored property of the same
     * class a getter returns (one hop: that property's own getter is not followed).
     */
    private fun originOf(expression: FirExpression): FirExpression? = when (expression) {
        is FirPropertyAccessExpression -> {
            val symbol = expression.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol
            when {
                symbol == null -> null
                symbol.isLocal -> symbol.takeIf { expression.explicitReceiver == null && it.isVal && !it.hasDelegate }?.resolvedInitializer
                else -> returnedSameClassProperty(symbol)
            }
        }
        is FirFunctionCall -> rememberCall(expression)?.calculation?.body?.let(::lastValue)
        else -> null
    }

    /** The read a member property's getter returns when its body is that one read of a stored property of the same class, read through `this`. */
    @OptIn(SymbolInternals::class)
    private fun returnedSameClassProperty(symbol: FirPropertySymbol): FirExpression? {
        val owner = symbol.containingClassLookupTag()?.classId ?: return null
        val getter = symbol.getterSymbol ?: return null
        if (getter.isDefault || symbol.hasDelegate) return null
        getter.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        val read = getter.fir.body?.statements?.singleOrNull()?.let(::returnedValue)?.unwrapped() as? FirPropertyAccessExpression ?: return null
        val target = read.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return null
        if (target.containingClassLookupTag()?.classId != owner || target.getterSymbol?.isDefault == false || target.hasDelegate) return null
        return read.takeIf { read.dispatchReceiver?.unwrapped() is FirThisReceiverExpression }
    }

    /** What a lambda's body evaluates to: its last statement, a `return` looked through. */
    private fun lastValue(body: FirBlock): FirExpression? = body.statements.lastOrNull()?.let(::returnedValue)

    private fun returnedValue(statement: FirStatement): FirExpression? =
        (if (statement is FirReturnExpression) statement.result else statement) as? FirExpression

    /** The parameter among [candidates] that [expression] is, directly or through local `val`s initialized with it. */
    private fun parameterOf(expression: FirExpression, candidates: Set<FirValueParameterSymbol>, depth: Int): FirValueParameterSymbol? {
        val access = expression.unwrapped() as? FirPropertyAccessExpression ?: return null
        if (access.explicitReceiver != null) return null
        return when (val symbol = access.calleeReference.toResolvedCallableSymbol()) {
            is FirValueParameterSymbol -> symbol.takeIf { it in candidates }
            is FirPropertySymbol -> {
                if (depth >= MAX_ALIAS_DEPTH || !symbol.isLocal || !symbol.isVal || symbol.hasDelegate) return null
                symbol.resolvedInitializer?.let { parameterOf(it, candidates, depth + 1) }
            }
            else -> null
        }
    }

    /**
     * Whether a live value can be passed for a parameter of [type]: a supertype of a snapshot
     * collection or of `State` (`List`, `Collection`, `Map`, `Any`), or a type parameter.
     */
    private fun canHoldLive(session: FirSession, type: ConeKotlinType): Boolean {
        val expanded = type.fullyExpandedType(session)
        val classId = expanded.classId ?: return true
        val starred = starProjected(session, classId, nullable = true) ?: return false
        return (SNAPSHOT_COLLECTIONS + STATE).any { live ->
            val liveType = starProjected(session, live, nullable = false) ?: return@any false
            AbstractTypeChecker.isSubtypeOf(session.typeContext, liveType, starred)
        }
    }

    private fun isSubtypeOf(session: FirSession, type: ConeKotlinType, classId: ClassId): Boolean {
        val expanded = type.fullyExpandedType(session)
        if (expanded.isNothingOrNullableNothing) return false
        val superType = starProjected(session, classId, nullable = true) ?: return false
        return AbstractTypeChecker.isSubtypeOf(session.typeContext, expanded, superType)
    }

    private fun starProjected(session: FirSession, classId: ClassId, nullable: Boolean): ConeKotlinType? {
        val symbol = session.symbolProvider.getClassLikeSymbolByClassId(classId) ?: return null
        return classId.constructClassLikeType(Array(symbol.typeParameterSymbols.size) { ConeStarProjection }, isMarkedNullable = nullable)
    }

    /**
     * The symbol a chain of property reads starts from: `state` for `state.items`, `items` for
     * `items`, `this.items` and `Store.items`; null when it starts from anything but a property
     * read (a call's result).
     */
    private fun rootSymbol(expression: FirExpression): FirBasedSymbol<*>? = when (val unwrapped = expression.unwrapped()) {
        is FirPropertyAccessExpression -> when (val receiver = receiverOf(unwrapped)) {
            null, is FirThisReceiverExpression, is FirResolvedQualifier -> unwrapped.calleeReference.toResolvedCallableSymbol()
            else -> rootSymbol(receiver)
        }
        is FirSafeCallExpression -> (unwrapped.selector as? FirExpression)?.let(::rootSymbol)
        else -> null
    }

    /** A chain of property reads and calls as a dotted path, `state.items` however it is spelled (`this.state?.items`); null for anything else. */
    private fun path(expression: FirExpression): String? = when (val unwrapped = expression.unwrapped()) {
        is FirPropertyAccessExpression -> {
            val name = unwrapped.calleeReference.toResolvedCallableSymbol()?.name?.asString()
            when (val receiver = receiverOf(unwrapped)) {
                null, is FirThisReceiverExpression -> name
                is FirResolvedQualifier -> name?.let { receiver.relativeClassFqName?.asString()?.let { owner -> "$owner.$it" } }
                else -> name?.let { path(receiver)?.let { root -> "$root.$it" } }
            }
        }
        is FirFunctionCall -> {
            val name = unwrapped.calleeReference.toResolvedCallableSymbol()?.name?.asString()
            receiverOf(unwrapped)?.let { receiver -> name?.let { path(receiver)?.let { root -> "$root.$it()" } } }
        }
        is FirSafeCallExpression -> (unwrapped.selector as? FirExpression)?.let(::path)
        else -> null
    }

    /** The explicit receiver of [access], looking through the subject of a safe call to what it checks. */
    private fun receiverOf(access: FirQualifiedAccessExpression): FirExpression? {
        val receiver = access.explicitReceiver?.unwrapped() ?: return null
        return if (receiver is FirCheckedSafeCallSubject) receiver.originalReceiverRef.value.unwrapped() else receiver
    }

    private fun FirExpression.unwrapped(): FirExpression = when (this) {
        is FirSmartCastExpression -> originalExpression.unwrapped()
        is FirCheckNotNullCall -> argumentList.arguments.firstOrNull()?.unwrapped() ?: this
        else -> unwrapArgument().let { if (it === this) this else it.unwrapped() }
    }

    /**
     * What a piece of code does with values, in the parts that run while it runs: the
     * expressions whose contents it reads ([contents]: a receiver of a call or a property read,
     * which covers indexing, iteration, `in` and the calls of a `for` loop; a destructured value; a
     * spread), the properties it reads ([properties]), and what it declares itself
     * ([declaredInside]). A value handed whole as an argument is not a read of its contents: the
     * callee may only keep it. Starting from [root], the lambda being walked, or from an expression.
     */
    private class Uses private constructor(private val root: FirAnonymousFunction?) : FirVisitorVoid() {
        val contents = ArrayList<FirExpression>()

        /** The expressions in [contents] read there only for their size or emptiness (`items.size`, `items.isEmpty()`). */
        val sizeReads = HashSet<FirExpression>()
        val properties = ArrayList<FirPropertyAccessExpression>()
        val declaredInside = HashSet<FirBasedSymbol<*>>()
        private val inPlace = HashSet<FirAnonymousFunction>()

        override fun visitElement(element: FirElement) {
            element.acceptChildren(this)
        }

        override fun visitAnonymousFunction(anonymousFunction: FirAnonymousFunction) {
            // A lambda that runs later (an onClick, lazy { }) reads the value when it runs: it is current then.
            if (anonymousFunction !== root && anonymousFunction !in inPlace) return
            anonymousFunction.valueParameters.forEach { declaredInside += it.symbol }
            anonymousFunction.acceptChildren(this)
        }

        override fun visitAnonymousObject(anonymousObject: FirAnonymousObject) {}

        override fun visitRegularClass(regularClass: FirRegularClass) {}

        override fun visitNamedFunction(namedFunction: FirNamedFunction) {}

        override fun visitProperty(property: FirProperty) {
            declaredInside += property.symbol
            // `val (first, second) = items` reads `items` element by element, through a variable declared here.
            if (property.name == SpecialNames.DESTRUCT) property.initializer?.let { contents += it }
            property.acceptChildren(this)
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment) {
            // What is assigned to is not read.
            variableAssignment.rValue.accept(this)
        }

        override fun visitDesugaredAssignmentValueReferenceExpression(desugaredAssignmentValueReferenceExpression: FirDesugaredAssignmentValueReferenceExpression) {}

        override fun visitPropertyAccessExpression(propertyAccessExpression: FirPropertyAccessExpression) {
            properties += propertyAccessExpression
            propertyAccessExpression.explicitReceiver?.let {
                val receiver = it.receiverValue()
                contents += receiver
                if (propertyAccessExpression.calleeReference.name in SIZE_PROPERTIES) sizeReads += receiver
            }
            propertyAccessExpression.acceptChildren(this)
        }

        // FirVisitorVoid routes these to visitElement, not to visitFunctionCall.
        override fun visitImplicitInvokeCall(implicitInvokeCall: FirImplicitInvokeCall) = visitFunctionCall(implicitInvokeCall)

        override fun visitComponentCall(componentCall: FirComponentCall) = visitFunctionCall(componentCall)

        override fun visitFunctionCall(functionCall: FirFunctionCall) {
            val callee = functionCall.calleeReference.toResolvedCallableSymbol()
            val runtimeName = (callee as? FirNamedFunctionSymbol)?.callableId?.takeIf { it.packageName == RUNTIME }?.callableName?.asString()
            // derivedStateOf { } and snapshotFlow { } observe what they read; a state factory is handed its initial content.
            if (runtimeName in OBSERVING || runtimeName in SEEDS) return
            // The arguments of a constructor are its seeds, as in compose.rememberKeys: `remember { Holder(items) }` keeps the value itself.
            val seedsOnly = callee is FirConstructorSymbol
            functionCall.explicitReceiver?.let {
                val receiver = it.receiverValue()
                contents += receiver
                if (functionCall.calleeReference.name in SIZE_FUNCTIONS && functionCall.argumentList.arguments.isEmpty()) sizeReads += receiver
                it.accept(this)
            }
            val mapping = functionCall.resolvedArgumentMapping
            for (argument in functionCall.argumentList.arguments) {
                val unwrapped = argument.unwrapArgument()
                if (unwrapped is FirAnonymousFunctionExpression) {
                    // A lambda an inline function runs in place is part of this code; any other runs later.
                    val parameter = mapping?.get(argument)?.symbol
                    if (callee is FirNamedFunctionSymbol && callee.isInline && parameter != null && !parameter.isCrossinline && !parameter.isNoinline) {
                        inPlace += unwrapped.anonymousFunction
                        unwrapped.accept(this)
                    }
                    continue
                }
                if (seedsOnly) continue
                // A value handed whole to a call is not read here: `createPager(items)` may only keep it, as a
                // constructor does. A spread hands over the elements, which reads them.
                if (unwrapped is FirVarargArgumentsExpression) {
                    unwrapped.arguments.filterIsInstance<FirSpreadArgumentExpression>().forEach { contents += it.expression }
                }
                unwrapped.accept(this)
            }
        }

        private fun FirExpression.receiverValue(): FirExpression =
            if (this is FirCheckedSafeCallSubject) originalReceiverRef.value else this

        companion object {
            /** The uses in [element]; [root] is the lambda the walk starts from, entered whatever it is handed to. */
            fun of(element: FirElement, root: FirAnonymousFunction?): Uses = Uses(root).also { element.accept(it) }
        }
    }
}
