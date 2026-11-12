// KOTRAIL_CONFIG: rules.idempotent=on, rules.idempotent.allow=Audit.record,kotlin.text.StringBuilder.append*,java.lang.StringBuilder.append*, rules.idempotent.deny=Mailer.send,charge
import com.kitakkun.kotrail.idempotency.Idempotent

object Audit {
    val entries = mutableListOf<String>()
    fun record(entry: String) {}
}

class Mailer {
    fun send(to: String) {}
    fun preview(to: String) = to
}

fun charge(amount: Int) {}

class Checkout(private val mailer: Mailer) {
    val receipt = StringBuilder()

    @Idempotent
    fun confirm(to: String) {
        // deny adds to the built-in table.
        <!KOTRAIL_NOT_IDEMPOTENT!>mailer.send(to)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>charge(10)<!>
        mailer.preview(to)
        // allow wins over the built-in table.
        Audit.record(to)
        receipt.append(to)
        <!KOTRAIL_NOT_IDEMPOTENT!>Audit.entries.add(to)<!>
    }

    @Idempotent
    fun confirmLocally(to: String) {
        // A denied call on an object the function created is its own business.
        Mailer().send(to)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, flexibleType, functionDeclaration, integerLiteral, javaFunction,
objectDeclaration, primaryConstructor, propertyDeclaration */
