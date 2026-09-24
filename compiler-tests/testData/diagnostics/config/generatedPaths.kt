// KOTRAIL_CONFIG: rules.noNotNullAssertion=on, generated.paths=*/kotlin-sources/*/generatedPaths.kt
// Not reported: the file's path (the test copies it under kotlin-sources/) matches generated.paths, so every rule skips it.
class Holder(private val name: String?) {
    fun length(): Int = name!!.length
}

/* GENERATED_FIR_TAGS: checkNotNullCall, classDeclaration, functionDeclaration, nullableType, primaryConstructor,
propertyDeclaration */
