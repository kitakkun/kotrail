package com.kitakkun.kotrail.inferred

import java.security.MessageDigest

/**
 * The bytes a JVM compilation writes into the Kotlin metadata of a declaration, as the compiler
 * plugin data of the plugin id `com.kitakkun.kotrail`, so that Kotlin's incremental compilation
 * sees an inferred fact change: it compares that data declaration by declaration, and a
 * declaration whose data changed counts as changed, so the files that use it are compiled again.
 *
 * The bytes are the UTF-8 of one JSON object, `{"name": {"parameter": ["value", ...]}, ...}`:
 * the declaration's facts under their names in the facts file, with the same arrays, and for a
 * class the digest of its model surface under [SURFACE] (`{"surface": {"digest": ["..."]}}`).
 * Names and parameters are sorted and nothing else goes in (no path, no time, no version), so
 * the same declaration gives the same bytes on every compilation, and a declaration with nothing
 * to say gets no data at all.
 */
object PluginMetadata {
    /** The entry of a class's model surface digest, next to its facts. */
    const val SURFACE: String = "surface"

    /** The one parameter of [SURFACE]: the digest, as a single string. */
    const val DIGEST: String = "digest"

    /** The bytes for [facts], names and parameters sorted; null when there is nothing to write. */
    fun encode(facts: Map<String, Map<String, List<String>>>): ByteArray? {
        if (facts.isEmpty()) return null
        val sorted = facts.toSortedMap().mapValues { (_, arguments) -> arguments.toSortedMap() }
        return FactsFile.renderFacts(sorted).toByteArray(Charsets.UTF_8)
    }

    /** The facts in bytes [encode] wrote, or null when they are not such bytes. */
    fun decode(bytes: ByteArray): Map<String, Map<String, List<String>>>? = FactsFile.parseFacts(String(bytes, Charsets.UTF_8))

    /** [SURFACE] for [digest], to put next to a class's facts. */
    fun surface(digest: String): Pair<String, Map<String, List<String>>> = SURFACE to mapOf(DIGEST to listOf(digest))

    /**
     * A short digest of a model surface: the first 16 hex digits of the SHA-256 of [lines] joined
     * by line breaks. The lines say what a model walk reads of a class, in the order it reads it.
     */
    fun digest(lines: List<String>): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
