// KOTRAIL_CONFIG: rules.mustBeSerializable=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSerializable
import com.kitakkun.kotrail.serialization.MustBeSerializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class Filter(val query: String, val page: Int)

data class Draft(val text: String, val revision: Int)

enum class Tab { Home, Search }

@Serializable
class Envelope<T>(val payload: T)

fun <@MustBeSerializable T : Any> persist(value: T) {
    println(value)
}

fun send(@MustBeSerializable payload: Any) {
    println(payload)
}

fun <T : Any> keep(value: T) {
    println(value)
}

fun serializerFor(): KSerializer<Draft> = throw UnsupportedOperationException()

// The type argument comes from a lambda: its result, or its parameter.
fun <@MustBeSerializable T : Any> restore(init: () -> T): T = init()

fun <@MustBeSerializable T : Any> onEvent(handler: (T) -> Unit) {
    println(handler)
}

// Two type parameters, only the second under contract.
fun <K : Any, @MustBeSerializable V : Any> cache(key: K, value: V) {
    println("$key=$value")
}

// Two type parameters, both under contract.
fun <@MustBeSerializable A : Any, @MustBeSerializable B : Any> pair(a: A, b: B) {
    println("$a,$b")
}

@Composable
fun Screen() {
    // Not reported: an @Serializable class, an enum, primitives, and collections of them.
    val filter = rememberSerializable { Filter("q", 1) }
    val tab = rememberSerializable { Tab.Home }
    val count = rememberSerializable { 0 }
    val names = rememberSerializable { listOf("a") }
    val wrapped = rememberSerializable { Envelope(Filter("q", 2)) }

    // Reported: a plain data class has no serializer.
    val draft = <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>rememberSerializable { Draft("d", 1) }<!>

    // Reported: a collection of a non-serializable class.
    val drafts = <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>rememberSerializable { listOf(Draft("d", 1)) }<!>

    // Reported: a serializable wrapper around a non-serializable argument.
    val badEnvelope = <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>rememberSerializable { Envelope(Draft("d", 1)) }<!>

    // Not reported: an explicit serializer takes responsibility.
    val custom = rememberSerializable(serializer = serializerFor()) { Draft("d", 1) }

    Text("$filter $tab $count $names $wrapped $draft $drafts $badEnvelope $custom")
}

fun contracts() {
    // @MustBeSerializable on a type parameter: checked per call site.
    persist(Filter("q", 1))
    <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>persist(Draft("d", 1))<!>

    // @MustBeSerializable on a value parameter: the argument's type is checked.
    send(Tab.Search)
    <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>send(Draft("d", 1))<!>

    // Not reported: no contract at all.
    keep(Draft("d", 1))

    // Several type parameters: each annotated one is checked by position, whether the type
    // arguments are written or inferred.
    cache(Draft("k", 1), Filter("q", 1))
    <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>cache(Filter("q", 1), Draft("d", 1))<!>
    <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>cache<String, Draft>("k", Draft("d", 1))<!>
    pair(Filter("q", 1), Tab.Home)
    // Reported twice: both arguments fail their own contract.
    <!KOTRAIL_TYPE_NOT_SERIALIZABLE, KOTRAIL_TYPE_NOT_SERIALIZABLE!>pair(Draft("d", 1), Envelope(Draft("e", 2)))<!>

    // Inferred from a lambda's result or parameter type, in a call made inside another lambda.
    val fromLambda = restore { Filter("q", 1) }
    val badFromLambda = <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>restore { Draft("d", 1) }<!>
    onEvent { event: Filter -> println(event) }
    <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>onEvent { event: Draft -> println(event) }<!>
    listOf(1).forEach { <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>persist(Draft("d", it))<!> }
    println("$fromLambda $badFromLambda")

    // Nested type arguments are walked: a Map needs both its key and its value serializable.
    persist(mapOf("k" to Filter("q", 1)))
    <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>persist(mapOf("k" to Draft("d", 1)))<!>
    <!KOTRAIL_TYPE_NOT_SERIALIZABLE!>persist(mapOf(Draft("d", 1) to "v"))<!>
    persist(Envelope(listOf(Tab.Home to Filter("q", 1))))
}

// Not reported: a type parameter is checked where it is finally instantiated, not here.
fun <T : Any> forward(value: T) = persist(value)

/* GENERATED_FIR_TAGS: classDeclaration, data, enumDeclaration, enumEntry, functionDeclaration, integerLiteral,
lambdaLiteral, localProperty, nullableType, primaryConstructor, propertyDeclaration, stringLiteral, typeConstraint,
typeParameter */
