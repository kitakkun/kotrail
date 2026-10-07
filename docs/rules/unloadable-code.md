# Unloadable code

**Diagnostics:** `KOTRAIL_THREAD_LOCAL_IN_UNLOADABLE_CODE` (error, on the property or the construction), `KOTRAIL_UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE` (error, on the call), `KOTRAIL_OUTBOUND_REFERENCE_IN_BUNDLED_CODE` (error, on the package directive of the first file, for a finding in a bundled module)
**Key:** `rules.unloadableCode` (**off by default**)
**Settings:** `registrations` (default: the JVM, AWT and IntelliJ hooks below), `disposableTypes` (default `[com.intellij.openapi.Disposable]`)

## What it is for

Some code is loaded through a class loader of its own and dropped later: a host plugin loaded
per plugin and replaced on hot reload, an IDE plugin the platform unloads. The loader can go
only when nothing outside it refers to its classes or instances, so the hazard is an outbound
reference: a plugin object handed to something that outlives the plugin. Once one is held, the
whole loader stays, with every class and resource under it.

State that stays inside the loader is not the problem: an `object` cache or a top-level `lazy`
holding the plugin's own values goes with the loader. The rule reports the two shapes that
reach outside and are visible in Kotlin:

```kotlin
val buffer: ThreadLocal<Buffer> = ThreadLocal()                  // reported: a platform thread keeps the value
Runtime.getRuntime().addShutdownHook(thread)                     // reported: registered, never unregistered
bus.connect().subscribe(TOPIC, listener)                         // reported: connect() with no parent disposable
bus.connect(parentDisposable).subscribe(TOPIC, listener)         // fine: scoped to the plugin's lifetime
```

## Switching it on

Off by default: nothing here matters to an ordinary library. Switch it on for the compilations
that are unloaded, through the module's own configuration file or a compilation override:

```kotlin
kotrail {
    compilation("main") {
        configFile = file("kotrail-plugin.yaml")   // rules: { unloadableCode: on }
    }
}
```

Everything bundled into the same class loader is subject to the same hazard, and the plugin
module's artifact bundles the projects on its runtime class path. So every compilation the
Gradle plugin configures records the findings the rule looks for under
`build/kotrail/unloadable/<target>-<compilation>` (whether or not the rule is on there), and a
JVM or Android compilation with the rule on reports what the main compilations (`main`, or an
Android variant) of the projects on its runtime class path recorded, transitively, on the
package directive of its first file by name as `KOTRAIL_OUTBOUND_REFERENCE_IN_BUNDLED_CODE`,
with the file (relative to the build's root directory) and line. One switch on the plugin
module covers whatever it bundles, and the set follows the dependency graph, `implementation`
and `runtimeOnly` dependencies alike.

Each project offers its records as a variant of its own, the consumable configuration
`kotrailUnloadableElements` (`jvmKotrailUnloadableElements` for a multiplatform target,
`releaseKotrailUnloadableElements` for an Android variant), which no publication includes, and
Gradle picks the one for the consumer's platform as it picks the
[facts variant](../inferred-metadata.md#how-it-is-resolved): a multiplatform dependency's
target of that platform, and for an Android consumer of a library without an Android target,
its JVM target. A dependency's `test` or `preview` compilation is
never read, a project without Kotrail gives nothing, and a common compilation, or one of
another platform, bundles nothing: the hazard is a JVM class loader's. A plugin that bundles
code through a configuration of its own rather than the runtime class path (an IDE plugin that
copies a `hostRuntime` configuration into its directory and loads it through its own class
loader) names it in the Gradle plugin:

```kotlin
kotrail {
    compilation("main") {
        bundledConfigurations.add("hostRuntime")
    }
}
```

Outside Gradle, the compiler plugin's `unloadableDir` and `bundledUnloadableDir` options do
the same.

### A task cycle

The records are inputs of the compile task whether or not the rule is on there (the Gradle
plugin does not read `kotrail.yaml`), and the compile tasks that write them run first. So a
compilation's compile task depends on the compile tasks of every project on its runtime class
path, those it only loads at runtime (`runtimeOnly`) included. When such a project compiles
against the module that loads it (a host with `runtimeOnly(project(":plugin"))` whose plugin
declares `compileOnly(project(":host"))`), the two compile tasks depend on each other and
Gradle stops with a circular dependency between them. Switching the rule off does not break
the cycle; the module that is never unloaded leaves its runtime class path out instead:

```kotlin
// The host's build script
kotrail {
    bundlesRuntimeClasspath = false
}
```

`bundledConfigurations` still count with it off, so a module that is unloaded itself and loads
such a project names what it does bundle there.

## When it fires

- **ThreadLocal**: a property of a `ThreadLocal` type (or a subtype), static or not, local or
  not, or a `ThreadLocal` constructed without being declared as one. A value set on a platform
  thread outlives the plugin.
- **Registration**: a call to a function matched by `registrations` with no argument of a
  `disposableTypes` type. The default list:
  - `java.lang.Runtime.addShutdownHook`, `java.lang.Thread.setDefaultUncaughtExceptionHandler`
  - `java.awt.Toolkit.addAWTEventListener`, `java.awt.KeyboardFocusManager.addPropertyChangeListener`, `java.awt.KeyboardFocusManager.addKeyEventDispatcher`
  - `com.intellij.util.messages.MessageBus.connect`, `com.intellij.openapi.application.Application.addApplicationListener`,
    `com.intellij.openapi.extensions.ExtensionPointName.addExtensionPointListener`, `com.intellij.openapi.extensions.ExtensionPointName.addChangeListener`,
    `com.intellij.openapi.editor.EditorFactory.addEditorFactoryListener`, `com.intellij.openapi.vfs.VirtualFileManager.addVirtualFileListener`,
    `com.intellij.openapi.project.ProjectManager.addProjectManagerListener`

  `registrations` replaces the list; a member function is named by its declaring class.

## Opting a site out

A registration that is right where it is (a shutdown hook for a headless entry point the plugin
never runs) is opted out at the site, with its reason: `@Suppress` of any of the rule's
diagnostic names on the declaration, or `unloadableCode.exclude` in the configuration of the
module that declares it. Such a site is neither reported there nor recorded for the modules that
bundle it, whether or not the rule is on in the declaring module.

## When it stays quiet

- State that stays inside the loader: `object` and `companion object` properties, top-level
  values, caches of the plugin's own objects.
- A registration that passes a disposable, or one that is not in `registrations`.
- The rule is off, which it is unless the compilation says otherwise. A module with the rule
  off still records its findings for the modules that bundle it.

The IntelliJ platform's own list of what a dynamic plugin must avoid is longer (components,
service overrides, PSI references kept across unload, `FileType` and `Language` objects as map
keys); see [Dynamic Plugins](https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html).

## Fixtures

`compiler-tests/testData/diagnostics/unloadableCode.kt`

## Implementation notes

`fir/checkers/UnloadableCodeChecker.kt`: `ThreadLocalChecker`, a `FirPropertyChecker`, and
`RegistrationChecker`, a `FirFunctionCallChecker` that also catches a bare `ThreadLocal()`; both
report when the rule is on and record when the compilation names an `unloadableDir`.
`BundledChecker`, a `FirFileChecker`, reads the bundled records (`fir/UnloadableRecords.kt`)
once per session and reports them from the anchor file. The registration's arguments are tested
by subtype against `disposableTypes`. The Gradle side, the consumable configuration and the
artifact views that wire the records between projects, is `UnloadableRecords.kt` in the Gradle
plugin.
