// KOTRAIL_CONFIG: rules.compose.retainLeak=on
import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.annotation.DoNotRetain
import androidx.compose.runtime.retain.RetainObserver
import androidx.compose.runtime.retain.RetainedEffect
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.retain.retainRetainedValuesStoreRegistry
import java.lang.ref.WeakReference

class Holder(val view: View)

class Presenter(private val context: Context) {
    fun title(): String = context.toString()
}

class Screen(val presenter: Presenter, val title: String)

class Box<T>(val value: T)

class AppContext : Context()

@DoNotRetain
class Session

class Data(val count: Int = 0)

class Cache {
    private val entries = mutableMapOf<String, String>()
    fun get(key: String): String? = entries[key]
}

class Observed : RememberObserver {
    override fun onRemembered() {}
    override fun onForgotten() {}
    override fun onAbandoned() {}
}

class Tracked : RememberObserver, RetainObserver {
    override fun onRemembered() {}
    override fun onForgotten() {}
    override fun onAbandoned() {}
    override fun onRetained() {}
    override fun onEnteredComposition() {}
    override fun onExitedComposition() {}
    override fun onRetired() {}
    override fun onUnused() {}
}

class Lookup(private val id: String) {
    // Built on every read: the property itself stores nothing.
    val view: View get() = View(Context())
}

// Reported: a Context, a View held by a property, a class marked @DoNotRetain.
@Composable
fun Retained(context: Context, view: View) {
    retain <!KOTRAIL_RETAINED_LEAK!>{ context }<!>
    retain <!KOTRAIL_RETAINED_LEAK!>{ Holder(view) }<!>
    retain <!KOTRAIL_RETAINED_LEAK!>{ Session() }<!>
}

// Reported: a private property holds a Context as much as a public one does, and so does a model two classes down.
@Composable
fun Nested(context: Context) {
    retain <!KOTRAIL_RETAINED_LEAK!>{ Presenter(context) }<!>
    retain <!KOTRAIL_RETAINED_LEAK!>{ Screen(Presenter(context), "home") }<!>
}

// Reported: a subclass of Context, a list of views, a project generic holding a view.
@Composable
fun Shapes(appContext: AppContext, views: List<View>, view: View) {
    retain <!KOTRAIL_RETAINED_LEAK!>{ appContext }<!>
    retain <!KOTRAIL_RETAINED_LEAK!>{ views }<!>
    retain <!KOTRAIL_RETAINED_LEAK!>{ Box(view) }<!>
}

// Reported: keys are kept as long as the value is.
@Composable
fun Keys(view: View, session: Session, id: String) {
    retain(<!KOTRAIL_RETAINED_LEAK!>view<!>) { Data() }
    retain(id, <!KOTRAIL_RETAINED_LEAK!>session<!>) { Data() }
    RetainedEffect(<!KOTRAIL_RETAINED_LEAK!>view<!>) { onRetire {} }
    retainRetainedValuesStoreRegistry().LocalRetainedValuesStoreProvider(<!KOTRAIL_RETAINED_LEAK!>view<!>) {}
}

// Reported twice: the same holder as a key and as the value, each found on its own.
@Composable
fun KeyAndValue(holder: Holder) {
    retain(<!KOTRAIL_RETAINED_LEAK!>holder<!>) <!KOTRAIL_RETAINED_LEAK!>{ holder }<!>
}

// Reported: retain throws for a RememberObserver that is not a RetainObserver.
@Composable
fun Observers() {
    retain <!KOTRAIL_RETAINED_REMEMBER_OBSERVER!>{ Observed() }<!>
}

// Reported: a lambda of a @Composable type composes, also in a function that does not.
fun ComponentActivity.install(context: Context) {
    setContent {
        val retained = retain <!KOTRAIL_RETAINED_LEAK!>{ context }<!>
        retained.toString()
    }
}

// Quiet: plain data, a key that is a value, an observer of both kinds.
@Composable
fun Plain(count: Int, id: String) {
    retain { Data(count) }
    retain(id) { Cache() }
    retain { Tracked() }
    retain { Box(count) }
    RetainedEffect(id) { onRetire {} }
}

// Quiet: a library class other than the leak types ends the walk, and a computed property stores nothing.
@Composable
fun Library(context: Context, id: String) {
    retain { WeakReference(context) }
    retain { Lookup(id) }
}

// Quiet: what a lambda captures is not in its type.
@Composable
fun Captured(view: View) {
    retain { { view.toString() } }
}

// Quiet: not a composable.
fun NotComposable(context: Context, view: View, id: String) {
    retain { context }
    retain { Holder(view) }
    retain { Session() }
    retain(view) { Data() }
    retain { Observed() }
    retain(id) { Cache() }
}

/* GENERATED_FIR_TAGS: classDeclaration, flexibleType, funWithExtensionReceiver, functionDeclaration, getter,
integerLiteral, javaFunction, lambdaLiteral, nullableType, override, primaryConstructor, propertyDeclaration,
stringLiteral, typeParameter */
