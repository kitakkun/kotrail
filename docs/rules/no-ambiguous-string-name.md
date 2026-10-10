# No ambiguous string name

**Diagnostic:** `KOTRAIL_STRING_NAME_HIDES_KIND` (error, on the name)
**Key:** `rules.noAmbiguousStringName` (on by default)
**Settings:** `kinds`, `extraKinds` (the kinds of text and the words that say them), `apis`, `extraApis` (the callables that take each kind; see [Kinds and APIs](#kinds-and-apis))
**Fix:** automatic for a local, a lambda parameter, a private property and a private function's parameter (see below)

## What it rejects

A `String` whose name does not say what kind of text it holds:

```kotlin
class Recorder(val ffmpeg: String) {                        // reported: 'ffmpeg' holds the path of an executable
    fun record() = ProcessBuilder(ffmpeg, "-i", "screen").start()
    fun available() = ffmpeg.isNotEmpty()
}

fun users(client: HttpClient, base: String) =               // reported: 'base' holds a URL
    client.get("$base/users")

fun check(rule: String, input: String) = Regex(rule).matches(input)   // reported: 'rule' holds a regular expression
```

## What it asks for

```kotlin
class Recorder(val ffmpegPath: String) {
    fun record() = ProcessBuilder(ffmpegPath, "-i", "screen").start()
    fun available() = ffmpegPath.isNotEmpty()
}

fun users(client: HttpClient, baseUrl: String) = client.get("$baseUrl/users")

fun check(ruleRegex: String, input: String) = Regex(ruleRegex).matches(input)
```

A `String`'s type says nothing about the text in it: a path, a URL, a pattern, a class name and
a line of SQL are all `String`. The name is the only place left to say it, and a name that does
not makes every use a guess: the reader of `ffmpeg.isNotEmpty()` cannot tell whether it is a
tool, a command line or a path, and has to find the one line where the value reaches
`ProcessBuilder` to learn it. The rule reads the kind from that line, from the API the value
flows into, and asks the name to carry a word of that kind. The message suggests the name with
the kind's first word as a suffix (`ffmpegPath`, `baseUrl`, `ruleRegex`, `IMPL_CLASS_NAME` for a
constant) and lists the other words that pass too: `ffmpegExecutable`, `homeEndpoint`,
`rulePattern`. A name that is one word saying only that the value is text (`text`, `value`,
`line`, `string`, `raw`, `input`, `content`, `str`, `s`) has no role to keep, so the suggestion is
the kind's word alone: `text` handed to `decodeFromString` is asked for `json`, `value` handed to
`File` for `path`, `RAW` for `JSON`.

## Kinds and APIs

Each kind has words a name may carry; the first is the suggested suffix. Each API entry names a
callable and the kind of text its argument holds. By default:

| Kind | Words (first: the suffix) | APIs |
|---|---|---|
| `path` | `path`, `file`, `dir`, `directory`, `executable`, `exe`, `command`, `cmd`, `binary`, `bin`, `location`, `folder` | a project's own `path:` entries |
| `file` | the same | `java.io.File`, `FileInputStream`, `FileOutputStream`, `FileReader`, `FileWriter`, `java.nio.file.Path.of`, `java.nio.file.Paths.get`, `kotlin.io.path.Path`, `okio.Path.Companion.toPath` |
| `directory` | `dir`, then the rest of the same | a file API used as a directory (below) |
| `executable` | the same as `path` | `java.lang.ProcessBuilder`, `ProcessBuilder.command`, `java.lang.Runtime.exec` |
| `url` | `url`, `uri`, `endpoint`, `link`, `href` | `java.net.URL` and `java.net.URI` given one argument, `java.net.URI.create`, `android.net.Uri.parse`, Ktor's `io.ktor.client.request` `get`, `post`, `put`, `delete`, `request` and `url` (their `urlString`), `io.ktor.http.Url` |
| `regex` | `regex`, `pattern`, `expression` | `kotlin.text.Regex`, `kotlin.text.toRegex` (its receiver), `java.util.regex.Pattern.compile` |
| `json` | `json`, `body`, `payload`, `response`, `request` | `kotlinx.serialization.json.Json.decodeFromString`, `kotlinx.serialization.StringFormat.decodeFromString` and its reified extension (their `string`), `Json.parseToJsonElement`, `org.json.JSONObject`, `org.json.JSONArray` |
| `id` | `id`, `uuid`, `identifier`, `key` | `java.util.UUID.fromString` |
| `date` | `date`, `time`, `timestamp`, `instant`, `iso` | `java.time.LocalDate.parse`, `LocalDateTime.parse`, `Instant.parse`, `kotlinx.datetime.LocalDate.parse`, `LocalDateTime.parse`, `kotlin.time.Instant.parse` (what `kotlinx.datetime.Instant` is since 0.7) |
| `format` | `pattern`, `format`, `formatter` | `java.time.format.DateTimeFormatter.ofPattern`, `java.text.SimpleDateFormat` |
| `host` | `host`, `hostname`, `address`, `addr`, `server`, `domain` | `java.net.InetAddress.getByName`, `java.net.Socket` and `java.net.InetSocketAddress` (their host) |
| `keyName` | `name`, `key`, `variable`, `property`, `env` | `java.lang.System.getenv`, `System.getProperty`, `System.setProperty` (the key) |
| `className` | `className`, `class` | `java.lang.Class.forName` |
| `sql` | `sql`, `query`, `statement`, `stmt` | `android.database.sqlite.SQLiteDatabase.execSQL`, `rawQuery`, `java.sql.Statement.executeQuery`, `execute`, `executeUpdate`, `java.sql.Connection.prepareStatement` |

A word matches consecutive words of the name, split on camelCase and `_` boundaries and compared
case-insensitively: `ffmpegPath`, `pathToAdb`, `FFMPEG_PATH`, `homeEndPoint`, `implClassName` and
`sessionId` pass; `ffmpeg`, `profile` (the `file` inside it is not a word), `fmt` and `token` do
not.

The four path kinds share their words and differ in what the message says and suggests. The
value is a directory, suggested `<name>Dir`, when:

- it is the first of two or more parts of a path constructor or factory:
  `File(appData, "captures")`, `Path.of(root, "a", "b")`, `Paths.get(root, "log")`,
  `Path(base, "cache")` (the parts after it resolve against it);
- it starts a `"$x/..."` template that a path API or a process API takes (below);
- the created `File` or `Path` is used as one right away: `File(cache).mkdirs()`,
  `.isDirectory`, `.listFiles()`;
- the API's entry is of the `directory` kind.

A value that reaches APIs of several kinds passes with a word of any of them; the suggestion
follows the kind it reaches first, and the message names each kind it reaches.

## When it fires

- The subject is a property (member or top-level), a value parameter (of a function, a
  constructor or a lambda) or a local, of type `String` or `CharSequence` (nullable included),
  declared in the file.
- Its value reaches the argument of one of `apis` that holds the text: by default the extension
  receiver when it is text (`x.toRegex()`, `x.toPath()`), else the first argument (`File(x)`,
  `UUID.fromString(x)`), or the command head of `ProcessBuilder(x, ...)`,
  `ProcessBuilder(listOf(x, ...))`, `ProcessBuilder(arrayOf(x, ...))`, `Runtime.exec(x)` and
  `exec(arrayOf(x, ...))`; an entry may name another argument (see Settings). The value gets
  there directly (also through a smart cast, `!!`, or a scope function's `it` or `this`:
  `x?.let { File(it) }`, `x.also { ... }`, `x.takeIf { ... }`, `x.takeUnless { ... }`,
  `x.run { ... }`, `x.apply { ... }`, `with(x) { ... }`, where the lambda stands for `x` and
  adds no hop), or one hop away:
  - through a local initialized from it (`val sdkPath = home; File(sdkPath)` reports `home`), or
    a scope function's named lambda parameter (`ndk.let { program -> File(program) }` reports
    both);
  - as the argument of a same-file function's parameter that itself reaches one
    (`runTool(emulator)` where `fun runTool(executablePath: String) = ProcessBuilder(executablePath).start()`),
    also from a scope function's lambda: `ffmpeg?.let { posterFrame(it) }` where
    `private fun posterFrame(tool: String) { ProcessBuilder(tool).start() }` reports `ffmpeg` and `tool`;
  - as the head of a command list that reaches a process API: a local initialized with
    `listOf`, `mutableListOf`, `arrayListOf` or `arrayOf` whose first element is the value
    (`val command = listOf(ffmpeg, "-i"); ProcessBuilder(command)`), a `buildList` whose first
    `add(x)` or `addAll(listOf(x, ...))` is the value, held by a local or returned from a
    same-file function called in the process API's head (`ProcessBuilder(command("list"))`
    where `fun command(vararg arguments: String) = buildList { add(idevice); addAll(arguments) }`),
    or such a list passed to a same-file function's parameter that reaches one;
  - as the first segment of a string template that continues with `/`: `File("$base/Library")`,
    or `val sdk = "$userHome/sdk"; File(sdk)`, report `base` and `userHome` as directories, and
    `URI("$base/users")` reports `base` as a URL. A value later in the template is not
    followed: in `File("$top/$name")`, `name` is a file name and stays quiet.
- It reaches the API whatever branches run. A use inside a branch of a `when` or an `if` whose
  subject and conditions do not read the value counts only when every branch of a `when` that
  covers every case (an `else`, or an exhaustive `when` over an enum, a sealed type or a
  `Boolean`) reaches the same kind; a use outside such branches always counts. In
  `when (type) { JSON -> format.decodeFromString(text); STRING -> text }`, `text` is JSON only
  when `type` says so, and the rule does not ask its name to say JSON; in
  `if (text.startsWith("[")) format.decodeFromString(text)` the condition reads the value itself,
  and the use counts. This holds for every kind, and for the hops below: a local's use inside a
  branch whose condition reads the value the local was initialized from counts too.
- Its name carries no word of any kind it reaches.

The message says what the value holds, the API it reaches and the line, the suggested name, and
the words any name may carry instead:

```
'base' holds a URL (it reaches 'HttpClient.get' at Api.kt:12), but its name does not say so:
name it 'baseUrl', or any name that carries one of url, uri, endpoint, link, href.
```

With the fix on, the declaration and every reference to it in the file are renamed to the
suggestion when all of them are known: a local `val`, a lambda parameter, a private `val`
property that is not a constructor parameter, or a parameter of a private function that no call
in the file passes by name. No fix is recorded when the file already declares or refers to
something by the new name (a string literal or a comment that mentions it does not count), for
a `var`, or for a public, internal or protected declaration, whose uses may be in other
files; the message still suggests the name.

## When it stays quiet

- The name already carries a word of a kind it reaches.
- The value never reaches an API of `apis`: `val name = "x"; println(name)`.
- The value is not a `String` or `CharSequence`: `val commandLine = listOf("adb", "devices")`.
- The argument is not the one the entry names: the child name of `File(parent, child)`, the
  input of `Regex(pattern).matches(input)`, the port of `Socket(host, port)`.
- `URL` and `URI` given several parts: `URI(scheme, host, path, fragment)` starts with a scheme,
  not a URL.
- The value is read from a member of another object before the hop: in
  `val tool = config.ffmpeg; File(tool)`, `tool` is reported, the declaration nearest to the
  use, and `Config.ffmpeg` is not followed.
- A command list that never reaches a process API: `val arguments = listOf(tar, "-x"); println(arguments)`.
- A string template the value does not start, or that does not continue with `/`
  (`"$stem.log"`, `"x$prefix/y"`).
- The value reaches the API only in some branches of a `when` or an `if` that is not about the
  value: `if (verbose) File(target).exists()`, or one branch of `when (type)` of several.
- The declaration is not in the file: the parameters of the APIs themselves, or a property of a
  class in another file.
- An `override`, `expect` or `actual` declaration, whose name the supertype or the other side
  fixed, and a parameter of an override.
- Test source sets: fixtures and fakes pass text around under short names on purpose, and the
  rule would mostly report them.

## Known misses

- A `String` stored in another class's property and read back elsewhere is not followed to where
  it is read; following it needs a module-wide index of such properties.
- A value that reaches an API two hops away, or through a collection operation
  (`commands.map { ... }`, `candidates.firstOrNull()`): only one hop is followed.
- An API that is not in the table: a project's own HTTP client, DAO or process runner, until it
  is added to `extraApis`.

## Settings

`kinds` and `apis` replace the defaults when set; `extraKinds` and `extraApis` add to the
defaults, or to the replaced lists, so a project adds its own HTTP client or DAO without
restating the table.

- `kinds`, `extraKinds`: a mapping of kind names to their words, the first word the suggested
  suffix. An `extraKinds` entry named like an existing kind adds its words to that kind, after
  its own; a new name adds a kind, which the message calls "text of kind '<name>'". A default
  API whose kind a replaced `kinds` leaves out is dropped.
- `apis`, `extraApis`: a list of `<kind>:<fully qualified name>` entries, a constructor named by
  its class (`java.net.URI`), a member by its class (`java.sql.Statement.executeQuery`), a
  companion member through `Companion` (`kotlinx.datetime.LocalDate.Companion.parse`), a
  top-level or extension function by its package (`kotlin.text.toRegex`). An entry may end in
  the argument that holds the text: `#receiver` for the extension receiver, `#<index>` for the
  value parameter at that index, counted from 0, or `#<parameter name>` (Java classes rarely
  keep their parameter names; name their argument by its index); without one, the extension
  receiver when it is text, else the first argument. Every kind an entry names must be in the
  kinds.

```yaml
rules:
  noAmbiguousStringName:
    extraKinds:
      url: [route]                     # 'pageRoute' passes for a URL too
      channel: [channel, topic]        # a project's own kind; suggestion '<name>Channel'
    extraApis:
      - url:com.acme.net.Client.open   # the head: the first argument
      - sql:com.acme.db.Dao.query#1    # the second parameter
      - url:com.acme.net.fetch#target  # the parameter named 'target'
      - channel:com.acme.bus.subscribe
```

To check only some kinds, replace the table; the default APIs of the kinds left out go with
them:

```yaml
rules:
  noAmbiguousStringName:
    kinds:
      file: [path, file, dir, directory]
      directory: [dir, path, directory]
      executable: [path, executable, binary]
      url: [url, uri, endpoint]
```

On the command line and in a fixture's `KOTRAIL_CONFIG`, a kind is one option per entry:
`rules.noAmbiguousStringName.extraKinds=url=route`, and the APIs a comma-separated list:
`rules.noAmbiguousStringName.extraApis=url:com.acme.net.Client.open,sql:com.acme.db.Dao.query#1`.

## Related rules

[Name says type](name-says-type.md) asks a name to end with what its type is, and leaves
`String` and `CharSequence` alone because their type says nothing; this rule covers them from
the other side, by what the text is used as. [Prefer duration](prefer-duration.md) asks the same
of a number: a bare `500` does not say it is milliseconds.
[No parameter comments](no-parameter-comments.md) removes the comment that would otherwise
explain what a parameter holds, which is the name's job.

## Fixtures

`compiler-tests/testData/diagnostics/noAmbiguousStringName.kt` for the path kinds and the flow
tracking, `noAmbiguousStringNameKinds.kt` for the other kinds, `noAmbiguousStringNameBranches.kt`
for uses in branches and names that say only that they hold text, `noAmbiguousStringNameApis.kt`
for a replaced `apis` list, `noAmbiguousStringNameExtra.kt` for `extraApis` and `extraKinds`,
each with its messages in the `.diag.txt` beside it; the rename of a local with its uses, a
string template included, and one whose new name appears only in a comment and a string
literal, are exercised by the `kotrailFix` functional test.

## Implementation notes

`fir/checkers/NoAmbiguousStringNameChecker.kt`: the file is walked once, by the first property or
value parameter checked in it, and the findings are kept by the declaration's offset; a property
checker and a value parameter checker report from the declaration itself, so a `@Suppress` on
it or an enclosing declaration applies. The walk collects the subjects, the argument every
`apis` entry names at each call to it (a constructor matched by its class, a function by its
fully qualified name), and the hops: the locals initialized from a subject, a command list
headed by it or a `"$subject/..."` template, the same for the arguments mapped to the parameters
of same-file functions, the command lists same-file functions return, and every reference to a
subject. A scope function's implicit `it` or receiver is an alias of the value it was called on,
resolved wherever a subject is read, so the lambda is no hop; a named parameter of one is a hop
like a local. A subject's uses are its own and its hops' holders', ordered by where it reaches
them (the call for its own, the hand-over for a hop); a command list counts only at a process
API, and a template makes a path use a directory. A primary-constructor property is reported as
its parameter. The rename edits the name in the declaration and each reference's callee source,
and is dropped when any reference's text is not the name, or when a declaration or a named
reference anywhere in the file has the new name.
