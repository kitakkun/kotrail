# Forbidden call

**Diagnostic:** `FORBIDDEN_CALL` (error, on the whole call)
**Switch:** `rules.forbiddenCall` (default `true`)
**Severity key:** `severity.forbiddenCall`
**Setting:** `forbiddenCall.functions` (comma-separated fully qualified callables, default empty)

## What it rejects

```kotlin
// forbiddenCall.functions=kotlin.io.println,java.lang.Thread.sleep,java.util.Date,kotlinx.coroutines.GlobalScope.launch
println("debug")
Thread.sleep(100)
val now = Date()
GlobalScope.launch { sync() }
```

## What it asks for

Use whatever the project has decided replaces the listed callable: a logger instead of `println`,
`delay` instead of `Thread.sleep`, `Instant` instead of `Date`, a structured scope instead of
`GlobalScope`. The rule carries no opinion of its own; it turns a team convention that used to
live in code review into a compile error.

## When it fires

A function or constructor call resolves to a callable whose fully qualified name is listed in
`forbiddenCall.functions`. Names are spelled as follows:

| Kind | Spelling | Example |
| --- | --- | --- |
| Top-level function | `package.name` | `kotlin.io.println` |
| Member (class, object, Java static) | `class.name` | `java.lang.Thread.sleep`, `com.acme.Logger.debug` |
| Constructor | class name | `java.util.Date` |
| Extension called through an object | `object.name` | `kotlinx.coroutines.GlobalScope.launch` |

An extension is matched both by its own name (`kotlinx.coroutines.launch`, which catches every
call of that extension) and by the object it is called through (`kotlinx.coroutines.GlobalScope.launch`,
which only catches calls on that qualifier). Overloads share one name: listing `kotlin.io.println`
forbids every `println`.

## When it stays quiet

- `forbiddenCall.functions` is empty (the default).
- The callee is not listed: another overload set, another member of the same object, a same-named
  function in another package.
- Property accesses and callable references (`::println`): only calls are inspected, so a forbidden
  function that is passed around as a value is not reported at the reference.
- Compiler-generated calls without real source, such as the `iterator()` of a `for` loop.
- Extensions listed by qualifier (`Scope.launch`) that are called on an instance (`scope.launch`).

## Fixtures

`compiler-tests/testData/diagnostics/config/forbiddenCall.kt`

## Implementation notes

`fir/checkers/ForbiddenCallChecker.kt`. A `FirFunctionCallChecker` that resolves the callee symbol
and compares the configured strings against the candidate spellings of its `CallableId`
(`asSingleFqName()`, the class FQN for constructors, plus `qualifier.name` when the explicit
receiver is a `FirResolvedQualifier`). The reported argument is the configured entry that matched.
