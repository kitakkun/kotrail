# Prefer function references

**Diagnostic:** `KOTRAIL_PREFER_FUNCTION_REFERENCE` (error, on the lambda)
**Switch:** `rules.preferFunctionReferences` (default `true`)
**Severity key:** `severity.preferFunctionReferences`
**Setting:** `preferFunctionReferences.forms` (default `topLevel,bound,typeQualified`)

## Choosing which forms to ask for

The rule produces exactly three shapes of reference, and each can be switched off
independently. Teams that find `File::readText`-style references harder to read than
`{ it.readText() }` drop `typeQualified` and keep the rest.

| Form | Example | Produced from |
|---|---|---|
| `topLevel` | `::transform`, `::save` | a top-level function, or a member on the implicit receiver |
| `bound` | `repository::save`, `this::load`, `Formatter::format` | a member called on a stable value |
| `typeQualified` | `User::name`, `String::trim`, `File::readText` | a member or extension called on the first lambda parameter |

```properties
preferFunctionReferences.forms=topLevel,bound
```

## What it rejects

```kotlin
users.map { transform(it) }
users.map { it.name }
users.forEach { repository.save(it) }
ints.zip(ints) { a, b -> pair(a, b) }
```

## What it asks for

```kotlin
users.map(::transform)
users.map(User::name)
users.forEach(repository::save)
ints.zip(ints, ::pair)
```

The message carries the exact reference to use. `{ it.name }` and `User::name` mixed across a
codebase is a typical drift; the reference form is shorter and cannot silently pick up extra
work.

## When it fires

A lambda whose body is a single call (or property read) that forwards the lambda's parameters
in order, with nothing added, dropped, or transformed, when the reference is guaranteed to
resolve to the same callee:

- the callee has no `vararg` parameter, no type parameters, and no same-named overload
  (top-level in its package, or declared in its class);
- every parameter of the callee receives one lambda parameter (no default argument is relied
  on);
- the receiver, if any, is the first lambda parameter (`Type::member`), `this`, an object or
  companion, or a plain `val` / parameter (`value::member`);
- the lambda and the callee agree on `suspend`.

## When it stays quiet

- The lambda or the callee is `@Composable` (composable function references are not something to
  push people toward).
- The lambda has a receiver type (`T.() -> R`).
- The body calls a function-typed value (`{ block() }`): there is no named callee.
- The receiver is a fresh expression (`{ Repository().save(it) }`) or a `var`.
- The callee is a constructor (constructor overload sets are not inspected yet).
- Arguments are reordered, transformed, or partially supplied, or the body has more than one
  statement.

Note on allocation: a reference passed to a non-inline function allocates a function object
exactly like a lambda would, so the rewrite is neutral for performance. Passed to an inline
function, both are inlined.

## Fixtures

`compiler-tests/testData/diagnostics/preferFunctionReferences.kt`

## Implementation notes

`fir/checkers/PreferFunctionReferenceChecker.kt`, a `FirAnonymousFunctionChecker`. Overloads
are detected through `declaredFunctions` of the owning class or
`symbolProvider.getTopLevelFunctionSymbols`; `suspend` is compared between the lambda's
resolved function type and the callee's status.
