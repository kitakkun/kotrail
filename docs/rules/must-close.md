# Must close

**Diagnostic:** `KOTRAIL_RESOURCE_NOT_CLOSED` (error, on the creation)
**Key:** `rules.mustClose` (on by default)
**Settings:** `factories` (default: the `kotlin.io` and `kotlin.io.path` stream, reader and writer factories, `java.nio.file.Files.new*`, `Files.lines`, `Files.walk`, `Files.list`, `FileChannel.open`, `ServerSocket.accept`), `ignoredTypes` (default: the in-memory `ByteArray*`, `String*` and `CharArray*` streams and `okio.Buffer`)

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
- Nothing settles it: it is not the receiver of `use { }` or of a consuming helper that closes
  (`useLines`, `forEachLine`, `readLines`; `readText` is not one), directly, through `apply` /
  `also` / `let` / `run`, through a `?.` chain, or through a wrapper whose closing closes it
  (`sink.buffer().use { }`, `stream.bufferedReader().readLines()`); it is not a local that the
  body later closes with `close()` or `use`; and it does not leave the function.

## When it stays quiet

- The resource leaves the function: returned (`fun open() = File(p).bufferedReader()`),
  assigned to a property (also through `also { installed = it }`), passed as an argument, or
  the receiver of a bound callable reference (`thread(block = surface::close)`,
  `onDispose(surface::close)`). Whoever receives it is the owner; the rule does not follow it.
- The resource is a wrapper over a borrowed stream: a stream the function received rather than
  created (a parameter, `this`, a property, the result of a call that is not a creation).
  Closing the wrapper would close the lender's stream, so the wrapper is not the function's to
  close. [Wrappers over borrowed streams](#wrappers-over-borrowed-streams) lists the forms.
- The resource wraps a stream its source owns:
  - a stream of a `Process` (`process.inputStream`, `errorStream`, `outputStream`, directly or
    through a local): the process closes its pipes as it ends, so
    `process.inputStream.bufferedReader().readText()` needs no `close()`;
  - a stream of an `HttpURLConnection` (`connection.inputStream`, `errorStream`,
    `outputStream`) when the function calls `connection.disconnect()`, in a `finally` or
    anywhere else in the body: the connection closes what it handed out. Without the
    `disconnect()`, a reader over `connection.inputStream` is still the function's to close.
- The type is on `ignoredTypes`: an in-memory buffer holds nothing a close would free, and a
  project's `AutoCloseable` registration handles (an uninstall token) go there too:

  ```yaml
  rules:
    mustClose:
      ignoredTypes: [java.io.ByteArrayOutputStream, okio.Buffer, com.acme.probe.Installation]
  ```
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

## Wrappers over borrowed streams

The rule does not track a resource created over a borrowed stream:

- A constructor or factory that takes, as an argument, a resource that is not a creation in the
  same function: `BufferedReader(parameterReader)`, or `BoundedReader(this, length)` inside the
  wrapped reader's own class.
- A wrapper chained on such a wrapper, or on a stream reached from a parameter or `this`:
  `Wrapper(parameterStream).bufferedReader()`, `parameterStream.bufferedReader()`,
  `this.stream.buffered()`.
- A constructor or factory around either of the above, nested to any depth, directly or
  through a local: `BufferedReader(InputStreamReader(parameterStream))`, or
  `val reader = InputStreamReader(parameterStream)` followed by `BufferedReader(reader)`.

Two forms stay tracked. A wrapper whose innermost stream is a fresh creation,
`BufferedReader(FileReader(path))` or `BufferedReader(InputStreamReader(FileInputStream(path)))`,
carries the obligation of what it wraps. A wrapper chained on a stream pulled out of an object,
`request.body.bufferedReader()`, is the function's to close, unless the object is a source that
owns the stream (see [When it stays quiet](#when-it-stays-quiet)).

## Pointers without a deallocator

A type that is only a pointer into memory owned elsewhere, with no deallocator of its own (a
native pointer into a buffer another object frees), is `AutoCloseable` in name only: its
`close()` frees nothing, and closing it early can be wrong. List it in `ignoredTypes`:

```yaml
rules:
  mustClose:
    ignoredTypes: [java.io.ByteArrayOutputStream, okio.Buffer, com.acme.gfx.PixelView]
```

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
result of a `use` / `let` / `run` lambda), the `close()` / `use` calls on those locals and the
bound callable references whose receiver is one of them. A creation with a closeable argument
that is not itself a creation (directly or through a local holding one), or that is a creation
wrapping a borrowed stream itself, wraps a borrowed stream and is not collected, and so is a
creation whose closeable receiver is itself a wrapper over a borrowed stream, a parameter, or
rooted at `this` through property accesses. The question recurses through nested creations,
with a visited set so a creation met again answers no.
A stream is its source's when it is `inputStream`, `errorStream` or `outputStream` (or the
getter) of an expression typed `java.lang.Process`, or of one typed `java.net.HttpURLConnection`
that reads a local, parameter or property the body calls `disconnect()` on; a pass before the
walk collects those, so a `disconnect()` in a `finally` below the read counts. A creation whose
closeable receiver is such a stream is not collected either.
