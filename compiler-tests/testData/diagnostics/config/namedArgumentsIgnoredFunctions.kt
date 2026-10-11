// KOTRAIL_CONFIG: rules.namedArgumentsForRepeatedTypes=on, rules.namedArgumentsForRepeatedTypes.ignoredFunctions=geometry.Vec.Companion.of,geometry.average*
// The project's own functions whose arguments can come in any order are left alone, every overload
// of a listed name. The list replaces the default one, so maxOf is reported again.
package geometry

class Vec(val x: Int, val y: Int, val z: Int) {
    companion object {
        fun of(x: Int, y: Int, z: Int): Vec = Vec(x = x, y = y, z = z)
        fun of(x: Long, y: Long, z: Long): Vec = Vec(x = x.toInt(), y = y.toInt(), z = z.toInt())
    }
}

fun average(a: Double, b: Double, c: Double): Double = (a + b + c) / 3
fun average(a: Int, b: Int, c: Int): Int = (a + b + c) / 3
fun averageOf(a: Long, b: Long, c: Long): Long = (a + b + c) / 3
fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

// Not reported: listed by name, a companion member included, and a glob covers a family of names.
fun ignored() {
    Vec.of(1, 2, 3)
    Vec.of(1L, 2L, 3L)
    average(1.0, 2.0, 3.0)
    average(1, 2, 3)
    averageOf(1L, 2L, 3L)
}

// Reported: not listed; the constructor is not the companion's `of`, and the default list is gone.
fun reported() {
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>lerp(0.0, 1.0, 0.5)<!>
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>Vec(1, 2, 3)<!>
    <!KOTRAIL_NAMED_ARGUMENTS_REQUIRED!>maxOf(1.0, 2.0, 3.0)<!>
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, companionObject, functionDeclaration, integerLiteral,
multiplicativeExpression, objectDeclaration, primaryConstructor, propertyDeclaration */
