# Test naming

**Diagnostics:** `KOTRAIL_TEST_NAME_NOT_DESCRIPTIVE`, `KOTRAIL_TEST_NAME_NOT_IDENTIFIER` (error, on the function name)
**Switch:** `rules.test.naming` (default `true`)
**Severity key:** `severity.test.naming`
**Settings:** `test.annotations`, `test.naming.style`, `test.naming.minWords`

## What it rejects

```kotlin
@Test fun returnsEmptyList() { }        // reported: an identifier only hints at what is verified
@Test fun `fails`() { }                 // reported: backticks alone are not a description
@Test fun `rejects duplicates`() { }    // reported: two words, below the default minimum of three
```

## What it asks for

```kotlin
@Test fun `returns an empty list when nothing matches`() { }
@Test fun `rejects an order with duplicate line items`() { }
```

Kotlin's coding conventions allow method names with spaces in tests, and only in tests. A
sentence states what the test verifies; an identifier compresses it until the reader has to open
the body. When a test breaks, the failing name is often all a reader gets.

## How a test is recognized

By annotation, never by source set: the compiler sees one compilation at a time and cannot tell
a test source set from a main one. `test.annotations` lists the fully qualified annotations that
mark a function as a test; the default covers `kotlin.test` and JUnit 4 and 5:

```properties
test.annotations=kotlin.test.Test,org.junit.Test,org.junit.jupiter.api.Test,\
  org.junit.jupiter.api.RepeatedTest,org.junit.jupiter.api.TestFactory,\
  org.junit.jupiter.api.TestTemplate,org.junit.jupiter.params.ParameterizedTest
```

Setting the key **replaces** the list, so a project on its own framework names its own
annotation, and a project that wants the rule to apply to fewer cases shortens it. Spec-style
frameworks (Kotest, Spek) declare their cases as lambdas rather than annotated functions, so
they are never reported.

## Counting words instead of reading the source

A name that contains a space can only have been written in backticks, so the rule needs no access
to the source text: requiring at least `test.naming.minWords` (default `3`) whitespace-separated
words asks for a backticked sentence, and rejects `returnsEmptyList` and `` `fails` `` alike.
Setting it to `2` requires backticks and nothing more; setting it to `1` accepts any name, which
makes the rule inert — switch the rule off instead.

## Android instrumented tests

An instrumented test runs on a device, and a method name with spaces fails there. Give that
compilation `test.naming.style=identifier` and the rule asks for the opposite, reporting
`KOTRAIL_TEST_NAME_NOT_IDENTIFIER` for a name that is not a plain identifier. As with every per-source-set
setting, this is expressed by handing the compilation its own configuration file (see
[Test source sets](../../configuration.md#test-source-sets)):

```properties
# kotrail-androidTest.properties
test.naming.style=identifier
```

## When it stays quiet

- The function carries none of the configured annotations.
- The function is an `override` or an `expect` declaration: its name is fixed elsewhere.
- The name meets the word count (backticked style) or is a plain identifier (identifier style).

## Fixtures

`compiler-tests/testData/diagnostics/testNaming.kt`,
`compiler-tests/testData/diagnostics/config/testNaming.kt`,
`compiler-tests/testData/diagnostics/config/testNamingIdentifier.kt`

## Implementation notes

`fir/test/checkers/TestNamingChecker.kt`, with the annotation lookup in `fir/test/TestFunctions.kt`
so that later test rules share it. A `FirSimpleFunctionChecker` that compares each resolved
annotation's class id against the configured names and then inspects `declaration.name`.
