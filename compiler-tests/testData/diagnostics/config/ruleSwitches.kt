// KOTRAIL_CONFIG: rules.preferExplicitBackingField=true, rules.noPassThroughReturn=false
// A rule that is switched on reports; one that is switched off stays quiet however plainly the
// file violates it. This is the shape of a test-source-set configuration: hand compileTestKotlin
// a different properties file, or different options.

class Cases {
    private val _items = mutableListOf<String>()

    // Reported: the rule is on.
    val <!PREFER_EXPLICIT_BACKING_FIELD!>items<!>: List<String> get() = _items
}

// Not reported: the rule is off.
fun identity(x: Int) = x

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, getter, propertyDeclaration */
