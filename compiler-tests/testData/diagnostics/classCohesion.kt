// KOTRAIL_CONFIG: rules.classCohesion=on
class Draft(val text: String)
class Repo {
    fun save(draft: Draft) {}
}
class Exporter {
    fun export() {}
}
class Session {
    fun clear() {}
}

// Reported: three groups that share nothing: the draft handlers, the export, the logout.
class <!KOTRAIL_CLASS_NOT_COHESIVE!>ScreenViewModel<!>(private val repo: Repo, private val exporter: Exporter, private val session: Session) {
    var draft: Draft? = null

    fun onEdit(text: String) {
        draft = Draft(text)
    }

    fun onSave() {
        repo.save(draft ?: return)
    }

    fun onExport() {
        exporter.export()
    }

    fun onLogout() {
        session.clear()
    }
}

// Not reported: every member reaches every other through the state they share or the calls between them.
class Counter {
    private var count = 0
    private var history = mutableListOf<Int>()

    fun increment() {
        count++
        record()
    }

    fun reset() {
        count = 0
        history.clear()
    }

    fun current(): Int = count

    private fun record() {
        history.add(count)
    }
}

// Not reported: a helper that touches nothing of the class is not a group; the rest is one group.
class Formatter(private val locale: String) {
    fun format(amount: Long): String = pad(amount.toString()) + locale
    fun formatShort(amount: Long): String = amount.toString() + locale
    fun label(): String = locale
    fun describe(): String = format(1) + label()

    private fun pad(text: String): String = text.padStart(8)
}

// Not reported: fewer members than minMembers, whatever their groups.
class Small(private val a: Int, private val b: Int) {
    fun first() = a
    fun second() = b
}

// Not reported: a data class, an interface, an object's constants.
data class Point(val x: Int, val y: Int) {
    fun moveX(dx: Int) = copy(x = x + dx)
    fun moveY(dy: Int) = copy(y = y + dy)
    fun originX() = 0
    fun originY() = 0
}

interface Handlers {
    fun onA()
    fun onB()
    fun onC()
    fun onD()
}

/* GENERATED_FIR_TAGS: additiveExpression, assignment, classDeclaration, data, elvisExpression, functionDeclaration,
incrementDecrementExpression, integerLiteral, interfaceDeclaration, nullableType, primaryConstructor,
propertyDeclaration */
