// KOTRAIL_CONFIG: rules.nullChainLength=on, rules.nullChainLength.maxSafeCalls=3, rules.nullChainLength.maxElvis=0
// With maxSafeCalls set, a receiver chain longer than the limit is reported once, on the outermost access.
class Name(val value: String?)
class City(val name: Name?)
class Address(val city: City?)
class Customer(val address: Address?)
class Order(val customer: Customer?)

fun examples(order: Order?, other: Order?): String? {
    // Reported: four safe calls along one chain.
    val a = <!KOTRAIL_SAFE_CALL_CHAIN_TOO_LONG!>order?.customer?.address?.city?.name<!>

    // Not reported: three.
    val b = order?.customer?.address?.city

    // Not reported: a safe call inside an argument starts a chain of its own.
    val c = order?.customer?.address?.let { other?.customer?.address?.city }

    // Not reported: maxElvis is 0 here, so fallbacks are not counted.
    val d = a?.value ?: b?.name?.value ?: c?.name?.value ?: "unknown"

    return d
}

/* GENERATED_FIR_TAGS: classDeclaration, elvisExpression, functionDeclaration, lambdaLiteral, localProperty,
nullableType, primaryConstructor, propertyDeclaration, safeCall, smartcast, stringLiteral */
