package com.kitakkun.kotrail.compose.insets

/**
 * A primitive window inset family. Composite families from
 * `com.kitakkun.kotrail.compose.insets.WindowInsetsType` expand to unions of these.
 */
enum class PrimitiveInset(val encodedName: String) {
    StatusBars("statusBars"),
    NavigationBars("navigationBars"),
    CaptionBar("captionBar"),
    DisplayCutout("displayCutout"),
    Ime("ime"),
    SystemGestures("systemGestures"),
    MandatorySystemGestures("mandatorySystemGestures"),
    TappableElement("tappableElement"),
    Waterfall("waterfall");

    companion object {
        private val byEncodedName = entries.associateBy { it.encodedName }
        fun fromEncodedName(name: String): PrimitiveInset? = byEncodedName[name]
    }
}

/**
 * Bit mask over sides, modeled exactly like `WindowInsetsSides` in Compose: the horizontal
 * sides are tracked separately for left-to-right and right-to-left layouts, so `Start`
 * (left in LTR, right in RTL) and `Left` (left in both) are distinct sets that overlap.
 */
object Sides {
    const val NONE = 0
    const val TOP = 1
    const val BOTTOM = 1 shl 1
    const val LEFT_IN_LTR = 1 shl 2
    const val RIGHT_IN_LTR = 1 shl 3
    const val LEFT_IN_RTL = 1 shl 4
    const val RIGHT_IN_RTL = 1 shl 5

    const val LEFT = LEFT_IN_LTR or LEFT_IN_RTL
    const val RIGHT = RIGHT_IN_LTR or RIGHT_IN_RTL
    const val START = LEFT_IN_LTR or RIGHT_IN_RTL
    const val END = RIGHT_IN_LTR or LEFT_IN_RTL
    const val HORIZONTAL = LEFT or RIGHT
    const val VERTICAL = TOP or BOTTOM
    const val ALL = HORIZONTAL or VERTICAL

    private val BITS = listOf(
        TOP to 't',
        BOTTOM to 'b',
        LEFT_IN_LTR to 'l',
        RIGHT_IN_LTR to 'r',
        LEFT_IN_RTL to 'L',
        RIGHT_IN_RTL to 'R',
    )

    /** Lossless encoding used in `@InferredWindowInsetsHandling`: one letter per bit, in [BITS] order. */
    fun encode(mask: Int): String = buildString {
        for ((bit, letter) in BITS) if (mask and bit != 0) append(letter)
    }

    fun decode(encoded: String): Int? {
        var mask = NONE
        for (ch in encoded) {
            mask = mask or (BITS.firstOrNull { it.second == ch }?.first ?: return null)
        }
        return mask
    }

    /**
     * Human-readable form. Horizontal bits are named by the largest matching Compose constants
     * (`left`, `right`, `start`, `end`); leftovers are spelled out with their layout direction.
     */
    fun describe(mask: Int): String {
        if (mask == ALL) return "all sides"
        if (mask == NONE) return "no sides"
        val parts = mutableListOf<String>()
        if (mask and TOP != 0) parts += "top"
        if (mask and BOTTOM != 0) parts += "bottom"
        var horizontal = mask and HORIZONTAL
        for ((named, label) in listOf(LEFT to "left", RIGHT to "right", START to "start", END to "end")) {
            if (horizontal and named == named) {
                parts += label
                horizontal = horizontal and named.inv()
            }
        }
        if (horizontal and LEFT_IN_LTR != 0) parts += "left in LTR"
        if (horizontal and RIGHT_IN_LTR != 0) parts += "right in LTR"
        if (horizontal and LEFT_IN_RTL != 0) parts += "left in RTL"
        if (horizontal and RIGHT_IN_RTL != 0) parts += "right in RTL"
        return parts.joinToString(", ")
    }

    /** Resolves a `WindowInsetsSide` entry name or a `WindowInsetsSides` constant name. */
    fun fromName(name: String): Int? = when (name) {
        "Top" -> TOP
        "Bottom" -> BOTTOM
        "Left" -> LEFT
        "Right" -> RIGHT
        "Start" -> START
        "End" -> END
        "Horizontal" -> HORIZONTAL
        "Vertical" -> VERTICAL
        "All" -> ALL
        else -> null
    }
}

/** An immutable set of (primitive inset, sides) pairs. Empty side masks are never stored. */
class InsetsSet private constructor(private val sidesByInset: Map<PrimitiveInset, Int>) {
    val isEmpty: Boolean get() = sidesByInset.isEmpty()
    val entries: Map<PrimitiveInset, Int> get() = sidesByInset

    fun union(other: InsetsSet): InsetsSet = build {
        sidesByInset.forEach { (k, v) -> merge(k, v) }
        other.sidesByInset.forEach { (k, v) -> merge(k, v) }
    }

    fun intersect(other: InsetsSet): InsetsSet = build {
        sidesByInset.forEach { (k, v) -> put(k, v and (other.sidesByInset[k] ?: Sides.NONE)) }
    }

    fun minus(other: InsetsSet): InsetsSet = build {
        sidesByInset.forEach { (k, v) -> put(k, v and (other.sidesByInset[k] ?: Sides.NONE).inv()) }
    }

    fun only(sides: Int): InsetsSet = build {
        sidesByInset.forEach { (k, v) -> put(k, v and sides) }
    }

    fun containsAll(other: InsetsSet): Boolean =
        other.sidesByInset.all { (k, v) -> ((sidesByInset[k] ?: Sides.NONE) and v) == v }

    fun encode(): List<String> =
        sidesByInset.entries.sortedBy { it.key.ordinal }.map { (k, v) -> "${k.encodedName}:${Sides.encode(v)}" }

    fun describe(): String =
        sidesByInset.entries.sortedBy { it.key.ordinal }.joinToString(", ") { (k, v) -> "${k.encodedName} (${Sides.describe(v)})" }

    override fun equals(other: Any?): Boolean = other is InsetsSet && other.sidesByInset == sidesByInset
    override fun hashCode(): Int = sidesByInset.hashCode()
    override fun toString(): String = "InsetsSet(${describe()})"

    companion object {
        val EMPTY = InsetsSet(emptyMap())

        fun decode(encoded: List<String>): InsetsSet? = build {
            for (entry in encoded) {
                val parts = entry.split(':', limit = 2)
                if (parts.size != 2) return null
                val inset = PrimitiveInset.fromEncodedName(parts[0]) ?: return null
                val mask = Sides.decode(parts[1]) ?: return null
                merge(inset, mask)
            }
        }

        /** Expands a `WindowInsetsType` entry name (or a `WindowInsets.Companion` property name) into primitives. */
        fun fromTypeName(name: String, sides: Int = Sides.ALL): InsetsSet? {
            val primitives: List<PrimitiveInset> = when (name) {
                "StatusBars", "statusBars" -> listOf(PrimitiveInset.StatusBars)
                "NavigationBars", "navigationBars" -> listOf(PrimitiveInset.NavigationBars)
                "CaptionBar", "captionBar" -> listOf(PrimitiveInset.CaptionBar)
                "DisplayCutout", "displayCutout" -> listOf(PrimitiveInset.DisplayCutout)
                "Ime", "ime" -> listOf(PrimitiveInset.Ime)
                "SystemGestures", "systemGestures" -> listOf(PrimitiveInset.SystemGestures)
                "MandatorySystemGestures", "mandatorySystemGestures" -> listOf(PrimitiveInset.MandatorySystemGestures)
                "TappableElement", "tappableElement" -> listOf(PrimitiveInset.TappableElement)
                "Waterfall", "waterfall" -> listOf(PrimitiveInset.Waterfall)
                "SystemBars", "systemBars" -> SYSTEM_BARS
                "SafeDrawing", "safeDrawing" -> SAFE_DRAWING
                "SafeGestures", "safeGestures" -> SAFE_GESTURES
                "SafeContent", "safeContent" -> SAFE_DRAWING + SAFE_GESTURES
                else -> return null
            }
            return build { primitives.forEach { merge(it, sides) } }
        }

        private val SYSTEM_BARS = listOf(PrimitiveInset.StatusBars, PrimitiveInset.NavigationBars, PrimitiveInset.CaptionBar)
        private val SAFE_DRAWING = SYSTEM_BARS + listOf(PrimitiveInset.DisplayCutout, PrimitiveInset.Ime)
        private val SAFE_GESTURES = listOf(
            PrimitiveInset.SystemGestures,
            PrimitiveInset.MandatorySystemGestures,
            PrimitiveInset.TappableElement,
            PrimitiveInset.Waterfall,
        )

        private inline fun build(block: Builder.() -> Unit): InsetsSet {
            val builder = Builder()
            builder.block()
            return InsetsSet(builder.result.filterValues { it != Sides.NONE })
        }

        private class Builder {
            val result = mutableMapOf<PrimitiveInset, Int>()
            fun put(inset: PrimitiveInset, sides: Int) { result[inset] = sides }
            fun merge(inset: PrimitiveInset, sides: Int) { result[inset] = (result[inset] ?: Sides.NONE) or sides }
        }
    }
}

/** Result of analyzing what a composable handles. [unverifiable] is set when an insets expression could not be evaluated. */
data class InsetsAnalysis(val handled: InsetsSet, val unverifiable: Boolean) {
    fun union(other: InsetsAnalysis): InsetsAnalysis =
        InsetsAnalysis(handled.union(other.handled), unverifiable || other.unverifiable)

    companion object {
        val EMPTY = InsetsAnalysis(InsetsSet.EMPTY, unverifiable = false)
        val UNKNOWN = InsetsAnalysis(InsetsSet.EMPTY, unverifiable = true)
    }
}
