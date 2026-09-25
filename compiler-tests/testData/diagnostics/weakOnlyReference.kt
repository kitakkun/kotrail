// KOTRAIL_CONFIG: rules.weakOnlyReference=on
import java.lang.ref.WeakReference

fun interface Listener {
    fun onEvent(event: String)
}

class Registry {
    private val listeners = mutableListOf<WeakReference<Listener>>()
    private val handlers = mutableListOf<WeakReference<(String) -> Unit>>()
    private val kept = Listener { }

    fun subscribe(listener: WeakReference<Listener>) {
        listeners += listener
    }

    fun register(listener: Listener) {
        // Reported: the weak reference is the only reference to the lambda.
        handlers.add(<!KOTRAIL_WEAK_REFERENCE_TO_FRESH_OBJECT!>WeakReference<(String) -> Unit> { event -> println(event) }<!>)

        // Reported: an anonymous object, and a fresh instance, held by nothing else.
        subscribe(<!KOTRAIL_WEAK_REFERENCE_TO_FRESH_OBJECT!>WeakReference(object : Listener {
            override fun onEvent(event: String) {}
        })<!>)
        subscribe(<!KOTRAIL_WEAK_REFERENCE_TO_FRESH_OBJECT!>WeakReference(Listener { })<!>)

        // Reported: a local that is only ever wrapped.
        val fresh = Listener { }
        subscribe(<!KOTRAIL_WEAK_REFERENCE_TO_FRESH_OBJECT!>WeakReference(fresh)<!>)

        // Not reported: a parameter, a property, or a local that is used elsewhere too.
        subscribe(WeakReference(listener))
        subscribe(WeakReference(kept))
        val shared = Listener { }
        shared.onEvent("hello")
        subscribe(WeakReference(shared))
    }
}

/* GENERATED_FIR_TAGS: anonymousObjectExpression, classDeclaration, flexibleType, funInterface, functionDeclaration,
functionalType, interfaceDeclaration, javaFunction, lambdaLiteral, localProperty, override, propertyDeclaration,
stringLiteral */
