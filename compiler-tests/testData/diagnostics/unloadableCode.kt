// KOTRAIL_CONFIG: rules.unloadableCode=on, rules.unloadableCode.registrations=custom.register,java.lang.Runtime.addShutdownHook, rules.unloadableCode.disposableTypes=custom.Disposable
package custom

import java.lang.ThreadLocal

interface Disposable
class Entry(val name: String)

fun register(listener: Any, parent: Disposable? = null) {}

// Not reported: state that stays inside the loader goes with it.
object Registry {
    val entries = mutableListOf<Entry>()
    var current: Entry? = null
    val lazily by lazy { Entry("lazy") }
}

class Host {
    companion object {
        var instance: Host? = null
    }

    // Reported: a ThreadLocal, wherever it is declared.
    <!KOTRAIL_THREAD_LOCAL_IN_UNLOADABLE_CODE!>val buffer: ThreadLocal<Entry> = ThreadLocal()<!>

    fun install(parent: Disposable) {
        // Reported: registered for the rest of the platform's life, with nothing to undo it.
        <!KOTRAIL_UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE!>register(this)<!>
        <!KOTRAIL_UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE!>Runtime.getRuntime().addShutdownHook(Thread())<!>
        // Not reported: scoped to a disposable.
        register(this, parent)
        // Reported: a ThreadLocal constructed without a declaration, and one declared locally.
        <!KOTRAIL_THREAD_LOCAL_IN_UNLOADABLE_CODE!>ThreadLocal<Entry>()<!>.set(Entry("x"))
        <!KOTRAIL_THREAD_LOCAL_IN_UNLOADABLE_CODE!>val local = ThreadLocal.withInitial { Entry("local") }<!>
        println(local.get())
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, companionObject, flexibleType, functionDeclaration, interfaceDeclaration,
javaFunction, lambdaLiteral, localProperty, nullableType, objectDeclaration, outProjection, primaryConstructor,
propertyDeclaration, propertyDelegate, samConversion, stringLiteral, thisExpression */
