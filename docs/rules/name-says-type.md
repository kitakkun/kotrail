# Name says type

**Diagnostic:** `KOTRAIL_NAME_HIDES_TYPE` (error, on the name)
**Key:** `rules.nameSaysType` (on by default)
**Settings:** `scope` (default `members`), `publicParameters` (default `true`), `exempt`, `valueTypes`, `valueSupertypes`, `containers`, `typeSuffixes`, `nameSuffixes` (defaults below)
**Fix:** none

## What it rejects

A property or parameter whose name does not end with what it holds:

```kotlin
class NodeEditor(
    private val request: PerformNodeAction,      // reported: end it with 'Action'
    private val title: TextStyle,                // reported: end it with 'Style'
    private val source: InputStream,             // reported: end it with 'Stream'
    private val delegate: UserRepository,        // reported: end it with 'Repository'
    private val repo: UserRepository,            // reported: end it with 'Repository'
    private val processes: ProcessTable,         // reported: end it with 'Table'
    private val versions: HostVersionsRepository, // reported: end it with 'Repository'
    private val limit: McpParameter<Int>,        // reported: end it with 'Parameter'
    private val children: List<UiNode>,          // reported: end it with 'Nodes'
    private val executor: ExecutorService,       // reported: end it with 'Service'
) {
    private val installLock = Mutex()            // reported: end it with 'Mutex'
}
```

## What it asks for

```kotlin
class NodeEditor(
    private val requestedAction: PerformNodeAction,
    private val titleStyle: TextStyle,
    private val sourceStream: InputStream,
    private val delegateRepository: UserRepository,
    private val userRepository: UserRepository,
    private val processTable: ProcessTable,
    private val versionsRepository: HostVersionsRepository,
    private val limitParameter: McpParameter<Int>,
    private val childNodes: List<UiNode>,
    private val executorService: ExecutorService,
) {
    private val installMutex = Mutex()
}
```

A value's name is its role followed by what it is. The role is free: `requested`, `title`,
`delegate`, `install` are the author's to choose. What it is comes last, as the head noun of its
type, the last word of the type's name: `Action` for a `PerformNodeAction`, `Style` for a
`TextStyle`, `Repository` for a `UserRepository`.

A reader does not always have the type on the mental stack: in a diff, a review, a grep result,
or code an assistant wrote, the declaration with the type is somewhere else. `request`, `title`
and `source` read as a request, a string and a source of something, and `processes` reads as a
list of processes when it is a table. A name that ends with the head noun says what the value is
wherever it appears, and English puts that word last: a `titleStyle` is a style, a
`requestedAction` is an action.

## When it fires

Names and type names are split into words at camelCase humps, acronyms, digits and underscores,
and compared case-insensitively; a plural matches its singular (`s`, `es`, `y` to `ies`, and
`children`, `people`, `leaves`, `indices`). A name passes when its last word is one of the head
nouns of its type:

- **The type's head noun:** the last word of the class's simple name. `requestedAction` passes
  for a `PerformNodeAction`; `request`, and `node`, which is a word of the type but not its last,
  do not.
- **Meaningless type suffixes are dropped** first (`typeSuffixes`: `Values`, `Impl`, `Object`):
  `contentPadding` passes for a `PaddingValues`, `repository` for a `UserRepositoryImpl`, and
  `saveMutation` for a `MutationObject<User>`. A name may still end with the dropped word
  (`paddingValues`). `Data`, `Info` or `Manager` are not
  on the list: they are part of what the type is (`userData: UserData`).
- **Outer classes:** the head noun of a nested class's outer classes: `negotiationResult`
  passes for a `NegotiationResult.Success`.
- **Type aliases:** the alias's head noun and that of the type it stands for: for
  `typealias Token = AccessCredential`, `token` and `credential` both pass, and the message asks
  for the alias, the name the reader sees. A platform alias that keeps the class's name
  (`ArrayList`, `HashMap` for their `java.util` classes) is just the class.
- **The type's own name:** a name that is the type's simple name, case aside, passes
  (`jmdns: JmDNS`), and so does a last word that spells out the type's head noun: the singular
  of the head noun, three letters or more, followed by three letters or more (`libraries: Libs`).
  `appLocale` does not spell out `Local`.
- **Containers** (`containers`, or a subtype of one: a `CompletableDeferred` is a `Deferred`):
  the head noun of the container, of the listed container it extends (`deferred` for a
  `CompletableDeferred`), or of an element, plural or not. Nested containers are walked to the
  innermost elements, and every type argument of a container with several is an element:

| Type | Passes | Reported |
|---|---|---|
| `List<UiNode>` | `childNodes`, `nodes`, `nodeList` | `children` |
| `Channel<Request>` | `requests`, `requestChannel` | `pending` |
| `StateFlow<UiState>` | `uiState`, `_uiState`, `stateFlow` | `screen` |
| `Flow<List<User>>` | `users`, `userFlow`, `userList` | `members` |
| `Map<String, User>` | `users`, `userMap`, `usersById`, `idToUser` | `cache`, `cacheById` |
| `KClass<User>` | `userClass`, `users` | `type` |
| `WeakReference<Listener>` | `listenerReference`, `listener` | `listenerRef` |
| `MutableMap<String, CompletableDeferred<Reply>>` | `pendingReplies`, `replyMap` | `pending` |

  A map's name ends with what it holds or with `Map`, before a phrase that names its key
  (`usersById`, below).

A supertype's head noun does not count, whatever the depth. `request: PerformNodeAction` is
reported though a `PerformNodeAction` is a `NodeRequest`: the name says the value is a request,
and a reader who meets `request` in a diff looks for a request type, not for the action it is
(`requestedAction`). `executor: ExecutorService` is reported the same way and asks for
`executorService`, and `listener: ClickListener` passes on the type's own head noun whatever the
listener extends. A container keeps its supertypes in one sense only: a generic subtype of a
`containers` entry is recognized as a container through it, and its name may end with the listed
container's head noun besides its own and an element's (`replyDeferred` or `replies` for a
`CompletableDeferred<Reply>`).

Before its last word is taken, a name loses a leading underscore (`_uiState`) and a trailing
qualifier (`nameSuffixes`: `OrNull`, `OrDefault`, `OrEmpty`), so `selectedItemOrNull` passes for
an `Item?`. Nullability does not matter: `user: User?` passes as `user: User` does.

A trailing phrase opened by `By`, `For`, `Of`, `Per`, `From`, `To`, `With`, `In`, `On` or `At`
(such a word that is neither the first nor the last) qualifies the word before it, so that word
counts as the name's last: `viewsById: Map<Int, View>` passes as views, `entriesByAddress` as
entries. The last word as written counts too, so a map named from its key to its value
(`idToUser`) passes. A type name loses its trailing phrase the same way before its head noun is
taken (`root: ViewRootForTest` passes, a `ViewRootForTest` is a root), with `By`, `For`, `Of`,
`Per`, `From`, `To` and `With` only: in a type name `In`, `On` and `At` mostly end a phrasal verb
(`SignInRequest` is a request, not a sign).

The rule has no table of abbreviations or synonyms: `repo`, `vm`, `cfg` and `lock: Mutex` are
reported on purpose. A project that accepts one name everywhere lists it under `exempt` (`config`,
for instance). A PascalCase or SCREAMING_SNAKE name ends with the head noun too:
`LongClick: NodeActionHandler` is reported, `LongClickHandler` passes, unless the value sits in
the companion of its own class (below).

The message names the head noun and gives an example:

```
[Kotrail] 'request' does not end with what it holds (PerformNodeAction): end the name with 'Action', as in 'requestAction'.
[Kotrail] 'repo' does not end with what it holds (UserRepository): end the name with 'Repository', as in 'userRepository'.
[Kotrail] 'children' does not end with what it holds (List<UiNode>): end the name with 'Nodes'.
```

The example is the name followed by the head noun (`requestAction`, `titleStyle`). When the name
ends with an abbreviation of the type's name, the example spells the type's name out
(`userRepository`, `loginViewModel`, `appConfig`, `transaction` for `tx`). An abbreviation starts
with the first letter of one of the type's words and is read as pieces, each the first letter of
the next word followed by some of that word's later letters in order (`repo`, `cert`, `cfg`,
`vm`), or as that first letter followed by consonants alone (`tx`, `ctx`); a word whose letters
merely appear somewhere in the type name is not one (`None: NetworkRedactionRules` gives
`NoneRules`). When the name ends with a word of the type before the head noun, the example
continues with the type's words from there (`processes` gives `processTable`). A container's
example ends with the container's head noun (`pendingChannel`).

The name's own words are kept as written (`SHA256_HEX` gives `SHA256_HEX_REGEX`), and what
follows its role words comes back after the type's words: a trailing phrase (`cacheById` gives
`cacheMapById`), a qualifier (`OrNull`) and a trailing number (`state1: DataModel<T1>` gives
`stateModel1`, `r1: Reply` gives `reply1`). A digit group inside a type name stays with its word
(`cert: X509Certificate` gives `x509Certificate`), an acronym of three letters or more is written
as a word in camelCase (`path: URL` gives `pathUrl`), and a SCREAMING name that starts with the
head noun has it moved to the end (`KEY_SERVER_PORT` gives `SERVER_PORT_KEY`).

A plural name of a container is only asked for the element's plural (`'Nodes'`): the singular of
its role is not the rule's to guess. An element that reads as a plural already (`Coordinates`,
`Tokens`) or is not counted (`Data`, `Info`) is asked for as it is. The example is a suggestion:
the right role word (`requestedAction` or `requestAction`) is the author's.

The default `scope`, `members`, checks:

- member properties, constructor properties included (reported on the parameter);
- the value parameters of constructors and of member and top-level functions.

`all` also checks top-level properties, locals, local functions' parameters and lambda
parameters. Short local names are often fine, which is why that is not the default.
`publicParameters: false` takes the parameters of public API out of either scope (see
[Public API parameters](#public-api-parameters)).

## When it stays quiet

- The type asks nothing of a name:
  - a value type (`valueTypes`): `count: Int`, `timeout: Duration`, `padding: Dp`,
    `background: Color`, `dir: File`, `counter: AtomicInteger`, `worker: Job`, `loop: Thread`,
    `tree: JsonObject`, `payload: Any`. A value says nothing beyond its role, and `timeoutDuration`
    or `countInt` would repeat what every reader knows;
  - a subtype of a `valueSupertypes` entry: `error: IOException`, `cause: Throwable`, named for
    their role; text of any kind, `text: AnnotatedString` or `output: StringBuilder`, as every
    `CharSequence` is; a time mark, `started: TimeMark` or a `ValueTimeMark`;
  - Objective-C's root class and protocol and the opaque pointers of C interop, which play the
    role of `Any`: `node: NSObject`, `observers: List<NSObjectProtocol>`,
    `RETURN_SELECTOR: COpaquePointer?`. They are listed by their exact names, so a subclass such
    as `UIView` or `UIWindow` keeps its head noun;
  - a type whose last word, once a `typeSuffixes` word is dropped, is an adjective in `-able` or
    `-ible`: `expire: Runnable`, `host: AutoCloseable`, `contents: Transferable`,
    `order: Comparable<User>`. It says what the value can do, not what it is, and has no head
    noun. A short stem keeps `Table` and `Cable` nouns, and `Variable` and a few other nouns are
    listed. A container's elements are read the same way: `tasks: List<Runnable>` asks nothing,
    while `jobs: Map<Runnable, User>` still asks for `Users`;
  - a function type of any kind: `onClick: () -> Unit`, `load: suspend (String) -> User`,
    `factory: KFunction0<User>`, and a `@Composable () -> Unit`, which the Compose compiler gives
    a function kind of its own (`ComposableFunction0`). A function is named for what it does;
  - an object, its own single value: `json: Json.Default`, `settings: Defaults`;
  - a container whose elements all ask nothing: `names: List<String>`, `handlers: List<() -> Unit>`,
    `firstFrame: CompletableDeferred<Unit>`;
  - a type parameter (`value: T`), a star projection (`type: KClass<*>`) or an anonymous type.
- The declaration is a member of a **token table**: a class or object whose declared properties,
  its constructor properties included, are two or more and all of one type, such as
  `Typography(val titleLarge: TextStyle, val titleMedium: TextStyle, val bodyMedium: TextStyle)`,
  `Shapes(val small: Shape, val medium: Shape, val large: Shape)` or
  `object ColorSchemes { val Light: ColorScheme; val Dark: ColorScheme }`. Those names are a
  design system's vocabulary, used as keys like enum entries (`typography.titleLarge`), and the
  class says the type once for all of them. A parameter of the table's own functions that carries
  a property's name is a key too: `fun copy(titleLarge: TextStyle = this.titleLarge, ...)`.
- The property is declared in a `companion object` and holds an instance of the class the
  companion belongs to, nullable or not, or a container of such instances:
  `val Default = TextStyles()` in `TextStyles`'s companion, `val Empty: Plugins` in `Plugins`'s,
  `val BuiltIns: List<ColorSchemeId>` in `ColorSchemeId`'s. It is read with the type's name in
  front, `TextStyles.Default`, `Rules.None`, `State.Idle`, which says what the value is. A
  property of another type in the same companion is checked like any other, and so is an
  instance of the class anywhere else: at the top level, in an object, or in the companion of
  another class (`val LongClick = NodeActionHandler()`).
- The name is on the `exempt` list, matched with or without its leading underscores: `it`,
  `index`, `other`, and an `on*` callback (`onLogin: LoginListener`), named by convention.
- The property is a `const val`, named for its role.
- The declaration is a composition local named by the Compose convention: its type is a
  `CompositionLocal` or a subtype (`ProvidableCompositionLocal`), and `Local` is the first word of
  its name (`LocalAppLocale`). Named otherwise (`appLocale`), it ends with `Local` like any value.
- The declaration is a property, or a constructor parameter, of a `@Serializable` class: a DTO
  mirrors a wire format, whose names are not the code's to choose.
- The declaration is an `override` or `actual` (its name is decided where it is declared, and
  reported there), a property accessor's parameter, a catch parameter, or a declaration the
  compiler made up.
- The parameter is a `@Composable` function's `modifier` or `content`, named by the Compose
  conventions.
- The file is in a test source set (`src/test/`, `src/jvmTest/`, ...).

## Settings

```yaml
rules:
  nameSaysType:
    scope: all
    publicParameters: false
    exempt: [it, index, other, "on*", config, ctx]
    valueTypes: [kotlin.Int, kotlin.String, "kotlin.Function*", java.io.File]
    valueSupertypes: [kotlin.Throwable, kotlin.CharSequence]
    containers: ["kotlin.collections.*", "kotlinx.coroutines.flow.*"]
    typeSuffixes: [Values, Impl, Object]
    nameSuffixes: [OrNull, OrDefault, OrEmpty, OrThrow]
```

Each list replaces its default; keep the entries you still want.

- `exempt`, globs over names: `it`, `this`, `self`, `value`, `result`, `item`, `element`, `key`,
  `index`, `i`, `j`, `k`, `x`, `y`, `z`, `e`, `t`, `other`, `on*`.
- `valueTypes`, globs over fully qualified types: `kotlin.Boolean`, `kotlin.Byte`, `kotlin.Short`,
  `kotlin.Int`, `kotlin.Long`, `kotlin.Float`, `kotlin.Double`, `kotlin.Char`, `kotlin.UByte`,
  `kotlin.UShort`, `kotlin.UInt`, `kotlin.ULong`, `kotlin.Number`, the primitive arrays
  (`kotlin.IntArray` and its kin), `kotlin.String`, `kotlin.CharSequence`, `kotlin.Unit`,
  `kotlin.Any`, `kotlin.Nothing`, `kotlin.Function*`, `kotlin.jvm.functions.Function*`,
  `kotlin.coroutines.SuspendFunction*`, `kotlin.reflect.KFunction*`,
  `kotlin.reflect.KSuspendFunction*`, `androidx.compose.runtime.internal.ComposableFunction*`,
  `kotlin.Pair`, `kotlin.Triple`, `kotlin.ranges.*`, `kotlin.time.Duration`,
  `kotlin.time.Instant`, `kotlinx.datetime.*`, `java.time.*`, `java.io.File`,
  `java.nio.file.Path`, `okio.Path`, `java.util.concurrent.atomic.*`, `kotlinx.coroutines.Job`,
  `java.lang.Thread`, `kotlinx.serialization.json.*`, `androidx.compose.ui.unit.Dp`,
  `androidx.compose.ui.unit.TextUnit`, `androidx.compose.ui.unit.IntOffset`,
  `androidx.compose.ui.unit.IntSize`, `androidx.compose.ui.unit.DpSize`,
  `androidx.compose.ui.geometry.*`, `androidx.compose.ui.graphics.Color`,
  `platform.darwin.NSObject`, `platform.darwin.NSObjectProtocol`, `kotlinx.cinterop.ObjCObject`,
  `kotlinx.cinterop.CPointer`, `kotlinx.cinterop.COpaquePointer`. A function type of any
  kind and an object are value types whatever this list says; the function globs stay as a
  fallback for a compiler that does not report a function kind. An enum is not a value type: its
  head noun is usually the right end for the name (`displayMode: DisplayMode`).
- `valueSupertypes`, globs over fully qualified types whose subtypes, and themselves, are value
  types: `kotlin.Throwable`, `kotlin.CharSequence`, `kotlin.time.TimeMark`.
- `containers`, globs over fully qualified generic types: `kotlin.collections.*`,
  `kotlin.sequences.Sequence`, `kotlin.Array`, `kotlin.Result`, `kotlin.Lazy`,
  `kotlinx.collections.immutable.*`, `kotlinx.coroutines.flow.*`,
  `kotlinx.coroutines.channels.*`, `kotlinx.coroutines.Deferred`,
  `androidx.compose.runtime.State`, `androidx.compose.runtime.MutableState`,
  `androidx.compose.runtime.snapshots.*`, `java.util.*` (`LinkedHashMap`, `ArrayDeque`,
  `Optional`, `java.util.concurrent.*`), `java.lang.ThreadLocal`, `java.lang.ref.*`,
  `kotlin.reflect.KClass` and `java.lang.Class`. A generic subtype of a listed type is a container
  too (`CompletableDeferred<Reply>`, a `Deferred`), and accepts the listed type's head noun
  besides its own. A listed type without type arguments
  (`ExecutorService`) is checked like any class, and `valueTypes` wins over `containers`
  (`AtomicReference<User>` asks nothing). A generic class not on the list is checked like any
  class: `limit: McpParameter<Int>` asks for `Parameter`.
- `typeSuffixes`, words (camelCase runs, matched case-insensitively) dropped from the end of a
  type's simple name before its head noun is taken, as long as a word is left: `Values`, `Impl`,
  `Object`.
- `nameSuffixes`, words dropped from the end of a name before its last word is compared, as long
  as a word is left: `OrNull`, `OrDefault`, `OrEmpty`.
- `publicParameters`, `true` or `false` (default `true`): whether the parameters of public API
  are checked, below.

### Public API parameters

A parameter's name is part of its function's signature for every caller that passes the argument
by name: `load(repo = cache)` stops compiling when `repo` becomes `userRepository`. In an
application those callers are the project's own code, renamed in the same change, so the default
`true` checks every parameter. A library published to others has callers its author cannot
update, and renaming a public parameter is a breaking change there; `publicParameters: false`
leaves those parameters alone:

```kotlin
fun load(repo: UserRepository)                // not reported: a public function
class Presenter(val repo: UserRepository)     // not reported: a public constructor
private fun cache(repo: UserRepository)       // reported: not public API
```

Left alone are the value parameters of functions and constructors whose effective visibility is
public or protected: a public member of a public class, a protected member of an open one, a
public top-level function. A constructor's `val` and `var` parameters count as parameters, since
their name is the named argument's too, so `class Presenter(private val repo: UserRepository)`
is left alone as well; a property declared in the class body is still checked, as are the
parameters of private, internal and local functions, of a private constructor, and of a public
function in a class that is not public itself. Switch it off in the modules a library publishes
(a module's `configFile` can be its own, see the [Gradle plugin](../gradle-plugin.md)), and keep
it on for an application and for modules nobody outside the project calls.

## Fix

None. The head noun is certain, the role word is not: `request: PerformNodeAction` may become
`requestedAction`, `pendingAction` or `requestAction`, and only the author knows which. The
message's example is a starting point.

## Related rules

[Named arguments for repeated types](named-arguments-for-repeated-types.md) names arguments at the
call site; this rule names the values themselves. [No ambiguous string name](no-ambiguous-string-name.md)
covers the `String` and `CharSequence` this rule leaves alone: their type says nothing, so it reads
the kind of text from the API the value reaches and asks the name to say it.

## Fixtures

`compiler-tests/testData/diagnostics/nameSaysType.kt` (with its messages in
`nameSaysType.diag.txt`, which pin the examples),
`compiler-tests/testData/diagnostics/config/nameSaysTypeScopeAll.kt` (`scope: all`, a replaced
`exempt` list, `valueTypes` narrowed so that a `const val` is seen to be exempt, and a top-level
instance reported beside a companion's own),
`compiler-tests/testData/diagnostics/config/nameSaysTypePublicParameters.kt`
(`publicParameters: false`: public and protected functions' and constructors' parameters quiet,
private and internal ones still reported)

## Implementation notes

`fir/checkers/NameSaysTypeChecker.kt`: `Properties`, a `FirPropertyChecker`, and `Parameters`, a
`FirValueParameterChecker`, so that a `@Suppress` on a parameter or its function applies. The
type is the declaration's resolved return type; aliases are expanded for the value-type and
container decisions (`fullyExpandedType`) and kept, through the abbreviated type, for the head
nouns and the message. A class's head nouns are those of every segment of its class id's
relative name, so a nested class carries its outer classes'; its supertypes' are not taken. A
function type is recognized by `isSomeFunctionType`, which asks the session's function type
kinds and so covers a compiler plugin's kinds (Compose's `ComposableFunction*`); an object by its
class kind; a `valueSupertypes` entry by `lookupSuperTypes` over the class, and so are a
container that is a subtype of a `containers` entry and a composition local. A token table is
read from the class's declared properties (`ModelSurface.properties`), compared by their fully
expanded types without nullability. A parameter is public API when its function's or
constructor's `effectiveVisibility` is `publicApi` (public or protected, all the way out). A
companion is recognized by `isCompanion` on the class that declares the property, and the class
it belongs to by the companion's outer class id; the property's type is compared with that class
once aliases are expanded, a container's through its elements, all of which must be that class.
`TypeWords` holds the word rules: splitting, suffixes, plurals, the advice and its example.
