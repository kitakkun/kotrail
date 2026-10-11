package com.kitakkun.kotrail.fir

import com.kitakkun.kotrail.fir.HelperDistance.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HelperDistanceTest {
    private fun placed(
        name: String,
        lines: IntRange,
        references: Set<String>,
        kind: Kind,
        owner: String?,
        isPrivate: Boolean,
    ) = HelperDistance.Member(name, listOf(lines), references, kind, owner, companionOf = null, isObject = false, isPrivate = isPrivate, isConstant = false, builds = null)

    private fun far(target: String, vararg members: HelperDistance.Member<String>): HelperDistance.FarHelper<String>? =
        farWithin(100, 60, target, *members)

    private fun farWithin(maxCallerDistance: Int, maxHelperNoise: Int, target: String, vararg members: HelperDistance.Member<String>): HelperDistance.FarHelper<String>? {
        val byKey = members.associateBy { it.key }
        return HelperDistance.farFromCaller(byKey.getValue(target), byKey, maxCallerDistance, maxHelperNoise)
    }

    private fun effectiveDistance(helper: String, caller: String, vararg members: HelperDistance.Member<String>): Int {
        val byKey = members.associateBy { it.key }
        return HelperDistance.effectiveDistance(byKey.getValue(helper), byKey.getValue(caller), byKey)
    }

    private fun helperNoise(helper: String, caller: String, vararg members: HelperDistance.Member<String>): Double {
        val byKey = members.associateBy { it.key }
        return HelperDistance.helperNoise(byKey.getValue(helper), byKey.getValue(caller), byKey)
    }

    @Test
    fun `a helper exactly at the distance is mostly noise away but not yet far`() {
        val members = arrayOf(
            placed("load", 1..10, setOf("parse"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("unrelated", 11..100, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("parse", 101..110, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertEquals(100, effectiveDistance("parse", "load", *members))
        assertEquals(0.82, helperNoise("parse", "load", *members))
        assertNull(far("parse", *members))
    }

    @Test
    fun `a helper past the distance with mostly noise before it is far from its only caller`() {
        val found = far(
            "parse",
            placed("load", 1..10, setOf("parse"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("unrelated", 11..110, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("parse", 111..120, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertEquals("load", found?.caller?.key)
        assertEquals(110, found?.distance)
    }

    @Test
    fun `a helper is not far when its caller fills the gap, when it has no caller or two, or when it is above`() {
        val longCaller = far(
            "parse",
            placed("load", 1..150, setOf("parse"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("parse", 151..160, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertNull(longCaller)
        val noCaller = far(
            "parse",
            placed("load", 1..10, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("unrelated", 11..200, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("parse", 201..210, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertNull(noCaller)
        val twoCallers = far(
            "parse",
            placed("load", 1..10, setOf("parse"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("save", 11..20, setOf("parse"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("unrelated", 21..200, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("parse", 201..210, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertNull(twoCallers)
        val above = far(
            "parse",
            placed("parse", 1..10, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("unrelated", 11..200, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("load", 201..210, setOf("parse"), Kind.FUNCTION, owner = null, isPrivate = false),
        )
        assertNull(above)
    }

    @Test
    fun `the caller's closure is transitive and its cycles end`() {
        val members = arrayOf(
            placed("load", 1..10, setOf("first", "parse"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("first", 11..50, setOf("first", "second"), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("second", 51..90, setOf("first", "load"), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("parse", 91..100, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertEquals(10, effectiveDistance("parse", "load", *members))
        assertEquals(0.0, helperNoise("parse", "load", *members))
    }

    @Test
    fun `the caller's other helpers between it and a helper are neither distance nor noise`() {
        val members = arrayOf(
            placed("load", 1..10, setOf("first", "second", "third"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("first", 11..70, setOf("nested"), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("nested", 71..90, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("second", 91..130, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("third", 131..140, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertEquals(10, effectiveDistance("third", "load", *members))
        assertEquals(0.0, helperNoise("third", "load", *members))
        assertNull(far("third", *members))
    }

    @Test
    fun `unrelated code between counts where the caller's own helpers do not`() {
        val members = arrayOf(
            placed("load", 1..10, setOf("first", "third"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("first", 11..70, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("unrelated", 71..200, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("third", 201..210, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertEquals(140, effectiveDistance("third", "load", *members))
        assertEquals(0.62, helperNoise("third", "load", *members))
        val found = far("third", *members)
        assertEquals(200, found?.distance)
        assertEquals(140, found?.effectiveDistance)
        assertEquals(false, found?.above)
    }

    @Test
    fun `a run of helpers with one caller is measured from its member nearest the caller`() {
        val placedRun = arrayOf(
            placed("toolbar", 1..1, setOf("play", "pause", "stop", "skip"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("unrelated", 3..99, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("play", 101..101, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("pause", 103..103, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("stop", 105..105, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("skip", 107..107, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertEquals(103, effectiveDistance("skip", "toolbar", *placedRun))
        for (icon in listOf("play", "pause", "stop", "skip")) assertNull(far(icon, *placedRun), icon)

        val farRun = arrayOf(
            placed("toolbar", 1..1, setOf("play", "pause"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("unrelated", 3..100, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("play", 102..102, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("pause", 104..104, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertEquals(101, far("play", *farRun)?.effectiveDistance)
        assertEquals(102, far("pause", *farRun)?.effectiveDistance)

        val brokenRun = arrayOf(
            placed("toolbar", 1..1, setOf("play", "pause"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("unrelated", 3..99, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("play", 101..101, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("other", 103..103, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("pause", 105..105, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertNull(far("play", *brokenRun))
        assertEquals(103, far("pause", *brokenRun)?.effectiveDistance)
    }

    @Test
    fun `a private top-level property far above its only caller is far, a private function there is not`() {
        val property = far(
            "table",
            placed("table", 1..1, emptySet(), Kind.PROPERTY, owner = null, isPrivate = true),
            placed("unrelated", 2..150, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("load", 151..160, setOf("table"), Kind.FUNCTION, owner = null, isPrivate = false),
        )
        assertEquals("load", property?.caller?.key)
        assertEquals(150, property?.distance)
        assertEquals(true, property?.above)
        val function = far(
            "parse",
            placed("parse", 1..1, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("unrelated", 2..150, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("load", 151..160, setOf("parse"), Kind.FUNCTION, owner = null, isPrivate = false),
        )
        assertNull(function)
    }

    @Test
    fun `a constant is never far, above or below its caller`() {
        val constant = HelperDistance.Member("LIMIT", listOf(1..1), emptySet(), Kind.PROPERTY, null, null, false, true, true, null)
        assertNull(
            far(
                "LIMIT",
                constant,
                placed("unrelated", 2..150, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
                placed("load", 151..160, setOf("LIMIT"), Kind.FUNCTION, owner = null, isPrivate = false),
            ),
        )
    }

    @Test
    fun `a factory within 30 lines of the class it builds is placed by its type`() {
        fun factory(builds: IntRange) =
            HelperDistance.Member("newLoader", listOf(151..152), emptySet(), Kind.FUNCTION, null, null, false, true, false, builds)
        val rest = arrayOf(
            placed("load", 1..10, setOf("newLoader"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("unrelated", 11..150, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
        )
        assertNull(far("newLoader", factory(153..200), *rest))
        assertNull(far("newLoader", factory(100..121), *rest))
        assertEquals("load", far("newLoader", factory(183..200), *rest)?.caller?.key)
        assertEquals("load", far("newLoader", factory(20..40), *rest)?.caller?.key)
    }

    @Test
    fun `the properties of the caller's class are vocabulary and lead nowhere`() {
        val members = arrayOf(
            placed("View", 1..1, emptySet(), Kind.CLASS, owner = null, isPrivate = false),
            placed("title", 2..2, setOf("decorate"), Kind.PROPERTY, owner = "View", isPrivate = false),
            placed("render", 3..12, setOf("title", "format"), Kind.FUNCTION, owner = "View", isPrivate = false),
            placed("decorate", 13..62, emptySet(), Kind.FUNCTION, owner = "View", isPrivate = true),
            placed("format", 63..64, emptySet(), Kind.FUNCTION, owner = "View", isPrivate = true),
        )
        assertEquals(60, effectiveDistance("format", "render", *members))
    }

    @Test
    fun `companion constants are vocabulary, a top-level private property is followed`() {
        val companion = HelperDistance.Member("Parser.Companion", listOf(64..64), emptySet(), Kind.CLASS, "Parser", "Parser", true, false, false, null)
        fun layout(reads: String) = arrayOf(
            placed("cache", 1..1, setOf("describe"), Kind.PROPERTY, owner = null, isPrivate = true),
            placed("Parser", 2..2, emptySet(), Kind.CLASS, owner = null, isPrivate = false),
            placed("load", 3..12, setOf(reads, "format"), Kind.FUNCTION, owner = "Parser", isPrivate = false),
            placed("describe", 13..62, emptySet(), Kind.FUNCTION, owner = "Parser", isPrivate = true),
            placed("format", 63..63, emptySet(), Kind.FUNCTION, owner = "Parser", isPrivate = true),
            companion,
            placed("LIMIT", 65..65, setOf("describe"), Kind.PROPERTY, owner = "Parser.Companion", isPrivate = false),
        )
        assertEquals(60, effectiveDistance("format", "load", *layout("LIMIT")))
        assertEquals(10, effectiveDistance("format", "load", *layout("cache")))
    }

    @Test
    fun `only private functions outside objects and private top-level properties are helpers`() {
        val members = listOf(
            placed("Server", 1..1, emptySet(), Kind.CLASS, owner = null, isPrivate = false),
            placed("cache", 2..2, emptySet(), Kind.PROPERTY, owner = "Server", isPrivate = true),
            placed("start", 3..10, setOf("cache", "format", "DEFAULT_PORT", "table", "lookup"), Kind.FUNCTION, owner = "Server", isPrivate = false),
            placed("unrelated", 11..60, emptySet(), Kind.FUNCTION, owner = "Server", isPrivate = false),
            placed("format", 61..70, emptySet(), Kind.FUNCTION, owner = "Server", isPrivate = true),
            HelperDistance.Member("Server.Companion", listOf(71..71), emptySet(), Kind.CLASS, "Server", "Server", true, false, false, null),
            placed("DEFAULT_PORT", 72..72, emptySet(), Kind.PROPERTY, owner = "Server.Companion", isPrivate = true),
            placed("table", 80..80, emptySet(), Kind.PROPERTY, owner = null, isPrivate = true),
            HelperDistance.Member("Registry", listOf(90..90), emptySet(), Kind.CLASS, null, null, true, false, false, null),
            placed("lookup", 91..95, emptySet(), Kind.FUNCTION, owner = "Registry", isPrivate = true),
        ).toTypedArray()

        for (notHelper in listOf("DEFAULT_PORT", "lookup", "cache")) assertNull(farWithin(10, 50, notHelper, *members), notHelper)

        assertEquals(58, effectiveDistance("format", "start", *members))
        assertEquals(0.74, helperNoise("format", "start", *members))
        assertEquals("start", farWithin(10, 50, "format", *members)?.caller?.key)

        assertEquals(67, effectiveDistance("table", "start", *members))
        assertEquals(0.76, helperNoise("table", "start", *members))
        assertEquals("start", farWithin(10, 50, "table", *members)?.caller?.key)
    }

    private fun movedTogether(
        moved: String,
        anchor: IntRange,
        before: Boolean,
        maxCallerDistance: Int,
        vararg members: HelperDistance.Member<String>,
    ): List<String> {
        val byKey = members.associateBy { it.key }
        return HelperDistance.movedTogether(listOf(byKey.getValue(moved)), anchor, before, byKey, maxCallerDistance, 50).map { it.key }
    }

    private val releaseLayout = arrayOf(
        placed("update", 1..1, setOf("check"), Kind.FUNCTION, owner = null, isPrivate = false),
        placed("render", 2..20, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
        placed("check", 21..22, setOf("fetch", "isRateLimited", "describe"), Kind.FUNCTION, owner = null, isPrivate = true),
        placed("fetch", 23..23, setOf("download"), Kind.FUNCTION, owner = null, isPrivate = true),
        placed("download", 24..24, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        placed("isRateLimited", 25..25, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        placed("describe", 26..26, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        placed("audit", 27..27, setOf("describe"), Kind.FUNCTION, owner = null, isPrivate = false),
    )

    @Test
    fun `a moved helper takes along, in call order, the functions only it reaches that the move leaves far`() {
        assertEquals(listOf("check", "fetch", "download", "isRateLimited"), movedTogether("check", 1..1, false, 10, *releaseLayout))
    }

    @Test
    fun `with the distance check off, only a function the move leaves above its caller goes along`() {
        assertEquals(listOf("check"), movedTogether("check", 1..1, false, 0, *releaseLayout))
        val members = arrayOf(
            placed("parse", 1..1, setOf("magic", "sum"), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("magic", 2..2, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("load", 3..3, setOf("parse"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("verify", 4..4, setOf("sum"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("sum", 5..5, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertEquals(listOf("parse", "magic"), movedTogether("parse", 3..3, false, 0, *members))
    }

    @Test
    fun `a function the move leaves right below its caller stays, and so do the functions only it calls`() {
        val members = arrayOf(
            placed("update", 1..1, setOf("check"), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("retry", 2..3, setOf("backoff"), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("backoff", 4..4, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("render", 5..30, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("check", 31..31, setOf("retry"), Kind.FUNCTION, owner = null, isPrivate = true),
        )
        assertEquals(listOf("check"), movedTogether("check", 1..1, false, 10, *members))
    }

    @Test
    fun `a property moved down to its caller takes along the function only it calls`() {
        val members = arrayOf(
            placed("registry", 1..1, setOf("build"), Kind.PROPERTY, owner = null, isPrivate = true),
            placed("build", 2..2, emptySet(), Kind.FUNCTION, owner = null, isPrivate = true),
            placed("render", 3..30, emptySet(), Kind.FUNCTION, owner = null, isPrivate = false),
            placed("register", 31..31, setOf("registry"), Kind.FUNCTION, owner = null, isPrivate = false),
        )
        assertEquals(listOf("registry", "build"), movedTogether("registry", 31..31, true, 10, *members))
    }
}
