// KOTRAIL_CONFIG: rules.sealedWhenBranchStyle=on, rules.sealedWhenBranchStyle.style=object
// With the object style, an `is` check against an object is reported instead.
sealed interface Action {
    data class Save(val draft: Boolean) : Action
    data object Cancel : Action
}

fun handle(action: Action) {
    when (action) {
        is Action.Save -> println("save")
        <!KOTRAIL_SEALED_WHEN_BRANCH_STYLE!>is Action.Cancel<!> -> println("cancel")
    }

    // Not reported: written the way the style asks.
    when (action) {
        is Action.Save -> println("save")
        Action.Cancel -> println("cancel")
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, data, equalityExpression, functionDeclaration, interfaceDeclaration,
isExpression, nestedClass, objectDeclaration, primaryConstructor, propertyDeclaration, sealed, smartcast, stringLiteral,
whenExpression, whenWithSubject */
