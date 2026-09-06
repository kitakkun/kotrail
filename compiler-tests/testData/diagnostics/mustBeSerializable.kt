// KOTRAIL_CONFIG: rules.compose.previewRequired=false, rules.compose.composablesPerFile=false
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

@Composable
fun Screen() {
    // Not reported: an @Serializable class, an enum, primitives, and collections of them.
    val filter = rememberSerializable { Filter("q", 1) }
    val tab = rememberSerializable { Tab.Home }
    val count = rememberSerializable { 0 }
    val names = rememberSerializable { listOf("a") }
    val wrapped = rememberSerializable { Envelope(Filter("q", 2)) }

    // Reported: a plain data class has no serializer.
    val draft = <!TYPE_NOT_SERIALIZABLE!>rememberSerializable { Draft("d", 1) }<!>

    // Reported: a collection of a non-serializable class.
    val drafts = <!TYPE_NOT_SERIALIZABLE!>rememberSerializable { listOf(Draft("d", 1)) }<!>

    // Reported: a serializable wrapper around a non-serializable argument.
    val badEnvelope = <!TYPE_NOT_SERIALIZABLE!>rememberSerializable { Envelope(Draft("d", 1)) }<!>

    // Not reported: an explicit serializer takes responsibility.
    val custom = rememberSerializable(serializer = serializerFor()) { Draft("d", 1) }

    Text("$filter $tab $count $names $wrapped $draft $drafts $badEnvelope $custom")
}

fun contracts() {
    // @MustBeSerializable on a type parameter: checked per call site.
    persist(Filter("q", 1))
    <!TYPE_NOT_SERIALIZABLE!>persist(Draft("d", 1))<!>

    // @MustBeSerializable on a value parameter: the argument's type is checked.
    send(Tab.Search)
    <!TYPE_NOT_SERIALIZABLE!>send(Draft("d", 1))<!>

    // Not reported: no contract at all.
    keep(Draft("d", 1))
}

// Not reported: a type parameter is checked where it is finally instantiated, not here.
fun <T : Any> forward(value: T) = persist(value)

/* GENERATED_FIR_TAGS: classDeclaration, data, enumDeclaration, enumEntry, functionDeclaration, integerLiteral,
lambdaLiteral, localProperty, nullableType, primaryConstructor, propertyDeclaration, stringLiteral, typeConstraint,
typeParameter */
