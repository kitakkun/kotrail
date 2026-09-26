// KOTRAIL_CONFIG: rules.nullChainLength=on
class Profile(val displayName: String?, val email: String?)
class User(val profile: Profile?, val name: String?)
class Cache(val name: String?)

fun fallback(): String? = "fallback"

fun examples(user: User?, cached: Cache?): String {
    // Reported: three fallbacks in one expression (default limit 2).
    val a = <!KOTRAIL_ELVIS_CHAIN_TOO_LONG!>user?.profile?.displayName ?: user?.profile?.email ?: cached?.name ?: "unknown"<!>

    // Reported: the association does not matter.
    val b = <!KOTRAIL_ELVIS_CHAIN_TOO_LONG!>((user?.name ?: cached?.name) ?: fallback()) ?: "unknown"<!>

    // Not reported: two fallbacks.
    val c = user?.name ?: cached?.name ?: "unknown"

    // Not reported: the trailing escape is not a candidate.
    val d = user?.name ?: cached?.name ?: return "none"
    val e = user?.name ?: cached?.name ?: throw IllegalStateException()

    // Not reported: a chain inside a lambda is its own expression.
    val f = user?.name ?: run { cached?.name ?: fallback() ?: "x" }

    // Not reported: a priority list of plain names is clearest as a chain; only computed candidates count.
    val explicit: String? = user?.name
    val inherited: String? = cached?.name
    val default: String? = fallback()
    val h = explicit ?: inherited ?: default ?: "none"

    // Reported: the same length with computed candidates.
    val i = <!KOTRAIL_ELVIS_CHAIN_TOO_LONG!>user?.name ?: cached?.name ?: fallback() ?: "none"<!>

    // Not reported: safe calls are not counted unless maxSafeCalls is set.
    val g = user?.profile?.displayName?.length?.toString()

    return a + b + c + d + e + f + g + h + i
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, elvisExpression, functionDeclaration, lambdaLiteral,
localProperty, nullableType, primaryConstructor, propertyDeclaration, safeCall, stringLiteral */
