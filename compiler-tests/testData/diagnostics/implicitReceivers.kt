// KOTRAIL_CONFIG: rules.implicitReceivers=on
class View {
    var text: String = ""
    fun invalidate() {}
}

@DslMarker
annotation class Html

@Html
class Tag {
    fun attr(name: String) {}
}

fun tag(block: Tag.() -> Unit) {}

class Screen(val view: View) {
    fun title(): String = "t"
    val subtitle: String = "s"

    fun bind() {
        view.apply {
            // Reported: title() and subtitle belong to Screen, but View is the nearest receiver.
            text = <!KOTRAIL_IMPLICIT_RECEIVER_FROM_OUTER_SCOPE!>title()<!>
            text = <!KOTRAIL_IMPLICIT_RECEIVER_FROM_OUTER_SCOPE!>subtitle<!>

            // Not reported: qualified, or resolved on the nearest receiver.
            text = this@Screen.title()
            invalidate()
            this.invalidate()
        }

        // Not reported: one receiver in scope.
        text()
        with(view) {
            // Reported: `view` itself is Screen's property, read through the outer receiver.
            <!KOTRAIL_IMPLICIT_RECEIVER_FROM_OUTER_SCOPE!>view<!>.text = <!KOTRAIL_IMPLICIT_RECEIVER_FROM_OUTER_SCOPE!>title()<!>

            // Not reported: qualified.
            this@Screen.view.invalidate()
        }

        // Not reported: a lambda without a receiver adds nothing.
        listOf(1).forEach { text() }

        // Not reported: a DslMarker receiver already forbids the outer access; the call resolves on Tag.
        tag { attr(<!KOTRAIL_IMPLICIT_RECEIVER_FROM_OUTER_SCOPE!>title()<!>) }
    }

    private fun text() {}

    // Reported: the extension receiver of the function is nearer than the class.
    fun View.show() {
        text = <!KOTRAIL_IMPLICIT_RECEIVER_FROM_OUTER_SCOPE!>title()<!>
        invalidate()
    }

    // Not reported: a nested class starts over; Screen's members are not in scope.
    class Nested {
        fun run() = 1
    }

    inner class Inner {
        // Reported: Screen is the outer receiver of an inner class.
        fun run(): String = <!KOTRAIL_IMPLICIT_RECEIVER_FROM_OUTER_SCOPE!>title()<!>
    }
}

// Not reported: a top-level extension has one receiver.
fun View.reset() {
    text = ""
    invalidate()
}

/* GENERATED_FIR_TAGS: annotationDeclaration, assignment, classDeclaration, funWithExtensionReceiver,
functionDeclaration, functionalType, inner, integerLiteral, lambdaLiteral, nestedClass, primaryConstructor,
propertyDeclaration, stringLiteral, thisExpression, typeWithExtension */
