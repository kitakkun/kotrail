// KOTRAIL_CONFIG: rules.compose.retainLeak=on
import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.annotation.DoNotRetain
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.RetainObserver
import androidx.compose.runtime.retain.RetainedEffect
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.retain.retainRetainedValuesStoreRegistry
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.util.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

class WeakHolder(context: Context) {
    private val reference = WeakReference(context)
    fun context(): Context? = reference.get()
}

// Compares views without holding one.
class ViewOrder : Comparator<View> {
    override fun compare(a: View, b: View): Int = 0
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

// Reported: a library generic holds what its type arguments are, through a project class too.
@Composable
fun Generics(context: Context, view: View, holders: StateFlow<Holder>) {
    retain <!KOTRAIL_RETAINED_LEAK!>{ mutableStateOf(view) }<!>
    retain <!KOTRAIL_RETAINED_LEAK!>{ MutableStateFlow(view) }<!>
    retain <!KOTRAIL_RETAINED_LEAK!>{ holders }<!>
    retain <!KOTRAIL_RETAINED_LEAK!>{ lazy { context } }<!>
}

// Reported: a platform type holds what its Kotlin type does, a referent taken out of a weak reference included.
@Composable
fun Platform(context: Context, view: View) {
    retain <!KOTRAIL_RETAINED_LEAK!>{ Optional.of(view) }<!>
    retain <!KOTRAIL_RETAINED_LEAK!>{ WeakReference(context).get() }<!>
}

// Reported: the value is what the lambda returns, though the call infers a wider type.
@Composable
fun Widened(context: Context) {
    retain<Any> <!KOTRAIL_RETAINED_LEAK!>{ context }<!>
}

// Reported: keys are kept as long as the value is.
@Composable
fun Keys(view: View, session: Session, state: MutableState<View>, id: String) {
    retain(<!KOTRAIL_RETAINED_LEAK!>view<!>) { Data() }
    retain(id, <!KOTRAIL_RETAINED_LEAK!>session<!>) { Data() }
    retain(<!KOTRAIL_RETAINED_LEAK!>state<!>) { Data() }
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

// Quiet: a weak or a soft reference does not keep what it refers to, also held by a project class,
// and a computed property stores nothing.
@Composable
fun Library(context: Context, view: View, id: String) {
    retain { WeakReference(context) }
    retain { SoftReference(view) }
    retain { WeakHolder(context) }
    retain { Lookup(id) }
}

// Quiet: a generic of data holds data, an interface a class implements stores nothing, and a
// result coerced to Unit retains Unit.
@Composable
fun GenericData(context: Context, count: Int) {
    retain { mutableStateOf(count) }
    retain { ViewOrder() }
    retain<Any> { count }
    retain<Unit> { context }
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
