// KOTRAIL_CONFIG: rules.noDataClassInPublicApi=on, rules.noDataClassInPublicApi.scope=all

// Without explicit API mode this module does not look like a library, so the default scope would
// stay quiet; `scope=all` applies the rule regardless.
data class <!KOTRAIL_DATA_CLASS_IN_PUBLIC_API!>Config<!>(val timeout: Int, val retries: Int)

internal data class Draft(val text: String, val revision: Int)

/* GENERATED_FIR_TAGS: classDeclaration, data, primaryConstructor, propertyDeclaration */
