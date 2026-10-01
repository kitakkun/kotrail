package com.kitakkun.kotrail.inferred

/**
 * The bytes a JVM compilation writes into the Kotlin metadata of a declaration, as the compiler
 * plugin data of the plugin id `com.kitakkun.kotrail`, so that Kotlin's incremental compilation
 * sees an inferred fact change: it compares that data declaration by declaration, and a
 * declaration whose data changed counts as changed, so the files that use it are compiled again.
 *
 * The bytes are the UTF-8 of one JSON object, `{"name": {"parameter": ["value", ...]}, ...}`:
 * the declaration's facts under their names in the facts file, with the same arrays. Names and
 * parameters are sorted and nothing else goes in (no path, no time, no version), so the same
 * declaration gives the same bytes on every compilation, and a declaration with nothing to say
 * gets no data at all.
 */
object PluginMetadata {
    /** The bytes for [facts], names and parameters sorted; null when there is nothing to write. */
    fun encode(facts: Map<String, Map<String, List<String>>>): ByteArray? {
        if (facts.isEmpty()) return null
        val sorted = facts.toSortedMap().mapValues { (_, arguments) -> arguments.toSortedMap() }
        return FactsFile.renderFacts(sorted).toByteArray(Charsets.UTF_8)
    }

    /** The facts in bytes [encode] wrote, or null when they are not such bytes. */
    fun decode(bytes: ByteArray): Map<String, Map<String, List<String>>>? = FactsFile.parseFacts(String(bytes, Charsets.UTF_8))
}
