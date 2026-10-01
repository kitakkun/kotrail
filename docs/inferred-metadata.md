# Inferred metadata

Several rules need to know something about a function that only its body can tell: which insets
a composable handles, which locals it reads and provides, which parameters an effect captures,
what a function requires of its arguments, which arguments make it do nothing, whether it
starts work its caller cannot wait for, whether it creates a native-backed object and lets it
out, whether a composable draws anything. Within one module the body is at hand. For a caller in
another module the plugin writes down what it found and reads it back where the callee is used.
It does so in one of two ways, by target:

- **JVM and Android**: a facts file, `<module>.facts`, written beside the build output rather
  than into it and published as an artifact of its own. The class files, the JAR and the AAR
  carry nothing of Kotrail.
- **Klibs** (Native, JS, Wasm, and the metadata compilation of shared source sets): an
  annotation of string arrays on the declaration.

| Fact | Name in the facts file | Annotation (klib) | Rules |
|---|---|---|---|
| Insets a composable handles | `windowInsets` | `@InferredWindowInsetsHandling(handled)` | `compose.windowInsets` |
| Locals a composable reads and provides | `compositionLocals` | `@InferredCompositionLocals(reads, provides)` | `compose.compositionLocals` |
| The same for a property with a composable getter | `compositionLocalGetter` | `@InferredCompositionLocals(reads, provides)` | `compose.compositionLocals` |
| A local whose default throws | `requiredCompositionLocal` | `@InferredRequiredCompositionLocal` | `compose.compositionLocals` |
| Conditions a function requires of its arguments | `preconditions` | `@InferredPreconditions(conditions)` | `preconditions` |
| Conditions a class requires of its constructor's arguments | `classPreconditions` | `@InferredPreconditions(conditions)` | `preconditions` |
| Conditions under which a function returns at once, doing nothing | `noOpWhen` | `@InferredNoOpWhen(conditions)` | `noOpCall` |
| Parameters an effect keeps | `effectCapture` | `@InferredEffectCapture(captured)` | `compose.rememberKeys` |
| Work started that the caller cannot wait for | `startsAsyncWork` | `@InferredStartsAsyncWork` | `delayForCompletion` |
| A native-backed object created and let out | `nativeAllocation` | `@InferredNativeAllocation(types, path)` | `nativeAllocationInLoop` |
| A composable that draws nothing | `drawsUi` | `@InferredDrawsUi(value)` | `compose.previewRequired`, `compose.previewCoverage`, `compose.noTrailingCallback`, `compose.noCallbackInModel`, and any compilation that records its composables (`composablesDir`) |

Both carry the same strings: each fact encodes itself into the arrays of its annotation's
parameters, and the facts file keeps those arrays under the parameter names.

`drawsUi` is written only for a `Unit`-returning composable that draws nothing (an effect
wrapper, a probe that only reads a local), as `{"drawsUi": {"value": ["false"]}}`: a composable
without it is taken to draw, as one from a library compiled without Kotrail always is. The
Gradle plugin records the composables of every compilation for
[preview coverage](rules/compose/preview-coverage.md), so in practice every JVM compilation
writes it.

## The facts file (JVM and Android)

### Format

JSON lines: a header naming the module and the Kotrail version, then one line per declaration
that has a fact.

```
{"module": "lib", "kotrail": "0.1.0"}
{"file": "lib/src/main/kotlin/Manager.kt", "id": "lib.net/Manager.reconnect", "signature": "kotlin/Int", "facts": {"startsAsyncWork": {}}}
{"file": "lib/src/main/kotlin/Retry.kt", "id": "lib.net/retry", "signature": "kotlin/Int,kotlin/String", "facts": {"preconditions": {"conditions": ["times >= 0", "label.isNotBlank()"]}}}
{"file": "lib/src/main/kotlin/Retry.kt", "id": "lib.net/Percent", "signature": "", "facts": {"classPreconditions": {"conditions": ["value in 0..100"]}}}
```

- `file` is the source file, relative to the build's root directory with forward slashes (the
  `rootDir` plugin option, which the Gradle plugin always passes), so that a published facts
  file holds no path of the machine that built it and the same sources give the same bytes
  anywhere. Without the option, or for a file outside that directory, it is the absolute path
  the compiler reports.
- `id` is the package, a slash, then the class-qualified name: `lib.net/Manager.reconnect` for a
  member, `lib.net/retry` for a top-level callable, `lib.net/Percent` for a class,
  `lib.net/Manager.Companion.create` for a companion member.
- `signature` tells overloads apart: empty for a class and for a callable without parameters,
  otherwise one item per context parameter (`context:<type>`), then the extension receiver
  (`receiver:<type>`), then each value parameter, joined by `,`. A type is the class id of its
  fully expanded form (type aliases expanded; nullability and type arguments dropped;
  `kotlin/IntArray` for `vararg Int`), `*` for a type parameter.
- `facts` maps each fact's name to its arrays, keyed by the annotation's parameter names, `{}`
  for a marker.

Lines are sorted by file, then id, then signature, so the same sources give the same bytes.

The header is always there. A module with no facts (no rule that writes one is on, or no
declaration has anything to say) still gets a file, the header alone: it says the module was
compiled with Kotrail and has no facts, which is not the same as having no facts file at all (a
dependency compiled without Kotrail, or with `inferredMetadata = false`), and it gives the
publication its artifact.

The key is computed from the FIR symbol by one helper (`FactKeys`, in
`compiler-plugin/.../fir/inferred/`) on both sides: the writer from the FIR declaration behind
each IR declaration, the reader from the classpath symbol it is asked about. A declaration of a
module compiled with Compose has the same key on both sides, since neither looks at the lowered
IR signature.

### Where it is written

The compiler plugin writes the file where the `factsOut` option says, and nowhere else; a JVM
compilation without the option writes no facts. With the option, every JVM compilation writes
it, the header alone when there are no facts, and never deletes it (except with
`inferredMetadata = false`, see [Publishing a library](#publishing-a-library)). The Gradle
plugin passes, for every JVM and Android compilation,

```
build/kotrail/facts/<target>-<compilation>/<project>.facts
```

(`jvm-main` and `jvm-test` for Kotlin/JVM, `desktop-main` for a multiplatform target named
`desktop`), and registers the directory as an
output of the compile task, so that it is cached and restored with the classes and removed by
`clean`. Nothing goes into the classes directory, so neither the JAR nor the AAR carries the
file, and an application that packages its dependencies packages no facts.

### How it is published

Only the file of a main compilation is published (`main`, or an Android variant; never a test
compilation), next to the JAR or AAR, as `<artifact>-<version>-kotrail-facts.facts`
(classifier `kotrail-facts`, extension `facts`), and only when the project applies
`maven-publish`. It is a classifier artifact of the Maven publication and nothing more: no
software component publishes it as a variant, so the Gradle module metadata does not describe
it, and neither the module metadata nor the POM carries anything of Kotrail. A consumer finds it
by classifier (see [How it is resolved](#how-it-is-resolved)). Inside a build, a project
dependency reads it through the consumable configuration `kotrailFactsElements`
(`jvmKotrailFactsElements` for a multiplatform target, `releaseKotrailFactsElements` for an
Android variant), which is configuration-time wiring that no publication includes.

- **Kotlin/JVM**: every Maven publication of the project carries the file, the one made
  `from(components["java"])` included, whether the build script makes it or a publishing
  plugin does later. The exception is the plugin markers of `java-gradle-plugin`
  (`<plugin>PluginMarkerMaven`), which publish a POM alone.
- **Multiplatform**: the file of each JVM target is attached to that target's publication
  (`<artifact>-jvm`) through the Kotlin plugin's `KotlinTarget.mavenPublication`. The root
  publication (`<artifact>`) carries none: its module metadata redirects a JVM consumer to the
  target's module, whose file the consumer then asks for.

An Android library publishes an AAR per variant, and the facts file of each Android variant
compilation (`debug`, `release`, `freeRelease`) is written separately. Which file a
publication carries depends on how the library is laid out:

- **Kotlin/Android library** (`com.android.library` with the Kotlin plugin): the Android Gradle
  plugin makes a software component of each `android.publishing { singleVariant("release") }`,
  named after the variant, and of each `multipleVariants("custom") { ... }`, named as declared.
  Every Maven publication of the project gets the facts file of one variant: the variant its
  component is named after (read once the publication has been made `from` the component),
  else the variant the publication itself is named after, else `release`. A
  `multipleVariants` component, whose AARs differ by classifier, therefore carries the facts of
  `release`. A publication named after no variant, in a project without a `release` variant,
  carries no facts file.
- **`androidTarget()` of a multiplatform project**: the Kotlin plugin makes one publication per
  published variant (`androidRelease` as `<artifact>-android`, `androidDebug` as
  `<artifact>-android-debug`), and runs the target's `mavenPublication` actions on each. Each
  gets the facts file of its variant. With `publishLibraryVariantsGroupedByFlavor`, a
  publication per flavor (`androidFree`) carries the build types as classifiers, and gets the
  facts file of the flavor's `release` variant (`freeRelease`), or of `release` without
  flavors.
- **`com.android.kotlin.multiplatform.library`**: the Android target has a single `main`
  compilation and a single publication (`<artifact>-android`), which gets its file through
  `KotlinTarget.mavenPublication`, as a JVM target does.

With `inferredMetadata = false` nothing is attached: there is no file to publish. The setting
is per compilation, so on Android a variant whose compilation has it off gives no file: the
publication of that `androidTarget()` variant carries none, and a `com.android.library`
publication falls back as if that variant did not exist.

A compilation with no Kotlin sources at all (its compile task is `NO-SOURCE`, as for a module
of Java sources or resources only) never runs the compiler, so it writes no facts file, not even
the header, and a publication that attaches it fails with `artifact file does not exist`. Such a
library module sets `inferredMetadata = false`. The Gradle plugin does not try to detect it: at
configuration time it cannot tell a source set that is empty from one whose sources a generator
has yet to write.

### How it is resolved

For every JVM and Android compilation the Gradle plugin collects the facts files of what the
compile classpath resolved to, and hands them to the compiler as `facts`, one option per file:

1. **Project dependencies, by variant.** An artifact view of the compile classpath with variant
   reselection, limited to the projects of the build (and of included builds), asks each for a
   variant with `org.gradle.usage = kotrail-facts`, the compilation's platform (`jvm` or
   `androidJvm`; an Android compilation also takes `jvm`) and its name (an exact match first,
   then `main`, then `release`). A project that applies Kotrail answers with its
   `*KotrailFactsElements` configuration; one that does not gives nothing; the view is lenient.
2. **External modules, by classifier.** For every external module on the compile classpath, the
   configuration `kotrailFacts` (`testKotrailFacts`, `jvmKotrailFacts`, `jvmTestKotrailFacts`)
   asks the repositories for `group:module:version:kotrail-facts@facts`, leniently: a library
   without the artifact is simply absent. It takes the attributes of the compile classpath, so
   a multiplatform module's metadata redirects the request to its JVM or Android target's
   module, as it redirects the classpath. This works alike with Gradle module metadata and with
   repositories consumed through POM metadata only, at the cost of one lookup per external
   module in the repositories the build declares. A Kotlin/JVM library published by an earlier
   version, whose module metadata described the file as a variant, published it under the same
   classifier, and is read the same way.
3. A test compilation also reads the facts files of the compilations it is associated with
   (`main`, for `test`).

Where two files describe the same declaration, the one named first wins.

### Incremental compilation

Under incremental compilation the compiler sees only the files it recompiles. The writer reads
the facts file already at `factsOut`, drops the entries of the files it compiled (whatever they
say now; both sides are named relative to the root directory in the same way, so a recompiled
file matches its old entries) and of the files that no longer exist (a relative path resolved
against the root directory), adds what it found, and writes the file back; when nothing is
left, it writes the header alone.

A change to a library's body that keeps its ABI keeps the dependent's compile classpath the
same as far as the compile task's classpath normalization can tell, and would leave the
dependent up to date while a fact it reads has changed. The facts files are therefore an input
of every compile task that reads them (by content, not by path), and the compiler gets
`factsHash`, a SHA-256 over the contents of those files that it otherwise ignores, so that the
compiler arguments change with them. A changed fact thus reruns the dependent compilation,
non-incrementally, and its findings follow the library: remove a `require` from a library
function and the precondition finding at the call site in the consumer goes; put it back and
it returns. An unchanged fact changes nothing, and the task stays up to date.

#### The own facts file

Within one module the callee's body is normally at hand. Under incremental compilation it is
not, for a callee in a file the compiler does not compile again: Kotlin loads the declarations
of such a file back from the classes directory, as it would a library's, without their bodies,
and the class files carry nothing of Kotrail. What the previous build found in those bodies is
in the module's own facts file, whose entries for the files not compiled again are still there
(see above). So the compiler reads that file too: the Gradle plugin passes it as `ownFacts`, the
same path as `factsOut`, apart from the dependencies' `facts`, and not as an input of the
compile task, since it is the task's own output. The file is read while the frontend runs and
written after it, and the entries read from it are those of files the writer keeps as they
were, so the order does not change what is read.

A declaration loaded that way is told apart from a library's by where it comes from: the
classes and top-level functions have the origin `Precompiled`, and the members and top-level
properties, which Kotlin deserializes as `Library` either way, carry the module data of the
module being compiled rather than a library's. For such a declaration a fact is looked up in
the module's own facts file first, then in the dependencies' files; a library declaration is
only ever looked up in the dependencies' files, and a declaration compiled from source in none
(its body is analyzed).

What this fixes: an incremental build that compiles a use site, and not the files of the
callees it calls, checks the use site against those callees' facts as the last build recorded
them. Edit only the file with `manager.reconnect(); delay(500)` and the
`delayForCompletion` finding stays, where it used to vanish because `reconnect`, untouched,
had no facts; the same goes for `preconditions`, `noOpCall`, `nativeAllocationInLoop`,
`compose.windowInsets` and `compose.compositionLocals`. A composable that only calls an effect
wrapper in an untouched file is still seen to draw nothing, through the wrapper's `drawsUi` fact,
so `compose.previewRequired`, `compose.noTrailingCallback`, `compose.noCallbackInModel` and the
record of UI composables for `compose.previewCoverage` do not take it for UI, as they did when
such a wrapper looked like a library composable. The facts the recompiled file writes for itself
(a function that starts work because its callee does) are
computed against those facts too, rather than against callees that seemed to have none, so the
facts file stays complete across incremental builds.

What it does not fix: a change to a callee's body does not make Kotlin compile its callers
again, since their ABI is unaffected. Remove the `require` from a function, and the incremental
build compiles that file alone: the facts file loses the precondition, but the caller in
another file of the module is not checked again, and the precondition finding the last build
reported there is neither repeated nor withdrawn. It goes on the next build that compiles the
caller: a change to the caller's file, a clean build, or `--rerun-tasks`. Across modules this
does not happen (see above). See
[Incremental compilation](gradle-plugin.md#incremental-compilation) in the Gradle plugin page.

A module with `inferredMetadata = false` writes no facts file, so it has none to read back
either: under incremental compilation its untouched callees have no facts, as before.

### Without the Gradle plugin

A hand-wired build passes the options itself, as the Compose samples in this repository do:
`-P plugin:com.kitakkun.kotrail:factsOut=<file>` in the library, and
`-P plugin:com.kitakkun.kotrail:facts=<file>` (comma separated, or repeated) in the module that
depends on it, with the file as an input of the dependent's compile task. `factsHash` is
optional there. A build that compiles incrementally also passes
`-P plugin:com.kitakkun.kotrail:ownFacts=<file>`, the same file as `factsOut`, so that the
declarations of the files it does not compile again keep their facts.

## Annotations (klibs)

For a klib the facts are annotations, written through the compiler's metadata registrar so that
consumers read them with no extra wiring. The annotation classes live in `kotrail-annotations`,
but the metadata never needs that artifact: without it the plugin writes against a stub class.
A klib consumer's partial linkage removes an annotation whose class it cannot find, silently by
default, so a consumer without Kotrail is not affected. Klibs have no facts file, and none of
the resolution above applies to them.

Inline functions get their facts like any other function. On the JVM the fact is in the facts
file; in a klib the annotation sits on the declaration only, and what a backend copies into a
caller is the body, so the stub never reaches the caller's output. The functional tests build a
library without the annotations artifact whose inline functions (public, internal and
`@PublishedApi`, reified, crossinline, suspend, with defaults, members) are inlined within it
and by a consumer, which reads their facts. Where a fact is missing all the same, nothing
breaks: a caller in the same module analyzes the body, and a caller in another module sees a
callee with no fact and reports nothing about it.

## Publishing a library

A JVM or Android library built with this version of Kotrail references no Kotrail class and
carries no Kotrail file: its facts are the separate `kotrail-facts` artifact, which a consumer
without Kotrail never resolves, and which R8, ProGuard and the APK never see. It is published by
classifier alone, so the library's Gradle module metadata and POM name nothing of Kotrail: no
variant, no attribute, no dependency, unless the build declares a Kotrail dependency itself
(the annotations artifact in a multiplatform source set, say; see
[How it is published](#how-it-is-published)). A consumer that
does use Kotrail resolves it (see [How it is resolved](#how-it-is-resolved)), so a `delay(500)`
after a call into the library that starts work is still reported.

Libraries built by earlier versions of Kotrail carry their facts file inside the JAR or AAR, as
`META-INF/kotrail/<module>.facts`. This version no longer reads those files: a consumer sees
such a library as if it had been compiled without Kotrail, until it is rebuilt. Two switches
stay for them:

- `packagingExcludes` (on by default): in a module that applies `com.android.application`, the
  Gradle plugin adds `META-INF/kotrail/**` to `android.packaging.resources.excludes`, which
  leaves those files out of the APK and bundle. `packagingExcludes = false` keeps them. An app
  built without the Gradle plugin can add the same exclude itself.
- `consumerRules` (on by default): every module ships the consumer rule
  `-dontwarn com.kitakkun.kotrail.**`, through `consumerProguardFiles` for an Android library,
  as `META-INF/proguard/kotrail.pro` in the JAR for Kotlin/JVM and the JVM targets of a
  multiplatform module. The output of this version needs neither; the rule is for JVM
  libraries built by even earlier versions, whose class files carry the annotations and whose
  consumers' shrinkers would otherwise fail on `Missing class com.kitakkun.kotrail...`.

A module that should write no facts at all sets `inferredMetadata = false` in the
`kotrail { }` block (or `-P plugin:com.kitakkun.kotrail:inferredMetadata=false` without the
Gradle plugin). The compiler plugin then registers no writer and warms no fact for it: no
facts file on the JVM (one left at `factsOut` by an earlier build is deleted), no annotation in
a klib, and nothing is published. Callers of the module in other modules then see it as a
dependency compiled without Kotrail: none of the cross-module checks (async work before a
`delay`, insets handled twice, preconditions, calls that do nothing, captured effect parameters,
native allocations in a loop) has anything to read about it and reports nothing on its account. Analysis within the
module is unchanged, and so is reading the facts of the module's own dependencies. The switch
can be set per compilation (`compilation("main") { inferredMetadata = false }`), and the
consumer rules have their own switch, `consumerRules = false`. See also
[the Gradle plugin](gradle-plugin.md#multiplatform).

## One shape for every fact

Each fact is an `InferredFactService` (`compiler-plugin/.../fir/inferred/`), a session
component keyed on a symbol, with a memoizing cache and a cycle guard. Asked about any symbol,
source or classpath, it answers in this order:

1. `override`: what wins over everything (a hand-written `@HandlesWindowInsets`, a knowledge
   base entry);
2. for a symbol not compiled from source, the entry under the fact's name in a facts file: for
   a declaration of this module that an incremental compilation loaded from the classes
   directory, the module's own facts file, then the dependencies'; for a library declaration,
   the dependencies' only (see
   [The own facts file](#the-own-facts-file));
3. the declaration's own annotation, always for a classpath symbol (a klib, a hand-written
   annotation, or a JVM library built by an earlier Kotrail) and also for a source one when the
   fact says so (a contract the writer also produces: insets, preconditions, no-op conditions,
   locals);
4. `knowledge`: what fills in for a declaration without metadata (the Material 3 table, the
   `kotlinx.coroutines.delay` entries);
5. for a source symbol only, `analyze` on the body resolved to `BODY_RESOLVE`.

The fact also knows how to `decode` itself from its arrays, read from a facts file or an
annotation alike, and `encode` itself into them, or say there is nothing worth writing.

Bodies are released after Fir2Ir, so `InferredFactWarmup` computes every fact for every source
declaration while FIR is available; each fact says which declarations are worth it (a
composable, a non-suspend function). `InferredMetadataWriter` (`.../ir/inferred/`) then walks
the module once and, for every exported declaration (non-private, non-local, directly in a file
or a class) that does not already carry the annotation, asks each fact with one of its rules on
for its arrays, and writes them to the facts file on the JVM or through
`metadataDeclarationRegistrar` for a klib. `InferredFacts` lists the facts, each naming its
name in the facts file, its annotation, its parameters, the declarations it applies to, and the
service it reads.

Adding a fact is one service subclass, one entry in `InferredFacts`, a name in
`InferredFactNames`, and the annotation class.
