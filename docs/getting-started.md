# Getting started

Kotrail is a Kotlin compiler plugin. Applying its Gradle plugin is the whole installation, and
from then on a rule violation is a compile error, in the same loop an assistant already uses to
fix type errors.

## 1. Apply the plugin

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.20"          // or multiplatform, android, ...
    id("com.kitakkun.kotrail") version "0.1.0"
}
```

Apply a Kotlin plugin first: Kotrail reads the Kotlin version from it and picks the matching
compiler plugin. The [supported Kotlin versions](supported-kotlin-versions.md) are 2.3.21, 2.4.0,
2.4.10, and 2.4.20; any other version fails with a message that says so.

## 2. Build

```bash
./gradlew build
```

Every rule is on, at error severity. A violation reads like this:

```
e: Home.kt:12:5 [Kotrail] This function is 84 lines of code (limit 80). Split it so that each
   piece does one thing and has a name.
```

Each message names what was found and what to do instead, which is what an assistant needs
to fix it without being told.

## 3. Tune what needs tuning

Turn a rule off, soften it to a warning, or change a limit in one properties file:

```properties
# kotrail.properties
rules.preferValueClass=false
severity.commentLength=warning
functionLength.maxLines=60
```

```kotlin
kotrail {
    configFile = layout.projectDirectory.file("kotrail.properties")
    test {
        configFile = layout.projectDirectory.file("kotrail-test.properties")   // relax rules in tests
    }
}
```

A single spot that is right as it is takes `@Suppress("KOTRAIL_FUNCTION_TOO_LONG")`, like any
compiler diagnostic. Everything else about configuration, including the note you can append to
every message and the predicates that carve out generated code, is in
[Configuration](configuration.md).

## 4. Write down the project's own rules

Some rules are inert until the project says what it wants, and those are the ones that carry
what no assistant can guess:

```properties
forbiddenCall.functions=kotlin.io.println, kotlinx.coroutines.GlobalScope.launch
visibilityPolicy.private=composable && name(*Preview)
requiredAnnotation.policy[screens]=composable && name(*Screen) -> com.acme.navigation.Screen
note=See docs/conventions.md before changing this.
```

## Where to go next

- [Rules](rules/README.md): one page per rule, with what it rejects, what it asks for, and when it
  stays quiet.
- [The Gradle plugin](gradle-plugin.md): the whole `kotrail { }` block, per-compilation files,
  multiplatform, and applying the compiler plugin without Gradle.
- [Configuration](configuration.md): every key, precedence, notes, exclusion predicates, and
  suppression.
