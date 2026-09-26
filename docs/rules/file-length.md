# File length

**Diagnostics:** `KOTRAIL_FILE_TOO_LONG` (error, on the package directive), `KOTRAIL_TOO_MANY_TOP_LEVEL_DECLARATIONS` (error, on each name past the limit)
**Key:** `rules.fileLength` (on by default)
**Settings:** `maxLines` (default `500`), `maxTopLevelDeclarations` (default `10`); `0` switches either check off

## What it rejects

A file with more lines of code than `maxLines`, or more distinct top-level names than
`maxTopLevelDeclarations`:

```kotlin
// Utils.kt
fun formatDate(...)            // 1
fun parseDate(...)             // 2
data class DateRange(...)      // 3
fun retry(...)                 // ...
val DEFAULT_TIMEOUT = 30
fun toJson(...)
fun fromJson(...)
class JsonConfig(...)
fun log(...)
fun logError(...)
fun measure(...)               // 11: reported, and every name after it
```

## What it asks for

Files with a shape: one main type, the helpers only it needs, and a name for anything that is
more than a helper:

```kotlin
// Dates.kt         formatDate, parseDate, DateRange
// Json.kt          JsonConfig, toJson, fromJson
// Retry.kt         retry, DEFAULT_TIMEOUT
// Logging.kt       log, logError
// Measure.kt       measure
```

A file that keeps taking one more helper ends up flat: a long list of declarations with nothing
to say which belong together, where a reader has to scan everything to find anything. That is the
file-level form of the long function, and it is what an assistant produces when the nearest file
is the easiest place to add to. The lines check catches the file that grew; the names check
catches the file that went flat, which can happen well under the line limit.

## When it fires

- `maxLines`: the file has more lines of code than the limit. Blank lines, lines holding nothing
  but a brace, comment lines, and the `package` and `import` lines do not count. Reported once,
  on the package directive.
- `maxTopLevelDeclarations`: the file declares more distinct top-level names than the limit.
  Classes, interfaces, objects, functions, properties and type aliases count; overloads of one
  function count once; `@Preview` functions belong to their component and do not count. Each
  name past the limit is reported, in declaration order, so moving them out fixes the file.

## When it stays quiet

- Either limit is `0`.
- Nested declarations: members of a class are its business, not the file's. A file with one
  class of thirty members is not flat.
- The location matches the rule's `exclude` predicate, or the file is generated.

## Related rules

[Function length](function-length.md) is the same measure one level down;
[Composables per file](compose/composables-per-file.md) counts public UI composables only, with
its own limit; [Narrative order](narrative-order.md) orders what a file keeps.

## Fixtures

`compiler-tests/testData/diagnostics/fileLength.kt`,
`compiler-tests/testData/diagnostics/fileLengthNames.kt`

## Implementation notes

`fir/checkers/FileLengthChecker.kt`, a `FirFileChecker`. Lines come from the file's source text
with the same line test as the function-length rule plus the package and import exclusion; names
come from the file's direct declarations.
