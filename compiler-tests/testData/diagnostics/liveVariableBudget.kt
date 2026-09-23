// KOTRAIL_CONFIG: rules.liveVariableBudget=on, rules.liveVariableBudget.max=4
class Order(val amount: Int, val placedAt: Int)

fun summarize(orders: List<Order>, rate: Int, label: String): String {
    val total = orders.sumOf { it.amount }
    val taxed = total * rate
    val count = orders.size
    val first = orders.first().placedAt
    // Reported: orders, label, taxed, count, first are live here (total and rate were last read above).
    <!KOTRAIL_TOO_MANY_LIVE_VARIABLES!>val last = orders.last().placedAt<!>
    return "$label $count $taxed $first $last"
}

// Not reported: never more than four live at once; each value is used and dropped.
fun stepwise(orders: List<Order>, rate: Int): Int {
    val total = orders.sumOf { it.amount }
    val taxed = total * rate
    val rounded = taxed / 10
    return rounded + orders.size
}

// Not reported: a variable declared in a branch is not live outside it.
fun branches(orders: List<Order>, rate: Int, flag: Boolean): Int {
    val total = orders.sumOf { it.amount }
    if (flag) {
        val a = total + 1
        val b = a + 2
        println(a + b)
    } else {
        val c = total + 3
        println(c)
    }
    return total * rate
}

// Reported: inside a loop, everything the loop reads stays live for every statement in it.
fun looping(orders: List<Order>, rate: Int, label: String, limit: Int): Int {
    var acc = 0
    for (order in orders) {
        <!KOTRAIL_TOO_MANY_LIVE_VARIABLES!>acc += order.amount * rate<!>
        if (acc > limit) println(label)
    }
    return acc
}


fun fetch(id: Int): Int = id

// Not reported: a variable is not live inside its own initializer, so the branches of `call`
// carry orders, rate, label, limit but not call itself.
fun ownInitializer(orders: List<Order>, rate: Int, label: String, limit: Int): Int {
    val call = if (rate > limit) {
        val first = fetch(rate)
        first + orders.size
    } else {
        val second = fetch(limit)
        second + label.length
    }
    return call
}

// Not reported: the parameters of a lambda end with the lambda, even inside a loop (rate, total,
// order, amounts are live at the `+=`, not `it` and `amount`); the two `e` of two catch clauses
// are never live together.
fun scoped(orders: List<Order>, rate: Int): Int {
    var total = 0
    for (order in orders) {
        val amounts = listOf(order).mapNotNull { it.amount.takeIf { amount -> amount > rate } }
        total += amounts.size
    }
    try {
        total += fetch(total)
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: Throwable) {
        println(e)
    }
    return total
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, comparisonExpression, forLoop,
functionDeclaration, ifExpression, integerLiteral, lambdaLiteral, localProperty, multiplicativeExpression,
primaryConstructor, propertyDeclaration, stringLiteral */
