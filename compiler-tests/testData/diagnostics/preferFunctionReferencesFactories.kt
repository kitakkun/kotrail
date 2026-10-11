// KOTRAIL_CONFIG: rules.preferFunctionReferences=on
// DUMP_KOTRAIL_FIXES
// A top-level function named like the class it returns, or like a supertype of that class, reads as
// a constructor call, and is left alone like one: `remember { MutableInteractionSource() }` stays,
// as `remember { SnackbarHostState() }` does. The declared return type counts, nullability aside.

interface MutableInteractionSource
private class MutableInteractionSourceImpl : MutableInteractionSource
fun MutableInteractionSource(): MutableInteractionSource = MutableInteractionSourceImpl()
fun createInteractionSource(): MutableInteractionSource = MutableInteractionSourceImpl()

class SnackbarHostState

interface Job
interface CompletableJob : Job
private class JobImpl(val parent: Job?) : CompletableJob
fun Job(parent: Job?): CompletableJob = JobImpl(parent)

interface Connection
fun Connection(url: String): Connection? = null

interface Label
fun Label(text: String): String = text

fun <T> remember(calculation: () -> T): T = calculation()

fun factories(parents: List<Job?>, urls: List<String>, texts: List<String>) {
    // Not reported: a constructor, and a function named like the type it returns.
    remember { SnackbarHostState() }
    remember { MutableInteractionSource() }

    // Not reported: named like a supertype of the returned class, or like the class of a nullable result.
    parents.map { Job(it) }
    urls.map { Connection(it) }

    // Reported: an ordinary top-level function.
    remember <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ createInteractionSource() }<!>

    // Reported: named like a class, but it returns another type.
    texts.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ Label(it) }<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, interfaceDeclaration, lambdaLiteral,
nullableType, primaryConstructor, propertyDeclaration, typeParameter */
