# Inferred metadata

Several rules need to know something about a function that only its body can tell: which insets
a composable handles, which locals it reads and provides, which parameters an effect captures,
what a function requires of its arguments, which arguments make it do nothing, whether it
starts work its caller cannot wait for, whether it creates a native-backed object and lets it
out. Within one module the body is at hand. For a caller in another module the plugin writes
what it found into the class file or klib, as an annotation of string arrays, and reads it back
where the callee is used.

| Fact | Annotation | Rule |
|---|---|---|
| Insets a composable handles | `@InferredWindowInsetsHandling(handled)` | `compose.windowInsets` |
| Locals a composable (or a composable getter) reads and provides | `@InferredCompositionLocals(reads, provides)` | `compose.compositionLocals` |
| A local whose default throws | `@InferredRequiredCompositionLocal` | `compose.compositionLocals` |
| Conditions a function or class requires of its arguments | `@InferredPreconditions(conditions)` | `preconditions` |
| Conditions under which a function returns at once, doing nothing | `@InferredNoOpWhen(conditions)` | `noOpCall` |
| Parameters an effect keeps | `@InferredEffectCapture(captured)` | `compose.rememberKeys` |
| Work started that the caller cannot wait for | `@InferredStartsAsyncWork` | `delayForCompletion` |
| A native-backed object created and let out | `@InferredNativeAllocation(types, path)` | `nativeAllocationInLoop` |

The annotation classes live in `kotrail-annotations`, but the metadata never needs that
artifact: without it the plugin writes against a stub class, on every target. A consumer without
Kotrail does not have the class, and what that means depends on who reads the output:

- The JVM, at runtime, skips an annotation whose class it cannot load. Nothing happens.
- A klib consumer's partial linkage removes the annotation silently.
- R8 and ProGuard, shrinking an app that depends on the module, report every class a program
  class references and cannot find, and R8 fails the build on it (`Missing class
  com.kitakkun.kotrail.concurrency.InferredStartsAsyncWork`). The Gradle plugin therefore ships a
  consumer rule with every module it is applied to, on by default (`consumerRules`):

  ```
  -dontwarn com.kitakkun.kotrail.**
  ```

  An Android library (`com.android.library`) adds it to `consumerProguardFiles`, so the AAR
  carries it; a Kotlin/JVM module and the JVM targets of a multiplatform one put it in the JAR as
  `META-INF/proguard/kotrail.pro`, which R8 reads from every dependency JAR. Nothing needs to keep
  the annotations: they have binary retention and nothing reads them at runtime, so the shrinker
  may drop them.

A module that should not carry the metadata at all sets `inferredMetadata = false` (see
[Publishing a library](#publishing-a-library)). See also [the Gradle plugin](gradle-plugin.md#multiplatform).

## Publishing a library

A library published for consumers that do not use Kotrail has two choices:

- **Leave the metadata on** (the default), with the consumer rules the Gradle plugin ships. The
  annotations are inert for such a consumer: the JVM ignores them, and the `-dontwarn` rule keeps
  R8 and ProGuard quiet. A consumer that does use Kotrail gets the library's facts, so a
  `delay(500)` after a call into the library that starts work is still reported. A consumer that
  shrinks with rules of its own and ignores those of its dependencies adds the rule above itself.
- **Turn it off**: `inferredMetadata = false` in the `kotrail { }` block of the published module
  (or `-P plugin:com.kitakkun.kotrail:inferredMetadata=false` without the Gradle plugin). The
  compiler plugin then registers no metadata writer and warms no fact for it, and the class files
  and klibs carry no reference to Kotrail. This is the recommended setting for a published module
  whose consumers do not use Kotrail.

With the metadata off, callers of the module in other modules see it as a dependency compiled
without Kotrail: none of the cross-module checks (async work before a `delay`, insets handled
twice, preconditions, calls that do nothing, captured effect parameters, native allocations in a
loop) has anything to read about it and reports nothing on its account. Analysis within the
module is unchanged, and so is reading the facts of the module's own dependencies. The switch can
be set per compilation (`compilation("main") { inferredMetadata = false }`), and the consumer rules
have their own switch, `consumerRules = false`, for a module that ships no rules of any kind.

## One shape for every fact

Each fact is an `InferredFactService` (`compiler-plugin/.../fir/inferred/`), a session
component keyed on a symbol, with a memoizing cache and a cycle guard. Asked about any symbol,
source or classpath, it answers in this order:

1. `override`: what wins over everything (a hand-written `@HandlesWindowInsets`, a knowledge
   base entry);
2. the declaration's own annotation, always for a classpath symbol and also for a source one
   when the fact says so (a contract the writer also produces: insets, preconditions, no-op
   conditions, locals);
3. `knowledge`: what fills in for a declaration without metadata (the Material 3 table, the
   `kotlinx.coroutines.delay` entries);
4. for a source symbol only, `analyze` on the body resolved to `BODY_RESOLVE`.

The fact also knows how to `decode` itself from the annotation's arrays and `encode` itself
into them, or say there is nothing worth writing.

Bodies are released after Fir2Ir, so `InferredFactWarmup` computes every fact for every source
declaration while FIR is available; each fact says which declarations are worth it (a
composable, a non-suspend function). `InferredMetadataWriter` (`.../ir/inferred/`) then walks
the module once and, for every exported declaration (non-private, non-local, directly in a file
or a class) that does not already carry the annotation, asks each fact whose rule is on for its
arrays and writes them through `metadataDeclarationRegistrar`. `InferredFacts` lists the facts,
each naming its annotation, its parameters, the declarations it applies to, and the service it
reads.

Adding a fact is one service subclass, one entry in `InferredFacts`, and the annotation class.
