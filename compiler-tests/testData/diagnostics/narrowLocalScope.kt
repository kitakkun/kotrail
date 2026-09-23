// KOTRAIL_CONFIG: rules.narrowLocalScope=on
class User(val name: String, val isGuest: Boolean, val tags: List<String>)

fun format(user: User): String = user.name.uppercase()

fun greet(user: User, count: Int, fallback: String?): String {
    // Reported: only the else branch reads it, and the initializer is a plain read.
    val <!KOTRAIL_NARROW_LOCAL_SCOPE!>name<!> = user.name
    if (user.isGuest) {
        println("guest")
    } else {
        println(name)
    }

    // Reported: a string template, an elvis, and built-in arithmetic are pure too.
    val <!KOTRAIL_NARROW_LOCAL_SCOPE!>label<!> = "${user.name} ($count)"
    val <!KOTRAIL_NARROW_LOCAL_SCOPE!>shown<!> = fallback ?: user.name
    val <!KOTRAIL_NARROW_LOCAL_SCOPE!>next<!> = count + 1
    when {
        count > 3 -> {
            println(label)
            println(shown)
            println(next)
        }
        else -> println("few")
    }

    // Not reported: the initializer calls a function; moving it would change when it runs.
    val formatted = format(user)
    if (count > 0) {
        println(formatted)
    }

    // Not reported: used in the condition itself, so it is used outside every branch.
    val threshold = count * 2
    if (threshold > 10) {
        println(threshold)
    }

    // Not reported: used in two branches.
    val both = user.name
    if (count > 1) {
        println(both)
    } else {
        println(both + "!")
    }

    // Not reported: used inside a loop body; the read would happen on every iteration.
    val perItem = user.name
    for (tag in user.tags) {
        println(perItem + tag)
    }

    // Not reported: used inside a lambda, which runs later or never.
    val captured = user.name
    user.tags.forEach { println(captured + it) }

    // Not reported: used in the declaring block as well as in a branch.
    val direct = user.name
    println(direct)
    if (count > 2) {
        println(direct)
    }

    // Reported: a try block is a branch too.
    val <!KOTRAIL_NARROW_LOCAL_SCOPE!>attempt<!> = user.name
    try {
        println(attempt)
    } catch (e: IllegalStateException) {
        println("failed")
    }

    // Not reported: a branch without braces has no place to move the declaration into, but the reader still carries it.
    val <!KOTRAIL_NARROW_LOCAL_SCOPE!>brief<!> = user.name
    if (count > 4) println(brief)

    return "done"
}

fun far(user: User, count: Int): String {
    // Reported: first used seven lines below, with unrelated work in between.
    val <!KOTRAIL_LOCAL_DECLARED_TOO_EARLY!>greeting<!> = "Hello, ${user.name}"
    println("one")
    println("two")
    println("three")
    println("four")
    println("five")
    println("six")
    println(greeting)

    // Not reported: used within the limit.
    val near = user.name
    println("a")
    println("b")
    println(near)

    // Not reported: the initializer reads a var that a statement in between assigns.
    var mutable = count
    val snapshot = mutable + 1
    println("x")
    println("y")
    println("z")
    println("w")
    println("v")
    println("u")
    mutable = 0
    println(snapshot + mutable)

    // Reported: the var it reads is left alone until the first use, so the value is the same either way.
    var stable = count
    val <!KOTRAIL_LOCAL_DECLARED_TOO_EARLY!>copy<!> = stable + 1
    println("x")
    println("y")
    println("z")
    println("w")
    println("v")
    println("u")
    println(copy)
    stable = 0

    // Not reported: the var is assigned inside a lambda, which any call in between could run.
    var captured = count
    val fromCaptured = captured + 1
    val bump = { captured += 1 }
    println("x")
    println("y")
    println("z")
    println("w")
    println("v")
    println(fromCaptured)
    bump()

    // Not reported: the initializer calls a function.
    val computed = format(user)
    println("p")
    println("q")
    println("r")
    println("s")
    println("t")
    println("o")
    println(computed)

    return greeting
}


class Colors(val border: String, val selection: String)

fun row(modifier: String, content: () -> Unit) { content() }

// Not reported: the else branch reads it, and so does a lambda nested in a lambda further down.
fun segmented(enabled: Boolean, options: List<String>, theme: Colors) {
    val colors = theme
    row(modifier = if (enabled) "plain" else colors.border) {
        options.forEach { option ->
            val background = when {
                option.isEmpty() -> colors.selection
                else -> "none"
            }
            println(background)
        }
    }
}

// Not reported, and no crash: the entries of a destructuring follow it as statements inside its own range.
fun destructure(first: Pair<String, Int>, last: Pair<String, Int>): String {
    val (name, count) = first
    val (again, more) = last
    println(again)
    println(more)
    return if (count > 0) name else "none"
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, comparisonExpression, elvisExpression, forLoop,
functionDeclaration, ifExpression, integerLiteral, lambdaLiteral, localProperty, multiplicativeExpression, nullableType,
primaryConstructor, propertyDeclaration, stringLiteral, tryExpression, whenExpression */
