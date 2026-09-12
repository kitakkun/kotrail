// KOTRAIL_CONFIG: rules.visibilityPolicy=true, visibilityPolicy.private=composable && name(*Preview), visibilityPolicy.internal=name(*Impl) || annotated(custom.Internal)

package custom

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

annotation class Internal

@Composable
fun Home(title: String) {
    Text(title)
}

// Reported: a preview must be private.
@Composable
fun <!VISIBILITY_TOO_WIDE!>HomePreview<!>() = Home("home")

// Not reported: private, as the policy asks.
@Composable
private fun SettingsPreview() = Home("settings")

// Not reported: the private policy only covers composables named *Preview.
fun preview(): String = "not a composable"

interface Repository {
    fun load(): String
}

// Reported: *Impl must be internal.
class <!VISIBILITY_TOO_WIDE!>RepositoryImpl<!> : Repository {
    override fun load(): String = "impl"
}

// Not reported: internal satisfies the policy.
internal class CacheImpl : Repository {
    override fun load(): String = "cache"
}

// Not reported: a public member of an internal class is effectively internal.
internal class Session {
    fun tokenImpl(): String = "token"
}

// Reported: annotated, so the internal policy applies; the property is public.
@Internal
val <!VISIBILITY_TOO_WIDE!>registry<!>: List<String> = emptyList()

// Not reported: an override's visibility is fixed by what it overrides, policy or not.
class Remote : Repository {
    @Internal
    override fun load(): String = "remote"
}

fun use() {
    HomePreview(); SettingsPreview(); preview(); RepositoryImpl().load(); CacheImpl().load(); Session().tokenImpl()
    Remote().load(); registry.size
}

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, functionDeclaration, interfaceDeclaration, override,
propertyDeclaration, stringLiteral */
