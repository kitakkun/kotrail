package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailNameSaysType
import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.NameSaysTypeScope
import com.kitakkun.kotrail.exclude.Glob
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.ModelSurface
import com.kitakkun.kotrail.fir.compose.isComposable
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirValueParameterChecker
import org.jetbrains.kotlin.fir.containingClassLookupTag
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.declarations.utils.isConst
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.isCatchParameter
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.abbreviatedType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.lowerBoundIfFlexible
import org.jetbrains.kotlin.fir.types.renderReadable
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Reports a property or parameter whose name does not end with what it holds:
 *
 * ```kotlin
 * class NodeEditor(
 *     private val request: PerformNodeAction,   // reported: end it with 'Action', as in 'requestAction'
 *     private val repo: UserRepository,         // reported: end it with 'Repository', as in 'userRepository'
 * ) {
 *     private val pending = Channel<Request>()  // reported: end it with 'Channel', as in 'pendingChannel'
 * }
 * ```
 *
 * A name is a role followed by what the value is: the role is the author's to choose, and the
 * name ends with the head noun of the type, the last word of its name (`requestedAction` for a
 * `PerformNodeAction`, `titleStyle` for a `TextStyle`). A reader does not always have the type at
 * hand: in a diff, a review, a search result or code an assistant wrote, the last word of the name
 * is what says what the value is. The words a name may end with, and how they are found, are in
 * [TypeWords].
 *
 * Value types (`valueTypes`, subtypes of `valueSupertypes`, function types, objects), types
 * without a head noun (`Runnable`), containers of them and type parameters are left alone, as are
 * names on the `exempt` list, overrides, `actual` declarations, `const` properties, the members of
 * a token table (two or more properties of one type, keys of a vocabulary like
 * `Typography.titleLarge`), a companion object's instances of the class it belongs to
 * (`TextStyles.Default`), the properties and constructor parameters of a `@Serializable` class,
 * a composition local named `LocalX`, a composable's `modifier` and `content`, and test source
 * sets.
 *
 * The default scope is member properties and the value parameters of constructors and member or
 * top-level functions; `all` adds top-level properties, locals, local functions' and lambdas'
 * parameters. `publicParameters: false` leaves out the parameters of public and protected
 * functions and constructors, which callers may name at the call site. There is no fix: the role
 * word (`requestedAction` or `requestAction`) is the author's choice.
 */
object NameSaysTypeChecker {
    object Properties : FirPropertyChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirProperty) {
            val config = context.session.kotrailConfig
            if (!config.isEnabled(KotrailRule.NAME_SAYS_TYPE)) return
            val source = declaration.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            if (declaration.isOverride || declaration.isActual) return
            if (declaration.isCatchParameter == true) return
            // A constant is named for its role.
            if (declaration.isConst) return
            val settings = config.nameSaysType
            val member = !declaration.isLocal && declaration.dispatchReceiverType != null
            if (!member && settings.scope != NameSaysTypeScope.ALL) return
            val owner = context.containingDeclarations.lastOrNull() as? FirClassSymbol<*>
            if (member && owner != null && (owner.isSerializable(context.session) || owner.isTokenTable(context.session))) return
            val type = (declaration.returnTypeRef as? FirResolvedTypeRef)?.coneType ?: return
            if (member && owner != null && owner.isCompanionHoldingOwner(type, settings, context.session)) return
            checkName(source, declaration.name, type, settings)
        }
    }

    object Parameters : FirValueParameterChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirValueParameter) {
            val config = context.session.kotrailConfig
            if (!config.isEnabled(KotrailRule.NAME_SAYS_TYPE)) return
            val source = declaration.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            val settings = config.nameSaysType
            val owner = declaration.containingDeclarationSymbol
            if (!isSubject(owner, declaration.name, settings.scope, context.session)) return
            if (!settings.publicParameters && isPublicApi(owner)) return
            val type = (declaration.returnTypeRef as? FirResolvedTypeRef)?.coneType ?: return
            checkName(source, declaration.name, type, settings)
        }

        /**
         * Whether [owner] is a function or constructor whose parameter names are public API: its
         * effective visibility is public or protected, so callers outside the module may pass
         * arguments by name, and a rename breaks them.
         */
        private fun isPublicApi(owner: FirBasedSymbol<*>): Boolean = when (owner) {
            is FirConstructorSymbol -> owner.effectiveVisibility.publicApi
            is FirNamedFunctionSymbol -> owner.effectiveVisibility.publicApi
            else -> false
        }

        /**
         * Parameters of constructors and of named functions whose names are not fixed elsewhere;
         * lambdas and local functions under `all`. A `@Serializable` class's constructor mirrors a
         * schema, whose names are not the code's to choose, and a token table's constructor
         * properties are its vocabulary, as are the parameters of its functions that carry a
         * property's name (`copy(title: TextStyle = this.title)`).
         */
        private fun isSubject(owner: FirBasedSymbol<*>, name: Name, scope: NameSaysTypeScope, session: FirSession): Boolean = when (owner) {
            is FirConstructorSymbol -> {
                val owningClass = owner.containingClassLookupTag()?.toRegularClassSymbol(session)
                when {
                    owner.isActual -> false
                    owningClass == null -> true
                    owningClass.isSerializable(session) -> false
                    else -> !owningClass.isTokenTableKey(name, session)
                }
            }
            is FirNamedFunctionSymbol -> when {
                owner.isOverride || owner.isActual -> false
                name.asString() in COMPOSABLE_SLOTS && owner.isComposable(session) -> false
                owner.isLocal -> scope == NameSaysTypeScope.ALL
                else -> owner.containingClassLookupTag()?.toRegularClassSymbol(session)?.isTokenTableKey(name, session) != true
            }
            is FirAnonymousFunctionSymbol -> scope == NameSaysTypeScope.ALL
            else -> false
        }
    }

    /** A composable's `modifier` and `content` are named by the Compose conventions, not by their type. */
    private val COMPOSABLE_SLOTS = setOf("modifier", "content")

    private val SERIALIZABLE = ClassId(FqName("kotlinx.serialization"), Name.identifier("Serializable"))

    /** A data class that mirrors a serialized schema, whose names follow the schema. */
    private fun FirClassSymbol<*>.isSerializable(session: FirSession): Boolean = hasAnnotation(SERIALIZABLE, session)

    /** The fewest properties of one type that make a class a token table rather than a coincidence. */
    private const val TOKEN_TABLE_SIZE = 2

    /**
     * Whether this class is a token table: [TOKEN_TABLE_SIZE] or more properties, all of one type,
     * such as a design system's `Typography(titleLarge: TextStyle, titleMedium: TextStyle,
     * bodyMedium: TextStyle)`, `Shapes(small: Shape, medium: Shape, large: Shape)` or an
     * `object ColorSchemes { val Light: ColorScheme; val Dark: ColorScheme }`. Its property names
     * are keys of a vocabulary, read as `typography.titleLarge` like an enum entry, and the type is
     * said once by the class.
     */
    private fun FirClassSymbol<*>.isTokenTable(session: FirSession): Boolean {
        if (this !is FirRegularClassSymbol) return false
        val types = ModelSurface.properties(this).map { TypeWords.identity(it.resolvedReturnType, session) }
        return types.size >= TOKEN_TABLE_SIZE && types.distinct().size == 1
    }

    /**
     * Whether this is a companion object and [type] holds instances of the class it belongs to:
     * the class itself, nullable or not, or a container of it. Such a property is read with the
     * class's name in front (`TextStyles.Default`, `Rules.None`, `ColorSchemeId.BuiltIns`), which
     * says what the value is.
     */
    private fun FirClassSymbol<*>.isCompanionHoldingOwner(type: ConeKotlinType, settings: KotrailNameSaysType, session: FirSession): Boolean {
        if (!isCompanion) return false
        val ownerId = classId.outerClassId ?: return false
        return TypeWords.holdsOnly(type, ownerId, settings, session)
    }

    /** Whether [name] is a key of this class as a token table: the name of one of its properties. */
    private fun FirRegularClassSymbol.isTokenTableKey(name: Name, session: FirSession): Boolean =
        isTokenTable(session) && ModelSurface.properties(this).any { it.name == name }

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun checkName(source: KtSourceElement, name: Name, type: ConeKotlinType, settings: KotrailNameSaysType) {
        if (name.isSpecial) return
        val written = name.asString()
        if (written == "_" || settings.exempt.any { it.matches(written) || it.matches(written.trimStart('_')) }) return
        val path = context.containingFileSymbol?.sourceFile?.path
        if (path != null && isTestSourcePath(path)) return
        if (TypeWords.isCompositionLocal(written, type, context.session)) return
        val expected = TypeWords.expected(type, settings, context.session) ?: return
        if (TypeWords.endsWithHeadNoun(written, expected, settings)) return
        reportKotrail(source, KotrailDiagnostics.NAME_HIDES_TYPE, written, expected.rendered, TypeWords.advice(written, expected, settings))
    }
}

/**
 * The words a name may end with for a type, and the advice the message gives. Kept apart from the
 * checker so that the word rules read in one place.
 *
 * Words are split at camelCase humps, acronyms, digits and underscores and compared
 * case-insensitively, a plural matching its singular. A name passes when its last word with a
 * letter, once a leading underscore and a trailing qualifier (`nameSuffixes`: `OrNull`) are
 * dropped, is one of the type's head nouns, read either as written or before a trailing phrase
 * opened by `By`, `For`, `Of` and the like (`users` in `usersById`, `user` in `idToUser`):
 * - the last word of the class's simple name, with a meaningless suffix (`typeSuffixes`: `Impl`,
 *   `Values`) dropped or kept and a trailing phrase cut (`root` for `ViewRootForTest`):
 *   `repository` for `UserRepositoryImpl`, `padding` or `values` for `PaddingValues`;
 * - that of an outer class (`result` for `NegotiationResult.Success`) and of the type alias the
 *   type is written with;
 * - for a container (`containers`, or a subtype of one), that of the container, of the listed
 *   container it extends (`deferred` for a `CompletableDeferred`) or of any element, nested
 *   containers walked to the innermost elements: `requestChannel` or `requests` for a
 *   `Channel<Request>`.
 *
 * A supertype's head noun is not the type's: `request: PerformNodeAction` says a request, though
 * the action is a `NodeRequest`, and `executor: ExecutorService` is asked for `executorService`.
 *
 * A name that is the type's simple name (`jmdns: JmDNS`), or whose last word spells out the type's
 * own head noun (`libraries: Libs`), passes too. A type whose last word is an adjective in `-able`
 * or `-ible` (`Runnable`, `AutoCloseable`) has no head noun and asks nothing.
 */
internal object TypeWords {
    /**
     * What a type asks of a name. [accepted]: the lower-case head nouns a name may end with.
     * [shown]: the words of the type name the message shows, as written and without a meaningless
     * suffix (`User`, `Repository` for `UserRepositoryImpl`); the last is the head noun the message
     * asks for. [element]: for a container, the words of the name of what it holds (`Request` in
     * `Channel<Request>`), whose plural the message asks a plural name for; `null` otherwise.
     * [ownNames]: the lower-case simple names of the type and of the alias it is written with, which
     * a name may be as a whole (`jmdns: JmDNS`). [ownHead]: the singular of the type's own head noun
     * (`lib` for `Libs`), which the name's last word may spell out (`libraries`); `null` for a
     * container, whose head noun (`list`, `set`) begins too many unrelated words.
     */
    class Expected(
        val rendered: String,
        val accepted: Set<String>,
        val shown: List<String>,
        val element: List<String>?,
        val ownNames: Set<String>,
        val ownHead: String?,
    )

    /** A container's element: its class, aliases expanded, the words the message shows, and the head nouns it accepts. */
    private class Element(val classId: ClassId, val shown: List<String>, val nouns: Set<String>)

    private val BOUNDARY = Regex("_+|(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")

    /** The words of [name] as written, split at underscores, camelCase humps, acronyms and digits; leading underscores dropped. */
    private fun split(name: String): List<String> = name.trimStart('_').split(BOUNDARY).filter { it.isNotEmpty() }

    /** The lower-case words of [name]. */
    private fun words(name: String): List<String> = split(name).map { it.lowercase() }

    /** [words] without trailing [suffixes] (each a run of words such as `OrNull`), as long as a word is left. */
    private fun withoutSuffixes(words: List<String>, suffixes: List<String>): List<String> {
        val suffixWords = suffixes.map(::words).filter { it.isNotEmpty() }
        var rest = words
        while (true) {
            val suffix = suffixWords.firstOrNull { suffix ->
                rest.size > suffix.size && rest.takeLast(suffix.size).map { it.lowercase() } == suffix
            } ?: return rest
            rest = rest.dropLast(suffix.size)
        }
    }

    /** The words of a class's simple name that have a letter, as written, without a meaningless suffix: `Padding` for `PaddingValues`. */
    private fun shownWords(simpleName: String, settings: KotrailNameSaysType): List<String> =
        withoutSuffixes(typeNameWords(simpleName), settings.typeSuffixes)

    /**
     * The head nouns of a simple name: its last word with a letter, and the last one left once a
     * meaningless suffix is dropped, a trailing phrase cut first (`root` for `ViewRootForTest`).
     */
    private fun headNouns(simpleName: String, settings: KotrailNameSaysType): Set<String> {
        val written = typeNameWords(simpleName)
        val head = withoutSuffixes(written, settings.typeSuffixes).lastOrNull()
        return listOfNotNull(written.lastOrNull(), head).mapTo(mutableSetOf()) { it.lowercase() }
    }

    /**
     * The words of a type's simple name that have a letter, up to a trailing phrase: `View`, `Root`
     * for `ViewRootForTest`. Digits between two words belong to the one before (`X509`,
     * `Certificate` for `X509Certificate`); digits at the end are dropped.
     */
    private fun typeNameWords(simpleName: String): List<String> {
        val written = split(simpleName)
        val words = mutableListOf<String>()
        for ((index, word) in written.withIndex()) {
            when {
                word.any(Char::isLetter) -> words += word
                words.isNotEmpty() && index < written.lastIndex -> words[words.lastIndex] = words.last() + word
            }
        }
        return withoutPhrase(words, TYPE_PHRASE_OPENERS)
    }

    /**
     * Words that open a trailing phrase in a name, which qualifies the word before it: `usersById`
     * is users, `rootForTest` a root.
     */
    private val NAME_PHRASE_OPENERS = setOf("by", "for", "of", "per", "from", "to", "with", "in", "on", "at")

    /**
     * Those that open one in a type name (`ViewRootForTest` is a root). `In`, `On` and `At` are left
     * out: in a type name they mostly end a phrasal verb (`SignInRequest`, `AddOnInfo`).
     */
    private val TYPE_PHRASE_OPENERS = setOf("by", "for", "of", "per", "from", "to", "with")

    /** [words] up to a trailing phrase: a word in [openers] that is neither the first word nor the last. */
    private fun withoutPhrase(words: List<String>, openers: Set<String>): List<String> {
        val at = (1 until words.size - 1).firstOrNull { index -> words[index].lowercase() in openers } ?: return words
        return words.subList(0, at)
    }

    /**
     * The head nouns of a class: those of every segment of [classId]'s relative name, its own and
     * its outer classes' (`success` and `result` for `NegotiationResult.Success`).
     */
    private fun segmentNouns(classId: ClassId, settings: KotrailNameSaysType): Set<String> =
        classId.relativeClassName.pathSegments().flatMapTo(mutableSetOf()) { headNouns(it.asString(), settings) }

    /**
     * Whether [name] says what [expected] is: its last word the rule reads is one of the head nouns,
     * or spells out the type's own (`libraries` for `Libs`), or the name is the type's simple name.
     */
    fun endsWithHeadNoun(name: String, expected: Expected, settings: KotrailNameSaysType): Boolean {
        if (name.trimStart('_').lowercase() in expected.ownNames) return true
        val words = withoutSuffixes(split(name), settings.nameSuffixes)
        // The word before a trailing phrase (`users` in `usersById`), and the last word as written (`user` in `idToUser`).
        val lastWords = listOf(withoutPhrase(words, NAME_PHRASE_OPENERS), words)
            .mapNotNull { candidate -> candidate.lastOrNull { word -> word.any(Char::isLetter) }?.lowercase() }
        if (lastWords.isEmpty()) return true
        val ownHead = expected.ownHead
        return lastWords.any { last ->
            expected.accepted.any { sameWord(it, last) } ||
                (ownHead != null && last.startsWith(ownHead) && last.length - ownHead.length >= SPELLED_OUT_REST_LENGTH)
        }
    }

    /** The fewest letters of a type's head noun that a name's last word may spell out: `lib` for `Libs`. */
    private const val SPELLED_OUT_HEAD_LENGTH = 3

    /**
     * The fewest letters a name's last word adds to the type's head noun it spells out: `raries`
     * after `lib`. A word one or two letters longer is another word (`locale` for `Local`).
     */
    private const val SPELLED_OUT_REST_LENGTH = 3

    /** The singular of a lower-case head noun, when it is long enough to be spelled out in a name: `lib` for `libs`. */
    private fun spelledOutHead(head: String): String? {
        val singular = when {
            !looksPlural(head) -> head
            head.endsWith("ies") -> head.dropLast(3) + "y"
            else -> head.dropLast(1)
        }
        return singular.takeIf { it.length >= SPELLED_OUT_HEAD_LENGTH }
    }

    /**
     * Whether a word is an adjective in `-able` or `-ible` (`Runnable`, `Closeable`, `Transferable`),
     * which says what a value can do rather than what it is. A stem of three letters or more keeps
     * `Table` and `Cable` nouns; a few longer nouns are listed.
     */
    private fun isAdjective(word: String): Boolean {
        val lower = word.lowercase()
        if (!lower.endsWith("able") && !lower.endsWith("ible")) return false
        return lower.length - "able".length >= 3 && lower !in NOUNS_IN_ABLE
    }

    private val NOUNS_IN_ABLE = setOf(
        "variable", "vegetable", "syllable", "constable", "timetable", "turntable", "receivable", "deliverable", "collectible",
    )

    private val COMPOSITION_LOCAL = ClassId(FqName("androidx.compose.runtime"), Name.identifier("CompositionLocal"))

    /**
     * Whether [name] is a composition local named by the Compose convention: `Local` as its first
     * word (`LocalAppLocale`) and a subtype of `CompositionLocal` for type.
     */
    fun isCompositionLocal(name: String, type: ConeKotlinType, session: FirSession): Boolean {
        val words = split(name)
        if (words.size < 2 || words.first() != "Local") return false
        val target = classOf(type.lowerBoundIfFlexible(), session) ?: return false
        if (target.lookupTag.classId == COMPOSITION_LOCAL) return true
        val symbol = target.lookupTag.toRegularClassSymbol(session) ?: return false
        return lookupSuperTypes(symbol, lookupInterfaces = true, deep = true, useSiteSession = session)
            .any { it.lookupTag.classId == COMPOSITION_LOCAL }
    }

    /** Whether two words are the same, one perhaps the plural of the other (`s`, `es`, `y` to `ies`, and a few irregular ones). */
    private fun sameWord(a: String, b: String): Boolean = a == b || isPluralOf(a, b) || isPluralOf(b, a)

    private fun isPluralOf(plural: String, singular: String): Boolean =
        plural == singular + "s" || plural == singular + "es" ||
            (singular.endsWith("y") && plural == singular.dropLast(1) + "ies") ||
            IRREGULAR_PLURALS[singular] == plural

    private val IRREGULAR_PLURALS = mapOf("child" to "children", "person" to "people", "leaf" to "leaves", "index" to "indices")

    /** Whether a lower-case word reads as a plural: an irregular one, or one ending in `s` but not in `ss`, `us` or `is`. */
    private fun looksPlural(word: String): Boolean =
        word in IRREGULAR_PLURALS.values ||
            (word.length > 2 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is"))

    /** Words that are not counted, and so have no plural of their own: `Data`, `Info`. */
    private val MASS_NOUNS = setOf(
        "data", "metadata", "info", "information", "media", "feedback", "news", "equipment", "series", "species",
        "software", "hardware", "firmware", "middleware", "progress", "evidence",
    )

    /**
     * The plural of a word as written: `Nodes`, `Entries`, `Boxes`, `Children`. A word that reads as
     * a plural already (`Coordinates`, `Tokens`) or is not counted (`Data`) is left as it is.
     */
    private fun pluralOf(word: String): String {
        val lower = word.lowercase()
        val irregular = IRREGULAR_PLURALS[lower]
        return when {
            looksPlural(lower) || lower in MASS_NOUNS -> word
            irregular != null -> "${word.first()}${irregular.drop(1)}"
            lower.length > 1 && lower.endsWith("y") && lower[lower.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
            listOf("s", "x", "z", "ch", "sh").any { lower.endsWith(it) } -> word + "es"
            else -> word + "s"
        }
    }

    /** Hard keywords an example can turn into (`class`, `object`), which cannot be a name. */
    private val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
        "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    /** A word of a name as written, and the offsets in the name where it starts and ends. */
    private class Token(val text: String, val start: Int, val end: Int)

    /** The words of [name] (leading underscores already dropped), split as [split] splits them, with where each one is. */
    private fun tokens(name: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var at = 0
        for (word in split(name)) {
            val start = name.indexOf(word, at)
            tokens += Token(word, start, start + word.length)
            at = start + word.length
        }
        return tokens
    }

    /** How a name is written, which an example follows. */
    private enum class Style { SCREAMING, SNAKE, CAMEL, PASCAL }

    private fun styleOf(name: String): Style = when {
        name.count(Char::isLetter) > 1 && name.none(Char::isLowerCase) -> Style.SCREAMING
        '_' in name && name.none(Char::isUpperCase) -> Style.SNAKE
        name.first().isUpperCase() -> Style.PASCAL
        else -> Style.CAMEL
    }

    /**
     * An example name: the words of the name from [keptFrom] until [keptTo], kept as written, then
     * [added], words of the type to spell in the name's style.
     */
    private class Example(val keptFrom: Int, val keptTo: Int, val added: List<String>)

    /**
     * The message's advice for [name]: the head noun to end it with and an example, as in
     * `'Action', as in 'requestAction'`. A plural name of a container is asked for the element's
     * plural alone (`'Nodes'`): the singular of its role is not the rule's to guess.
     *
     * The example is built from the role words, those before a trailing qualifier (`OrNull`), a
     * trailing phrase (`ById`) and a trailing number (`1`); the role words it keeps are copied as
     * written (`SHA256_HEX`), and what followed them comes back after the type's words
     * (`cacheMapById`, `stateModel1`).
     */
    fun advice(name: String, expected: Expected, settings: KotrailNameSaysType): String {
        val head = expected.shown.last().replaceFirstChar(Char::uppercaseChar)
        val original = name.trimStart('_')
        val tokens = tokens(original)
        val words = tokens.map { it.text }
        val core = withoutPhrase(withoutSuffixes(words, settings.nameSuffixes), NAME_PHRASE_OPENERS)
        val roleSize = core.indexOfLast { word -> word.any(Char::isLetter) } + 1
        if (roleSize == 0) return "'$head'"
        val role = core.subList(0, roleSize)
        val element = expected.element
        if (element != null && looksPlural(role.last().lowercase())) {
            return "'${pluralOf(element.last()).replaceFirstChar(Char::uppercaseChar)}'"
        }
        val style = styleOf(original)
        val example = example(role, expected, style)
        val kept = if (example.keptTo > example.keptFrom) original.substring(tokens[example.keptFrom].start, tokens[example.keptTo - 1].end) else ""
        val rest = original.substring(tokens[roleSize - 1].end)
        val spelled = kept + spelledAdded(example.added, style, keptBefore = kept.isNotEmpty()) + rest
        val safe = if (spelled in KEYWORDS) (if (spelled.first() in "aeiou") "an" else "a") + spelled.replaceFirstChar(Char::uppercaseChar) else spelled
        return "'$head', as in '${name.takeWhile { it == '_' }}$safe'"
    }

    /**
     * The example for the role words [role]:
     * - a SCREAMING name that starts with the head noun (`KEY_SERVER_PORT` for a `Key`): the rest
     *   of the name, then the head noun (`SERVER_PORT_KEY`);
     * - ending with a word of the type before its head noun, the type's words from there on
     *   (`processes` for a `ProcessTable`: `processTable`);
     * - ending with an abbreviation of the type's name from one of its words on (`repo`, `vm`,
     *   `cfg`, `tx`; see [abbreviates]): the type's words from there on, or the whole type name for
     *   a one-word name (`userRepository`, `transaction`, and `requestChannel` for a container);
     * - otherwise the role words followed by the head noun (`requestAction`).
     */
    private fun example(role: List<String>, expected: Expected, style: Style): Example {
        val last = role.last().lowercase()
        val typeWords = expected.shown.map { it.lowercase() }
        if (style == Style.SCREAMING && role.size > 1 && sameWord(role.first().lowercase(), typeWords.last())) {
            return Example(1, role.size, listOf(expected.shown.last()))
        }
        val sameAt = typeWords.dropLast(1).indexOfFirst { sameWord(it, last) }
        if (sameAt >= 0) return Example(0, role.size - 1, expected.shown.drop(sameAt))
        val abbreviatedFrom = typeWords.indices.firstOrNull { from -> abbreviates(last, typeWords.drop(from)) }
        if (abbreviatedFrom != null) {
            return if (role.size > 1) {
                Example(0, role.size - 1, expected.shown.drop(abbreviatedFrom))
            } else {
                Example(0, 0, expected.element.orEmpty() + expected.shown)
            }
        }
        return Example(0, role.size, listOf(expected.shown.last()))
    }

    private const val CONSONANTS = "bcdfghjklmnpqrstvwxz"

    /**
     * Whether the lower-case [short] abbreviates the run of lower-case [words]: shorter than them,
     * starting with the first letter of the first word, and either
     * - pieces in order, each the first letter of the next word followed by some of that word's
     *   later letters in order: `repo` (repository), `cert` (certificate), `cfg` (config), `vm`
     *   (view model), `r` (reply);
     * - or that first letter followed by consonants alone: `tx` (transaction), `ctx` (context).
     *
     * A word whose letters merely appear somewhere in the run is neither: `none` for network
     * redaction rules.
     */
    private fun abbreviates(short: String, words: List<String>): Boolean {
        if (short.isEmpty() || words.isEmpty() || short.length >= words.sumOf { it.length }) return false
        if (short.first() != words.first().first()) return false
        if (short.length >= 2 && short.drop(1).all { it in CONSONANTS }) return true
        return readsAsPieces(short, 0, words, 0)
    }

    /** Whether [short] from [at] on reads as pieces of [words] from [word] on, as [abbreviates] describes them. */
    private fun readsAsPieces(short: String, at: Int, words: List<String>, word: Int): Boolean {
        if (at == short.length) return true
        if (word == words.size || short[at] != words[word].first()) return false
        val text = words[word]
        var position = 1
        var taken = at + 1
        while (true) {
            if (readsAsPieces(short, taken, words, word + 1)) return true
            if (taken == short.length) return false
            val found = text.indexOf(short[taken], position)
            if (found < 0) return false
            position = found + 1
            taken++
        }
    }

    /**
     * Type words added to an example, spelled in the name's [style]: joined by underscores in a
     * SCREAMING or snake name, capitalized in camelCase, where an acronym of three letters or more
     * is written as a word (`pathUrl`). [keptBefore]: whether words of the name come before them.
     */
    private fun spelledAdded(words: List<String>, style: Style, keptBefore: Boolean): String {
        if (words.isEmpty()) return ""
        val separator = if (keptBefore) "_" else ""
        return when (style) {
            Style.SCREAMING -> separator + words.joinToString("_") { it.uppercase() }
            Style.SNAKE -> separator + words.joinToString("_") { it.lowercase() }
            Style.CAMEL, Style.PASCAL -> words.mapIndexed { index, word ->
                if (index == 0 && !keptBefore && style == Style.CAMEL) decapitalized(word) else capitalized(word)
            }.joinToString("")
        }
    }

    /** A word capitalized for camelCase: `Style`, and `Url` for the acronym `URL`; a two-letter acronym stays (`IO`). */
    private fun capitalized(word: String): String =
        if (word.count(Char::isLetter) >= 3 && word.none(Char::isLowerCase)) word.first() + word.drop(1).lowercase() else word.replaceFirstChar(Char::uppercaseChar)

    /** `user` for `User`, `url` for `URL`, `urlSession` for `URLSession`. */
    private fun decapitalized(name: String): String {
        val upper = name.takeWhile(Char::isUpperCase).length
        return when {
            upper == 0 -> name
            upper == name.length -> name.lowercase()
            upper == 1 -> name.substring(0, 1).lowercase() + name.substring(1)
            else -> name.substring(0, upper - 1).lowercase() + name.substring(upper - 1)
        }
    }

    /**
     * What [type] asks of a name, or `null` when it asks nothing: a value type, a container of
     * value types, a type parameter, or a type that cannot be named (anonymous, unresolved).
     */
    fun expected(type: ConeKotlinType, settings: KotrailNameSaysType, session: FirSession): Expected? {
        val written = type.lowerBoundIfFlexible()
        val target = classOf(written, session) ?: return null
        if (asksNothing(target, settings, session)) return null
        val targetId = target.lookupTag.classId
        // A type alias is the name the reader sees: its head nouns count besides the expansion's, and the message shows it.
        val aliasId = aliasOf(written, target)
        if (aliasId != null && settings.valueTypes.matches(aliasId.asSingleFqName().asString())) return null
        // A platform alias (`ArrayList` for `java.util.ArrayList`) only moves the class to another package.
        val ownAlias = aliasId?.takeIf { it.shortClassName != targetId.shortClassName }
        val aliasNouns = ownAlias?.let { segmentNouns(it, settings) }.orEmpty()
        val aliasShown = ownAlias?.let { shownWords(it.shortClassName.asString(), settings) }?.takeIf { it.isNotEmpty() }
        val rendered = render(written)
        val ownNames = listOfNotNull(targetId, ownAlias).mapTo(mutableSetOf()) { it.shortClassName.asString().lowercase() }

        if (isContainer(target, settings, session)) {
            val containerNouns = mutableSetOf<String>()
            val elements = mutableListOf<Element>()
            collectElements(target, settings, session, containerNouns, elements)
            val element = elements.lastOrNull() ?: return null
            val accepted = containerNouns + elements.flatMap { it.nouns } + aliasNouns
            if (aliasShown != null) return Expected(rendered, accepted, aliasShown, element = null, ownNames, ownHead = null)
            val shown = shownWords(targetId.shortClassName.asString(), settings).takeIf { it.isNotEmpty() } ?: return null
            return Expected(rendered, accepted, shown, element.shown, ownNames, ownHead = null)
        }

        val shown = aliasShown ?: shownWords(targetId.shortClassName.asString(), settings).takeIf { it.isNotEmpty() } ?: return null
        // `Runnable`, `AutoCloseable`: what a value can do is not a word a name can end with.
        if (isAdjective(shown.last())) return null
        val ownHead = spelledOutHead(shown.last().lowercase())
        return Expected(rendered, segmentNouns(targetId, settings) + aliasNouns, shown, element = null, ownNames, ownHead)
    }

    /**
     * Whether [type] holds instances of [classId] and of nothing else a name is asked about: the
     * class itself, nullable or not and aliases expanded, or a container whose elements are all
     * that class (`List<ColorSchemeId>`).
     */
    fun holdsOnly(type: ConeKotlinType, classId: ClassId, settings: KotrailNameSaysType, session: FirSession): Boolean {
        val target = classOf(type.lowerBoundIfFlexible(), session) ?: return false
        if (target.lookupTag.classId == classId) return true
        if (!isContainer(target, settings, session)) return false
        val elements = mutableListOf<Element>()
        collectElements(target, settings, session, mutableSetOf(), elements)
        return elements.isNotEmpty() && elements.all { it.classId == classId }
    }

    /** The alias [written] is spelled with, when it stands for another class than [target]. */
    private fun aliasOf(written: ConeKotlinType, target: ConeClassLikeType): ClassId? {
        val abbreviation = written.abbreviatedType?.lowerBoundIfFlexible() as? ConeClassLikeType ?: return null
        return abbreviation.lookupTag.classId.takeIf { it != target.lookupTag.classId }
    }

    /**
     * Whether a value of [type] carries nothing a name must repeat: a value type by its name
     * (`valueTypes`) or by a supertype (`valueSupertypes`: `error: IOException`), a function type of
     * any kind (`@Composable () -> Unit` included, which the Compose compiler gives a kind of its
     * own), or an object, which is its own single value (`json: Json.Default`).
     */
    private fun asksNothing(type: ConeClassLikeType, settings: KotrailNameSaysType, session: FirSession): Boolean {
        val classId = type.lookupTag.classId
        if (settings.valueTypes.matches(classId.asSingleFqName().asString())) return true
        if (type.isSomeFunctionType(session)) return true
        val symbol = type.lookupTag.toRegularClassSymbol(session) ?: return false
        if (symbol.classKind == ClassKind.OBJECT) return true
        if (settings.valueSupertypes.isEmpty()) return false
        val supertypes = lookupSuperTypes(symbol, lookupInterfaces = true, deep = true, useSiteSession = session).map { it.lookupTag.classId }
        return (supertypes + classId).any { settings.valueSupertypes.matches(it.asSingleFqName().asString()) }
    }

    /**
     * Whether [type] is a generic container with type arguments: a `containers` entry, or a subtype
     * of one (`CompletableDeferred` is a `Deferred`). A listed type without type arguments is a
     * class like any other.
     */
    private fun isContainer(type: ConeClassLikeType, settings: KotrailNameSaysType, session: FirSession): Boolean {
        if (type.typeArguments.isEmpty()) return false
        if (settings.containers.matches(type.lookupTag.classId.asSingleFqName().asString())) return true
        return containerSupertypes(type, settings, session).isNotEmpty()
    }

    /** The supertypes of [type] that are `containers` entries. */
    private fun containerSupertypes(type: ConeClassLikeType, settings: KotrailNameSaysType, session: FirSession): List<ClassId> {
        val symbol = type.lookupTag.toRegularClassSymbol(session) ?: return emptyList()
        return lookupSuperTypes(symbol, lookupInterfaces = true, deep = true, useSiteSession = session)
            .map { it.lookupTag.classId }
            .filter { settings.containers.matches(it.asSingleFqName().asString()) }
    }

    /**
     * Walks the type arguments of [container]: a nested container adds its head nouns, and those of
     * the listed containers it extends, to [containerNouns] and is walked in turn; any other class
     * that asks something of a name is an element. Type parameters, star projections, value types
     * and types without a head noun (`Runnable`) add nothing.
     */
    private fun collectElements(
        container: ConeClassLikeType,
        settings: KotrailNameSaysType,
        session: FirSession,
        containerNouns: MutableSet<String>,
        elements: MutableList<Element>,
    ) {
        containerNouns += headNouns(container.lookupTag.classId.shortClassName.asString(), settings)
        for (supertype in containerSupertypes(container, settings, session)) {
            containerNouns += headNouns(supertype.shortClassName.asString(), settings)
        }
        for (projection in container.typeArguments) {
            val argument = projection.type?.lowerBoundIfFlexible() ?: continue
            val expanded = classOf(argument, session) ?: continue
            when {
                asksNothing(expanded, settings, session) -> {}
                isContainer(expanded, settings, session) -> collectElements(expanded, settings, session, containerNouns, elements)
                else -> {
                    val alias = aliasOf(argument, expanded)?.takeIf { it.shortClassName != expanded.lookupTag.classId.shortClassName }
                    val shown = shownWords((alias ?: expanded.lookupTag.classId).shortClassName.asString(), settings)
                    if (shown.isEmpty() || isAdjective(shown.last())) continue
                    val nouns = segmentNouns(expanded.lookupTag.classId, settings) + alias?.let { segmentNouns(it, settings) }.orEmpty()
                    elements += Element(expanded.lookupTag.classId, shown, nouns)
                }
            }
        }
    }

    /** The class [type] stands for once aliases are expanded, or `null` for a type parameter, an anonymous class or an error. */
    private fun classOf(type: ConeKotlinType, session: FirSession): ConeClassLikeType? {
        if (type is ConeTypeParameterType) return null
        val expanded = type.fullyExpandedType(session).lowerBoundIfFlexible()
        if (expanded !is ConeClassLikeType || expanded is ConeErrorType) return null
        if (expanded.lookupTag.classId.shortClassName.isSpecial) return null
        return expanded
    }

    /** A key that two types share when they are the same class with the same arguments, nullability and aliases aside. */
    fun identity(type: ConeKotlinType, session: FirSession): String {
        val expanded = type.fullyExpandedType(session).lowerBoundIfFlexible()
        if (expanded !is ConeClassLikeType) return expanded.renderReadable().removeSuffix("?")
        val arguments = expanded.typeArguments.map { projection -> projection.type?.let { identity(it, session) } ?: "*" }
        val name = expanded.lookupTag.classId.asString()
        return if (arguments.isEmpty()) name else "$name<${arguments.joinToString()}>"
    }

    private fun List<Glob>.matches(fqName: String): Boolean = any { it.matches(fqName) }

    /** The type as a reader would write it, without packages and with its aliases: `Channel<Request>`, `User?`, `Members`. */
    private fun render(type: ConeKotlinType): String {
        val nullable = if (type.isMarkedNullable) "?" else ""
        val abbreviation = type.abbreviatedType?.lowerBoundIfFlexible()
        if (abbreviation != null) return render(abbreviation).removeSuffix("?") + nullable
        return when (type) {
            is ConeClassLikeType -> {
                val arguments = type.typeArguments.map { projection -> projection.type?.lowerBoundIfFlexible()?.let(::render) ?: "*" }
                val name = type.lookupTag.classId.shortClassName.asString()
                (if (arguments.isEmpty()) name else "$name<${arguments.joinToString()}>") + nullable
            }
            is ConeTypeParameterType -> type.lookupTag.name.asString() + nullable
            else -> type.renderReadable()
        }
    }
}
