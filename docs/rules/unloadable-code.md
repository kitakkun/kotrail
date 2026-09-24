# Unloadable code

**Diagnostics:** `KOTRAIL_THREAD_LOCAL_IN_UNLOADABLE_CODE` (error, on the property or the construction), `KOTRAIL_UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE` (error, on the call)
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

Everything bundled into the same class loader is subject to the same hazard, so switch the rule
on for every module the unloadable artifact bundles, not only the module that declares the
plugin. Reporting across modules from the records of a compilation's classpath, the way
[preview coverage](compose/preview-coverage.md) does, is a possible extension.

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

## When it stays quiet

- State that stays inside the loader: `object` and `companion object` properties, top-level
  values, caches of the plugin's own objects.
- A registration that passes a disposable, or one that is not in `registrations`.
- The rule is off, which it is unless the compilation says otherwise.

The IntelliJ platform's own list of what a dynamic plugin must avoid is longer (components,
service overrides, PSI references kept across unload, `FileType` and `Language` objects as map
keys); see [Dynamic Plugins](https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html).

## Fixtures

`compiler-tests/testData/diagnostics/unloadableCode.kt`

## Implementation notes

`fir/checkers/UnloadableCodeChecker.kt`: `ThreadLocalChecker`, a `FirPropertyChecker`, and
`RegistrationChecker`, a `FirFunctionCallChecker` that also catches a bare `ThreadLocal()`. The
registration's arguments are tested by subtype against `disposableTypes`.
