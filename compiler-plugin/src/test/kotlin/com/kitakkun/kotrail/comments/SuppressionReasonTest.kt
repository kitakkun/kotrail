package com.kitakkun.kotrail.comments

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuppressionReasonTest {
    private val annotation = "@Suppress(\"KOTRAIL_X\")"

    /** Whether the annotation at [occurrence] (first by default) in [text] has a reason. */
    private fun hasReason(text: String, occurrence: Int = 0): Boolean {
        var start = -1
        repeat(occurrence + 1) { start = text.indexOf(annotation, start + 1) }
        check(start >= 0) { "occurrence $occurrence of the annotation is not in the text" }
        return SuppressionReason.hasReason(text, start, start + annotation.length) { line -> annotation in line }
    }

    @Test
    fun `a comment on the same line after the annotation counts`() {
        assertTrue(hasReason("$annotation // the map is built above with String keys\nfun f() {}"))
    }

    @Test
    fun `a comment on the line directly above counts`() {
        assertTrue(hasReason("// the SDK throws its own base type\n$annotation\nfun f() {}"))
    }

    @Test
    fun `a KDoc ending on the line above counts`() {
        assertTrue(hasReason("/**\n * Polls the device.\n */\n$annotation\nfun f() {}"))
    }

    @Test
    fun `a blank line between the comment and the annotation counts`() {
        assertTrue(hasReason("// a section comment\n\n$annotation\nfun f() {}"))
    }

    @Test
    fun `a comment two lines above does not count`() {
        assertFalse(hasReason("// about g\nfun g() {}\n$annotation\nfun f() {}"))
    }

    @Test
    fun `a block comment on the same line before the annotation counts`() {
        assertTrue(hasReason("/* the cast is checked by the schema */ $annotation fun f() {}"))
    }

    @Test
    fun `an annotation in the middle of a modifier list line looks at that line`() {
        assertTrue(hasReason("public $annotation inline fun f() {} // inlined for the call sites"))
        assertFalse(hasReason("public $annotation inline fun f() {}"))
    }

    @Test
    fun `a comment above a group counts for every member`() {
        val text = "// legacy entry points kept for the old callers\n$annotation fun a() {}\n\n$annotation fun b() {}\n\n$annotation fun c() {}"
        assertTrue(hasReason(text, 0))
        assertTrue(hasReason(text, 1))
        assertTrue(hasReason(text, 2))
    }

    @Test
    fun `a plain declaration breaks the group`() {
        val text = "// legacy entry points kept for the old callers\n$annotation fun a() {}\n\nfun plain() {}\n\n$annotation fun b() {}\n\n$annotation fun c() {}"
        assertTrue(hasReason(text, 0))
        assertFalse(hasReason(text, 1))
        assertFalse(hasReason(text, 2))
    }

    @Test
    fun `a multi-line member and its closing brace are walked through`() {
        val text = "// legacy entry points kept for the old callers\n$annotation\nfun a() {\n    body()\n}\n\n$annotation fun b() {}"
        assertTrue(hasReason(text, 1))
    }

    @Test
    fun `a documented declaration above the group is not the group's comment`() {
        val text = "/** About Foo. */\nclass Foo {\n    fun x() {}\n}\n\n$annotation fun b() {}"
        assertFalse(hasReason(text))
    }

    @Test
    fun `a comment three lines above a single annotation with two blank lines between counts`() {
        assertTrue(hasReason("// a section comment\n\n\n$annotation\nfun f() {}"))
    }

    @Test
    fun `a comment with three blank lines between it and the annotation does not count`() {
        assertFalse(hasReason("// a section comment\n\n\n\n$annotation\nfun f() {}"))
    }

    @Test
    fun `a section comment a blank line above a group counts for every member`() {
        val text = "// Each operation below is kept for the old callers.\n\n$annotation fun a() {}\n\n$annotation fun b() {}\n\n$annotation fun c() {}"
        assertTrue(hasReason(text, 0))
        assertTrue(hasReason(text, 1))
        assertTrue(hasReason(text, 2))
    }

    @Test
    fun `a file header set off by a blank line is not the reason for a file annotation`() {
        val written = "@file:Suppress(\"KOTRAIL_X\")"
        val header = "// Copyright header\n\n"
        assertFalse(SuppressionReason.hasReason("$header$written\n", header.length, header.length + written.length) { line -> "@file:Suppress" in line })
    }

    @Test
    fun `a comment directly above a file annotation counts`() {
        val written = "@file:Suppress(\"KOTRAIL_X\")"
        val comment = "// the generated parser is long by design\n"
        assertTrue(SuppressionReason.hasReason("$comment$written\n", comment.length, comment.length + written.length) { line -> "@file:Suppress" in line })
    }

    @Test
    fun `a file annotation is not a group member`() {
        val text = "// the generated parser is long by design\n@file:Suppress(\"KOTRAIL_X\")\n\n$annotation\nfun f() {}"
        assertFalse(hasReason(text))
    }

    @Test
    fun `a comment inside a string argument is not a reason`() {
        val written = "@Suppress(\"KOTRAIL_X\", \"// not a comment\")"
        assertFalse(SuppressionReason.hasReason("$written\nfun f() {}", 0, written.length) { line -> "@Suppress" in line })
    }
}
