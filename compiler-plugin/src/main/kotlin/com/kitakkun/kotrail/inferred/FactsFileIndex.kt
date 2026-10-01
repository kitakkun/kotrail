package com.kitakkun.kotrail.inferred

import java.io.File

/**
 * The facts of some facts files, keyed by declaration id and signature: those of a compilation's
 * dependencies (the `facts` plugin option), or the compilation's own file as the previous build
 * left it (the `ownFacts` plugin option). Built once, on the first lookup, and shared by every FIR
 * session of the compilation. A file that is missing or cannot be read contributes nothing; where
 * two files describe the same declaration, the one named first wins.
 */
class FactsFileIndex(private val files: () -> List<File>) {
    data class Key(val id: String, val signature: String)

    private val facts: Map<Key, Map<String, Map<String, List<String>>>> by lazy {
        val result = HashMap<Key, MutableMap<String, Map<String, List<String>>>>()
        val seen = HashSet<Key>()
        for (file in files()) {
            val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: continue
            val fromFile = HashMap<Key, MutableMap<String, Map<String, List<String>>>>()
            for (entry in FactsFile.parse(text)) {
                fromFile.getOrPut(Key(entry.id, entry.signature)) { HashMap() }.putAll(entry.facts)
            }
            for ((key, value) in fromFile) {
                if (seen.add(key)) result[key] = value
            }
        }
        result
    }

    /** The arguments of fact [name] recorded for the declaration, or null when no facts file records it. */
    fun lookup(id: String, signature: String, name: String): Map<String, List<String>>? = facts[Key(id, signature)]?.get(name)
}

/**
 * Which facts file answers for a declaration, by where the declaration comes from:
 *
 * - [Origin.SOURCE]: none. The body is at hand and is analyzed; a facts file could only say what
 *   an earlier build found in it.
 * - [Origin.PRECOMPILED]: a declaration of this module in a file that an incremental compilation
 *   did not compile again, loaded back from the output directory without its body. The module's
 *   own facts file ([own]) first: the previous build wrote it, and the entries of the files a
 *   compilation does not compile stay in it (the writer replaces only those of the files it
 *   compiles). Then the dependencies' files, as for a library.
 * - [Origin.LIBRARY]: anything else on the classpath. The dependencies' files ([dependencies])
 *   only: the own file describes this module and nothing else.
 */
class FactsFileLookup(private val dependencies: FactsFileIndex, private val own: FactsFileIndex) {
    enum class Origin { SOURCE, PRECOMPILED, LIBRARY }

    /** The arguments of fact [name] recorded for the declaration, or null when no facts file that answers for it records it. */
    fun lookup(id: String, signature: String, name: String, origin: Origin): Map<String, List<String>>? = when (origin) {
        Origin.SOURCE -> null
        Origin.PRECOMPILED -> own.lookup(id, signature, name) ?: dependencies.lookup(id, signature, name)
        Origin.LIBRARY -> dependencies.lookup(id, signature, name)
    }
}

/** The names the facts go by in a facts file, shared by the IR writer and the FIR services that read them back. */
object InferredFactNames {
    const val WINDOW_INSETS: String = "windowInsets"
    const val COMPOSITION_LOCALS: String = "compositionLocals"
    const val COMPOSITION_LOCAL_GETTER: String = "compositionLocalGetter"
    const val REQUIRED_COMPOSITION_LOCAL: String = "requiredCompositionLocal"
    const val PRECONDITIONS: String = "preconditions"
    const val CLASS_PRECONDITIONS: String = "classPreconditions"
    const val NO_OP_WHEN: String = "noOpWhen"
    const val EFFECT_CAPTURE: String = "effectCapture"
    const val STARTS_ASYNC_WORK: String = "startsAsyncWork"
    const val NATIVE_ALLOCATION: String = "nativeAllocation"
    const val DRAWS_UI: String = "drawsUi"
}
