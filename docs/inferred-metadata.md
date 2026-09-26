# Inferred metadata

Several rules need to know something about a function that only its body can tell: which insets
a composable handles, which locals it reads and provides, which parameters an effect captures,
what a function requires of its arguments, whether it starts work its caller cannot wait for,
whether it creates a native-backed object and lets it out. Within one module the body is at
hand. For a caller in another module the plugin writes what it found into the class file or
klib, as an annotation of string arrays, and reads it back where the callee is used.

| Fact | Annotation | Rule |
|---|---|---|
| Insets a composable handles | `@InferredWindowInsetsHandling(handled)` | `compose.windowInsets` |
| Locals a composable (or a composable getter) reads and provides | `@InferredCompositionLocals(reads, provides)` | `compose.compositionLocals` |
| A local whose default throws | `@InferredRequiredCompositionLocal` | `compose.compositionLocals` |
| Conditions a function or class requires of its arguments | `@InferredPreconditions(conditions)` | `preconditions` |
| Parameters an effect keeps | `@InferredEffectCapture(captured)` | `compose.rememberKeys` |
| Work started that the caller cannot wait for | `@InferredStartsAsyncWork` | `delayForCompletion` |
| A native-backed object created and let out | `@InferredNativeAllocation(types, path)` | `nativeAllocationInLoop` |

The annotation classes live in `kotrail-annotations`, but the metadata never needs that
artifact: without it the plugin writes against a stub class, on every target, and a consumer
without Kotrail ignores the annotation (the JVM skips annotations it cannot load; a klib
consumer's partial linkage removes them silently). See [the Gradle plugin](gradle-plugin.md#multiplatform).

## One shape for every fact

Each fact is an `InferredFactService` (`compiler-plugin/.../fir/inferred/`), a session
component keyed on a symbol, with a memoizing cache and a cycle guard. Asked about any symbol,
source or classpath, it answers in this order:

1. `override`: what wins over everything (a hand-written `@HandlesWindowInsets`, a knowledge
   base entry);
2. the declaration's own annotation, always for a classpath symbol and also for a source one
   when the fact says so (a contract the writer also produces: insets, preconditions, locals);
3. `knowledge`: what fills in for a declaration without metadata (the Material 3 table);
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
