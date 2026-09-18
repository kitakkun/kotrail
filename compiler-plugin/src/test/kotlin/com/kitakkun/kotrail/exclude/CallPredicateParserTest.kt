package com.kitakkun.kotrail.exclude

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallPredicateParserTest {
    @Test
    fun `a call is matched by what is called and how`() {
        val launchOnGlobalScope = CallPredicateParser.parse("fqn(kotlinx.coroutines.launch) && receiver(kotlinx.coroutines.GlobalScope)")
        assertTrue(launchOnGlobalScope.matches(call(fqn = "kotlinx.coroutines.launch", receiver = "kotlinx.coroutines.GlobalScope")))
        assertFalse(launchOnGlobalScope.matches(call(fqn = "kotlinx.coroutines.launch", receiver = "kotlinx.coroutines.CoroutineScope")))

        val stringLog = CallPredicateParser.parse("fqn(com.acme.log) && extension(kotlin.String)")
        assertTrue(stringLog.matches(call(fqn = "com.acme.log", extension = "kotlin.String")))
        assertFalse(stringLog.matches(call(fqn = "com.acme.log", extension = "kotlin.Int")))

        val outsideIo = CallPredicateParser.parse("fqn(com.acme.io.read) && !context(com.acme.io.IoScope)")
        assertTrue(outsideIo.matches(call(fqn = "com.acme.io.read")))
        assertFalse(outsideIo.matches(call(fqn = "com.acme.io.read", contexts = listOf("com.acme.io.IoScope"))))

        val overload = CallPredicateParser.parse("fqn(com.acme.parse) && params(kotlin.String, kotlin.Int)")
        assertTrue(overload.matches(call(fqn = "com.acme.parse", params = listOf("kotlin.String", "kotlin.Int"))))
        assertFalse(overload.matches(call(fqn = "com.acme.parse", params = listOf("kotlin.String"))))

        val date = CallPredicateParser.parse("constructor(java.util.Date)")
        assertTrue(date.matches(call(fqn = "java.util.Date.<init>", constructed = "java.util.Date")))
        assertFalse(date.matches(call(fqn = "java.util.Date.getTime")))

        val anyPrint = CallPredicateParser.parse("fqn(kotlin.io.print*)")
        assertTrue(anyPrint.matches(call(fqn = "kotlin.io.println")))
        assertFalse(anyPrint.matches(call(fqn = "kotlin.io.readln")))
    }

    @Test
    fun `malformed call predicates are rejected with a position`() {
        assertThrows(ExcludeParser.ExcludeSyntaxException::class.java) { CallPredicateParser.parse("name(println)") }
        assertThrows(ExcludeParser.ExcludeSyntaxException::class.java) { CallPredicateParser.parse("fqn()") }
        assertThrows(ExcludeParser.ExcludeSyntaxException::class.java) { CallPredicateParser.parse("suspend(x)") }
        assertThrows(ExcludeParser.ExcludeSyntaxException::class.java) { CallPredicateParser.parse("fqn(a) &&") }
    }

    private fun call(
        fqn: String,
        constructed: String? = null,
        extension: String? = null,
        receiver: String? = null,
        contexts: List<String> = emptyList(),
        params: List<String> = emptyList(),
    ) = CallSite(
        fqn = fqn,
        constructedClass = constructed,
        extensionReceiver = extension,
        receiver = receiver,
        contextParameters = contexts,
        parameters = params,
        annotations = emptySet(),
        isSuspend = false,
        isComposable = false,
    )
}
