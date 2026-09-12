# No unimplemented code

**Diagnostic:** `KOTRAIL_UNIMPLEMENTED_CODE` (error, on the call)
**Switch:** `rules.noUnimplemented` (default `true`)
**Severity key:** `severity.noUnimplemented`

## What it rejects

```kotlin
override fun load(): User = TODO("wire the repository")
override fun clear() { throw NotImplementedError() }
```

## What it asks for

Either the implementation, or an explicit decision such as `UnsupportedOperationException`
with a message that says why the operation is not part of the contract.

`TODO()` type-checks as anything and throws the first time it runs. That makes it the easiest way
for a generated draft to look finished: the file compiles, the tests that do not reach the stub
pass, and the crash arrives in production. This rule makes the placeholder a compile error.

## Keeping it out of the shipping build only

Stubs are legitimate while a feature is under construction, so the intended setup is: on for the
build that ships, off everywhere else. The compiler sees one compilation at a time, so the split
is expressed by handing each compilation its own configuration file (see
[Test source sets](../configuration.md#test-source-sets)):

```properties
# kotrail-dev.properties (debug variants and tests)
rules.noUnimplemented=false
```

```kotlin
tasks.withType<KotlinCompile>().configureEach {
    val shipping = name.contains("Release") && !name.contains("Test")
    val file = if (shipping) "kotrail.properties" else "kotrail-dev.properties"
    compilerOptions.freeCompilerArgs.addAll(
        "-P", "plugin:com.kitakkun.kotrail:configFile=${projectDir.resolve(file)}",
    )
}
```

A local `./gradlew assembleDebug` then accepts `TODO()`, and the release build refuses it.

## When it fires

- A call to `kotlin.TODO`, with or without a reason.
- A construction of `kotlin.NotImplementedError`, whether thrown or stored.

## When it stays quiet

- `// TODO` comments: not code, and not a compile-time concern.
- A user-defined function named `TODO`: the callable's fully qualified name is compared, not its
  spelling.
- `UnsupportedOperationException` and other explicit refusals: those are decisions, not
  placeholders.
- Code in a compilation whose configuration file switches the rule off.

## Fixtures

`compiler-tests/testData/diagnostics/noUnimplemented.kt`

## Implementation notes

`fir/checkers/UnimplementedCodeChecker.kt`. A `FirFunctionCallChecker` that resolves the callee
and compares its `CallableId` (`kotlin/TODO`) or, for constructors, its `ClassId`
(`kotlin/NotImplementedError`). The reported argument names what was found.
