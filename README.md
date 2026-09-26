<p align="center">
  <img src="docs/public/kotrail-lockup.png" alt="Kotrail" width="282" height="104">
</p>

**A flexible set of compiler checker rules that keep your Kotlin code durable when developing with AI.**

Kotrail is a Kotlin compiler plugin (K2 / FIR, with IR support where cross-module metadata is
needed) that adds checks the standard compiler does not provide. It turns the conventions your
team already agrees on into compile errors, so code written by AI assistants stays on the rails
instead of drifting a little further with every generation.

> **Status: early development.** No release yet; snapshots of every artifact are published to
> the Central Portal snapshot repository on each push to `main` (see
> [Trying a snapshot](#trying-a-snapshot)). Every rule can be switched off or demoted to a
> warning, per project and per compilation. Feedback on the direction is very welcome.

## Why

AI coding assistants are fast, but they are not consistent. Given the same codebase they will
happily produce five different styles of the same thing: a `!!` here, a stray `println` there, a
new data class where a value class was the convention, a `GlobalScope` launch that nobody asked
for. Each individual choice is defensible. Together they erode the shape of the code.

Prompts and `CLAUDE.md`-style instruction files help, but they are advisory. The model can forget
them, misread them, or simply run out of context. Linters help too, but they run after the fact and
are easy to skip.

The compiler is the one gate every line of code has to pass. Kotrail puts your conventions there.

- **Enforced, not suggested.** A rule violation is a compile error. The assistant sees it in the
  same loop it sees type errors, and fixes it before you ever review the change.
- **Flexible.** Every rule has a switch and its thresholds are tunable, per project and per
  source set.
- **Precise.** Rules run on the resolved FIR tree, not on text or tokens, so they can reason about
  types, receivers, annotations, and call targets rather than pattern-match on source.
- **Honest.** A rule reports only when the rewrite it asks for is guaranteed to preserve behavior.

## Rules

71 rules ship today, grouped by what they protect. Full pages, with every condition and
fixture, live under [`docs/rules/`](docs/rules/README.md); the index there lists each rule with
its diagnostic and whether it is on by default.

- **Readability and structure** (14): [Function length](docs/rules/function-length.md) and a [Live variable budget](docs/rules/live-variable-budget.md) keep a
  body small enough to hold in mind, and [File length](docs/rules/file-length.md) keeps a file from becoming a flat list of
  unrelated names, and [Class cohesion](docs/rules/class-cohesion.md) a class from becoming several classes under one name; [No literal loop](docs/rules/no-literal-loop.md) unfolds `listOf(false, true).forEach { if (it) ... }`
  back into the two calls it was; [Narrative order](docs/rules/narrative-order.md) and [Parameter order](docs/rules/parameter-order.md) put helpers after
  their first caller and data before callbacks; [Narrow local scope](docs/rules/narrow-local-scope.md), [Prefer val](docs/rules/prefer-val.md) and
  [Prefer idiom](docs/rules/prefer-idiom.md) tidy what is left.
- **Naming and style** (8): [Prefer function references](docs/rules/prefer-function-references.md) over `{ transform(it) }`,
  [Named arguments for repeated types](docs/rules/named-arguments-for-repeated-types.md) for `Padding(8, 16, 8, 16)`, no comments longer than five lines
  and no fully qualified names inline.
- **API design** (7): [Prefer explicit backing fields](docs/rules/prefer-explicit-backing-field.md) instead of `_items`,
  [Prefer value class](docs/rules/prefer-value-class.md) over a one-field data class, no mutable collection or data class in a
  public API.
- **Errors and concurrency** (8): [Must close](docs/rules/must-close.md) for a reader or stream created and never closed;
  [Catch too broad](docs/rules/catch-too-broad.md) and [No swallowed cancellation](docs/rules/no-swallowed-cancellation.md) for
  the `catch (e: Exception)` reflex, [Delay for completion](docs/rules/delay-for-completion.md) for `reconnect(); delay(500)`,
  [Preconditions](docs/rules/preconditions.md) for arguments that violate the callee's own `require`, checked across modules.
- **Memory and lifetime** (4): [Native allocation in loop](docs/rules/native-allocation-in-loop.md) for a Skia `Bitmap` created per frame,
  [Weak-only reference](docs/rules/weak-only-reference.md) for a listener collected at once, [Unretained](docs/rules/unretained.md) for a parameter stored
  against its contract.
- **Architecture policies** (6): [Forbidden call](docs/rules/forbidden-call.md), [Dependency rules](docs/rules/dependency-rules.md),
  [Required supertype](docs/rules/required-supertype.md), [Required annotation](docs/rules/required-annotation.md) and [Visibility policy](docs/rules/visibility-policy.md) turn a project's own
  conventions into predicates over receivers, packages, names and annotations.
- **Compose** (19): [Remember keys](docs/rules/compose/remember-keys.md) for a `remember` or `LaunchedEffect` that freezes a value its keys
  do not cover, checked through helpers in other modules; [No global mutable state](docs/rules/compose/no-global-mutable-state.md) for a
  top-level or `object` `var` a composable reads and never recomposes on; [Complexity](docs/rules/compose/complexity.md) scores the state,
  effects and branches a composable makes a reader hold at once and names the block to extract;
  [No side effect in composition](docs/rules/compose/no-side-effect-in-composition.md);
  [Window insets handling](docs/rules/compose/window-insets.md) and [Composition locals](docs/rules/compose/composition-locals.md) as contracts verified across modules; naming,
  modifier, callback and preview conventions.
- **Test** (3): [Test must assert](docs/rules/test/must-assert.md), [No sleep in tests](docs/rules/test/no-sleep.md), [Test naming](docs/rules/test/naming.md).
- **Kotlin/Native** (2): [Objective-C throws](docs/rules/native/objc-throws.md) for a framework function that can throw without
  `@Throws`, which crashes Swift instead of handing it an `NSError`; [Objective-C identity](docs/rules/native/objc-identity.md) for `===`
  and `WeakReference` on Objective-C objects.

Rules that know the exact rewrite record it while compiling, and `./gradlew kotrailFix` applies
those fixes to the sources without compiling again; the rule pages say **Fix: automatic** where
that holds. See [Applying fixes](docs/gradle-plugin.md#applying-fixes).

## Configuration

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    id("com.kitakkun.kotrail") version "0.1.0"
}

kotrail {
    configFile = layout.projectDirectory.file("kotrail.yaml")
    test {
        configFile = layout.projectDirectory.file("kotrail-test.yaml")
    }
}
```

Every rule is on at error severity with no configuration at all, except the few the
[rules index](docs/rules/README.md) marks off by default, each with its reason. Everything else
is one `kotrail.yaml`, with a JSON Schema for editor completion:

```yaml
rules:
  preferValueClass: off
  commentLength: warning
  functionLength:
    maxLines: 60
    exclude: name(main)
  forbiddenCall:
    functions: [kotlin.io.println, kotlinx.coroutines.GlobalScope.launch]
```

See [`docs/gradle-plugin.md`](docs/gradle-plugin.md) for the whole DSL and the artifact scheme, and
[`docs/configuration.md`](docs/configuration.md) for every key, layering, and suppression.

## Trying a snapshot

Until the first release, the artifacts are snapshots under `0.1.0-SNAPSHOT` on the Central
Portal snapshot repository. Add it for plugins and dependencies, and use the snapshot version:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
    }
}

// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.20"
    id("com.kitakkun.kotrail") version "0.1.0-SNAPSHOT"
}
```

The compiler plugin is published once per supported Kotlin version and the Gradle plugin picks
the matching one; see [docs/publishing.md](docs/publishing.md) for the artifact scheme.

## Contributing

Ideas for rules are the most useful contribution right now. If there is a constraint you keep
re-explaining to your AI assistant, open an issue describing what it should reject and why.
[CONTRIBUTING.md](CONTRIBUTING.md) has the project layout, the build and test commands, and how
fixtures and releases work.

## License

Copyright 2026 kitakkun.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text, or
<https://www.apache.org/licenses/LICENSE-2.0>.

The wordmark is set in Barlow Semi Condensed (Jeremy Tribby, SIL Open Font License 1.1); see
[docs/public/ATTRIBUTION.md](docs/public/ATTRIBUTION.md).
