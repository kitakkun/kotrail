# Objective-C throws (Kotlin/Native)

**Diagnostic:** `KOTRAIL_OBJC_EXPORT_MISSING_THROWS` (error, on the function name)
**Key:** `rules.native.objcThrows` (on by default)
**Settings:** none

## What it rejects

A public function of an Apple framework that can let an exception out and declares no
`@Throws`:

```kotlin
fun parse(text: String): Config {
    require(text.isNotBlank()) { "empty" }     // reported: IllegalArgumentException reaches Swift undeclared
    return Config(text)
}

fun load(path: String): Config = parse(read(path))   // reported: what parse lets out passes through
```

## What it asks for

```kotlin
@Throws(IllegalArgumentException::class)
fun parse(text: String): Config { ... }

@Throws(IllegalArgumentException::class, IOException::class)
fun load(path: String): Config = parse(read(path))
```

Kotlin/Native lets an exception cross into Swift or Objective-C only from a function that
declares it: with `@Throws`, the exception arrives as an `NSError` the caller can handle; without
it, the runtime terminates the process. A framework author who writes `require(...)` in a public
function, or calls one that does, ships a crash the Swift side cannot catch. The rule asks for the
declaration at the boundary, where the exception becomes an error the caller sees.

## When it fires

- The compilation is an Apple native one (`kotlinx.cinterop.ObjCObject` resolves); everywhere
  else the rule is silent.
- The function is public API (`public` in a public class, or top level), has a body, is not an
  `override`, and is not `@HiddenFromObjC` itself or through its class.
- Its body can let an exception out: a `throw`; a call to `require`, `requireNotNull`, `check`,
  `checkNotNull` or `error`; a call to a function that declares `@Throws`; or a call to a function
  of this module that lets one out itself, transitively. The lambda of an inline function
  (`map`, `let`) counts as the body; any other lambda runs later and does not.
- No enclosing `try` catches it: a `catch` of `Throwable` or `Exception` covers everything, and a
  `catch` of the thrown class covers that throw.
- The message names the first thing found: `IllegalArgumentException (require)`,
  `ParseException (declared by 'parse')`, `ParseException (through 'load')`.

## When it stays quiet

- The function declares `@Throws`, whatever it lists.
- The function is internal, private, protected, an override, or hidden from Objective-C.
- The throw sits in a lambda that runs later (a callback, a coroutine), behind its own boundary.
- The function calls only functions of other modules without `@Throws`: what a library throws
  without declaring it is unknown to the rule.

## Related rules

[Catch too broad](../catch-too-broad.md) covers the catch side of the same boundary.

## Fixtures

`compiler-tests/testData/diagnostics/native/objcThrows.kt`

## Implementation notes

`fir/native/checkers/ObjCThrowsChecker.kt`, a function checker, over
`fir/native/ThrowsService.kt`, a session component that memoizes per function what its body lets
out: a visitor that skips lambdas except inline ones, tracks the catch types of enclosing `try`
blocks, and recurses into callees of this module.
