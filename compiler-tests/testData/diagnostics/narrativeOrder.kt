// KOTRAIL_CONFIG: rules.narrativeOrder=on
class Loader {
    // Reported: load() below is the first to call it.
    private fun <!KOTRAIL_HELPER_BEFORE_FIRST_USE!>parseHeader<!>(bytes: ByteArray): Int = bytes.size

    fun load(bytes: ByteArray): Int {
        val header = parseHeader(bytes)
        return header + parseBody(bytes)
    }

    // Not reported: declared after its first caller.
    private fun parseBody(bytes: ByteArray): Int = bytes.size * 2

    // Not reported: nothing in the class uses it.
    private fun unused(): Int = 0

    // Not reported: mutual recursion with its first caller.
    private fun ping(n: Int): Int = if (n == 0) 0 else pong(n - 1)
    private fun pong(n: Int): Int = if (n == 0) 0 else ping(n - 1)

    // Reported: referenced (not called) by a property initializer declared below.
    private fun <!KOTRAIL_HELPER_BEFORE_FIRST_USE!>format<!>(n: Int): String = n.toString()
    val formatter: (Int) -> String = ::format

    // Not reported: public functions may be called from anywhere; only private ones are ordered.
    fun helper(): Int = 1
    fun caller(): Int = helper()
}

// Reported: a private top-level function before its first caller in the file.
private fun <!KOTRAIL_HELPER_BEFORE_FIRST_USE!>trim<!>(s: String): String = s.trim()

fun normalize(s: String): String = trim(s).lowercase()

// Not reported: after its caller.
private fun lower(s: String): String = s.lowercase()

/* GENERATED_FIR_TAGS: additiveExpression, callableReference, classDeclaration, equalityExpression, functionDeclaration,
functionalType, ifExpression, integerLiteral, localProperty, multiplicativeExpression, propertyDeclaration */
