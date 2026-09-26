# Native allocation in loop

**Diagnostic:** `KOTRAIL_NATIVE_ALLOCATION_IN_LOOP` (error, on the construction or factory call)
**Key:** `rules.nativeAllocationInLoop` (on by default)
**Settings:** `types` (default `[org.jetbrains.skia.impl.Managed, java.awt.image.VolatileImage]`), `factories` (default `java.nio.ByteBuffer.allocateDirect` and the Skia companion factories `Image.makeFromEncoded`, `Image.makeRaster`, `Surface.makeRaster` and their kin), `callbacks` (default: `collect`, `onEach`, `withFrameNanos`, `repeat`, `forEach` and their kin)

## What it rejects

An object that holds native memory, created once per iteration and not closed:

```kotlin
for (frame in frames) {
    val bitmap = Bitmap().apply { allocPixels(info) }     // reported
    publish(bitmap.asComposeImageBitmap())
}

frames.forEach { publish(Bitmap()) }                       // reported: a per-item callback is a loop

while (streaming) {
    val buffer = ByteBuffer.allocateDirect(1024)           // reported
    ...
}
```

## What it asks for

One instance reused across iterations, or one closed on every path:

```kotlin
val bitmap = Bitmap()
for (frame in frames) { bitmap.installPixels(frame) ; publish(bitmap) }
bitmap.close()

for (frame in frames) {
    Bitmap().use { draw(it) }
}
```

A Skia `Bitmap`, a direct `ByteBuffer` and their kin are a few bytes on the heap and megabytes
off it, and the native part is freed only when a cleaner runs after the garbage collector has
found the wrapper. In a loop the wrappers never add up to enough heap pressure to trigger a
collection, so the native memory grows without bound: a decode loop creating one bitmap per
frame in a 512 MB heap reached 189 GB of native memory before the machine gave out.

## When it fires

- The call is a constructor of a type in `types` (subtypes included), or a function in
  `factories`.
- It sits in the body of a `for`, `while` or `do` loop, or in the lambda of a function in
  `callbacks`, which runs once per item or frame (`collect`, `onEach`, `withFrameNanos`,
  `repeat`, `forEach`, ...).
- It is not the receiver of `use { }`, directly or through `apply`, `also`, `let`, `run`,
  `takeIf`, `takeUnless`.

The result being kept in a property declared outside the loop does not quiet the rule: the
previous instance is dropped unclosed just the same.

## When it stays quiet

- The allocation is outside any loop and outside every per-item lambda.
- The allocation is closed by `use { }`.
- The type is not on the list; a project adds its own native-backed types under `types`, and
  its own frame callbacks under `callbacks`. All three lists replace the defaults.

## Through a function

The allocation is often one call away, in a helper the loop calls:

```kotlin
fun decode(frame: Frame): Bitmap = Bitmap().apply { installPixels(frame) }

for (frame in frames) {
    publish(decode(frame))                   // reported: 'decode' creates a 'Bitmap' each call
}
```

The rule summarizes what each function lets out: a function allocates when its body creates one
of the listed types or calls a factory, or calls a function that allocates, and the object is
neither the receiver of `use { }` nor a local the body closes with `close()`. A call in a loop to
such a function is reported at the call, with the path to the allocation (`decode > newBitmap`),
unless the result is itself the receiver of `use { }`. In this module the summary comes from
the body; for callers in other modules it is written into the class file as
`@InferredNativeAllocation(types = ["Bitmap"], path = ["newBitmap"])` metadata, on every
target and with no artifact needed, so the path shown crosses the module boundary. A library
compiled without Kotrail carries no metadata and is taken as not allocating. Inside the helper,
the lambdas of inline functions (`synchronized`, `withLock`, `run`, `apply`) run in place and
are followed; any other lambda is not. A function that returns an instance it keeps
(`cached ?: Bitmap().also { cached = it }`) creates it once, not per call, and is not counted.

Not covered: producers that never stop.

## Fixtures

`compiler-tests/testData/diagnostics/nativeAllocationInLoop.kt`,
`compiler-tests/testData/diagnostics/nativeAllocationAcrossModules.kt`

## Implementation notes

`fir/checkers/NativeAllocationInLoopChecker.kt`, a `FirFunctionCallChecker`. The loop test walks
the checker context's containing elements for a loop or for a lambda whose enclosing call is a
listed callback; the `use` test walks them in the other direction while each parent is a call
whose explicit receiver is the previous element.
