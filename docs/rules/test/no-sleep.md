# No sleep in tests

**Diagnostic:** `KOTRAIL_TEST_REAL_TIME_WAIT` (error, on the callee name)
**Key:** `rules.test.noSleep` (on by default)
**Settings:** `test.annotations`, `functions` (default `[java.lang.Thread.sleep, android.os.SystemClock.sleep, java.util.concurrent.TimeUnit.sleep]`), `virtualTime` (default `[kotlinx.coroutines.test.runTest]`)

## What it rejects

A wait on real time inside a test:

```kotlin
@Test fun `emits after a tick`() {
    ticker.start()
    Thread.sleep(500)                      // reported
    assertEquals(1, ticker.count)
}

@Test fun `emits after a tick`() = runBlocking {
    ticker.start()
    delay(500)                             // reported: real time
    assertEquals(1, ticker.count)
}
```

## What it asks for

```kotlin
@Test fun `emits after a tick`() = runTest {
    ticker.start()
    delay(500)                             // virtual time: returns at once
    assertEquals(1, ticker.count)
}
```

or, when the code under test is not a coroutine, awaiting the condition (a latch, a `Channel`,
Turbine's `awaitItem()`). A fixed wait is either too long, and the suite pays for it on every
run, or too short, and the test is flaky; on a slower machine it is both. Under `runTest`,
`delay` skips virtual time and returns immediately, so the same test runs in milliseconds and
deterministically.

## How a wait is recognized

- `functions` lists the fully qualified functions that always wait real time; these are reported
  anywhere in a test, including inside `runTest`, where `Thread.sleep` still blocks the thread.
- `kotlinx.coroutines.delay` is reported as well, except inside the lambda of a `virtualTime`
  function, where it is a virtual-time skip. A project with its own test-scope helper adds it:

```yaml
rules:
  test:
    noSleep:
      virtualTime:
        - kotlinx.coroutines.test.runTest
        - com.acme.test.runAppTest
```

Which functions are tests is `test.annotations`, shared with [test naming](naming.md).

## When it stays quiet

- The function carries none of the configured test annotations, whatever it calls.
- `delay` inside a `virtualTime` lambda, at any depth.
- A wait in production code called from the test: only the test body is inspected.

## Fixtures

`compiler-tests/testData/diagnostics/testNoSleep.kt`

## Implementation notes

`fir/test/checkers/TestSleepChecker.kt`, a `FirNamedFunctionChecker` over functions that
`isTestFunction` accepts. A `FirVisitorVoid` walks the body, reports calls whose `callableId` is in
`functions`, and reports `kotlinx.coroutines.delay` unless a `virtualTime` call is on the visitor's
stack. The `runTest` stub for the fixture lives in `compiler-tests/compose-stubs`.
