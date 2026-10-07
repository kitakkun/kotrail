# No null state from parameter

**Diagnostic:** `KOTRAIL_NULL_STORED_FROM_PARAMETER` (error, on the property's name, or on the constructor parameter for a property declared in the primary constructor)
**Key:** `rules.noNullStateFromParameter` (on by default)
**Settings:** none

## What it rejects

A null that a constructor receives and the object carries as state, when every use of that
state only bails out on null:

```kotlin
class Recorder(ffmpegPath: String?) {
    private val ffmpegPath: String? = ffmpegPath          // reported
    fun record() { val path = ffmpegPath ?: return; run(path) }
    fun probe(): Int = ffmpegPath!!.length
}

class Recorder(private val ffmpegPath: String?) {       // reported
    fun record() { ffmpegPath?.let { run(it) } }
    fun available(): Boolean { if (ffmpegPath == null) return false; return probe(ffmpegPath) }
}
```

Whether there is an ffmpeg is a decision, and it belongs to the caller, once. Carried inside the
object, it is made again at every use: each `?: return` silently does nothing, each `!!` fails
late and far from where the null came in, and each new method has to remember to ask.

## What it asks for

Decide at the boundary. Take a non-null parameter and let the caller handle the missing value:

```kotlin
class Recorder(private val ffmpegPath: String) {
    fun record() = run(ffmpegPath)
}

val recorder = ffmpegPath?.let { Recorder(it) }               // the caller decides, once
val recorder = Recorder(requireNotNull(ffmpegPath) { "ffmpeg not found" })
```

Or, if absence is a real state of the object, name it with a type instead of a bare null:

```kotlin
sealed interface Encoder {
    data object Unavailable : Encoder
    class Ffmpeg(val path: String) : Encoder
}
```

A `when` over `Encoder` then says what happens without an encoder in one place, and the compiler
checks that every place handles it. There is no automatic fix: which of the two fits, and what
the callers do with the missing value, is a design decision.

## When it fires

All of these hold:

- The property is `private`, has a nullable declared type and default accessors, is not an
  override, and is not `expect` or `actual`.
- It belongs to a class or an enum class that is not a `data` class, not a value class, not
  annotated `@Serializable` (any annotation of that name, `kotlinx.serialization.Serializable`
  included) or `@Parcelize`, and not `expect` or `actual`.
- Its value comes from a nullable primary-constructor parameter: it is a `private val` or
  `private var` declared in the primary constructor with a nullable type, or its initializer is a
  plain read of a nullable primary-constructor parameter (no call, no conversion). A parameter
  with a default `= null` counts.
- For a `var`: every assignment in the class stores a read of a nullable parameter
  (`fun configure(next: String?) { path = next }`).
- The class reads the property at least once, and every read bails out on null:
  - the left side of `?:` whose right side exits: `return`, `return@label`, `throw`, `break`,
    `continue`, or a call that returns `Nothing` (`error(...)`, `TODO()`);
  - `x!!`, and the argument of `requireNotNull` or `checkNotNull`;
  - a null test (`x == null`, `x != null`, `x is T`, `x !is T`) that is the condition of an
    `if` or `when` branch, directly or through `!`, `&&` and `||` as long as a null alone settles
    the condition, and the null leaves: the branch a null selects exits, or, when a null makes the
    condition false, nothing follows the branch (`if (x != null) run(x)`) or only an `else` that
    exits. The same test as the condition of `require` or `check` counts when a null fails it;
  - a safe call, or a chain of safe calls, on the property whose value is dropped
    (`x?.let { run(it) }` as a statement), whose value meets an elvis that exits
    (`x?.trim()?.length ?: return 0`), or meets `!!`;
  - a read smart cast to a non-null type: an earlier test already handled the null, and that
    test is judged on its own.
- A read of the same constructor parameter elsewhere in the class (another property's
  initializer, an `init` block) bails out as well.

## When it stays quiet

- Any read uses the null as a value: `x ?: "(none)"`, `x != null` returned or assigned, a null
  test whose null branch does work (`if (x == null) log("missing")`) or reaches an `else` that
  does, `x` passed to a nullable parameter, returned, stored, string-templated, compared with
  something other than `null`, a call on the nullable receiver (`x.orEmpty()`), a callable
  reference to it, or a safe call whose value is used (`val length = x?.length`). Anything the
  rule does not recognize as a bail-out counts as a use as a value.
- The constructor parameter is used as a value elsewhere in the class
  (`private val description = "path: $path"`).
- The property is `public`, `internal` or `protected`: code outside the class may use the null.
- A `data` class, a value class, or a class annotated `@Serializable` or `@Parcelize`: its
  properties mirror data that is written and read elsewhere.
- The class sets the property to null itself (`fun clear() { path = null }`): null is then a
  state the class manages. The same for any assignment other than a read of a nullable
  parameter.
- The initializer transforms the parameter (`= path?.trim()`), or the property has a custom
  getter or setter or a delegate.
- Nothing reads the property.
- Overrides, `expect` and `actual` declarations.
- Files in a test source set (`src/test/`, `src/jvmTest/`, `src/androidUnitTest/` and kin), and
  generated code, by the project's `generated` settings, like every rule.

## Known limits

- Only private properties are looked at. A public or internal property carries the same smell
  when its readers all bail out, but those readers can live anywhere, and the property's type is
  part of an API that callers may depend on.
- Properties assigned from function parameters, setter-style
  (`fun configure(path: String?) { this.path = path }`), are not looked at on their own: the null
  has to come in through the primary constructor. Such an assignment is accepted next to a
  constructor parameter, but a property that starts at `null` and is set only through a function
  is a different design (state that arrives later) and stays out of this round.

## Related rules

[No not-null assertion](no-not-null-assertion.md) reports each `!!` on its own; this rule names
where the null that every `!!` and `?: return` keeps answering came in.
[Narrow model parameters](narrow-model-parameters.md) asks a function for only the data it uses;
this rule asks a constructor for only the values the class can work with.

## Fixtures

`compiler-tests/testData/diagnostics/noNullStateFromParameter.kt`, with its rendered messages in
`noNullStateFromParameter.diag.txt`.

## Implementation notes

`fir/checkers/NoNullStateFromParameterChecker.kt`, a `FirPropertyChecker`. The stored parameter is
`correspondingValueParameterFromPrimaryConstructor` for a property declared in the constructor,
or the value parameter a plain initializer read resolves to, kept when its containing symbol is
the primary constructor. The class is walked once per candidate with a visitor that keeps the
chain of parents; each read of the property or of the parameter is judged from its parents,
skipping argument lists and argument wrappers. An exit is an expression of type `Nothing`, which
covers `return`, `throw`, `break`, `continue` and calls such as `error()`; a block takes the type
of its last statement, so a braced branch that ends in `return` exits too. Whether a safe call's
value is dropped is decided by the same walk up the parents as in
[No swap through also](no-swap-through-also.md) (`fir/ValueUse.kt`). The diagnostic of a
property declared in the constructor is reported on the value parameter's source, so it lands on
the name as written.
