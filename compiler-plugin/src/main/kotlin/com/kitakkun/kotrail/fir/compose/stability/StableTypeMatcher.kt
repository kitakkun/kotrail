package com.kitakkun.kotrail.fir.compose.stability

/**
 * One line of a Compose stability configuration file, in the Compose compiler's own grammar:
 * a fully qualified name in which `*` stands for one segment and `**` for any number, optionally
 * followed by a generic mask such as `<*,_>` that says which type arguments take part in the
 * stability (`*`) and which do not (`_`). Without a mask, every type argument takes part.
 *
 * ```
 * com.acme.model.User
 * com.acme.model.*
 * com.acme.**
 * com.acme.Wrapper<*,_>
 * ```
 *
 * A port of `androidx.compose.compiler.plugins.kotlin.analysis.FqNameMatcher`, so that the same
 * line means the same thing in `kotrail.yaml` and in `stabilityConfigurationFile`.
 */
class StableTypeMatcher(val pattern: String) {
    /** The literal prefix of the pattern, up to its first wildcard. */
    val key: String

    /** Bit `i` set: type argument `i` takes part in the stability. All bits set without a mask. */
    val mask: Int

    private val regex: Regex?

    init {
        val match = VALID_PATTERN.matchEntire(pattern) ?: throw IllegalArgumentException("'$pattern' is not a valid stable type pattern")
        val regexPattern = StringBuilder()
        val keyBuilder = StringBuilder()
        var hasWildcard = false
        var index = 0
        var hitGenericOpener = false
        while (index < pattern.length && !hitGenericOpener) {
            when (val c = pattern[index]) {
                '*' -> {
                    hasWildcard = true
                    if (pattern.getOrNull(index + 1) == '*') {
                        regexPattern.append(MULTI_WILD)
                        index++
                    } else {
                        regexPattern.append(SINGLE_WILD)
                    }
                }
                '.' -> if (hasWildcard) regexPattern.append(PACKAGE_SEGMENT) else keyBuilder.append(c)
                '<' -> hitGenericOpener = true
                else -> if (hasWildcard) regexPattern.append(c) else keyBuilder.append(c)
            }
            index++
        }
        regex = if (regexPattern.isNotEmpty()) Regex(regexPattern.toString()) else null

        val genericMask = match.groups["genericmask"]
        if (genericMask == null) {
            key = keyBuilder.toString()
            mask = 0.inv()
        } else {
            mask = genericMask.value.split(',').map { if (it == "*") 1 else 0 }.reduceIndexed { i, acc, flag -> acc or (flag shl i) }
            key = keyBuilder.subSequence(0, genericMask.range.first - 1).toString()
        }
    }

    fun matches(fqName: String): Boolean {
        if (pattern == "**") return true
        if (!fqName.startsWith(key)) return false
        val suffix = fqName.substring(key.length)
        return if (regex != null) regex.matches(suffix) else suffix.isEmpty()
    }

    private companion object {
        const val SINGLE_WILD = "\\w+"
        const val MULTI_WILD = "[\\w\\.]+"
        const val PACKAGE_SEGMENT = "\\."
        val VALID_PATTERN = Regex(
            "((\\w+\\*{0,2}|\\*{1,2})\\.)*" +
                "((\\w+(<?(?<genericmask>([*|_],)*[*|_])>)+)|(\\w+\\*{0,2}|\\*{1,2}))",
        )
    }
}

/** The project's stable type patterns, asked as one. */
class StableTypeMatchers(private val matchers: List<StableTypeMatcher>) {
    val isEmpty: Boolean get() = matchers.isEmpty()

    /** The mask of the first pattern that names [fqName], or `null` when none does. */
    fun maskFor(fqName: String): Int? = matchers.firstOrNull { it.matches(fqName) }?.mask

    /** Whether [fqName] or one of [superTypeFqNames] is named by a pattern. */
    fun matches(fqName: String, superTypeFqNames: List<String>): Boolean {
        if (matchers.isEmpty()) return false
        return maskFor(fqName) != null || superTypeFqNames.any { maskFor(it) != null }
    }
}
