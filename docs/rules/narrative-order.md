# Narrative order

**Diagnostics:** `KOTRAIL_HELPER_BEFORE_FIRST_USE` (error, on the function name),
`KOTRAIL_HELPER_FAR_FROM_CALLER` (error, on the helper's name; on the first member's name for a
run), `KOTRAIL_HELPER_OUTSIDE_CALLER_CLASS` (error, on the function name)
**Key:** `rules.narrativeOrder` (on by default)
**Settings:** `maxCallerDistance` (default `100` lines; `0` switches the distance check off),
`maxHelperNoise` (default `60` percent)
**Fix:** automatic for the order (`kotrailFix` moves the function to just after its first
caller) and for the distance when the helper and its caller share a container (it moves the
helper, or the whole run, to just after its caller, or a property above it to just before);
none for a helper outside its caller's class

## The story

Read from the top, a file should tell the story first and the details after, the way a newspaper
article does. A private helper exists for the code that calls it, so it has one right place:
after that code, and close to it. A helper placed above its caller makes the reader hold a detail
before learning what it is for; a helper placed far below makes the reader who follows the call
scroll past code they did not come for, and back again. A helper below its caller lets the
reader stop once the story is clear, and find the detail right there when they do follow the
call.

The rule checks both halves. The order check asks that a private function come after the first
declaration that uses it; the distance check asks that a private helper, once in that order, not
sit far from its only caller.

## The order check

### What it rejects

A private function declared before the first declaration that uses it:

```kotlin
private fun parseHeader(bytes: ByteArray): Header { ... }     // reported

fun load(bytes: ByteArray): Document {
    val header = parseHeader(bytes)
    ...
}
```

### What it asks for

```kotlin
fun load(bytes: ByteArray): Document {
    val header = parseHeader(bytes)
    ...
}

private fun parseHeader(bytes: ByteArray): Header { ... }
```

A private function's callers are all in the same class or file, so the order is decidable.

### When it fires

- The function is `private` and has a body.
- Some other declaration of the same container (a function, a property initializer or accessor,
  an `init` block) calls it or takes a reference to it, and the earliest such declaration comes
  after the function.

### When it stays quiet

- Nothing in the container uses the function, or the function is not private (a public
  function may be called from anywhere, and its order is a different question).
- The function and its first user use each other (mutual recursion): neither order tells the
  story.
- Nested classes are containers of their own; a helper used only from a nested class is ordered
  within that class. A top-level helper used only by members of a class comes after the class.

## The distance check

### What it rejects

A private helper used by one declaration only, placed far below it with unrelated code between
the two (or, for a private top-level property, far above it):

```kotlin
fun load(bytes: ByteArray): Document {            // line 1
    val header = parseHeader(bytes)
    ...
}

fun render(document: Document): String { ... }     // lines 12-50: nothing load needs
fun export(document: Document): File { ... }       // lines 52-90: nothing load needs

private fun parseHeader(bytes: ByteArray): Header { ... } // line 120: reported
```

### What it asks for

```kotlin
fun load(bytes: ByteArray): Document {
    val header = parseHeader(bytes)
    ...
}

private fun parseHeader(bytes: ByteArray): Header { ... }

fun render(document: Document): String { ... }
fun export(document: Document): File { ... }
```

The order is right, `parseHeader` comes after `load`, but a reader who follows the call has to
scroll past `render` and `export` to reach it. A private helper with one caller belongs right
after that caller, where the reader who follows the call finds it.

### The caller's other helpers

The caller's other helpers are not unrelated code. When `load` calls `parseHeader`, `parseBody`
and `parseFooter`, written below it in that order, `parseFooter` is many lines from `load` only
because the other two sit between; that is the right layout, and only one of them can come
first. Those lines count neither toward the distance nor toward the noise. The same goes for
anything they use in turn: the caller's closure, the declarations of the file it references,
transitively. The properties of the caller's own class and of its companion object are not part
of it: a reader takes a field or a constant as vocabulary, the way they take a parameter, rather
than as a place they go to.

### Runs

A run of helpers is one unit. When a screen draws six icons, each a private composable it alone
calls, written one after another, they cannot all sit next to it: the blank lines between them
push the later ones past the distance even when the first is right where it belongs. The run is
measured from its member nearest the caller, and when that one is placed, so are the rest. When
that one is far, the whole run is, and the rule reports the run once, on its first member, rather
than once per icon:

```text
Only 'icon' uses the run of 'homeIcon' and the 5 helpers after it, 140 lines away: put it right
next to 'icon' so the reader who follows the call finds it without scrolling past unrelated code.
```

### Direction

A private function above its first caller is the order check's finding, so the distance check
looks for functions below their caller only. A private top-level property, which the order check
leaves alone, is measured in both directions: below its only caller, as for a function, and above
it, with the same thresholds. A private top-level `val` read by a single caller a hundred lines
further down, with unrelated code between, is as far from its reader as a helper function would
be.

### When it fires

- The declaration is a helper: a private function, at the top level or in a class, or a private
  top-level property.
- Exactly one declaration of the file references it, and that caller is a function or a
  property with an accessor body.
- The caller starts above the helper; or the helper is a private top-level property and starts
  above its caller.
- The first lines of the two are more than `maxCallerDistance` lines apart once the lines of the
  caller's closure between them are left out.
- At least `maxHelperNoise` percent of the lines from the caller to the helper belong neither to
  the pair nor to the caller's closure: the reader scrolls past more unrelated code than the
  code they came for.
- The helper does not continue a placed run: walking from it toward the caller, the
  declarations of the same container are other helpers with the same only caller, all of them
  far as well, until a declaration that is not one of them (the caller, an unrelated declaration,
  another caller's helper).

A far run, helpers with the same only caller written one after another with no other
declaration of the container between, all of them far, is one finding, reported on its first
member in source order at the distance of its member nearest the caller.

### When it stays quiet

- The helper has no caller in the file, or several: there is no single place it belongs.
- The helper is a member of an `object` or a companion object, or a property of a class: those
  are constants and entry points a reader looks up by name, and fields laid out at the top by
  convention, not helpers whose place is a choice.
- The helper is a constant: a `const val`, or a `val` initialized with a literal, arithmetic
  over literals and `const` properties, or a single call on such a receiver (`16.dp`,
  `30 * 1000`). A reader looks a constant up by name wherever it sits.
- The helper is a factory: a function returning a class of the same file declared within 30
  lines of it, before or after. It is placed by the type it builds, not by its caller.
- The caller is a class header or `init` block, or a property initializer: those are not code a
  reader follows into a call.
- The helper is an override.
- The helper is close to its caller once the caller's closure between them is left out, or most
  lines between the two belong to the pair and the caller's closure (a long caller with its
  helper right after it, or the last of several helpers written in call order).
- The helper is part of a run whose member nearest the caller is neither past the distance nor
  mostly noise away.
- `maxCallerDistance` is `0`, or the file is in a test source set (`src/test`, `src/*Test`): the
  order check still runs there.

## A helper outside its caller's class

A top-level private function whose only caller is a member of a class has no good place outside
that class. The order check keeps it after the class, whose member is its first user; the distance
check measures from the member, so a long class puts it far wherever it sits:

```kotlin
class Parser {
    fun parse(s: String): Int = tokenize(s).size

    // ... 150 lines of other members ...
}

private fun tokenize(s: String): List<String> = s.split(" ")   // reported
```

When the distance check would report such a function (or a run of them), it reports
`KOTRAIL_HELPER_OUTSIDE_CALLER_CLASS` instead, asking for a private member right after the caller:

```kotlin
class Parser {
    fun parse(s: String): Int = tokenize(s).size

    private fun tokenize(s: String): List<String> = s.split(" ")

    // ... the other members ...
}
```

This finding has no fix. Inside the class body the class's members and those it inherits are in
scope, and they can capture a name the function's body resolves to something else today; only
the author can tell that the move keeps its meaning. A private top-level property used by one
member is not asked to move: inside the class there would be one per instance rather than one for
the file. It is reported as far, without a fix.

A top-level extension function whose only caller is a member of a class is not reported at all:

```kotlin
class Requester {
    fun send(request: Request): Int = request.headersWithBodyDefaults().size

    // ... 150 lines of other members ...
}

private fun Request.headersWithBodyDefaults(): Map<String, String> = headers + defaults   // not reported
```

An extension means something for its receiver alone, so it stays at the top level; there the
class's length keeps it far from its caller, and neither the class nor the distance can change
without making it something else (a member extension, callable only where the class is a
receiver). A far extension whose caller is top-level too is still reported as far, with the fix.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `maxCallerDistance` | `100` | Lines a private helper may sit from its only caller, first line to first line, before it is reported; lines of the caller's closure between the two do not count. `0` switches the distance check off; the order check remains. |
| `maxHelperNoise` | `60` | Percent of the lines from the caller to the helper that belong neither to the pair nor to the caller's closure, from which a helper past `maxCallerDistance` is reported. |

```yaml
rules:
  narrativeOrder:
    maxCallerDistance: 100
    maxHelperNoise: 60
```

Both thresholds have to hold: a long caller with its helper right after it is far by distance
but has no noise. Measured on a real codebase, the distance of a helper from its only caller had
a median of 27 lines and a 90th percentile of 117, and the share of unrelated lines between them
a median of 24 percent and a 90th percentile of 75; the defaults sit near the 90th percentile of
both, so only the tail is reported.

## Fix

- **Order:** the function's lines (and one following blank line) are deleted and inserted after
  its first user, separated by a blank line and indented like it.
- **Distance:** when the helper and its caller are siblings, both top-level or both members of
  the same class body, the helper moves right after its caller, a run as one block in its order,
  and a private top-level property above its caller right before it. A helper property whose
  initializer reads another property of the file keeps its place, since moving it would change
  the order in which the two are initialized, and so does a run with such a member: the
  diagnostic comes without a fix. So does any other pair that is not siblings.
- **Outside the caller's class:** no fix (see above).

`kotrailFix --rules narrativeOrder` applies the fixes of this rule alone.

## Related rules

- [Narrow local scope](narrow-local-scope.md) asks the same of locals inside a function: declared
  where they are used, no earlier.
- [Parameter order](parameter-order.md) gives a signature the same shape: the data it works on
  first, the functions that act on it after.

## Fixtures

- `compiler-tests/testData/diagnostics/narrativeOrder.kt`: the order check.
- `compiler-tests/testData/diagnostics/narrativeOrderDistance.kt`, with its messages in
  `narrativeOrderDistance.diag.txt`: the distance check, the class-member case and the
  extension left alone.
- `compiler-tests/testData/diagnostics/narrativeOrderSuppressed.kt`: suppressions by the base name
  at a moved severity.
- The `kotrailFix` functional test of the Gradle plugin: the block move of a far run.

## Implementation notes

`fir/checkers/NarrativeOrderChecker.kt`, a `FirFileChecker`. One walk over the file
(`DeclarationUses`) records, for every container (the file and every class, local ones included),
its direct declarations and what each of them uses anywhere inside it, a use being the resolved
callee of a call, a callable reference or a property access; for a class that is not local, it
also records what its header (the primary constructor) and its `init` blocks use, the code a
constructor call leads to. The order check reads it per container: a private function is
reported when the earliest declaration using it starts after it, and the fix is
`FixBuilder.moveAfter`.

The distance check, `fir/checkers/HelperDistanceChecker.kt`, builds from the same walk a model
of the file (every function, property and class outside local classes, with its line ranges,
what it references, the class that declares it, whether a class is an `object`, whether it is
private, whether a property is a constant, and for a function the lines of the same-file class it
returns) and hands it to `fir/HelperDistance.kt`, a pure function over that model that the unit
tests exercise with hand-made layouts. `HelperDistance.farFromCaller` decides a finding, walking
a far helper's same-container neighbors toward its caller for a placed member of its run; the
checker then keeps only callers that are functions or properties with an accessor body, groups
the far helpers into runs (a helper joins the run of the declaration right before it in the same
container when that one is a far helper of the same caller), and reports each run once, with
`FixBuilder.moveDeclarationsAfter` (or `moveDeclarationsBefore` for properties above their
caller) when every member and the caller are siblings. A run of top-level functions whose caller
is a class member is reported as outside the caller's class instead, and a top-level extension
function with such a caller (one with a receiver parameter) is dropped before the runs are
grouped.

As a file checker, the rule reads a `@Suppress` on the reported helper and on the declarations
around it from the declarations themselves, since the file's context holds only the file's own:
so a suppression by the base name holds at a severity the project moved, and a suppressed finding
records no fix. `narrativeOrderSuppressed.kt` pins the first.
