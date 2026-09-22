# JvmSynthetic for internal

**Diagnostics:** `KOTRAIL_INTERNAL_VISIBLE_TO_JAVA` (error, on the declaration name), `KOTRAIL_INTERNAL_CLASS_VISIBLE_TO_JAVA` (warning, on the class name)
**Key:** `rules.jvmSyntheticForInternal` (**off by default**); `jvmSyntheticForInternalClass` carries the class warning's severity
**Settings:** none
**Fix:** automatic for functions and properties (`kotrailFix` inserts the annotation)

## What it rejects

An `internal` function or property of a JVM module without `@JvmSynthetic`, and, as a warning,
an `internal` class:

```kotlin
internal fun reset() { }             // reported: public to Java, as reset$module
internal val cache: Cache = ...      // reported: getCache$module
internal class Cache                 // warned: a public class on the JVM, whatever Kotlin says
```

## What it asks for

```kotlin
@JvmSynthetic internal fun reset() { }
@get:JvmSynthetic internal val cache: Cache = ...
@get:JvmSynthetic @set:JvmSynthetic internal var label: String = ""
```

The JVM has no `internal`. The Kotlin compiler keeps other Kotlin modules out, but a Java
caller sees a public member whose name is mangled with the module's, and can call it.
`@JvmSynthetic` marks the member synthetic in bytecode, which the Java compiler ignores, so the
declaration becomes as hidden from Java as it is from Kotlin. Nothing does the same for a class:
a class cannot be synthetic, so an `internal` class stays public and the rule can only say so.
The ways out are a private nested class, or a module that Java consumers do not depend on.

## When it fires

- The compilation targets the JVM.
- The declaration is `internal` and reachable as such: its effective visibility is internal,
  not narrower (a member of a private class is already unreachable).
- A function lacks `@JvmSynthetic`; a property lacks `@get:JvmSynthetic`, or is a `var` whose
  non-private setter lacks `@set:JvmSynthetic`.

## When it stays quiet

- The rule is off, which it is by default: it is for a module whose consumers include Java.
- The compilation is not a JVM one.
- The member belongs to an interface (Java interfaces cannot carry synthetic methods) or is
  `expect`.

## Fixtures

`compiler-tests/testData/diagnostics/jvmSyntheticForInternal.kt`

## Implementation notes

`fir/checkers/JvmSyntheticForInternalChecker.kt`, a `FirBasicDeclarationChecker` that inspects
`FirRegularClass`, `FirNamedFunction`, and `FirProperty`, gated on `moduleData.platform.isJvm()`
and on `effectiveVisibility == EffectiveVisibility.Internal`. The annotation is looked up on the
function symbol, or on the property's getter and setter symbols.
