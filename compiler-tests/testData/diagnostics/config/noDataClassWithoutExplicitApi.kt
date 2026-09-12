// KOTRAIL_CONFIG: rules.noDataClassInPublicApi=true

// Not reported: the module does not compile with explicit API mode, so under the default scope it
// is an application, where data classes in public declarations are the normal shape of a model.
data class Config(val timeout: Int, val retries: Int)

/* GENERATED_FIR_TAGS: classDeclaration, data, primaryConstructor, propertyDeclaration */
