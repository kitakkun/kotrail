// KOTRAIL_CONFIG: rules.noNotNullAssertion=on, rules.noNotNullAssertion.exclude=path(*/kotlin-sources/*)
// Not reported: path(glob) matches the file's full path, directories included (the test copies it under kotlin-sources/).
class Holder(private val name: String?) {
    fun length(): Int = name!!.length
}

/* GENERATED_FIR_TAGS: checkNotNullCall, classDeclaration, functionDeclaration, nullableType, primaryConstructor,
propertyDeclaration */
