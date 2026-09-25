// KOTRAIL_CONFIG: configFile=compiler-tests/testData/diagnostics/config/dependencyRules.yaml, rules.dependencyRules=on
// The ui layer must not see the data layer, except its model package; see dependencyRules.yaml next to this file.

// FILE: UserDao.kt
package layered.data.db

class UserDao {
    fun load(): String = "user"
}

// FILE: Model.kt
package layered.data.model

class User(val name: String)

// FILE: Cache.kt
package layered.data.cache

object Cache {
    val size: Int = 0
}

// FILE: Widgets.kt
package layered.ui.widgets

class Widget

// FILE: Screen.kt
package layered.ui

// Reported: the import brings in a data package the policy denies.
<!KOTRAIL_DEPENDENCY_NOT_ALLOWED!>import layered.data.db.UserDao<!>
// Not reported: the model package is allowed.
import layered.data.model.User
// Not reported: the file's own subpackage.
import layered.ui.widgets.Widget

class Screen(private val dao: UserDao, private val widget: Widget) {
    fun title(user: User): String = user.name + dao.load()

    // Reported once for the cache package, at its first reference: a qualified use without an import.
    fun cacheSize(): Int = <!KOTRAIL_DEPENDENCY_NOT_ALLOWED!>layered.data.cache.Cache.size<!> + layered.data.cache.Cache.size
}

// FILE: Repository.kt
package layered.data

import layered.ui.Screen

// Not reported: no policy covers the data layer.
class Repository(val screen: Screen)

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, functionDeclaration, integerLiteral, objectDeclaration,
primaryConstructor, propertyDeclaration, stringLiteral */
