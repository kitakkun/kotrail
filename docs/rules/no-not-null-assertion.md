# No not-null assertion

**Diagnostic:** `KOTRAIL_NOT_NULL_ASSERTION` (error, on the `x!!` expression)
**Switch:** `rules.noNotNullAssertion` (default `true`)
**Severity key:** `severity.noNotNullAssertion`
**Settings:** none

## What it rejects

```kotlin
val name = user!!.name
val first = list.firstOrNull()!!
val port = config["port"]!!
```

## What it asks for

Say what should happen when the value is `null` instead of turning it into a
`NullPointerException` at the point of use:

```kotlin
val name = user?.name ?: return
val first = list.firstOrNull() ?: error("list is empty")
val port = requireNotNull(config["port"]) { "port is not configured" }
```

Safe calls, elvis with an early exit, `requireNotNull`/`checkNotNull` with a message, or a plain
null check that lets the compiler smart cast all express the intent that `!!` hides.

## When it fires

Every `!!` written in source, once per operator: `user!!.name!!` yields two diagnostics.

## When it stays quiet

- Compiler-generated null checks that have no real source.
- Everything that is not `!!`: `?.`, `?:`, `requireNotNull`, `checkNotNull`, smart casts.

There are no exemptions. A deliberate use (for example in a test that asserts non-nullness) can be
kept with `@Suppress("KOTRAIL_NOT_NULL_ASSERTION")` at the spot.

## Fixtures

`compiler-tests/testData/diagnostics/notNullAssertion.kt`

## Implementation notes

`fir/checkers/NotNullAssertionChecker.kt`. A `FirCheckNotNullCallChecker`: `x!!` is a
`FirCheckNotNullCall` whose source is the whole postfix expression, so the report needs no further
positioning.
