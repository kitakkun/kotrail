// KOTRAIL_CONFIG: rules.implicitReceivers=on, rules.implicitReceivers.qualifyAmbiguous=false, rules.implicitReceivers.maxDepth=2
// With maxDepth set, the lambda that brings the count of implicit receivers past the limit is reported.
class View {
    var text: String = ""
}

class Screen(val view: View) {
    var text: String = ""

    fun bind() {
        // Not reported: Screen and the apply receiver make two.
        view.apply {
            text = "a"
            // Reported: buildString's receiver makes three.
            text = buildString <!KOTRAIL_TOO_MANY_IMPLICIT_RECEIVERS!>{ append("b") }<!>
        }

        // Not reported: a lambda without a receiver does not count.
        listOf(1).forEach { view.text = "c" }
    }

    // Not reported: qualifyAmbiguous is off here, although View and Screen both have a text.
    fun View.show() {
        text = bind().toString()
    }
}

// Not reported: one receiver plus buildString's makes two.
fun View.label(): String = buildString { append(text) }

/* GENERATED_FIR_TAGS: assignment, classDeclaration, flexibleType, funWithExtensionReceiver, functionDeclaration,
integerLiteral, javaFunction, lambdaLiteral, primaryConstructor, propertyDeclaration, stringLiteral */
