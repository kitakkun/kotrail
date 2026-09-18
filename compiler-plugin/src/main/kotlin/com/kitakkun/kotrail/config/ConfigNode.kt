package com.kitakkun.kotrail.config

/**
 * A parsed configuration value with the position it came from. Every scalar is a string:
 * YAML's implicit typing (`no`, `1.10`, `0x10`) never applies, and Kotrail's own parsers decide
 * what a value means, so a file says exactly what it looks like it says.
 */
sealed class ConfigNode {
    /** `file:line:column` of the node, or the option that produced it, for messages. */
    abstract val at: String

    class Scalar(val value: String, override val at: String) : ConfigNode()

    /** `~`, `null`, or nothing after the colon: the key is unset, so that a later layer can take an entry away. */
    class Null(override val at: String) : ConfigNode()

    class Sequence(val items: List<ConfigNode>, override val at: String) : ConfigNode()

    class Mapping(
        val entries: LinkedHashMap<String, ConfigNode>,
        override val at: String,
        /** Where each key itself is written, for messages about the key rather than its value. */
        val keyPositions: Map<String, String> = emptyMap(),
    ) : ConfigNode() {
        operator fun get(key: String): ConfigNode? = entries[key]

        fun keyAt(key: String): String = keyPositions[key] ?: entries[key]?.at ?: at
    }
}

/** A configuration error with the position of the offending node; the loader reports it as a build failure. */
class ConfigException(message: String) : RuntimeException(message)

/** Tree operations shared by the file loader and the option patches. */
object ConfigTree {
    /**
     * Merges [over] onto [base]: mappings merge key by key, recursively; a scalar or a sequence
     * replaces whatever was there; a [ConfigNode.Null] removes the key, so that the built-in
     * default applies again.
     */
    fun merge(base: ConfigNode?, over: ConfigNode): ConfigNode? {
        if (over is ConfigNode.Null) return null
        if (base is ConfigNode.Mapping && over is ConfigNode.Mapping) {
            val merged = LinkedHashMap(base.entries)
            for ((key, value) in over.entries) {
                val result = merge(merged[key], value)
                if (result == null) merged.remove(key) else merged[key] = result
            }
            return ConfigNode.Mapping(merged, base.at, base.keyPositions + over.keyPositions)
        }
        return over
    }

    /** A mapping built from a dotted path, `a.b.c` to [leaf] giving `{a: {b: {c: leaf}}}`. */
    fun pathTo(path: List<String>, leaf: ConfigNode, at: String): ConfigNode =
        path.foldRight(leaf) { key, inner -> ConfigNode.Mapping(linkedMapOf(key to inner), at) }
}
