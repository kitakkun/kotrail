# Objective-C identity (Kotlin/Native)

**Diagnostics:**
`KOTRAIL_OBJC_IDENTITY_COMPARISON` (error, on the comparison),
`KOTRAIL_OBJC_WEAK_REFERENCE` (error, on the constructor call)
**Key:** `rules.native.objcIdentity` (on by default)
**Settings:** none

## The problem

Kotlin/Native does not give an Objective-C object one Kotlin identity. Each time the object
crosses into Kotlin, from a property, a notification, a collection, it may arrive in a fresh
wrapper, and two wrappers of one `UIWindow` are different Kotlin objects. Code that keeps a
registry of windows keyed by `===`, or looks a view up by identity, works on the first run and
misses on the next lookup. The same wrapper lifetime breaks `WeakReference`: the runtime
collects the wrapper as soon as nothing in Kotlin holds it, while the Objective-C object lives
on, so the reference answers `null` for a live object.

Neither mistake fails a build or a unit test on the JVM, and both are the kind of thing a
review points out repeatedly on iOS code.

## What it rejects

```kotlin
if (view.window === window) ...                     // reported: wrapper identity
registrations.firstOrNull { it.window === window }  // reported
val weakWindow = WeakReference(window)              // reported: the wrapper is collected
```

## What it asks for

```kotlin
if (view.window == window) ...                       // isEqual:, pointer equality for NSObject
registrations.firstOrNull { it.window?.objcPtr() == window.objcPtr() }
val strongWindow = window                            // or hold window.objcPtr()
```

`==` calls `isEqual:`, whose `NSObject` default compares pointers, and `hashCode()` calls
`hash`, so `==` and ordinary hash-based collections are correct on Objective-C objects.
`objcPtr()` compares addresses outright when the object's own `isEqual:` is not wanted.

## When it fires

- `===` or `!==` where either operand's type is an Objective-C class or protocol: anything that
  extends `kotlinx.cinterop.ObjCObject`, which is every `NSObject` subclass and every
  Objective-C protocol.
- `kotlin.native.ref.WeakReference(x)` where `x` is such an object and its class may be one
  defined in Objective-C: a class or protocol that cinterop generated, which carries
  `@ExternalObjCClass`. The class is read from the type of `x`, narrowed by a smart cast; when
  that type is one from Objective-C (a protocol, `NSObject`, `UIView`) and `x` reads a local
  `val` or a final `val` declared in the same file, without a delegate or a getter body, from the
  type of its initializer; and failing both, from the type argument. A parameter typed as a protocol is
  reported, since what it holds may come from Objective-C.

## When it stays quiet

- A comparison with `null`, which is a null check rather than an identity question.
- Identity comparisons and weak references on Kotlin objects.
- A weak reference to an instance of a class defined in Kotlin that extends `NSObject` or
  implements a protocol (`class Delegate : NSObject(), UIScrollViewDelegateProtocol`). Such an
  object is a Kotlin object first: its Kotlin side owns it and keeps one identity, with no
  wrapper in between, so the weak reference tracks the object itself as it does any Kotlin
  object. The class is known from the argument (`WeakReference(Delegate())`), from a smart cast
  (`if (d is Delegate) WeakReference(d)`), or from one hop to a `val`'s initializer
  (`val d: UIScrollViewDelegateProtocol = Delegate(); WeakReference(d)`). One hop only: a `val`
  initialized from another `val` is not followed further, and a `var`, a parameter, or a
  function result says no more than its declared type, so the weak reference is reported.
- Every compilation that is not a Kotlin/Native compilation linking a Darwin platform library:
  the interop names do not resolve there, so the rule does nothing on JVM, Android, JS, or
  Wasm sources.

## Fixtures

`compiler-tests/testData/diagnostics/native/objcIdentity.kt`, compiled against stubs of the
interop names under `compiler-tests/compose-stubs/src/main/kotlin/{kotlinx/cinterop,platform,kotlin/native/ref}`.
The platform stubs carry `@ExternalObjCClass` on every class and protocol, as cinterop output
does.

## Implementation notes

`fir/native/checkers/ObjCIdentityChecker.kt`. A `FirEqualityOperatorCallChecker` for the
identity operations, and a `FirFunctionCallChecker` for the `WeakReference` constructor, both
deciding through subtyping against `kotlinx.cinterop.ObjCObject`, which they look up through
the session's symbol provider and treat as absent when the compilation has no interop. A class
counts as defined in Kotlin when it is a subtype of `ObjCObject`, is not from `kotlinx.cinterop`
or the compiler's `objcnames` forward declarations, and neither it nor a class it is nested in
carries `kotlinx.cinterop.ExternalObjCClass`, the test the compiler itself applies; supertypes
are not consulted, since a Kotlin subclass of `NSObject` has no annotation of its own.
Kotlin/Native accepts only final classes and objects as Kotlin subtypes of Objective-C types, so
such a type names the object's exact class.
