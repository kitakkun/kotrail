interface Repository {
    fun load(): String
    fun save(value: String)
    fun clear()
}

class DraftRepository : Repository {
    // Reported: an expression body that is nothing but a placeholder.
    override fun load(): String = <!UNIMPLEMENTED_CODE!>TODO()<!>

    // Reported: a placeholder with a reason still ships nothing.
    override fun save(value: String) {
        <!UNIMPLEMENTED_CODE!>TODO("persist $value")<!>
    }

    // Reported: throwing the error directly is the same placeholder spelled out.
    override fun clear() {
        throw <!UNIMPLEMENTED_CODE!>NotImplementedError("clear is not supported yet")<!>
    }
}

class RealRepository : Repository {
    private val store = mutableListOf<String>()

    // Not reported: a TODO comment is not code.
    // TODO: replace the in-memory store.
    override fun load(): String = store.lastOrNull().orEmpty()

    override fun save(value: String) {
        store += value
    }

    // Not reported: an unsupported operation is an explicit decision, not a placeholder.
    override fun clear() {
        throw UnsupportedOperationException("clear is not part of the contract")
    }
}

// Not reported: a user-defined function that happens to share the name.
object Notes {
    fun TODO(note: String): String = "noted: $note"
}

fun useOwnTodo(): String = Notes.TODO("mine")

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, nullableType, override,
propertyDeclaration, stringLiteral */
