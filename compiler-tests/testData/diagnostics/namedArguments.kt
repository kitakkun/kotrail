// KOTRAIL_CONFIG: rules.namedArgumentsForRepeatedTypes=true
import java.util.Calendar

class Point(val x: Int, val y: Int, val z: Int)

data class User(val id: String, val name: String, val email: String)

class Mover {
    fun move(x: Int, y: Int, z: Int) {}
}

fun move(x: Int, y: Int, z: Int) {}
fun pair(x: Int, y: Int) {}
fun labeled(x: Int, y: Int, label: String?, hint: String) {}
fun mixed(a: Int, b: Int, c: Int?) {}
fun <T> triple(a: T, b: T, c: T) {}
fun withLambda(a: Int, b: Int, block: () -> Unit) = block()
fun sum(vararg values: Int): Int = values.sum()

class Grid {
    operator fun get(x: Int, y: Int, z: Int): Int = x + y + z
    operator fun invoke(x: Int, y: Int, z: Int) {}
}

// Reported: three positional Int arguments to a function, a constructor, and a member.
fun reported(mover: Mover) {
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>move(1, 2, 3)<!>
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>Point(1, 2, 3)<!>
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>mover.move(1, 2, 3)<!>
}

// Reported: three positional String arguments; the declared type is what counts, so `T` too.
fun reportedOtherTypes() {
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>User("1", "Ann", "ann@example.com")<!>
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>triple(1, 2, 3)<!>
}

// Not reported: named arguments, or only two positional arguments of one type.
fun named() {
    move(x = 1, y = 2, z = 3)
    move(1, 2, z = 3)
    pair(1, 2)
    labeled(1, 2, "label", "hint")
}

// Not reported: `Int` and `Int?` are different types; lambdas never count.
fun differentTypes() {
    mixed(1, 2, null)
    withLambda(1, 2) { }
}

// Not reported: varargs, Java callees, operator and invoke calls.
fun exempt(grid: Grid) {
    sum(1, 2, 3)
    Calendar.getInstance().set(2024, 1, 1)
    grid[1, 2, 3]
    grid(1, 2, 3)
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, data, flexibleType, functionDeclaration, functionalType,
integerLiteral, javaFunction, lambdaLiteral, nullableType, operator, primaryConstructor, propertyDeclaration,
stringLiteral, typeParameter, vararg */
