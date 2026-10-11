// IGNORE_DEXING
// With the object style, an `is` check against an object of another module is reported, a data object's
// included, whose generated `equals` arrives from the class files as an ordinary member.

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
// KOTRAIL_CONFIG: rules.sealedWhenBranchStyle=on, rules.sealedWhenBranchStyle.style=object
// FILE: Handler.kt
package app

import lib.action.Action

fun handle(action: Action) {
    when (action) {
        is Action.Save -> println("save")
        <!KOTRAIL_SEALED_WHEN_BRANCH_STYLE!>is Action.Cancel<!> -> println("cancel")
        <!KOTRAIL_SEALED_WHEN_BRANCH_STYLE!>is Action.Legacy<!> -> println("legacy")
        // Not reported: an object with its own equals is type-checked on purpose.
        is Action.Custom -> println("custom")
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, data, functionDeclaration, integerLiteral, interfaceDeclaration, isExpression,
nestedClass, nullableType, objectDeclaration, operator, override, primaryConstructor, propertyDeclaration, sealed,
smartcast, stringLiteral, whenExpression, whenWithSubject */
