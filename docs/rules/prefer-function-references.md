# Prefer function references

**Diagnostic:** `KOTRAIL_PREFER_FUNCTION_REFERENCE` (error, on the lambda)
**Key:** `rules.preferFunctionReferences` (on by default)
**Setting:** `forms` (default `topLevel,bound,typeQualified`)
**Fix:** automatic (`kotrailFix` replaces the lambda with the reference, moving a trailing lambda into the parentheses)

## Choosing which forms to ask for

The rule produces exactly three shapes of reference, and each can be switched off
independently. Teams that find `File::readText`-style references harder to read than
`{ it.readText() }` drop `typeQualified` and keep the rest.

| Form | Example | Produced from |
|---|---|---|
| `topLevel` | `::transform`, `::save` | a top-level function, or a member on the implicit receiver |
| `bound` | `repository::save`, `this::load`, `Formatter::format` | a member called on a stable value |
| `typeQualified` | `User::name`, `String::trim`, `File::readText` | a member or extension called on the first lambda parameter |

```yaml
rules:
  preferFunctionReferences:
    forms: [topLevel, bound]
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
work. A Java getter read as a synthetic property (`{ it.absolutePath }`) is referenced through
the getter, `File::getAbsolutePath`, since a reference to the synthetic property does not compile.

The class of a type-qualified reference is written the way the file can name it: a nested class
with its outer classes (`Sheet.Line::text`), a class imported under an alias by the alias
(`Span::beat` after `import lib.music.ChordSpan as Span`). A class the file has only met through
inference has no name there yet, such as the element type in `spans.map { it.beat }` when
`ChordSpan` lives in another package and nothing imports it. The reference is still asked for,
and the fix adds `import lib.music.ChordSpan` with it: among the imports at its sorted place, after
the package directive when there are none, at the start of the file when there is neither. When
file annotations come first, or the neighboring import or directive shares its line with other
code, the finding comes without a fix.

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
- The callee cannot be referenced at all: a member extension (`private fun Item.redacted()`
  declared inside a class); a property where a suspend function type is expected
  (`flow.map { it.keys }`), since a property reference is never `suspend`; or a property that
  shares its name with a function on the receiver or in its package, which is ambiguous
  (`NSObject::accessibilityViewIsModal` on Kotlin/Native, where an Objective-C property and its
  getter method arrive side by side).
- Arguments are reordered, transformed, or partially supplied, or the body has more than one
  statement.
- The reference would name a class whose simple name already means something else in the file
  (a class of that name declared, imported, or in the same package): only its package could tell
  them apart.

Note on allocation: a reference passed to a non-inline function allocates a function object
exactly like a lambda would, so the rewrite is neutral for performance. Passed to an inline
function, both are inlined.

## Fixtures

`compiler-tests/testData/diagnostics/preferFunctionReferences.kt`,
`compiler-tests/testData/diagnostics/preferFunctionReferencesImports.kt` (with the fixes in
`preferFunctionReferencesImports.fixes.txt`)

## Implementation notes

`fir/checkers/PreferFunctionReferenceChecker.kt`, a `FirAnonymousFunctionChecker`. Overloads
are detected through `declaredFunctions` of the owning class or
`symbolProvider.getTopLevelFunctionSymbols`; `suspend` is compared between the lambda's
resolved function type and the callee's status. A property reference is checked against the
receiver's `unsubstitutedScope` and the top-level functions of its package for a function of the
same name, and a `FirSyntheticPropertySymbol` is named by its `getterId`. The name of the
outermost class comes from `classNameInFile` (`fir/ClassNamesInFile.kt`, shared with
noFqnReferences): an explicit import, with its alias if it has one, or the simple name when the
file's package, a star import or a default import reaches the class and nothing shadows it. The
import goes in through `FixBuilder.addImport`, and `kotrailFix` applies an import that several
fixes of one file add only once.
