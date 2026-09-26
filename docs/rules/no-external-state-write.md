# No external state write

**Diagnostic:** `KOTRAIL_STATE_WRITTEN_OUTSIDE_OWNER` (error, on the assignment or call)
**Key:** `rules.noExternalStateWrite` (on by default)
**Settings:** `types` (default: `androidx.compose.runtime.MutableState`, `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.flow.MutableSharedFlow`), `plainVars` (default: `false`)

## What it rejects

A write to a class's mutable state from code outside that class:

```kotlin
class Scene {
    val capturing = mutableStateOf(false)
    fun render() { ... }
}

class CaptureTool(private val scene: Scene) {
    fun capture() {
        scene.capturing.value = true          // reported: Scene's state, changed by CaptureTool
        try { record() } finally { scene.capturing.value = false }
    }
}
```

## What it asks for

```kotlin
class Scene {
    private val capturing = mutableStateOf(false)

    fun <T> captureWhile(block: () -> T): T {
        capturing.value = true
        try { return block() } finally { capturing.value = false }
    }
}

class CaptureTool(private val scene: Scene) {
    fun capture() = scene.captureWhile { record() }
}
```

When two callers each flip the same flag around their own work, each with its own
`try`/`finally`, nothing says which transitions are legal or what happens when the two overlap:
one caller's `finally` resets the flag while the other is still working, and a test that runs
both fails now and then. The answer is scattered over every caller. A method on the owner puts
every transition in one place, where it can be guarded (a counter instead of a boolean, a
check that the flag is not already set) and tested. An assistant that needs the flag set reaches
for the property it can see, because the class offers nothing better.

## When it fires

- `owner.state.value = x`, where `state` is a property of another class whose type is one of
  `types` (subtypes included). `+=` and `++` on `value` count too.
- A call of `update`, `emit`, `tryEmit`, `compareAndSet`, `getAndUpdate` or `updateAndGet` whose
  receiver is such a property: `repo.state.update { it.copy(x = 1) }`.
- An assignment to a delegated `var x by mutableStateOf()` of another class.
- With `plainVars` on, an assignment to a plain `var` member of another class whose setter is
  public or internal: `owner.count = 3`, `owner.count += 1`, `owner.count++`. It is off by
  default: a plain `var` written from outside is usually a single writer's input (a form field,
  a setting the one caller that owns it fills in), and the flags that two writers flip around
  their own work are state holders, reported either way. Turn it on for a codebase that wants
  every cross-object write reviewed.
- A state holder is reported on every owner: a data class, a class with no functions of its
  own, anything. `scene.flag.value = true` on a `data class Scene(val flag: MutableState<Boolean>)`
  that also declares `fun render()` is the bug this rule exists for, however the class is
  declared.
- An `internal` owner is an owner like any other: the whole module sees it, not only the
  writer's file.
- The owner is the class or object that declares the property; a property of a companion
  object belongs to the class around it. A top-level `object` is an owner like any class, so
  `Session.active.value = true` from another class is reported.
- An extension function declared outside the owner is outside it, even when it writes through
  its own receiver (`fun Scene.stop() { capturing.value = false }`), and so is a
  `with(scene) { ... }` or `scene.apply { ... }` block on an object the function received.

## When it stays quiet

- The write is lexically inside the owner: its members, `init` blocks, companion, nested and
  inner classes, and lambdas and local functions in any of them.
- The write goes through `this` of a class, which includes a subclass changing a property it
  inherited.
- The property is a local or a top-level property. Global state is
  [No global mutable state](compose/no-global-mutable-state.md)'s concern.
- A plain `var` of a bag of values: a class with no functions of its own (only properties and a
  constructor), data class or not. There is no behavior to add a method to, and setting its
  fields is what it is for. A data class that declares a function is not a bag, and a state
  holder property is never exempt this way.
- The owner is a `private` top-level class. It is visible only in its own file, so every write
  comes from that file, which owns it as much as the class does: a presenter feeding the
  editing state it keeps in a file-private holder.
- The object is one the function is still configuring: the receiver or parameter of `apply`,
  `also`, `run`, `with` or `let` called directly on a call result
  (`Config().apply { name = "x" }`, `Config().also { it.name = "x" }`), or a local initialized
  by a constructor call (`val config = Config(); config.name = "x"`). Until it is handed out, the
  object is the function's own; handing it out later does not change that.
- A plain `var` of a class from another module or a library: its API is what its authors
  chose, and `paint.color = x` is how it is meant to be used. A state holder of one of `types` is
  reported whatever module declares it: a class that exposes a `MutableStateFlow` to the rest of
  the project is the case that matters most, and libraries do not expose those.
- The code is a test: a function annotated with one of `test.annotations`, or any file in a
  test source set (`src/test/`, `src/jvmTest/`, `src/androidUnitTest/` and the like). A test
  is expected to reach into what it checks.
- A `var` with a `private set` cannot be written from outside at all; the compiler already
  refuses it.

To count a project's own state holder, list it together with the defaults (and, here, review
plain `var` writes too):

```yaml
rules:
  noExternalStateWrite:
    types: [androidx.compose.runtime.MutableState, kotlinx.coroutines.flow.MutableStateFlow, kotlinx.coroutines.flow.MutableSharedFlow, com.acme.state.Store]
    plainVars: true
```

## Related rules

[No global mutable state](compose/no-global-mutable-state.md) covers top-level and `object`
`var`s a composable reads without recomposing; this rule covers who writes a class's state.
[Prefer explicit backing fields](prefer-explicit-backing-field.md) is the usual way to expose a
state read-only once the writes are inside the owner.

## Fixtures

`compiler-tests/testData/diagnostics/noExternalStateWrite.kt`

## Implementation notes

`fir/checkers/NoExternalStateWriteChecker.kt`: a variable-assignment checker for `value = x` and
plain `var` writes (unwrapping the desugared reference of `+=` and `++`) and a function-call
checker for the update and emit family. Both resolve the written property's declaring class
through its containing class lookup tag and compare it with the class symbols in
`CheckerContext.containingDeclarations`.
