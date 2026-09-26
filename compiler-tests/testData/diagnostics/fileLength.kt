// KOTRAIL_CONFIG: rules.fileLength=on, rules.fileLength.maxLines=5, rules.fileLength.maxTopLevelDeclarations=0
// Reported on the package directive: 6 lines of code, not counting this comment, the package and
// import lines, blank lines and lone braces.
<!KOTRAIL_FILE_TOO_LONG!>package custom<!>

import kotlin.math.abs

class Counter(
    val start: Int,
) {
    fun next(): Int {
        return abs(start) + 1
    }
}

fun total(counter: Counter): Int = counter.next()

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, functionDeclaration, integerLiteral, primaryConstructor,
propertyDeclaration */
