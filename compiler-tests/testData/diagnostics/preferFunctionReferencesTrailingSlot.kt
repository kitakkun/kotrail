// KOTRAIL_CONFIG: rules.preferFunctionReferences=on
// DUMP_KOTRAIL_FIXES
// A trailing lambda the fix moves into the parentheses keeps the parameter it binds to: by position
// when no argument is named, none goes to a vararg, and the arguments fill the parameters before the
// lambda's in order; by name otherwise. A lambda that is not trailing, inside the parentheses or the
// operand of an infix call, has the reference in its place, its label included. `written` at the end
// holds the calls those fixes leave, which compile.

import java.io.File

class User(val name: String)

fun String.shout(): String = uppercase()

class Mutex

fun <T> Mutex.withLock(owner: Any? = null, action: () -> T): T = action()

class Locator

infix fun Locator.onLocating(block: () -> Unit) = block()

class Tracker {
    fun onInstalling() {}
}

class Box<T>(val item: T) {
    fun visit(action: (T) -> Unit) = action(item)
    fun each(step: Int = 1, action: (T) -> Unit) = action(item)
}

fun interface Task {
    fun run()
}

fun shutDown() {}
fun consume(user: User) {}
fun pair(a: Int, b: Int): Int = a + b
fun configure(name: String = "", retries: Int = 0, onDone: () -> Unit) = onDone()
fun launchAll(vararg keys: Any?, block: () -> Unit) = block()
fun schedule(delay: Int = 0, task: Task) = task.run()
fun both(first: () -> Unit, flag: Boolean = false, second: () -> Unit) {
    if (flag) first() else second()
}

val names = listOf("a", "b")
val files = listOf(File("a"))
val users = listOf(User("a"))
val ints = listOf(1, 2)
val mutex = Mutex()
val locator = Locator()
val tracker = Tracker()
val box = Box(User("a"))
val handler: (Int, () -> Unit) -> Unit = { _, block -> block() }

fun named() {
    // By name: defaulted parameters come between the arguments and the lambda's parameter.
    names.joinToString(", ") <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.shout() }<!>
    files.joinToString <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.name }<!>
    mutex.withLock <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
    box.each <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ consume(it) }<!>
    schedule <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>

    // By name: a named argument comes before it, out of its place or in it.
    configure(name = "a") <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
    configure(name = "a", retries = 1) <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>

    // By name: a vararg parameter comes before it, with arguments or without.
    launchAll(1, 2) <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
    launchAll <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
}

fun positional() {
    // By position: the arguments fill every parameter before the lambda's, in order.
    users.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.name }<!>
    ints.zip(ints) <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ a, b -> pair(a, b) }<!>
    run() <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
    configure("a", 1) <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
    box.visit <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ consume(it) }<!>
    handler(1) <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
    Thread <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>

    // By position after a trailing comma, which stays last.
    configure(
        "a",
        1,
    ) <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
    configure("a", 1,) <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
}

fun inPlace() {
    // In place: the operand of an infix call.
    locator onLocating <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ tracker.onInstalling() }<!>
    locator onLocating <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>

    // In place: already inside the parentheses, by position or by name.
    names.joinToString(", ", transform = <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.shout() }<!>)
    users.map(<!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.name }<!>)

    // Each in its own slot: a lambda inside the parentheses and a trailing one.
    both(<!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>) <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
}

fun labeled() {
    // The label goes with the lambda.
    users.map step@<!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.name }<!>
    names.joinToString(", ") step@<!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.shout() }<!>
    users.map(step@<!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.name }<!>)
}

val labeledInitializer: (User) -> String = step@<!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.name }<!>

fun commented() {
    // Reported without a fix: a comment comes between the parentheses and the lambda.
    configure("a", 1) /* done */ <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ shutDown() }<!>
}

val written = listOf<Any?>(
    names.joinToString(", ", transform = String::shout),
    files.joinToString(transform = File::getName),
    mutex.withLock(action = ::shutDown),
    box.each(action = ::consume),
    schedule(task = ::shutDown),
    configure(name = "a", onDone = ::shutDown),
    configure(name = "a", retries = 1, onDone = ::shutDown),
    launchAll(1, 2, block = ::shutDown),
    launchAll(block = ::shutDown),
    users.map(User::name),
    ints.zip(ints, ::pair),
    run(::shutDown),
    configure("a", 1, ::shutDown),
    box.visit(::consume),
    handler(1, ::shutDown),
    Thread(::shutDown),
    configure(
        "a",
        1,
        ::shutDown,
    ),
    configure("a", 1, ::shutDown,),
    locator onLocating tracker::onInstalling,
    locator onLocating ::shutDown,
    both(::shutDown, second = ::shutDown),
)

/* GENERATED_FIR_TAGS: additiveExpression, callableReference, classDeclaration, flexibleType, funInterface,
funWithExtensionReceiver, functionDeclaration, functionalType, ifExpression, infix, integerLiteral, interfaceDeclaration,
javaCallableReference, javaFunction, javaProperty, lambdaLiteral, nullableType, primaryConstructor, propertyDeclaration,
samConversion, stringLiteral, typeParameter, vararg */
