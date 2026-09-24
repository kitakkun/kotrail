package com.kitakkun.kotrail.exclude

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExcludeParserTest {
    @Test
    fun `a disjunction of two atoms is false when neither matches`() {
        val predicate = ExcludeParser.parse("name(*Legacy*) || class(Generated*)")
        assertEquals(
            ExcludePredicate.Or(ExcludePredicate.NameIs(Glob("*Legacy*")), ExcludePredicate.ClassIs(Glob("Generated*"))),
            predicate,
        )
        assertFalse(predicate.matches(site(name = "plain")))
        assertTrue(predicate.matches(site(name = "parseLegacy")))
        assertTrue(predicate.matches(site(name = "map", classes = listOf("Inner", "GeneratedMapper"))))
    }

    @Test
    fun `bare class, function and property ask about the declaration's kind`() {
        val classOnly = ExcludeParser.parse("class && name(*Entity)")
        assertTrue(classOnly.matches(site(name = "UserEntity", classes = listOf("UserEntity"), kind = DeclarationKind.CLASS)))
        assertFalse(classOnly.matches(site(name = "UserEntity", classes = listOf("UserEntity"))))
        assertTrue(ExcludeParser.parse("function").matches(site(name = "f")))
        assertFalse(ExcludeParser.parse("property").matches(site(name = "f")))
        assertThrows(ExcludeParser.ExcludeSyntaxException::class.java) { ExcludeParser.parse("function(f)") }
    }

    @Test
    fun `globs match the whole value and treat star as any run of characters`() {
        assertTrue(Glob("com.acme.gen*").matches("com.acme.generated"))
        assertFalse(Glob("com.acme.gen*").matches("org.com.acme.generated"))
        assertTrue(Glob("*Test").matches("SpecTest"))
        assertFalse(Glob("Test").matches("SpecTest"))
        assertTrue(Glob("Ma?per").matches("Mapper"))
    }

    @Test
    fun `parentheses, negation and mixed operators parse with the expected precedence`() {
        val predicate = ExcludeParser.parse("extension(kotlin.String) || (suspend && visibility(private)) || !override")
        assertTrue(predicate.matches(site(name = "f", extension = "kotlin.String")))
        assertTrue(predicate.matches(site(name = "f", suspend = true, visibility = "private", override = true)))
        assertFalse(predicate.matches(site(name = "f", suspend = true, visibility = "public", override = true)))
        assertTrue(predicate.matches(site(name = "f")))
    }

    @Test
    fun `malformed predicates are rejected with a position`() {
        val error = assertThrows(ExcludeParser.ExcludeSyntaxException::class.java) { ExcludeParser.parse("name(*Legacy*) ||") }
        assertTrue(error.message.orEmpty().contains("position"), error.message)
        assertThrows(ExcludeParser.ExcludeSyntaxException::class.java) { ExcludeParser.parse("colour(red)") }
        assertThrows(ExcludeParser.ExcludeSyntaxException::class.java) { ExcludeParser.parse("suspend(x)") }
        assertThrows(ExcludeParser.ExcludeSyntaxException::class.java) { ExcludeParser.parse("visibility(secret)") }
    }

    private fun site(
        name: String,
        classes: List<String> = emptyList(),
        extension: String? = null,
        suspend: Boolean = false,
        visibility: String = "public",
        override: Boolean = false,
        kind: DeclarationKind = DeclarationKind.FUNCTION,
    ) = ReportSite(
        kind = kind,
        packageName = "custom",
        fileName = "File.kt",
        filePath = "/repo/src/main/kotlin/custom/File.kt",
        declarationName = name,
        classNames = classes,
        annotations = emptySet(),
        extensionReceiver = extension,
        contextParameters = emptyList(),
        visibility = visibility,
        isOverride = override,
        isSuspend = suspend,
        isInline = false,
        isComposable = false,
        isTest = false,
    )
}
