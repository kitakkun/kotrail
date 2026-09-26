// KOTRAIL_CONFIG: rules.noLiteralLoop=on

enum class Kind { PHOTO, VIDEO }

class Chip(val text: String, val selected: Boolean)

fun chip(text: String, selected: Boolean): Chip = Chip(text, selected)
fun show(chip: Chip) {}
fun register(name: String) {}

fun filters(allDevices: Boolean, kind: Kind?, day: String?, days: List<String>) {
    // Reported: two calls folded into a loop over the two booleans, unfolded by the reader.
    <!KOTRAIL_LITERAL_LOOP!>listOf(false, true)<!>.forEach { option ->
        show(chip(if (option) "All devices" else "This device", option == allDevices))
    }

    // Reported: a sentinel joined to the data, told apart in the body.
    (<!KOTRAIL_LITERAL_LOOP!>listOf(null) + Kind.entries<!>).forEach { option ->
        show(chip(option?.name ?: "All kinds", option == kind))
    }
    (<!KOTRAIL_LITERAL_LOOP!>listOf(null) + days<!>).forEach { option ->
        show(chip(option ?: "All dates", option == day))
    }

    // Reported: a for loop over literals with a when on the element.
    for (size in <!KOTRAIL_LITERAL_LOOP!>listOf("S", "M", "L")<!>) {
        val label = when (size) {
            "S" -> "Small"
            "M" -> "Medium"
            else -> "Large"
        }
        register(label)
    }

    // Reported: an indexed loop tells the element apart, in a property initializer as well as in a body.
    <!KOTRAIL_LITERAL_LOOP!>listOf(Kind.PHOTO, Kind.VIDEO)<!>.mapIndexed { index, item ->
        chip(if (item == Kind.VIDEO) "Video $index" else "Photo $index", item == kind)
    }

    // Not reported: an equality with a value handed on is not a decision; a radio group is one row per label.
    listOf("Alpha", "Beta", "Gamma").forEach { option -> show(chip(option, option == day)) }

    // Not reported: data looped as data, a literal list handed on without a branch, a table longer than maxElements.
    days.forEach { show(chip(it, it == day)) }
    listOf("a", "b").forEach(::register)
    listOf("a", "b").forEach { register(it) }
    listOf(1, 2, 4, 8, 16).forEach { register(if (it > 4) "big" else "small") }
    Kind.entries.forEach { show(chip(it.name, it == kind)) }
}

/* GENERATED_FIR_TAGS: additiveExpression, callableReference, classDeclaration, comparisonExpression, elvisExpression,
enumDeclaration, enumEntry, equalityExpression, forLoop, functionDeclaration, ifExpression, integerLiteral,
lambdaLiteral, localProperty, nullableType, primaryConstructor, propertyDeclaration, safeCall, stringLiteral,
whenExpression, whenWithSubject */
