// KOTRAIL_CONFIG: rules.native.objcThrows=on, rules.native.objcThrows.packages=custom.sdk.*

// FILE: Sdk.kt
package custom.sdk.api

// Reported: in an exported package.
fun <!KOTRAIL_OBJC_EXPORT_MISSING_THROWS!>parse<!>(text: String): String {
    require(text.isNotEmpty())
    return text
}

// FILE: Internal.kt
package custom.runtime

// Not reported: outside the packages the framework exports.
fun route(text: String): String {
    require(text.isNotEmpty())
    return text
}

/* GENERATED_FIR_TAGS: functionDeclaration */
