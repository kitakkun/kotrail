// KOTRAIL_CONFIG: rules.nameSaysType=on
// RENDER_DIAGNOSTICS_FULL_TEXT
// FILE: Dp.kt
package androidx.compose.ui.unit

@JvmInline
value class Dp(val value: Float)

// FILE: Color.kt
package androidx.compose.ui.graphics

@JvmInline
value class Color(val value: ULong)

// FILE: AnnotatedString.kt
package androidx.compose.ui.text

class AnnotatedString(val text: String) : CharSequence by text

// FILE: CPointer.kt
package kotlinx.cinterop

abstract class CPointed
abstract class COpaque : CPointed()
class CPointer<T : CPointed>
typealias COpaquePointer = CPointer<out COpaque>

// FILE: NameSaysType.kt
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import kotlin.reflect.KFunction0
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

interface NodeRequest
class PerformNodeAction : NodeRequest
class TextStyle
class UiNode
class Request
class Item
class Category
class Person
class User
class UserRepository
class UserRepositoryImpl
class LoginViewModel
class AppConfig
class UserData
class ProcessTable
class HostVersionsRepository
class AppAppearanceRepository
class NodeActionHandler
class HttpRequestBuilder
class CertificateUiEntry
class AccessCredential
class McpParameter<T>(val value: T)
data class UiState(val loading: Boolean)
interface Callback
interface ClickListener : Callback
interface LoginListener
interface PanelSlot
enum class DisplayMode { LIGHT, DARK }

@JvmInline
value class UserId(val value: String)

typealias Members = List<User>
typealias Token = AccessCredential

object Defaults
class Widget {
    companion object
}

sealed interface NegotiationResult {
    class Success : NegotiationResult
}

class NodeEditor(
    // Reported: the role is there, the head noun of the type is not, and a supertype's does not
    // stand for it (a PerformNodeAction is a NodeRequest).
    private val <!KOTRAIL_NAME_HIDES_TYPE!>request<!>: PerformNodeAction,
    private val <!KOTRAIL_NAME_HIDES_TYPE!>source<!>: InputStream,
    private val <!KOTRAIL_NAME_HIDES_TYPE!>delegate<!>: UserRepository,
    private val <!KOTRAIL_NAME_HIDES_TYPE!>limit<!>: McpParameter<Int>,
    // Reported: a word of the type that is not its last.
    private val <!KOTRAIL_NAME_HIDES_TYPE!>processes<!>: ProcessTable,
    private val <!KOTRAIL_NAME_HIDES_TYPE!>versions<!>: HostVersionsRepository,
    // Reported: ends with a noun of its own.
    private val <!KOTRAIL_NAME_HIDES_TYPE!>appearanceDataStore<!>: AppAppearanceRepository,
    // Reported: abbreviations.
    private val <!KOTRAIL_NAME_HIDES_TYPE!>repo<!>: UserRepository,
    private val <!KOTRAIL_NAME_HIDES_TYPE!>vm<!>: LoginViewModel,
    private val <!KOTRAIL_NAME_HIDES_TYPE!>cfg<!>: AppConfig,
    // Not reported: a role, then the head noun.
    private val requestedAction: PerformNodeAction,
    private val sourceStream: InputStream,
    private val userRepository: UserRepository,
    private val limitParameter: McpParameter<Int>,
    private val processTable: ProcessTable,
    private val versionsRepository: HostVersionsRepository,
    private val viewModel: LoginViewModel,
    private val scope: CoroutineScope,
    private val handler: CoroutineExceptionHandler,
    private val userData: UserData,
) {
    // Reported: a lock that does not say it is a mutex.
    private val <!KOTRAIL_NAME_HIDES_TYPE!>lock<!> = Mutex()
    private val <!KOTRAIL_NAME_HIDES_TYPE!>installLock<!>: Mutex = Mutex()
    private val installMutex = Mutex()

    // Not reported: value types, a value class and an enum ending the name, a nullable type, a function type.
    private val id: UserId = UserId("u")
    private val timeout: Duration = Duration.ZERO
    private val count: Int = 0
    private val user: User? = null
    private val mode: DisplayMode = DisplayMode.LIGHT
    private val callback: Callback? = null
    private val onClick: () -> Unit = {}

    // Not reported: an `on*` name, exempt by default.
    private val onLogin: LoginListener? = null

    // Reported: a function parameter, like a constructor's.
    fun load(<!KOTRAIL_NAME_HIDES_TYPE!>repo<!>: UserRepository): UserRepository {
        // Not reported: a local, outside the default scope.
        val local = repo
        return local
    }

    // Not reported: an exempt name, a type parameter.
    fun same(other: User): Boolean = other == user

    fun <T> wrap(element: T, holder: T): List<T> = listOf(element, holder)
}

class Label(
    val text: String,
    // Reported: a style outside a token table ends with what it is.
    val <!KOTRAIL_NAME_HIDES_TYPE!>title<!>: TextStyle,
    val titleStyle: TextStyle,
)

// Not reported: a token table, whose property names are a design system's vocabulary.
class Typography(val titleLarge: TextStyle, val titleMedium: TextStyle, val bodyMedium: TextStyle)

// Not reported: two properties of one type are a token table already.
class Headings(val large: TextStyle, val small: TextStyle)

class NodeTree(
    // Reported: a plural role without the element's head noun.
    val <!KOTRAIL_NAME_HIDES_TYPE!>children<!>: List<UiNode>,
    // Not reported: the element's head noun, plural.
    val childNodes: List<UiNode>,
) {
    // Not reported: the element's head noun (plural, nested, through the leading underscore) or the container's.
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val requestChannel = Channel<Request>()
    private val items: List<Item> = emptyList()
    private val categories: List<Category> = emptyList()
    private val people: List<Person> = emptyList()
    private val itemList: List<Item> = emptyList()
    private val _uiState = MutableStateFlow(UiState(false))
    val uiState: StateFlow<UiState> = _uiState
    val users: Flow<List<User>> = MutableStateFlow(emptyList())
    val userFlow: Flow<List<User>> = MutableStateFlow(emptyList())
    val userMap: Map<String, User> = emptyMap()

    // Reported: ends with neither the element nor the container.
    private val <!KOTRAIL_NAME_HIDES_TYPE!>pending<!> = Channel<Request>()
    private val <!KOTRAIL_NAME_HIDES_TYPE!>cache<!>: Map<String, User> = emptyMap()

    // Not reported: the phrase after By is cut, and the name ends with what the map holds.
    val usersById: Map<String, User> = emptyMap()

    // Not reported: a container of value types.
    private val names: List<String> = emptyList()

    // Reported: an abbreviation of the container's head noun.
    private val <!KOTRAIL_NAME_HIDES_TYPE!>listenerRef<!>: WeakReference<LoginListener>? = null
    private val listenerReference: WeakReference<LoginListener>? = null

    // Reported: the alias is the type the reader sees.
    private val <!KOTRAIL_NAME_HIDES_TYPE!>team<!>: Members = emptyList()
    private val members: Members = emptyList()
}

class Connection(
    // Reported: a word of the type is not enough, the name ends with its head noun.
    private val <!KOTRAIL_NAME_HIDES_TYPE!>request<!>: HttpRequestBuilder,
    private val <!KOTRAIL_NAME_HIDES_TYPE!>certificates<!>: List<CertificateUiEntry>,
    // Reported: a supertype's head noun is not the type's (an ExecutorService is an Executor).
    private val <!KOTRAIL_NAME_HIDES_TYPE!>executor<!>: ExecutorService,
    // Not reported: the type's own head noun, whatever its supertypes.
    private val executorService: ExecutorService,
    private val listener: ClickListener,
    // Not reported: a meaningless type suffix is dropped, or kept.
    private val contentPadding: PaddingValues,
    private val paddingValues: PaddingValues,
    private val repository: UserRepositoryImpl,
    // Not reported: an outer class's head noun.
    private val negotiationResult: NegotiationResult.Success,
    // Not reported: a qualifier at the end of the name is dropped.
    private val selectedItemOrNull: Item?,
    // Not reported: the alias's head noun, or the expansion's.
    private val token: Token,
    private val credential: Token,
    // Reported: neither; the message asks for the alias.
    private val <!KOTRAIL_NAME_HIDES_TYPE!>secret<!>: Token,
)

class Gestures {
    // Reported: a PascalCase name ends with its type's head noun too.
    val <!KOTRAIL_NAME_HIDES_TYPE!>LongClick<!>: NodeActionHandler = NodeActionHandler()
    val LongClickHandler: NodeActionHandler = NodeActionHandler()

    // Reported: and so does a SCREAMING_SNAKE one.
    val <!KOTRAIL_NAME_HIDES_TYPE!>DEFAULT_SOURCE<!>: InputStream? = null
}

class Theme(
    // Not reported: value types by default.
    val padding: Dp,
    val background: Color,
)

// Reported here, where the name is decided.
interface HasRepository {
    val <!KOTRAIL_NAME_HIDES_TYPE!>repo<!>: UserRepository
}

class Session : HasRepository {
    // Not reported: an override takes its name from the declaration it overrides.
    override val repo: UserRepository = UserRepository()
}

// Reported: a constructor parameter that is not a property.
class Presenter(<!KOTRAIL_NAME_HIDES_TYPE!>vm<!>: LoginViewModel) {
    val viewModel = vm
}

// Not reported: a composable's modifier and content follow the Compose conventions.
@Composable
fun Screen(modifier: Modifier, content: @Composable () -> Unit) {
    modifier.then(Modifier)
    content()
}

// Not reported in a composable, where `content` is the convention whatever its type; reported elsewhere.
@Composable
fun Panel(content: PanelSlot) {
    content.hashCode()
}

fun describe(<!KOTRAIL_NAME_HIDES_TYPE!>content<!>: PanelSlot): String = content.toString()

// Not reported: a top-level property, outside the default scope.
val repoInstance = UserRepository()

class Holders(
    // Not reported: function types of any kind are value types.
    val header: @Composable () -> Unit,
    val loader: suspend (String) -> User,
    val factory: KFunction0<User>,
    // Not reported: containers of every kind, ending with their elements' head noun or their own.
    val items: ImmutableList<Item>,
    val selectedItems: SnapshotStateList<Item>,
    val userMap: LinkedHashMap<String, User>,
    val requests: java.util.ArrayDeque<Request>,
    val userClass: KClass<User>,
    val builder: ThreadLocal<HttpRequestBuilder>,
    // Not reported: throwables, files, atomics, jobs, threads, and Any are value types.
    val error: IOException,
    val failure: IllegalStateException,
    val cause: Throwable,
    val dir: File,
    val latest: AtomicReference<User>,
    val worker: Job,
    val loop: Thread,
    val payload: Any,
) {
    // Not reported: the type is an object, which is its own single value.
    val settings: Defaults = Defaults
    val maker: Widget.Companion = Widget

    // Reported: a container whose head noun the name does not end with, nor its element's.
    val <!KOTRAIL_NAME_HIDES_TYPE!>type<!>: KClass<User> = User::class
}

// Not reported: a serializable class mirrors its schema, whose names are not the code's to choose.
@Serializable
class UserDto(val usr: User, val cfg: AppConfig) {
    val acct: User? = null
}

class Reply
class Variable
class JmDNS
class Libs
interface Disposable

class Texts(
    // Not reported: every CharSequence is text, a value type.
    val text: AnnotatedString,
    val output: StringBuilder,
    val title: java.lang.StringBuilder,
    // Not reported: a time mark is a value, named for the moment it marks.
    val started: TimeMark,
    val begin: TimeSource.Monotonic.ValueTimeMark,
    val since: ComparableTimeMark,
)

class Natives(
    // Not reported: Objective-C's root class and protocol, and opaque pointers, play the role of Any.
    val node: NSObject,
    val obj: NSObject?,
    val observers: List<NSObjectProtocol>,
    val handle: COpaquePointer?,
    val address: CPointer<COpaque>,
    val peer: ObjCObject,
    // Reported: a subclass of NSObject keeps its head noun.
    val <!KOTRAIL_NAME_HIDES_TYPE!>root<!>: UIWindow,
)

object Selectors {
    // Not reported: an opaque pointer, in a SCREAMING name too.
    val RETURN_SELECTOR: COpaquePointer? = null
}

class Capabilities(
    // Not reported: a type whose last word is an adjective has no head noun.
    val expire: Runnable,
    val host: AutoCloseable,
    val contents: java.awt.datatransfer.Transferable,
    val order: Comparable<User>,
    val owner: Disposable,
    val tasks: List<Runnable>,
    // Reported: a noun ending in -able is still a head noun.
    val <!KOTRAIL_NAME_HIDES_TYPE!>current<!>: Variable,
    // Reported: a container's element still asks for its head noun.
    val <!KOTRAIL_NAME_HIDES_TYPE!>jobs<!>: Map<Runnable, User>,
)

class Replies(
    // Not reported: a subtype of a container is a container, and a deferred value type asks nothing.
    val firstFrame: CompletableDeferred<Unit>,
    val pendingReplies: MutableMap<String, CompletableDeferred<Reply>>,
    val replyDeferred: CompletableDeferred<Reply>,
    // Reported: neither the element nor the container.
    val <!KOTRAIL_NAME_HIDES_TYPE!>pending<!>: CompletableDeferred<Reply>,
)

class Locals {
    // Not reported: a composition local named by the Compose convention.
    val LocalAppLocale: ProvidableCompositionLocal<String> = staticCompositionLocalOf { "en" }
}

class OtherLocals {
    // Reported: a composition local named otherwise ends with its head noun, which 'Locale' does not spell out.
    val <!KOTRAIL_NAME_HIDES_TYPE!>appLocale<!>: ProvidableCompositionLocal<String> = staticCompositionLocalOf { "en" }
}

class Discovery(
    // Not reported: the type's own name, or a last word that spells out its head noun.
    val jmdns: JmDNS,
    val libraries: Libs,
    // Reported: neither.
    val <!KOTRAIL_NAME_HIDES_TYPE!>registry<!>: JmDNS,
)

class ScreenView
class Entry
class WireLevelQaPlugin
interface ViewRootForTest
class SignInRequest

class Registries(
    // Not reported: a phrase after By, For, Of and the like is cut before the last word is read, in names and type names.
    val viewsById: HashMap<Int, WeakReference<ScreenView>>,
    val entriesByAddress: HashMap<Long, Entry>,
    val wirePluginsById: Map<String, WireLevelQaPlugin>,
    val userById: Map<String, User>,
    val USERS_BY_ID: Map<String, User>,
    val root: ViewRootForTest,
    // Not reported: the last word as written still counts, as in a map named from key to value.
    val idToUser: Map<String, User>,
    // Not reported: In, On and At end a phrasal verb in a type name, which keeps its head noun.
    val request: SignInRequest,
    // Reported: the message asks for the head noun before the phrase.
    val <!KOTRAIL_NAME_HIDES_TYPE!>cacheById<!>: Map<String, User>,
    val <!KOTRAIL_NAME_HIDES_TYPE!>host<!>: ViewRootForTest,
    val <!KOTRAIL_NAME_HIDES_TYPE!>pending<!>: SignInRequest,
)

class ColorScheme
class MutationObject<T>(val value: T)

// Not reported: a token table's copy parameters are its vocabulary too.
class AppTextStyles(val title: TextStyle, val body: TextStyle, val caption: TextStyle) {
    fun copy(title: TextStyle = this.title, body: TextStyle = this.body, caption: TextStyle = this.caption): AppTextStyles =
        AppTextStyles(title, body, caption)

    // Reported: a parameter named otherwise.
    fun scaled(<!KOTRAIL_NAME_HIDES_TYPE!>base<!>: TextStyle): AppTextStyles = AppTextStyles(base, base, base)
}

// Not reported: two properties of one type make a token table.
object HostColorSchemes {
    val Light: ColorScheme = ColorScheme()
    val Dark: ColorScheme = ColorScheme()
}

class Mutations(
    val label: String,
    // Not reported: an Object suffix says nothing of what the type is, and is dropped or kept.
    val saveMutation: MutationObject<User>,
    val deleteObject: MutationObject<User>,
    // Reported: the message asks for the head noun without the suffix.
    val <!KOTRAIL_NAME_HIDES_TYPE!>pending<!>: MutationObject<User>,
)

class LayoutCoordinates
class DesignTokens
class Transaction
class NetworkRedactionRules
class DataModel<T>(val value: T)
class Preferences {
    class Key<T>(val name: String)
}

// Reported, with examples that read as names.
class Examples<T1>(
    // A plural or a mass noun is not made plural again.
    val <!KOTRAIL_NAME_HIDES_TYPE!>positions<!>: List<LayoutCoordinates>,
    val <!KOTRAIL_NAME_HIDES_TYPE!>themes<!>: List<DesignTokens>,
    val <!KOTRAIL_NAME_HIDES_TYPE!>records<!>: List<UserData>,
    // A trailing digit group stays at the end.
    val <!KOTRAIL_NAME_HIDES_TYPE!>state1<!>: DataModel<T1>,
    val <!KOTRAIL_NAME_HIDES_TYPE!>r1<!>: Reply,
    // A word with digits in the type name stays one word, and an abbreviation is spelled out.
    val <!KOTRAIL_NAME_HIDES_TYPE!>cert<!>: java.security.cert.X509Certificate,
    val <!KOTRAIL_NAME_HIDES_TYPE!>tx<!>: Transaction,
    // An acronym is written as a word in camelCase.
    val <!KOTRAIL_NAME_HIDES_TYPE!>path<!>: java.net.URL,
)

object Patterns {
    // The role words are kept as written, digits included.
    val <!KOTRAIL_NAME_HIDES_TYPE!>SHA256_HEX<!>: Regex = Regex("[0-9a-f]{64}")
}

object PreferenceKeys {
    // A SCREAMING name that starts with the head noun moves it to the end.
    val <!KOTRAIL_NAME_HIDES_TYPE!>KEY_SERVER_PORT<!>: Preferences.Key<Int> = Preferences.Key("port")
}

object RedactionPresets {
    // A word whose letters merely appear in the type name is not an abbreviation of it.
    val <!KOTRAIL_NAME_HIDES_TYPE!>None<!>: NetworkRedactionRules = NetworkRedactionRules()
}

class Plugin

class PanelTextStyles {
    companion object {
        // Not reported: a companion's instance of its own class is read with the class's name in
        // front, as in PanelTextStyles.Default.
        val Default = PanelTextStyles()
    }
}

class Plugins(val plugins: List<Plugin>) {
    companion object {
        // Not reported: declared with its type.
        val Empty: Plugins = Plugins(emptyList())
    }
}

class ColorSchemeId(val value: String) {
    companion object {
        // Not reported: nullable or not, or in a container (ColorSchemeId.BuiltIns).
        val Light = ColorSchemeId("light")
        val Unset: ColorSchemeId? = null
        val BuiltIns: List<ColorSchemeId> = listOf(Light)

        // Reported: a companion's property of another type is checked like any other.
        val <!KOTRAIL_NAME_HIDES_TYPE!>Store<!> = UserRepository()
    }
}

class Toolbar {
    companion object {
        // Reported: an instance of another class, in the companion of this one.
        val <!KOTRAIL_NAME_HIDES_TYPE!>LongClick<!> = NodeActionHandler()
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, companionObject, data, enumDeclaration, enumEntry,
equalityExpression, functionDeclaration, functionalType, integerLiteral, interfaceDeclaration, lambdaLiteral,
localProperty, nestedClass, nullableType, objectDeclaration, override, primaryConstructor, propertyDeclaration, sealed,
stringLiteral, suspend, typeAliasDeclaration, typeParameter, value */
