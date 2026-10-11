@file:OptIn(SymbolInternals::class)

package com.kitakkun.kotrail.fir.checkers

import com.kitakkun.kotrail.KotrailRule
import com.kitakkun.kotrail.KotrailStringArgument
import com.kitakkun.kotrail.fir.FixEdit
import com.kitakkun.kotrail.fir.KotrailDiagnostics
import com.kitakkun.kotrail.fir.fix.FixBuilder
import com.kitakkun.kotrail.fir.kotrailConfig
import com.kitakkun.kotrail.fir.reportKotrail
import com.kitakkun.kotrail.fir.test.isTestSourcePath
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirValueParameterChecker
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.utils.correspondingValueParameterFromPrimaryConstructor
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirCheckNotNullCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirStringConcatenationCall
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenBranch
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.isExhaustive
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.text
import java.util.WeakHashMap

/**
 * Reports a `String` whose name does not say what kind of text it holds, the kind read from the API
 * the value flows into:
 *
 * ```kotlin
 * class Tools(val ffmpeg: String) {                        // reported: 'ffmpeg' holds the path of an executable
 *     fun convert() = ProcessBuilder(ffmpeg, "-i", "in.mp4").start()
 * }
 * fun users(base: String) = URI("$base/users")             // reported: 'base' holds a URL
 * class Tools(val ffmpegPath: String) { ... }              // fine
 * ```
 *
 * A `String`'s type says nothing about the text, so its name has to: `ffmpeg.isEmpty()` could be
 * about a tool, a command line or a path. The rule is usage-based: a property, a parameter or a
 * local of type `String` or `CharSequence` (nullable included) declared in the file is a subject
 * when its value reaches the argument of one of `noAmbiguousStringName.apis` that takes text
 * (`File(x)`, `ProcessBuilder(x, ...)`, `URI(x)`, `Regex(x)`, `UUID.fromString(x)`, ...), directly or
 * one hop away: through a local initialized from it (`val y = x; File(y)`), a command list headed
 * by it (`val command = listOf(x, "-i")`, or a `buildList` whose first `add` is `x`) held by a local
 * or returned from a same-file function and passed to a process API, a string template that starts
 * with it and continues with `/` (`"$x/sdk"`), or the argument of a same-file function's parameter
 * that itself reaches one. A scope function's `it` or `this` (`x?.let { f(it) }`,
 * `with(x) { f(this) }`) is the value itself and adds no hop. Its name must then carry one of the
 * words of a kind it reaches, as consecutive camelCase or `_` words: `ffmpegPath`, `workingDir`,
 * `baseUrl`, `implClassName` pass, `ffmpeg` does not. A value read back from another class's
 * property is not followed.
 *
 * A kind the value reaches only in some branches of a `when` or an `if` whose subject and
 * conditions do not read the value is not one it holds: in
 * `when (type) { JSON -> Json.parseToJsonElement(text); STRING -> JsonPrimitive(text) }`, `text`
 * is JSON only when `type` says so. It holds the kind when it reaches it in every branch of a
 * `when` that covers every case, or outside such branches.
 *
 * Each API entry names the kind of text it takes; the kinds table gives each kind its words, the
 * first being the suggested suffix. The four path kinds share their words: the head of a path
 * constructor or factory given two or more parts (`File(parent, child)`, `Path.of(a, b)`), the start
 * of a `"$x/..."` template, and a file or path used as a directory right away
 * (`File(x).mkdirs()`) hold a directory, whose suggestion is `<name>Dir`. A value that reaches
 * several kinds passes with a word of any of them, and the suggestion follows the first reached.
 * A name that is one word with no role of its own (`text`, `value`, `raw`, `s`; see
 * [GENERIC_NAMES]) is asked for the kind's word alone (`json`, `path`), not `textJson`.
 *
 * The rename to the suggestion is recorded as a fix when every use is known: a local `val`, a
 * lambda parameter, a private `val` property that is not a constructor parameter, or a
 * parameter of a private function that no call in the file passes by name. The new name must
 * not be declared or referred to anywhere in the file (a string literal or a comment does not
 * count). Test source sets are skipped. The file is analyzed once, by the
 * first declaration checked in it; the property and parameter checkers report from their own
 * declaration, so a `@Suppress` on it or an enclosing declaration applies.
 */
object NoAmbiguousStringNameChecker {
    private const val PATH = "path"
    private const val FILE = "file"
    private const val DIRECTORY = "directory"
    private const val EXECUTABLE = "executable"

    /** The kinds a path built on the value (`"$x/sdk"`) turns into a directory. */
    private val PATH_KINDS = setOf(PATH, FILE, DIRECTORY, EXECUTABLE)

    /** What a value of each default kind holds, for the message; a project's own kind holds "text of kind '<name>'". */
    private val DESCRIPTIONS = mapOf(
        PATH to "a path",
        FILE to "a file path",
        DIRECTORY to "a directory path",
        EXECUTABLE to "the path of an executable",
        "url" to "a URL",
        "regex" to "a regular expression",
        "json" to "JSON",
        "id" to "an identifier",
        "date" to "a date or time",
        "format" to "a format pattern",
        "host" to "a host name",
        "keyName" to "the name of a variable or property",
        "className" to "a class name",
        "sql" to "SQL",
    )

    /** Constructors whose head is the whole text only when it is the one argument: `URI(scheme, host, path, fragment)` starts with a scheme. */
    private val WHOLE_ONLY_ALONE = setOf("java.net.URL", "java.net.URI")

    /** Calls on the created `File` or `Path` that say it names a directory. */
    private val DIRECTORY_CALLS = setOf(
        "mkdir", "mkdirs", "isDirectory", "listFiles", "walk", "walkTopDown", "walkBottomUp", "deleteRecursively",
        "createDirectories", "createDirectory", "listDirectoryEntries",
    )

    /** Constructors and factories whose head, when given two or more parts, is the directory the rest resolves against. */
    private val MULTI_PART_APIS = setOf("java.io.File", "java.nio.file.Path.of", "java.nio.file.Paths.get", "kotlin.io.path.Path")

    /** Builders whose first element is the head of a command list. */
    private val COMMAND_LISTS = setOf(
        "kotlin.collections.listOf", "kotlin.collections.mutableListOf", "kotlin.collections.arrayListOf", "kotlin.arrayOf",
    )
    private const val BUILD_LIST = "kotlin.collections.buildList"
    private val LIST_ADDS = setOf("add", "addAll")

    /** Scope functions whose lambda gets the receiver as `it`. */
    private val IT_SCOPE_FUNCTIONS = setOf("kotlin.let", "kotlin.also", "kotlin.takeIf", "kotlin.takeUnless")

    /** Scope functions whose lambda gets the receiver (the first argument of `with`) as `this`. */
    private val THIS_SCOPE_FUNCTIONS = setOf("kotlin.run", "kotlin.apply", "kotlin.with")

    private val TEXT_TYPES = setOf(StandardClassIds.String, StandardClassIds.CharSequence)

    /** Names that say only that the value is text, which the kind's word replaces rather than follows. */
    private val GENERIC_NAMES = setOf("text", "value", "line", "string", "raw", "input", "content", "str", "s")

    /** Hard keywords a kind's word can be, which cannot be a name on their own. */
    private val KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
        "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    /** Reports a property or a local that is a finding of its file. */
    object Properties : FirPropertyChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirProperty) = reportIfFound(declaration.source)
    }

    /** Reports a parameter (of a function, a constructor or a lambda) that is a finding of its file. */
    object Parameters : FirValueParameterChecker(MppCheckerKind.Common) {
        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(declaration: FirValueParameter) = reportIfFound(declaration.source)
    }

    /**
     * The findings of each file by the start offset of the declaration, computed by the first
     * declaration checked in it. The values hold no FIR, so a file's entry goes with the file.
     */
    private val analyses = WeakHashMap<FirFile, Map<Int, Finding>>()

    private class Finding(val source: KtSourceElement, val name: String, val holds: String, val advice: String, val fix: List<FixEdit>)

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun reportIfFound(source: KtSourceElement?) {
        if (source == null || source.kind is KtFakeSourceElementKind) return
        val config = context.session.kotrailConfig
        if (!config.isEnabled(KotrailRule.NO_AMBIGUOUS_STRING_NAME)) return
        val file = context.containingFileSymbol?.fir ?: return
        val findings = synchronized(analyses) { analyses.getOrPut(file) { analyze(file, context.session) } }
        val finding = findings[source.startOffset] ?: return
        reportKotrail(finding.source, KotrailDiagnostics.STRING_NAME_HIDES_KIND, finding.name, finding.holds, finding.advice, finding.fix)
    }

    private fun analyze(file: FirFile, session: FirSession): Map<Int, Finding> {
        val path = file.sourceFile?.path?.replace('\\', '/') ?: file.name
        if (isTestSourcePath(path)) return emptyMap()
        val settings = session.kotrailConfig.noAmbiguousStringName
        val table = KindTable(settings.kinds.map { (name, words) -> TextKind(name, words, DESCRIPTIONS[name] ?: "text of kind '$name'") })
        val apis = settings.apis.groupBy({ it.fqName }) { Api(table.kinds.getValue(it.kind), it.argument) }

        val walker = Walker(session, apis, table)
        file.accept(walker)

        val findings = HashMap<Int, Finding>()
        for ((symbol, subject) in walker.subjects) {
            val reaches = walker.reachesOf(symbol).groupBy { it.use.kindIn(table).name }
                .filterValues { reachesOfKind -> alwaysReached(reachesOfKind.map { it.branches }, level = 0) }
                .values.flatten().sortedWith(compareBy({ it.order }, { it.use.at.startOffset }))
            if (reaches.isEmpty()) continue
            val kinds = reaches.map { it.use.kindIn(table) }.distinctBy { it.name }
            val nameWords = subject.name.words()
            if (kinds.any { it.isSaidBy(nameWords) }) continue
            val suggestion = suggestedName(subject.name, kinds.first().suffix)
            val words = kinds.flatMap { it.words }.distinctBy { it.lowercase() }
            val advice = "'$suggestion', or any name that carries one of ${words.joinToString()}"
            val holds = describe(reaches, table, file)
            findings[subject.source.startOffset] = Finding(subject.source, subject.name, holds, advice, renameFix(symbol, subject, suggestion, walker))
        }
        return findings
    }

    /**
     * Whether the uses whose branches are [chains] (outermost first, each list sharing the first
     * [level] branches) reach the API whatever branches run: one of them runs in no further branch,
     * or a `when` that covers every case has one in each of its branches, recursively.
     */
    private fun alwaysReached(chains: List<List<Branch>>, level: Int): Boolean {
        if (chains.any { it.size == level }) return true
        for ((whenExpression, inWhen) in chains.groupBy { it[level].whenExpression }) {
            if (!whenExpression.coversEveryCase()) continue
            val byBranch = inWhen.groupBy { it[level].index }
            if (whenExpression.branches.indices.all { index -> byBranch[index]?.let { alwaysReached(it, level + 1) } == true }) return true
        }
        return false
    }

    /** Whether one of the branches always runs: an `else`, or an exhaustive `when` over a sealed type, an enum or a Boolean. */
    private fun FirWhenExpression.coversEveryCase(): Boolean = isExhaustive || branches.any { it.condition is FirElseIfTrueCondition }

    /**
     * The name [name] is asked for: the kind's [word] alone for a name that says only that it is text
     * (`text` gives `json`), as long as that is no keyword, else the name followed by it (`ffmpegPath`).
     */
    private fun suggestedName(name: String, word: String): String {
        val words = name.words()
        if (words.size != 1 || words.single() !in GENERIC_NAMES) return name.withSuffix(word)
        val alone = if (name.any { it.isLetter() } && name.none { it.isLowerCase() }) {
            word.words().joinToString("_").uppercase()
        } else {
            word.replaceFirstChar { it.lowercaseChar() }
        }
        return if (alone in KEYWORDS) name.withSuffix(word) else alone
    }

    /**
     * What the value holds, kind by kind in the order it reaches them, with the API and the line: a kind
     * that takes the same words as one before it (a directory after a file) is left out.
     */
    private fun describe(reaches: List<Reach>, table: KindTable, file: FirFile): String {
        val described = mutableListOf<String>()
        val seenKinds = HashSet<String>()
        val seenWords = mutableListOf<Set<String>>()
        for (reach in reaches) {
            val kind = reach.use.kindIn(table)
            if (!seenKinds.add(kind.name)) continue
            val words = kind.words.map { it.lowercase() }.toSet()
            if (words in seenWords) continue
            seenWords += words
            val line = file.sourceFileLinesMapping?.getLineByOffset(reach.use.at.startOffset)?.plus(1)
            val where = if (line != null && line > 0) "${file.name}:$line" else file.name
            described += "${kind.description} (it reaches '${reach.use.api}' at $where)"
        }
        return described.joinToString(" and ")
    }

    /** One edit per occurrence of the name, or none when a use could be missed or the new name is taken. */
    private fun renameFix(symbol: FirBasedSymbol<*>, subject: Subject, newName: String, walker: Walker): List<FixEdit> {
        if (subject.scope == RenameScope.NONE) return emptyList()
        if (newName in walker.names) return emptyList()
        if (subject.scope == RenameScope.PRIVATE_FUNCTION_PARAMETER) {
            val named = Regex("[(,]\\s*${Regex.escape(subject.name)}\\s*=(?!=)")
            if (walker.callsTo(symbol).any { call -> FixBuilder.textOf(call)?.let(named::containsMatchIn) != false }) return emptyList()
        }
        val builder = FixBuilder.over(subject.source) ?: return emptyList()
        val declared = subject.namePattern.find(builder.text)?.groups?.get(1) ?: return emptyList()
        val edits = mutableListOf(builder.replaceRange(builder.offsetOf(declared.range.first), builder.offsetOf(declared.range.last + 1), newName))
        for (reference in walker.referencesTo(symbol)) {
            if (FixBuilder.textOf(reference) != subject.name) return emptyList()
            edits += FixBuilder.replace(reference, newName)
        }
        return edits.distinctBy { it.startOffset }
    }

    private enum class RenameScope { NONE, LOCAL, PRIVATE_PROPERTY, PRIVATE_FUNCTION_PARAMETER }

    private class Subject(val name: String, val source: KtSourceElement, val scope: RenameScope, val namePattern: Regex)

    /** A kind of text: the words a name says it with, the first of them the suggested suffix, and what the message says it holds. */
    private class TextKind(val name: String, val words: List<String>, val description: String) {
        val suffix: String = words.first()

        /** Whether a name, split into [nameWords], carries one of the words. */
        fun isSaidBy(nameWords: List<String>): Boolean = words.any { nameWords.carries(it.lowercase()) }
    }

    /** The kinds by name. */
    private class KindTable(kinds: List<TextKind>) {
        val kinds: Map<String, TextKind> = kinds.associateBy { it.name }
        val directory: TextKind? = this.kinds[DIRECTORY]

        /** A path built on a value (`"$x/sdk"`) makes it the directory the path is in; any other kind stays what it is. */
        fun underPath(kind: TextKind): TextKind = if (kind.name in PATH_KINDS) directory ?: kind else kind
    }

    /** An `apis` entry: the kind of text the callable takes, and which argument takes it. */
    private class Api(val kind: TextKind, val argument: KotrailStringArgument)

    /**
     * A branch of a `when` or an `if` (by its index in [whenExpression]) that code runs in only when the
     * branch is taken, and whose subject and conditions do not read the value followed.
     */
    private class Branch(val whenExpression: FirWhenExpression, val index: Int)

    /**
     * Where a subject's value reaches an API: the call site, the kind of text the API takes, its short
     * name, and the branches the call runs in (see [Branch]), outermost first.
     */
    private class Use(val at: KtSourceElement, val kind: TextKind, val api: String, val branches: List<Branch>) {
        /** Set when the created `File` or `Path` is used as a directory right away. */
        var usedAsDirectory = false

        /** The kind, a file or path used as a directory right away being a directory. */
        fun kindIn(table: KindTable): TextKind =
            if (usedAsDirectory && (kind.name == FILE || kind.name == PATH)) table.directory ?: kind else kind

        /** The same use reached through a path built on the value. */
        fun underPath(table: KindTable): Use = Use(at, table.underPath(kindIn(table)), api, branches)
    }

    /**
     * A use a subject reaches, at [order]: the offset of the call for its own, of the hand-over for one a
     * hop away; [branches], those the use runs in, the hand-over's included.
     */
    private class Reach(val use: Use, val order: Int, val branches: List<Branch>)

    /** How a hop hands a subject's value to its holder. */
    private enum class HopKind { LOCAL, PARAMETER, COMMAND_HEAD, TEMPLATE_HEAD }

    /**
     * [holder] (a local, a same-file parameter or function) carries [subject]'s value one hop on, handed
     * over at [order], in [branches] (see [Branch]); [enclosing] are all the `when`s around the
     * hand-over, which a local's uses inside them run in too and are judged by the hand-over.
     */
    private class Hop(
        val holder: FirBasedSymbol<*>,
        val subject: FirBasedSymbol<*>,
        val kind: HopKind,
        val order: Int,
        val branches: List<Branch>,
        val enclosing: Set<FirWhenExpression>,
    )

    /**
     * One walk of the file: the subjects it declares, the API calls their values reach
     * directly, the locals and same-file parameters they are handed to (the one hop), and every
     * reference to them for the rename.
     */
    private class Walker(
        private val session: FirSession,
        private val apis: Map<String, List<Api>>,
        private val table: KindTable,
    ) : FirVisitorVoid() {
        val subjects = LinkedHashMap<FirBasedSymbol<*>, Subject>()
        private val direct = HashMap<FirBasedSymbol<*>, MutableList<Use>>()
        private val useByCall = HashMap<FirFunctionCall, MutableList<Use>>()
        private val hops = mutableListOf<Hop>()
        private val references = HashMap<FirBasedSymbol<*>, MutableList<KtSourceElement>>()
        private val calls = HashMap<FirBasedSymbol<*>, MutableList<KtSourceElement>>()
        private val ancestors = ArrayList<FirElement>()

        /** The names the file declares or refers to; string literals and comments are not code, so not here. */
        val names = HashSet<String>()

        /**
         * A scope function's `it` or `this`, standing for the value the function was called on,
         * and whether that value is the code's own (see [subjectAccess]). Reading one reads the
         * value itself, so the lambda is no hop.
         */
        private val aliases = HashMap<FirBasedSymbol<*>, Pair<FirBasedSymbol<*>, Boolean>>()

        /**
         * The uses [symbol] reaches, in the order it reaches them: its own, and those one hop away. A
         * command list counts only at a process API; a template built on the value makes it the
         * directory of a path use.
         */
        fun reachesOf(symbol: FirBasedSymbol<*>): List<Reach> {
            val own = direct[symbol].orEmpty().map { Reach(it, it.at.startOffset, it.branches) }
            val hopped = hops.filter { it.subject == symbol }.flatMap { hop ->
                val uses = direct[hop.holder].orEmpty()
                val carried = when (hop.kind) {
                    HopKind.LOCAL, HopKind.PARAMETER -> uses
                    HopKind.COMMAND_HEAD -> uses.filter { it.kind.name == EXECUTABLE }
                    HopKind.TEMPLATE_HEAD -> uses.map { it.underPath(table) }
                }
                carried.map { use ->
                    val further = use.branches.filter { it.whenExpression !in hop.enclosing && !it.whenExpression.reads(hop.subject) }
                    Reach(use, hop.order, hop.branches + further)
                }
            }
            return (own + hopped).sortedWith(compareBy({ it.order }, { it.use.at.startOffset }))
        }

        fun referencesTo(symbol: FirBasedSymbol<*>): List<KtSourceElement> = references[symbol].orEmpty()

        /** The calls in the file to the function that declares the parameter [symbol]. */
        fun callsTo(symbol: FirBasedSymbol<*>): List<KtSourceElement> {
            val function = (symbol as? FirValueParameterSymbol)?.containingDeclarationSymbol ?: return emptyList()
            return calls[function].orEmpty()
        }

        override fun visitElement(element: FirElement) {
            collectName(element)
            if (element is FirFunctionCall) collectScopeLambda(element)
            when (element) {
                is FirProperty -> collectProperty(element)
                is FirValueParameter -> collectParameter(element)
                is FirQualifiedAccessExpression -> collectReference(element)
                is FirReturnExpression -> collectReturn(element)
                else -> {}
            }
            ancestors.add(element)
            element.acceptChildren(this)
            ancestors.removeAt(ancestors.lastIndex)
            if (element is FirFunctionCall) collectCall(element)
            if (element is FirQualifiedAccessExpression) markDirectory(element)
        }

        private fun collectName(element: FirElement) {
            val name = when (element) {
                is FirProperty -> element.name
                is FirValueParameter -> element.name
                is FirNamedFunction -> element.name
                is FirRegularClass -> element.name
                is FirTypeAlias -> element.name
                is FirQualifiedAccessExpression -> (element.calleeReference as? FirNamedReference)?.name
                else -> null
            } ?: return
            names += name.asString()
        }

        /**
         * `x?.let { f(it) }`, `x.apply { f(this) }`, `with(x) { f(this) }`: the lambda's implicit
         * `it` or receiver is `x`. A named parameter (`x.let { tool -> f(tool) }`) is a
         * declaration of its own, initialized from `x` like a local.
         */
        private fun collectScopeLambda(call: FirFunctionCall) {
            val fqName = call.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString() ?: return
            val lambda = call.argumentList.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression ?: return
            val function = lambda.anonymousFunction
            val (value, stand) = when (fqName) {
                in IT_SCOPE_FUNCTIONS -> {
                    val parameter = function.valueParameters.firstOrNull() ?: return
                    if (parameter.source?.kind !is KtFakeSourceElementKind) {
                        val receiver = call.extensionReceiver ?: return
                        receiver.subjectAccess(ownOnly = true)?.let { hops += hop(parameter.symbol, it, HopKind.LOCAL, call.offset()) }
                        return
                    }
                    (call.extensionReceiver ?: return) to parameter.symbol
                }
                in THIS_SCOPE_FUNCTIONS -> {
                    val receiver = function.receiverParameter ?: return
                    val value = if (fqName == "kotlin.with") call.argumentList.arguments.firstOrNull()?.unwrapArgument() else call.extensionReceiver
                    (value ?: return) to receiver.symbol
                }
                else -> return
            }
            value.subjectAccess(ownOnly = true)?.let { aliases[stand] = it to true; return }
            value.subjectAccess(ownOnly = false)?.let { aliases[stand] = it to false }
        }

        private fun collectProperty(property: FirProperty) {
            val source = property.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            if (property.isOverride || property.isExpect || property.isActual) return
            val initializer = property.initializer
            if (property.isLocal && initializer != null) collectHops(property.symbol, initializer, HopKind.LOCAL)
            if (!property.returnTypeRef.coneType.isText()) return
            val name = property.name.asString()
            if (!name.isPlainName()) return
            val scope = when {
                property.isVar -> RenameScope.NONE
                property.isLocal -> RenameScope.LOCAL
                property.visibility == Visibilities.Private -> RenameScope.PRIVATE_PROPERTY
                else -> RenameScope.NONE
            }
            val pattern = Regex("\\b(?:val|var)\\s+(${Regex.escape(name)})\\b")
            subjects[property.symbol] = Subject(name, source, scope, pattern)
        }

        /** How [value], held by [holder], carries a subject: as itself ([plain]), as the head of a command list, or under a template. */
        private fun collectHops(holder: FirBasedSymbol<*>, value: FirExpression, plain: HopKind) {
            val order = value.offset()
            value.subjectAccess(ownOnly = true)?.let { hops += hop(holder, it, plain, order) }
            commandHead(value)?.subjectAccess(ownOnly = true)?.let { hops += hop(holder, it, HopKind.COMMAND_HEAD, order) }
            value.templateHead(ownOnly = true)?.let { hops += hop(holder, it, HopKind.TEMPLATE_HEAD, order) }
        }

        /** A named function that returns a command list headed by a subject: `fun command() = buildList { add(adb) }`. */
        private fun collectReturn(returned: FirReturnExpression) {
            val function = returned.target.labeledElement as? FirNamedFunction ?: return
            val head = commandHead(returned.result)?.subjectAccess(ownOnly = true) ?: return
            hops += hop(function.symbol, head, HopKind.COMMAND_HEAD, returned.offset())
        }

        private fun collectParameter(parameter: FirValueParameter) {
            val source = parameter.source ?: return
            if (source.kind is KtFakeSourceElementKind) return
            if (!parameter.returnTypeRef.coneType.isText()) return
            val name = parameter.name.asString()
            if (!name.isPlainName()) return
            val scope = when (val owner = ancestors.lastOrNull()) {
                is FirAnonymousFunction -> RenameScope.LOCAL
                is FirNamedFunction -> {
                    if (owner.isOverride || owner.isExpect || owner.isActual) return
                    if (owner.visibility == Visibilities.Private) RenameScope.PRIVATE_FUNCTION_PARAMETER else RenameScope.NONE
                }
                is FirConstructor -> RenameScope.NONE
                else -> return
            }
            val pattern = Regex("^(?:[^:=]*\\s)?(${Regex.escape(name)})(?=\\s*(?::|$))")
            subjects[parameter.symbol] = Subject(name, source, scope, pattern)
        }

        private fun collectReference(access: FirQualifiedAccessExpression) {
            if (access is FirFunctionCall) return
            val symbol = access.calleeReference.toResolvedCallableSymbol()?.canonical() ?: return
            // A simple name's callee reference carries a fake source over the name alone.
            val source = access.calleeReference.source ?: access.source ?: return
            references.getOrPut(symbol) { mutableListOf() } += source
        }

        private fun collectCall(call: FirFunctionCall) {
            val callee = call.calleeReference.toResolvedCallableSymbol() ?: return
            val source = call.source
            if (source != null && callee is FirNamedFunctionSymbol) calls.getOrPut(callee) { mutableListOf() } += source
            call.resolvedArgumentMapping?.forEach { (argument, parameter) ->
                collectHops(parameter.symbol, argument.unwrapArgument(), HopKind.PARAMETER)
            }
            val fqName = when (callee) {
                is FirConstructorSymbol -> call.resolvedType.classId?.asSingleFqName()?.asString()
                else -> callee.callableId?.asSingleFqName()?.asString()
            } ?: return
            val entries = apis[fqName] ?: return
            val at = source ?: return
            if (at.kind is KtFakeSourceElementKind) return
            if (fqName in WHOLE_ONLY_ALONE && partCount(call) > 1) return
            val receiverType = call.extensionReceiver?.resolvedType?.classId?.shortClassName?.asString()
            val api = when {
                callee is FirConstructorSymbol -> call.resolvedType.classId?.shortClassName?.asString() ?: fqName
                call.extensionReceiver != null -> listOfNotNull(receiverType, callee.callableId?.callableName?.asString()).joinToString(".")
                else -> listOfNotNull(
                    callee.callableId?.classId?.relativeClassName?.asString()?.removeSuffix(".Companion"),
                    callee.callableId?.callableName?.asString(),
                ).joinToString(".")
            }
            for (entry in entries) {
                val head = argumentOf(call, callee, entry.argument) ?: continue
                val template = head.templateHead(ownOnly = false)
                val subject = template ?: head.subjectAccess(ownOnly = false) ?: head.calledFunction() ?: continue
                val multiPart = entry.argument == KotrailStringArgument.Head && call.extensionReceiver == null &&
                    fqName in MULTI_PART_APIS && partCount(call) >= 2
                val kind = when {
                    template != null -> table.underPath(entry.kind)
                    multiPart && entry.kind.name == FILE -> table.directory ?: entry.kind
                    else -> entry.kind
                }
                val use = Use(at, kind, api, branchesFor(subject))
                useByCall.getOrPut(call) { mutableListOf() } += use
                direct.getOrPut(subject) { mutableListOf() } += use
            }
        }

        /** A hop handed over where the walk is now, with the branches it runs in. */
        private fun hop(holder: FirBasedSymbol<*>, subject: FirBasedSymbol<*>, kind: HopKind, order: Int): Hop =
            Hop(holder, subject, kind, order, branchesFor(subject), ancestors.filterIsInstance<FirWhenExpression>().toSet())

        /**
         * The branches of a `when` or an `if` that the element the walk is at runs in, outermost first:
         * those whose result holds it (not their condition), of a `when` whose subject and conditions do
         * not read [symbol].
         */
        private fun branchesFor(symbol: FirBasedSymbol<*>): List<Branch> {
            val branches = ArrayList<Branch>()
            for (at in 0 until ancestors.size - 1) {
                val whenExpression = ancestors[at] as? FirWhenExpression ?: continue
                val branch = ancestors[at + 1] as? FirWhenBranch ?: continue
                if (ancestors.getOrNull(at + 2) !== branch.result) continue
                if (whenExpression.reads(symbol)) continue
                branches += Branch(whenExpression, whenExpression.branches.indexOf(branch))
            }
            return branches
        }

        /** Whether the subject or a condition of this `when` reads [symbol], as itself or through a scope function's `it` or `this`. */
        private fun FirWhenExpression.reads(symbol: FirBasedSymbol<*>): Boolean {
            var found = false
            val visitor = object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    if (found) return
                    if (element is FirWhenBranch) {
                        element.condition.accept(this)
                        return
                    }
                    if (element is FirExpression && element !is FirFunctionCall && element.subjectAccess(ownOnly = false) == symbol) {
                        found = true
                        return
                    }
                    element.acceptChildren(this)
                }
            }
            acceptChildren(visitor)
            return found
        }

        /** `File(x).mkdirs()`, `File(x).isDirectory`: the path names a directory. */
        private fun markDirectory(access: FirQualifiedAccessExpression) {
            val receiver = access.explicitReceiver as? FirFunctionCall ?: return
            val uses = useByCall[receiver] ?: return
            val name = access.calleeReference.toResolvedCallableSymbol()?.callableId?.callableName?.asString() ?: return
            if (name in DIRECTORY_CALLS) uses.forEach { it.usedAsDirectory = true }
        }

        /**
         * The argument of [call] that [argument] names, or the head of a command list built in place
         * there. The default head is the extension receiver when it is text, else the first argument.
         */
        private fun argumentOf(call: FirFunctionCall, callee: FirBasedSymbol<*>, argument: KotrailStringArgument): FirExpression? {
            val receiver = call.extensionReceiver
            val chosen = when (argument) {
                KotrailStringArgument.Head -> {
                    if (receiver != null && receiver.resolvedType.isText()) return receiver
                    firstElement(call)
                }
                KotrailStringArgument.Receiver -> return receiver
                is KotrailStringArgument.Index -> {
                    val parameter = (callee as? FirFunctionSymbol<*>)?.valueParameterSymbols?.getOrNull(argument.index) ?: return null
                    argumentFor(call) { it.name == parameter.name }
                }
                is KotrailStringArgument.Named -> argumentFor(call) { it.name.asString() == argument.name }
            } ?: return null
            return commandHead(chosen) ?: chosen
        }

        /** The argument mapped to the first parameter that [matches], or the first element of a vararg there. */
        private fun argumentFor(call: FirFunctionCall, matches: (FirValueParameter) -> Boolean): FirExpression? {
            val argument = call.resolvedArgumentMapping?.entries?.firstOrNull { matches(it.value) }?.key?.unwrapArgument() ?: return null
            if (argument !is FirVarargArgumentsExpression) return argument
            return argument.arguments.firstOrNull()?.unwrapArgument()
        }

        private fun FirElement.offset(): Int = source?.startOffset ?: -1

        /** The first value argument, or the first element of a vararg. */
        private fun firstElement(call: FirFunctionCall): FirExpression? {
            val head = call.argumentList.arguments.firstOrNull()?.unwrapArgument() ?: return null
            if (head !is FirVarargArgumentsExpression) return head
            return head.arguments.firstOrNull()?.unwrapArgument()
        }

        /** The parts a path is built from: `File(parent, child)` and `Path.of(first, "a", "b")` have two or more. */
        private fun partCount(call: FirFunctionCall): Int = call.argumentList.arguments.sumOf { argument ->
            val unwrapped = argument.unwrapArgument()
            if (unwrapped is FirVarargArgumentsExpression) unwrapped.arguments.size else 1
        }

        /**
         * The head of a command list: the first element of `listOf(x, ...)` / `arrayOf(x, ...)`, or of
         * `buildList { ... }`, whose first `add(x)` or `addAll(listOf(x, ...))` sets it.
         */
        private fun commandHead(expression: FirExpression): FirExpression? {
            val call = expression as? FirFunctionCall ?: return null
            val builder = call.calleeReference.toResolvedCallableSymbol()?.callableId?.asSingleFqName()?.asString()
            if (builder in COMMAND_LISTS) return firstElement(call)
            if (builder != BUILD_LIST) return null
            val lambda = call.argumentList.arguments.lastOrNull()?.unwrapArgument() as? FirAnonymousFunctionExpression ?: return null
            val firstAdd = lambda.anonymousFunction.body?.statements.orEmpty()
                .filterIsInstance<FirFunctionCall>()
                .firstOrNull { it.calleeName() in LIST_ADDS } ?: return null
            val added = firstElement(firstAdd) ?: return null
            return if (firstAdd.calleeName() == "add") added else commandHead(added)
        }

        private fun FirFunctionCall.calleeName(): String? = calleeReference.toResolvedCallableSymbol()?.callableId?.callableName?.asString()

        /**
         * The subject a string template starts with, when a `/` follows it: `"$home/sdk"` is a path
         * inside `home`. A subject after the start, `name` in `"$dir/$name"`, is not one.
         */
        private fun FirExpression.templateHead(ownOnly: Boolean): FirBasedSymbol<*>? {
            val parts = (this as? FirStringConcatenationCall)?.argumentList?.arguments ?: return null
            val head = parts.firstOrNull()?.subjectAccess(ownOnly) ?: return null
            val next = (parts.getOrNull(1) as? FirLiteralExpression)?.value as? String ?: return null
            return head.takeIf { next.startsWith("/") }
        }

        /** A call to a named function: the holder of the command list it returns. */
        private fun FirExpression.calledFunction(): FirBasedSymbol<*>? =
            (this as? FirFunctionCall)?.calleeReference?.toResolvedCallableSymbol() as? FirNamedFunctionSymbol

        /**
         * The declaration this expression reads, when it is a property or parameter access. With
         * [ownOnly], only a read without a receiver or through `this`: a hop follows the code's
         * own values, not a member of another object.
         */
        private fun FirExpression.subjectAccess(ownOnly: Boolean): FirBasedSymbol<*>? {
            val expression = when (this) {
                is FirSmartCastExpression -> originalExpression
                is FirCheckNotNullCall -> argumentList.arguments.firstOrNull()
                is FirCheckedSafeCallSubject -> return originalReceiverRef.value.subjectAccess(ownOnly)
                else -> this
            }
            if (expression is FirThisReceiverExpression) return expression.calleeReference.boundSymbol?.let { aliased(it, ownOnly) }
            val access = expression as? FirPropertyAccessExpression ?: return null
            if (ownOnly && access.explicitReceiver != null && access.explicitReceiver !is FirThisReceiverExpression) return null
            val symbol = access.calleeReference.toResolvedCallableSymbol()?.canonical() ?: return null
            return if (symbol in aliases) aliased(symbol, ownOnly) else symbol
        }

        /** The value a scope function's `it` or `this` stands for, when there is one to follow. */
        private fun aliased(symbol: FirBasedSymbol<*>, ownOnly: Boolean): FirBasedSymbol<*>? {
            val (value, own) = aliases[symbol] ?: return null
            return value.takeIf { own || !ownOnly }
        }

        /** A primary-constructor property is reported, and renamed, as its parameter. */
        private fun FirBasedSymbol<*>.canonical(): FirBasedSymbol<*> =
            (this as? FirPropertySymbol)?.correspondingValueParameterFromPrimaryConstructor ?: this

        private fun ConeKotlinType.isText(): Boolean = fullyExpandedType(session).classId in TEXT_TYPES
    }

    private fun String.isPlainName(): Boolean = isNotEmpty() && !startsWith("<") && this != "_"

    /** With the word `path`: `ffmpeg` -> `ffmpegPath`, `FFMPEG` -> `FFMPEG_PATH`; with `className`: `impl` -> `implClassName`. */
    private fun String.withSuffix(word: String): String =
        if (any { it.isLetter() } && none { it.isLowerCase() }) "${this}_${word.words().joinToString("_").uppercase()}"
        else this + word.replaceFirstChar { it.uppercaseChar() }

    /** Whether consecutive words of a name, joined, spell [word]: `implClassName` carries `classname` and `class`, `profile` no `file`. */
    private fun List<String>.carries(word: String): Boolean = indices.any { start ->
        val joined = StringBuilder()
        var end = start
        while (end < size && joined.length < word.length) joined.append(this[end++])
        joined.toString() == word
    }

    /** The lower-cased words of a camelCase or SCREAMING_CASE name: `XMLPathFor2` -> `xml`, `path`, `for2`. */
    private fun String.words(): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) words += current.toString().lowercase()
            current.clear()
        }
        for ((index, char) in withIndex()) {
            if (!char.isLetterOrDigit()) {
                flush()
                continue
            }
            val previous = getOrNull(index - 1)
            val next = getOrNull(index + 1)
            val startsWord = char.isUpperCase() && previous != null &&
                (previous.isLowerCase() || previous.isDigit() || (previous.isUpperCase() && next?.isLowerCase() == true))
            if (startsWord) flush()
            current.append(char)
        }
        flush()
        return words
    }
}
