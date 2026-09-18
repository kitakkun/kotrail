# Forbidden call

**Diagnostic:** `KOTRAIL_FORBIDDEN_CALL` (error, on the whole call)
**Switch:** `rules.forbiddenCall` (default `true`; inert until something is listed)
**Severity key:** `severity.forbiddenCall`
**Settings:** `forbiddenCall.functions` (fully qualified names, comma separated), `forbiddenCall[<name>]` (one call predicate per entry)

## What it rejects

```properties
forbiddenCall.functions=kotlin.io.println, java.lang.Thread.sleep
forbiddenCall[globalScope]=fqn(kotlinx.coroutines.launch) && receiver(kotlinx.coroutines.GlobalScope)
forbiddenCall[date]=constructor(java.util.Date)
```

```kotlin
println("debug")                  // reported (forbiddenCall[kotlin.io.println])
Thread.sleep(100)                 // reported (forbiddenCall[java.lang.Thread.sleep])
GlobalScope.launch { sync() }     // reported (forbiddenCall[globalScope])
val now = Date()                  // reported (forbiddenCall[date])
```

## What it asks for

Use whatever the project has decided replaces the listed callable: a logger instead of `println`,
`delay` instead of `Thread.sleep`, `Instant` instead of `Date`, a structured scope instead of
`GlobalScope`. The rule carries no opinion of its own; it turns a team convention that used to
live in code review into a compile error. The message names the entry that matched, so an entry
name that says why (`globalScope`, `blockingIo`) is the explanation.

## Two ways to list a call

`forbiddenCall.functions` is the short form: fully qualified names, and every overload of a
name is forbidden. Top-level functions are `package.name`, members (including Java statics and
object members) are `class.name`, and a constructor is its class name. A `Type.name` also
matches an extension `name` called on a `Type` receiver, explicit or implicit, which is how a
reader sees `GlobalScope.launch { }`.

`forbiddenCall[<name>]` takes a **call predicate**, for anything a bare name cannot say: which
receiver, which overload, which extension, with or without a context parameter. The grammar is
the one `exclude` uses (`&&`, `||`, `!`, parentheses); the atoms ask about the call:

| Atom | True when |
|---|---|
| `fqn(glob)` | the callee's fully qualified name matches; `fqn(kotlin.io.print*)` covers `print` and `println` |
| `constructor(glob)` | the call constructs a class whose fully qualified name matches |
| `extension`, `extension(fqn)` | the callee is an extension, of that declared receiver type |
| `receiver(fqn)` | the call is made on a receiver of that type: an object (`GlobalScope`), or a class, whether written explicitly or implicit |
| `context`, `context(fqn)` | the callee declares a context parameter, of that type |
| `params(fqn, fqn, ...)` | the callee's value parameters have exactly these types, in order; a vararg counts as its element type |
| `annotated(fqn)` | the callee carries the annotation |
| `suspend`, `composable` | the callee is one |

```properties
forbiddenCall[stringLog]=fqn(com.acme.log) && extension(kotlin.String)
forbiddenCall[bareRead]=fqn(com.acme.io.read) && !context(com.acme.io.IoScope)
forbiddenCall[legacyParse]=fqn(com.acme.parse) && params(kotlin.String, kotlin.Int)
forbiddenCall[blockingInCompose]=fqn(kotlinx.coroutines.runBlocking) && composable
```

Entries are keyed by name, so a later configuration file can replace one, add one, or drop one
with an empty value (`forbiddenCall[date]=`) while the rest stay in force.

## When it stays quiet

- Nothing is listed (the default).
- The call matches no entry: another overload, another receiver, a same-named function in another
  package.
- Property accesses and callable references (`::println`): only calls are inspected, so a
  forbidden function passed around as a value is not reported at the reference.
- Compiler-generated calls without real source, such as the `iterator()` of a `for` loop.
- Local functions, which have no stable fully qualified name.

## Fixtures

`compiler-tests/testData/diagnostics/config/forbiddenCall.kt` (the plain list),
`forbiddenCallPredicates.kt` (one entry per atom)

## Implementation notes

`fir/checkers/ForbiddenCallChecker.kt`. A `FirFunctionCallChecker` that describes the resolved
call as a `CallSite` (callee name, constructed class, declared extension receiver, the receiver
the call is made on, context and value parameter types, annotations, modifiers) and evaluates
the configured `CallPredicate`s against it. The predicate grammar is shared with `exclude`
through `PredicateGrammar`; the plain `forbiddenCall.functions` names become `fqn(...)` entries.
