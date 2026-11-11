// KOTRAIL_CONFIG: rules.idempotent=on, rules.idempotent.strict=true, rules.idempotent.allow=Cache.load
import com.kitakkun.kotrail.idempotency.Idempotent

object Cache {
    fun load(key: String): String = key
}

object Network {
    fun fetch(key: String): String = key
    @Idempotent
    fun put(key: String) {}
}

class Screen {
    val seen = mutableSetOf<String>()
    var title = ""

    @Idempotent
    fun show(key: String, render: (String) -> Unit) {
        // Unknown callees are reported under strict.
        <!KOTRAIL_CALLEE_NOT_KNOWN_IDEMPOTENT!>Network.fetch(key)<!>
        <!KOTRAIL_CALLEE_NOT_KNOWN_IDEMPOTENT!>render(key)<!>
        <!KOTRAIL_CALLEE_NOT_KNOWN_IDEMPOTENT!>println(key)<!>
        <!KOTRAIL_CALLEE_NOT_KNOWN_IDEMPOTENT!>helper()<!>
        // Annotated, allowed, standard library, collections and local functions are known.
        Network.put(key)
        Cache.load(key)
        title = key.uppercase().trim()
        seen.add(key)
        fun format(value: String) = "[$value]"
        title = format(key)
        val list = listOf(key).map { it.length }.sum()
    }

    fun helper() {}
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, functionDeclaration, functionalType, lambdaLiteral, localFunction,
localProperty, objectDeclaration, propertyDeclaration, stringLiteral */
