// KOTRAIL_CONFIG: rules.narrowModelParameters=true, narrowModelParameters.scope=all, narrowModelParameters.maxUnusedProperties=1
// With scope=all every function is inspected, and the stricter limit allows one unread property.

data class Order(val id: Long, val total: Int, val currency: String, val note: String)

// Reported: reads 1 of 4.
fun formatTotal(<!MODEL_PARAMETER_TOO_WIDE!>order<!>: Order): String = "${order.total}"

// Reported: reads 2 of 4 (2 unread, limit 1).
fun formatMoney(<!MODEL_PARAMETER_TOO_WIDE!>order<!>: Order): String = "${order.total} ${order.currency}"

// Not reported: reads 3 of 4.
fun summary(order: Order): String = "${order.id}: ${order.total} ${order.currency}"

// Not reported: overrides cannot change their signature.
interface Formatter {
    fun format(order: Order): String
}

class TotalFormatter : Formatter {
    override fun format(order: Order): String = "${order.total}"
}

// Not reported: the parameter is compared as a whole.
fun same(order: Order, other: Order): Boolean = order == other

// Not reported by this rule: the parameter is returned as a whole (the pass-through rule reports it instead).
fun identity(order: Order): Order = order

/* GENERATED_FIR_TAGS: classDeclaration, data, equalityExpression, functionDeclaration, interfaceDeclaration, override,
primaryConstructor, propertyDeclaration, stringLiteral */
