// KOTRAIL_CONFIG: rules.noParameterComments=on

package custom

// Reported: a comment between parameters, in every spelling.
fun connect(
    host: String,
    <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>// how many times to retry before giving up<!>
    retries: Int,
    timeoutMillis: Long, <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/* per attempt */<!>
    <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/** Whether to fall back to plain HTTP. */<!>
    allowInsecure: Boolean,
): String = "$host:$retries:$timeoutMillis:$allowInsecure"

// Reported: a comment before the first parameter, and one after the last.
fun send(<!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/* the payload */<!> body: String, sent: Boolean <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/* already? */<!>): Int = body.length + if (sent) 1 else 0

// Reported: the primary constructor is a parameter list too.
class Client(
    val host: String,
    <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>// milliseconds<!>
    val timeout: Long,
) {
    // Reported: so is a secondary one.
    constructor(host: String <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/* defaults the timeout */<!>) : this(host, 1_000)
}

// Not reported: KDoc right before a property declared in the primary constructor is the property's KDoc,
// with annotations or modifiers between them or not.
class Monster(
    /** The card this monster was read from. */
    val cardKey: String,
    /** Hit points left. */
    var health: Int,
    /** The name shown on the card. */
    @Suppress("unused")
    private val title: String,
)

// Reported: KDoc on a plain constructor parameter, comments other than KDoc on a property, KDoc with a comment
// between it and the property, and comments trailing a property.
class Card(
    <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/** The deck this card was drawn from. */<!>
    deck: String,
    <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>// The cost to play it.<!>
    val cost: Int,
    <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/* The rarity tier. */<!>
    val rarity: Int,
    <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/** The artist. */<!>
    <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>// credited on the back<!>
    val artist: String,
    val power: Int, <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>// before modifiers<!>
    val toughness: Int <!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/** Trailing, not before the next one. */<!>,
    val flavor: String,
) {
    // Reported: KDoc on a secondary constructor's parameter.
    constructor(<!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/** The deck. */<!> deck: String) : this(deck, 0, 0, "", 0, 0, "")
}

// Reported: an anonymous function declared with `fun`.
val handler = fun(<!KOTRAIL_COMMENT_IN_PARAMETER_LIST!>/* the event */<!> code: Int): Int = code

/**
 * Not reported: what the comments above should have been.
 *
 * @param host where to connect
 * @param retries how many times to retry before giving up
 */
fun documented(host: String, retries: Int): String = "$host:$retries"

// Not reported: a comment above the declaration.
fun above(host: String): String = host

fun body(host: String): String {
    // Not reported: a comment in the body.
    return host /* nor a trailing one */
}

// Not reported: a comment in a call's argument list is not a parameter list.
val called = connect("h", /* retries */ 3, 10L, false)

// Not reported: lambda parameters, and comments inside the lambda.
val mapped = listOf(1).map { /* n */ n -> n + 1 }

// Not reported: a string that only looks like a comment.
fun literal(text: String = "// not a comment"): String = text

/* GENERATED_FIR_TAGS: additiveExpression, anonymousFunction, classDeclaration, functionDeclaration, ifExpression,
integerLiteral, lambdaLiteral, primaryConstructor, propertyDeclaration, secondaryConstructor, stringLiteral */
