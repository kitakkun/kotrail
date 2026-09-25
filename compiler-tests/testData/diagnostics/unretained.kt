// KOTRAIL_CONFIG: rules.unretained=on
import com.kitakkun.kotrail.lifetime.Unretained
import java.lang.ref.WeakReference

class Job(val name: String) {
    fun start() {}
    fun invokeOnCompletion(block: () -> Unit) {}
}

class Tracker(val name: String)

fun log(job: Job) {}
fun watch(@Unretained job: Job) {}
fun later(block: () -> Unit) {}

interface Holder {
    fun get(): Job
}

class Registry {
    private var current: Job? = null
    private val jobs = mutableListOf<Job>()
    private val weak = mutableMapOf<String, WeakReference<Job>>()
    private var named = mapOf<String, Job>()

    // Reported: stored in a property, added to a collection, passed to a plain function, captured by a lambda that
    // outlives the call.
    fun register(@Unretained job: Job, name: String) {
        <!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>current = job<!>
        jobs.add(<!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>job<!>)
        log(<!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>job<!>)
        later { log(<!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>job<!>) }
    }

    // Reported: an alias through a local escapes the same way.
    fun registerAlias(@Unretained job: Job) {
        val handle = job
        <!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>current = handle<!>
    }

    // Reported: put into a value built with `to`, which is then stored; the message names the builder.
    fun registerNamed(@Unretained job: Job, name: String) {
        named = named + (name to <!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>job<!>)
    }

    // Reported: an anonymous object, a local class and a local function each retain what they read.
    fun hold(@Unretained job: Job): Holder = object : Holder {
        override fun get(): Job = <!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>job<!>
    }

    fun holdLocally(@Unretained job: Job): Holder {
        class Local : Holder {
            override fun get(): Job = <!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>job<!>
        }
        fun fetch(): Job = <!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>job<!>
        log(fetch())
        return Local()
    }

    // Not reported: kept through a weak reference, used by calling it, handed a lambda of its own, passed to a
    // function that declares its parameter unretained, scoped with also and apply.
    fun observe(@Unretained job: Job, name: String) {
        weak[name] = WeakReference(job)
        job.start()
        job.invokeOnCompletion { weak.remove(name) }
        watch(job)
        job.also { it.start() }
        job.apply { start() }
        require(job.name.isNotEmpty())
    }

    // Reported: returned.
    fun handle(@Unretained job: Job): Job = <!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>job<!>

    // Not reported: no annotation, no contract.
    fun keep(job: Job) {
        current = job
    }
}

// Reported: a constructor property retains its argument from the start.
class Owner(<!KOTRAIL_UNRETAINED_PARAMETER_RETAINED!>@Unretained val job: Job<!>)

// Not reported: a plain constructor parameter that is only used.
class Reader(@Unretained job: Job) {
    val name: String = job.name
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, functionDeclaration, functionalType, javaFunction, lambdaLiteral,
localProperty, nullableType, primaryConstructor, propertyDeclaration */
