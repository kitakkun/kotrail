# Prefer function references

**Diagnostic:** `KOTRAIL_PREFER_FUNCTION_REFERENCE` (error, on the lambda)
**Key:** `rules.preferFunctionReferences` (on by default)
**Settings:** `forms` (default `topLevel,bound,typeQualified`), `ignoredFunctions` (default `[]`)
**Fix:** automatic (`kotrailFix` replaces the lambda with the reference, moving a trailing lambda into the parentheses, by name when its position would bind another parameter; see [The fix](#the-fix))

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

## Leaving functions alone

`ignoredFunctions` lists functions and properties whose lambdas stay as they are, as globs over
the fully qualified name of what the lambda forwards to: `com.acme.transform`, a member as
`com.acme.Repository.save`, `com.acme.Repository.save*` for a family of names. Every overload of a
matching name is left alone. A Java getter read as a property (`{ it.absolutePath }`) is matched by
the getter's name, `java.io.File.getAbsolutePath`, the name the reference would use. The default is
empty.

```yaml
rules:
  preferFunctionReferences:
    ignoredFunctions: [com.acme.ui.Strings.format, java.io.File.getAbsolutePath]
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

The object or companion of a bound reference is written as the call spells it, which already
resolves in the file: `Metronome.Click.at(it)` becomes `Metronome.Click::at`, `Meter.of(it)` on a
companion becomes `Meter::of`, and `lib.music.Tempo.scale(it)` keeps its package.

## The fix

`kotrailFix` puts the reference where the lambda was. A trailing lambda moves into the
parentheses, as the argument of the parameter the lambda bound to:

- By position when that keeps the parameter: no argument of the call is named, none goes to a
  `vararg` parameter (which would take the reference in as well), and the arguments fill the
  parameters before the lambda's one by one, in order. `users.map { it.name }` becomes
  `users.map(User::name)`, and `ints.zip(ints) { a, b -> pair(a, b) }` becomes
  `ints.zip(ints, ::pair)`.
- By name otherwise. `names.joinToString(", ") { it.shout() }` becomes
  `names.joinToString(", ", transform = String::shout)`, since `prefix` and the other defaulted
  parameters come before `transform`, and `mutex.withLock { shutDown() }` becomes
  `mutex.withLock(action = ::shutDown)`, since `owner: Any?` comes first. A call with a named
  argument (`configure(name = "a") { shutDown() }`) or a `vararg` before the lambda
  (`launchAll(1, 2) { shutDown() }`) gets the name as well.
- Without a fix when neither is certain: the position would bind another parameter, and the
  callee's parameter names cannot be written, as those of a Java method or a function-typed value
  cannot. The finding is still reported.

After a trailing comma the reference goes last, on a line of its own when the comma ends its
line, and the comma stays. A lambda that is not trailing is replaced in place, with its label
(`step@{ it.name }`) if it has one: a lambda already inside the parentheses, named or not, and the
operand of an infix call, so that `locator onLocating { tracker.onInstalling() }` becomes
`locator onLocating tracker::onInstalling`. A trailing lambda with something other than
whitespace before it, such as a comment, gets no fix.

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
- The body is written with operator syntax: `{ -it }`, `{ !it }`, `{ a + b }`, `{ a..b }`,
  `{ it in set }`, `{ list[it] }`, `{ counter += it }`. The lambda names no function, and
  `slideInVertically { -it }` reads better than `slideInVertically(Int::unaryMinus)`. The same
  function called by name (`{ it.unaryMinus() }`), and an infix function written by name
  (`{ a, b -> a shl b }`), are still reported. A comparison (`{ a, b -> a < b }`), an equality,
  `!in` and an indexed assignment (`{ k, v -> table[k] = v }`) are never one forwarding call to
  begin with.
- The callee is a constructor (constructor overload sets are not inspected yet), or a top-level
  function named like the class it is declared to return, or like a supertype of that class,
  nullability aside: `remember { MutableInteractionSource() }`, `{ CoroutineScope(it) }`, and
  `{ Job(it) }`, whose `Job(parent)` returns a `CompletableJob`. Such a call reads as a constructor
  call, so it is left alone like `remember { SnackbarHostState() }`. A function named like a class
  that returns something else is still reported.
- The callee's fully qualified name matches `ignoredFunctions`.
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
`compiler-tests/testData/diagnostics/preferFunctionReferencesImports.kt` and
`compiler-tests/testData/diagnostics/preferFunctionReferencesLibraryImports.kt` (each with its
`.fixes.txt`),
`compiler-tests/testData/diagnostics/preferFunctionReferencesOperators.kt`,
`compiler-tests/testData/diagnostics/preferFunctionReferencesFactories.kt` and
`compiler-tests/testData/diagnostics/preferFunctionReferencesTrailingSlot.kt` (each with its
`.fixes.txt`), `compiler-tests/testData/diagnostics/config/functionReferenceIgnoredFunctions.kt`

## Implementation notes

`fir/checkers/PreferFunctionReferenceChecker.kt`, a `FirAnonymousFunctionChecker`. Operator
syntax is told by the call's `FirFunctionCallOrigin.Operator`, which the raw FIR builder gives to
prefix and binary operators, `in`, indexing and augmented assignments; a comparison is a
`FirComparisonExpression`, an equality a `FirEqualityOperatorCall`, `!in` a `not()` of the
`contains` call, and an indexed assignment a block that ends in an implicit `Unit`. A factory
function is told by the class of its `resolvedReturnType` and that class's `lookupSuperTypes`. Overloads
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

The fix tells a trailing lambda by `FirAnonymousFunctionExpression.isTrailingLambda`, and an
infix call's operand by its not being one. The enclosing call's `resolvedArgumentMapping` gives
the parameter the lambda binds to and those the other arguments fill, the
`FirNamedArgumentExpression`s left in the resolved argument list's `originalArgumentList` give the
named arguments, and the callee's `hasStableParameterNames` says whether a name can be written. A
lambda passed to a `fun interface` parameter arrives wrapped in its conversion, a
`FirFunctionTypeConversionExpression` (a `FirSamConversionExpression` on Kotlin 2.3), which
`unwrapFunctionConversion` in the compat layer takes off. The label comes from the lambda's
`FirLabel`, whose source is the label as written.
