package com.kitakkun.kotrail.fir.preconditions

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

object PreconditionNames {
    val INFERRED_PRECONDITIONS = ClassId(FqName("com.kitakkun.kotrail.preconditions"), Name.identifier("InferredPreconditions"))
    val CONDITIONS_PARAM: Name = Name.identifier("conditions")

    private val KOTLIN = FqName("kotlin")
    val REQUIRE = CallableId(KOTLIN, Name.identifier("require"))
    val CHECK = CallableId(KOTLIN, Name.identifier("check"))
    val REQUIRE_NOT_NULL = CallableId(KOTLIN, Name.identifier("requireNotNull"))
    val CHECK_NOT_NULL = CallableId(KOTLIN, Name.identifier("checkNotNull"))

    /** Calls whose first argument is a precondition on the enclosing declaration. */
    val CONDITION_CALLS: Set<CallableId> = setOf(REQUIRE, CHECK)

    /** Calls whose first argument must not be null. */
    val NOT_NULL_CALLS: Set<CallableId> = setOf(REQUIRE_NOT_NULL, CHECK_NOT_NULL)
}
