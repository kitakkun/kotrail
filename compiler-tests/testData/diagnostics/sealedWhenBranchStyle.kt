// KOTRAIL_CONFIG: rules.sealedWhenBranchStyle=on
sealed interface Action {
    data class Save(val draft: Boolean) : Action
    data object Cancel : Action
    data object Retry : Action
    object Legacy : Action
    object Custom : Action {
        override fun equals(other: Any?): Boolean = other is Custom
        override fun hashCode(): Int = 1
    }
}

sealed class State {
    class Loaded(val count: Int) : State()
    data object Empty : State()
}

enum class Mode { A, B }

fun handle(action: Action, state: State, mode: Mode, any: Any) {
    // Reported: object cases written as comparisons next to class cases.
    when (action) {
        is Action.Save -> println("save")
        <!KOTRAIL_SEALED_WHEN_BRANCH_STYLE!>Action.Cancel<!> -> println("cancel")
        <!KOTRAIL_SEALED_WHEN_BRANCH_STYLE!>Action.Retry<!>, <!KOTRAIL_SEALED_WHEN_BRANCH_STYLE!>Action.Legacy<!> -> println("again")
        is Action.Custom -> println("custom")
    }

    // Not reported: every branch is an `is` check.
    when (action) {
        is Action.Save -> println("save")
        is Action.Cancel -> println("cancel")
        is Action.Retry, is Action.Legacy -> println("again")
        is Action.Custom -> println("custom")
    }

    // Reported: a sealed class subject works the same way.
    when (state) {
        is State.Loaded -> println(state.count)
        <!KOTRAIL_SEALED_WHEN_BRANCH_STYLE!>State.Empty<!> -> println("empty")
    }

    // Not reported: else and guards are not object comparisons.
    when (action) {
        is Action.Save if action.draft -> println("draft")
        else -> println("other")
    }

    // Not reported: an object with its own equals is compared, not type-checked, on purpose.
    when (action) {
        is Action.Save -> println("save")
        Action.Custom -> println("custom")
        else -> println("other")
    }

    // Not reported: an enum has entries, not objects, and cannot use `is`.
    when (mode) {
        Mode.A -> println("a")
        Mode.B -> println("b")
    }

    // Not reported: the subject is not a sealed type.
    when (any) {
        Action.Cancel -> println("cancel")
        else -> println("other")
    }

    // Not reported: no subject.
    when {
        action == Action.Cancel -> println("cancel")
        else -> println("other")
    }
}

/* GENERATED_FIR_TAGS: andExpression, classDeclaration, data, disjunctionExpression, enumDeclaration, enumEntry,
equalityExpression, functionDeclaration, guardCondition, interfaceDeclaration, isExpression, nestedClass,
objectDeclaration, primaryConstructor, propertyDeclaration, sealed, smartcast, stringLiteral, whenExpression,
whenWithSubject */
