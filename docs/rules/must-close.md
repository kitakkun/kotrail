# Must close

**Diagnostic:** `KOTRAIL_RESOURCE_NOT_CLOSED` (error, on the creation)
**Key:** `rules.mustClose` (on by default)
**Settings:** `factories` (default: the `kotlin.io` and `kotlin.io.path` stream, reader and writer factories, `java.nio.file.Files.new*`, `Files.lines`, `Files.walk`, `Files.list`, `FileChannel.open`, `ServerSocket.accept`)

## What it rejects

A resource created in a function and neither closed nor handed on:

```kotlin
fun firstLine(path: String): String {
    val reader = File(path).bufferedReader()      // reported: nothing closes it
    return reader.readLine()
}
```

## What it asks for

```kotlin
fun firstLine(path: String): String = File(path).bufferedReader().use { it.readLine() }
```

or a `close()` in a `finally`, or handing the resource to whoever will close it. A stream, a
reader, a socket or a channel holds a file handle or a buffer the runtime frees only when a
finalizer runs, if ever; on a server or in a loop, that is a handle leak with a delay. An
assistant that needs a line from a file writes the reader and the read, and forgets the close,
because nothing in the code it is looking at says the object is a resource.

## When it fires

- A call in a function body creates a resource: a constructor of a class that is an
  `AutoCloseable` (`java.io.Closeable` included), or a call to one of `factories`.
- Nothing settles it: it is not the receiver of `use { }` (directly, through `apply` / `also` /
  `let` / `run`, or through a `?.` chain), it is not a local that the body later closes with
  `close()` or `use`, and it does not leave the function.

## When it stays quiet

- The resource leaves the function: returned (`fun open() = File(p).bufferedReader()`),
  assigned to a property, or passed as an argument. Whoever receives it is the owner; the rule
  does not follow it.
- The result comes from a function that is not a constructor and not on the list: an
  `inputStream` property, a pooled connection, a cached client. The rule cannot tell whether the
  caller owns those, so it says nothing. Add a project's own factories to `factories`:

  ```yaml
  rules:
    mustClose:
      factories: [com.acme.db.Database.openConnection, kotlin.io.bufferedReader]
  ```

- A local closed anywhere in the body counts as closed, whatever the paths; the rule does not
  reason about paths, so a `close()` reachable on one branch settles the resource.

## Related rules

[Native allocation in loop](native-allocation-in-loop.md) covers the objects whose memory is
off the heap, in a loop, following the helpers a loop calls; this rule covers any
`AutoCloseable`, once, in the function that creates it.

## Fixtures

`compiler-tests/testData/diagnostics/mustClose.kt`

## Implementation notes

`fir/checkers/MustCloseChecker.kt`, a function checker: one walk of the body collects the
creations, what the elements above each one do with it (`use`, return, assignment, argument),
the locals initialized from a creation (through scope functions, safe calls, `?:` and the
result of a `use` / `let` / `run` lambda) and the `close()` / `use` calls on those locals.
