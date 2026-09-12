// KOTRAIL_CONFIG: rules.functionLength=true, functionLength.maxLines=4, functionLength.maxComposableLines=6
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Not reported: four lines of code, at the limit.
fun withinLimit(items: List<Int>): Int {
    val doubled = items.map { it * 2 }
    val filtered = doubled.filter { it > 2 }
    val total = filtered.sum()
    return total
}

// Reported: five lines of code.
fun <!KOTRAIL_FUNCTION_TOO_LONG!>overLimit<!>(items: List<Int>): Int {
    val doubled = items.map { it * 2 }
    val filtered = doubled.filter { it > 2 }
    val capped = filtered.take(10)
    val total = capped.sum()
    return total
}

// Not reported: four lines of code once blank lines, comments, and brace-only lines are set aside.
fun paddedButShort(items: List<Int>): Int {
    /*
     * Drop the small ones.
     */
    val filtered = items.filter { it > 2 }

    // Nothing left means nothing to add up.
    if (filtered.isEmpty()) {
        return 0
    }
    return filtered.sum()
}

// Reported: an expression body is measured the same way.
fun <!KOTRAIL_FUNCTION_TOO_LONG!>longExpression<!>(items: List<Int>): Int = items
    .map { it * 2 }
    .filter { it > 2 }
    .take(10)
    .sum()

// Not reported: a composable has its own, larger limit.
@Composable
fun Summary(items: List<Int>) {
    Column {
        Text("first: ${items.first()}")
        Text("last: ${items.last()}")
        Text("size: ${items.size}")
        Text("sum: ${items.sum()}")
    }
}

// Reported: past the composable limit.
@Composable
fun <!KOTRAIL_FUNCTION_TOO_LONG!>Details<!>(items: List<Int>) {
    Column {
        Text("first: ${items.first()}")
        Text("last: ${items.last()}")
        Text("size: ${items.size}")
        Text("sum: ${items.sum()}")
        Text("max: ${items.max()}")
        Text("min: ${items.min()}")
    }
}

/* GENERATED_FIR_TAGS: comparisonExpression, functionDeclaration, ifExpression, integerLiteral, lambdaLiteral,
localProperty, multiplicativeExpression, propertyDeclaration, stringLiteral */
