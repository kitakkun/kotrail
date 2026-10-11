// IGNORE_DEXING
// The sealed hierarchy is read back from the library's class files, where a data object's generated
// `equals` arrives as an ordinary member, as it does for a file left alone by an incremental build.

// MODULE: lib
// FILE: Action.kt
package lib.action

sealed interface Action {
    data class Save(val draft: Boolean) : Action
    data object Cancel : Action
    object Legacy : Action
    object Custom : Action {
        override fun equals(other: Any?): Boolean = other is Custom
        override fun hashCode(): Int = 1
    }
}

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.sealedWhenBranchStyle=on
// FILE: Handler.kt
package app

import lib.action.Action

fun handle(action: Action) {
    // Reported: a data object and a plain object of the library, written as comparisons.
    when (action) {
        is Action.Save -> println("save")
        <!KOTRAIL_SEALED_WHEN_BRANCH_STYLE!>Action.Cancel<!> -> println("cancel")
        <!KOTRAIL_SEALED_WHEN_BRANCH_STYLE!>Action.Legacy<!> -> println("legacy")
        is Action.Custom -> println("custom")
    }

    // Not reported: an object with its own equals is compared, not type-checked, on purpose.
    when (action) {
        is Action.Save -> println("save")
        Action.Custom -> println("custom")
        else -> println("other")
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, data, equalityExpression, functionDeclaration, integerLiteral,
interfaceDeclaration, isExpression, nestedClass, nullableType, objectDeclaration, operator, override, primaryConstructor,
propertyDeclaration, sealed, smartcast, stringLiteral, whenExpression, whenWithSubject */
