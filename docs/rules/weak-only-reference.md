# Weak-only reference

**Diagnostic:** `KOTRAIL_WEAK_REFERENCE_TO_FRESH_OBJECT` (error, on the weak reference's construction)
**Key:** `rules.weakOnlyReference` (on by default)
**Settings:** `types` (default `[java.lang.ref.WeakReference, java.lang.ref.SoftReference, kotlin.native.ref.WeakReference]`)

## What it rejects

A weak reference that is the only reference to what it wraps:

```kotlin
bus.subscribe(WeakReference { event -> handle(event) })        // reported
bus.subscribe(WeakReference(object : Listener { ... }))        // reported
val listener = Listener()
bus.subscribe(WeakReference(listener))                         // reported: nothing else holds listener
```

## What it asks for

Something that keeps the object alive for as long as it should act, with the weak reference on
the other side:

```kotlin
class Screen {
    private val listener = Listener { event -> handle(event) }   // the screen holds it
    fun attach() = bus.subscribe(WeakReference(listener))        // the bus sees it while the screen lives
}
```

A weak reference keeps nothing alive; it only lets code observe an object while something else
keeps it. Wrapping a lambda, an anonymous object, or a fresh instance that no property, no
collection and no other local holds leaves the object to the next garbage collection, after
which the reference is empty and the listener silently stops doing its job. This is the bug
that tends to follow a "make it weak" fix to a leak: the leak goes, and so does the behavior.

## When it fires

- The call is a constructor of a type in `types`, subtypes included.
- Its first argument is a lambda, an anonymous object, a constructor call, or a local `val`
  initialized with one of those and read nowhere else in the enclosing function.

## When it stays quiet

- The argument is a parameter, a property, or a call that is not a constructor: something
  else is responsible for it.
- The argument is a local that the function also uses elsewhere (registered with something,
  passed on, called): the reading is conservative, so a local handed to a strong holder before
  being wrapped is not reported.

## Fixtures

`compiler-tests/testData/diagnostics/weakOnlyReference.kt`

## Implementation notes

`fir/checkers/WeakOnlyReferenceChecker.kt`, a `FirFunctionCallChecker`. A local's other reads
are counted by a visitor over the enclosing function's body.
