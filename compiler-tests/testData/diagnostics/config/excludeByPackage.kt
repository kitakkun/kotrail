// KOTRAIL_CONFIG: rules.noNotNullAssertion=true, rules.preferValueClass=true, exclude=package(com.acme.gen*)

// The project-wide predicate applies to every rule: nothing in this package is reported.
package com.acme.generated

data class RowId(val value: Long)

fun length(x: String?): Int = x!!.length

/* GENERATED_FIR_TAGS: checkNotNullCall, classDeclaration, data, functionDeclaration, nullableType, primaryConstructor,
propertyDeclaration */
