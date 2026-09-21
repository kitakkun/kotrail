// KOTRAIL_CONFIG: rules.preferValueClass=on
interface Identifier {
    val raw: Long
}

open class Entity

annotation class Model

// Reported: a single read-only property and nothing else.
data class <!KOTRAIL_PREFER_VALUE_CLASS!>UserId<!>(val raw: Long)

// Not reported: handled as Identifier, the value would be boxed anyway.
data class OrderId(override val raw: Long) : Identifier

// Not reported: a sealed action travels as its parent type and is boxed at every use.
sealed interface Action
data class SetEnabled(val enabled: Boolean) : Action

// Reported: computed properties and functions do not need a backing field.
data class <!KOTRAIL_PREFER_VALUE_CLASS!>Email<!>(val address: String) {
    val domain: String get() = address.substringAfter('@')

    fun isCorporate(): Boolean = domain == "example.com"
}

// Reported: nested (non-inner) classes are fine.
object Ids {
    data class <!KOTRAIL_PREFER_VALUE_CLASS!>SessionId<!>(val raw: String)
}

// Not reported: a value class property must be `val`.
data class Counter(var count: Int)

// Not reported: more than one property.
data class Point(val x: Int, val y: Int)

// Not reported: annotations often require a data class.
@Model
data class ModelId(val raw: Long)

// Not reported: a value class cannot extend a class.
data class EntityId(val raw: Long) : Entity()

// Not reported: already a value class.
@JvmInline
value class TagId(val raw: Long)

// Not reported: a property in the body needs a backing field.
data class Cached(val raw: Long) {
    val hash: Int = raw.hashCode()
}

// Not reported: a secondary constructor.
data class Wrapped(val raw: Long) {
    constructor(text: String) : this(text.length.toLong())
}

// Not reported: generic value classes are not considered.
data class Box<T>(val value: T)

// Not reported: local classes cannot be value classes.
class Holder {
    fun make(): Any {
        data class Local(val raw: Long)
        return Local(1L)
    }
}

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, data, equalityExpression, functionDeclaration, getter,
interfaceDeclaration, localClass, nestedClass, nullableType, objectDeclaration, override, primaryConstructor,
propertyDeclaration, secondaryConstructor, stringLiteral, typeParameter, value */
