# No data class in public API

**Diagnostic:** `DATA_CLASS_IN_PUBLIC_API` (error, on the class name)
**Switch:** `rules.noDataClassInPublicApi` (default `true`)
**Severity key:** `severity.noDataClassInPublicApi`
**Setting:** `noDataClassInPublicApi.scope` (default `explicitApi`; `all` applies it to every module)

## What it rejects

```kotlin
public data class Config(val timeout: Int, val retries: Int)

public sealed interface Outcome {
    public data class Success(val value: String) : Outcome     // just as public
}
```

## What it asks for

```kotlin
public class Config(public val timeout: Int, public val retries: Int) {
    override fun equals(other: Any?): Boolean = ...
    override fun hashCode(): Int = ...
}

internal data class Draft(val text: String)   // not part of the contract
```

A data class promises more than it writes down. Its constructor, `copy()`, `componentN()`,
`equals`, `hashCode` and `toString` are all derived from the property list, so the day a property
is added:

- the signatures of the constructor and of `copy()` change, which is binary incompatible;
- equality and `hashCode` change meaning for every consumer that compares instances;
- destructuring positions may shift.

This is why the [Kotlin library guidelines](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html)
advise against data classes in a public API. It is also exactly the kind of thing an assistant
adds without a second thought: a model class, generated as a data class because that is the
default shape, and made public because the function next to it is.

## Which modules it applies to

An application exposes data classes everywhere, and should. What makes a module a library is that
its public API is a contract, and Kotlin's way of saying so is explicit API mode
(`kotlin { explicitApi() }` / `-Xexplicit-api=strict`). By default the rule applies only to
modules compiled with explicit API mode, strict or warning. A library that does not use it can
set `noDataClassInPublicApi.scope=all`; a module that wants the rule off keeps
`rules.noDataClassInPublicApi=false` in its own file.

## When it stays quiet

- The module does not compile with explicit API mode and the scope is `explicitApi` (the default).
- The class is not effectively public: `internal`, `private`, or nested in something that is.
- `data object`: it has no properties for any of the above to depend on.
- A regular class, whatever it exposes.

## Fixtures

`compiler-tests/testData/diagnostics/noDataClassInPublicApi.kt`,
`compiler-tests/testData/diagnostics/config/noDataClassScope.kt`,
`compiler-tests/testData/diagnostics/config/noDataClassWithoutExplicitApi.kt`

## Implementation notes

`fir/checkers/NoDataClassInPublicApiChecker.kt`. A `FirRegularClassChecker` that checks
`isData` with class kind `CLASS`, the declaration's effective visibility (`publicApi`), and the
session's `explicitApiMode` analysis flag for the default scope.
