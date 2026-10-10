package com.kitakkun.kotrail.inferred

import java.io.File

/**
 * The facts of the facts files of a compilation's dependencies (the `facts` plugin option),
 * keyed by declaration id and signature. Built once, on the first lookup, and shared by every
 * FIR session of the compilation. A file that is missing or cannot be read contributes nothing;
 * where two files describe the same declaration, the one named first wins.
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
}
