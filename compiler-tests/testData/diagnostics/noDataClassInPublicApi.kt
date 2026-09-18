// EXPLICIT_API_MODE: STRICT
// KOTRAIL_CONFIG: rules.noDataClassInPublicApi=on

// This module compiles with explicit API mode, so it is a library and the rule applies.

// Reported: the constructor, copy() and componentN() of a public data class are a binary contract.
public data class <!KOTRAIL_DATA_CLASS_IN_PUBLIC_API!>Config<!>(val timeout: Int, val retries: Int)

// Reported: a subtype in a public sealed hierarchy is just as public.
public sealed interface Outcome {
    public data class <!KOTRAIL_DATA_CLASS_IN_PUBLIC_API!>Success<!>(val value: String, val elapsedMillis: Long) : Outcome
    public data object Cancelled : Outcome
}

// Not reported: internal is not part of the contract.
internal data class Draft(val text: String, val revision: Int)

// Not reported: nested inside an internal class, so not reachable from outside either.
internal class Session {
    public data class Token(val value: String, val issuedAt: Long)
}

// Not reported: a private nested data class.
public class Cache {
    private data class Entry(val value: String, val expiresAt: Long)

    public fun size(): Int = 0
}

// Not reported: a regular class promises only what it writes down.
public class Options(public val timeout: Int, public val retries: Int) {
    override fun equals(other: Any?): Boolean = other is Options && other.timeout == timeout && other.retries == retries
    override fun hashCode(): Int = 31 * timeout + retries
}

// Not reported: a data object has no properties for any of this to depend on.
public data object Defaults

/* GENERATED_FIR_TAGS: additiveExpression, andExpression, classDeclaration, data, equalityExpression,
functionDeclaration, integerLiteral, interfaceDeclaration, isExpression, multiplicativeExpression, nestedClass,
nullableType, objectDeclaration, operator, override, primaryConstructor, propertyDeclaration, sealed, smartcast */
