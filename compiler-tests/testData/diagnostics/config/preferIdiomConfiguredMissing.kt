// KOTRAIL_CONFIG: rules.preferIdiom=on, rules.preferIdiom.calls=kotlin.collections.getOrNull(0) -> kotlin.collections.headOrNull
// A replacement that names no existing function is never asked for.
fun examples(items: List<Int>) {
    val head = items.getOrNull(0)
    println(head)
}

/* GENERATED_FIR_TAGS: functionDeclaration, integerLiteral, localProperty, nullableType, propertyDeclaration */
