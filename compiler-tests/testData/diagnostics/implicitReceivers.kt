// KOTRAIL_CONFIG: rules.implicitReceivers=on
class View {
    var text: String = ""
    fun invalidate() {}
    fun title(): String = "view"
}

class Screen(val view: View) {
    var text: String = ""
    fun title(): String = "screen"
    val subtitle: String = "s"

    fun bind() {
        view.apply {
            // Reported: both View and Screen have a text and a title; the nearer one wins silently.
            <!KOTRAIL_IMPLICIT_RECEIVER_AMBIGUOUS!>text<!> = "a"
            <!KOTRAIL_IMPLICIT_RECEIVER_AMBIGUOUS!>title()<!>

            // Not reported: qualified.
            this.text = "b"
            this@Screen.text = "c"
            this@Screen.title()

            // Not reported: only one receiver has the name, whichever one it is.
            invalidate()
            subtitle.length
            log()
        }

        // Not reported: a class member used inside a scope lambda is the ordinary way to write Kotlin.
        with(view) {
            invalidate()
            subtitle.length
            log()
        }
        listOf(1).forEach { log() }
    }

    private fun log() {}

    // Reported: the extension receiver and the class both have a text.
    fun View.show() {
        <!KOTRAIL_IMPLICIT_RECEIVER_AMBIGUOUS!>text<!> = subtitle
        invalidate()
    }

    // Not reported: a nested class starts over; Screen's members are not in scope.
    class Nested {
        var text: String = ""
        fun run() {
            text = "n"
        }
    }

    inner class Inner {
        var text: String = ""

        // Reported: Inner and Screen both have a text.
        fun run() {
            <!KOTRAIL_IMPLICIT_RECEIVER_AMBIGUOUS!>text<!> = "i"
        }
    }
}

// Not reported: a top-level extension has one receiver.
fun View.reset() {
    text = ""
    invalidate()
}


class Builder {
    fun put(key: String) {}
    fun nested(block: Builder.() -> Unit) = Builder().block()
}

class Session {
    fun start() {}
}

class Client {
    fun run(block: Session.() -> Unit) = Session().block()

    // Not reported: a member extension takes both receivers as one declaration.
    private fun Session.configure() {}

    fun connect() {
        run {
            configure()
            start()
        }
    }

    // Not reported: every receiver has a toString; that another one has it too says nothing.
    fun describe(view: View): String = view.run { toString() }

    // Not reported: a builder nested in a builder of the same type means the nearest one.
    fun build(builder: Builder) {
        builder.nested {
            put("a")
            nested { put("b") }
        }
    }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, funWithExtensionReceiver, functionDeclaration, inner,
integerLiteral, lambdaLiteral, nestedClass, primaryConstructor, propertyDeclaration, stringLiteral, thisExpression */
