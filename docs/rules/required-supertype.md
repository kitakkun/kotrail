# Required supertype

**Diagnostic:** `KOTRAIL_SUPERTYPE_REQUIRED` (error, on the class name)
**Key:** `rules.requiredSupertype` (on by default; inert until a policy is set)
**Settings:** `policies` (named, each `where` and `supertype`)

## What it rejects

A class a policy covers that does not extend or implement the type the policy names:

```yaml
rules:
  requiredSupertype:
    policies:
      viewModels:
        where: class && name(*ViewModel)
        supertype: com.acme.arch.BaseViewModel
      repositories: class && package(com.acme.data.*) && name(*Repository) -> com.acme.data.Repository
```

```kotlin
class SettingsViewModel : ViewModel()       // reported: needs BaseViewModel
class UserRepository(val db: Db)            // reported: needs Repository
```

## What it asks for

The project's own base class or interface, however it is reached:

```kotlin
class SettingsViewModel : BaseViewModel()
class HomeViewModel : ScreenViewModel()     // ScreenViewModel extends BaseViewModel: fine
```

A base class is a convention the code next door does not state: every view model goes through
`BaseViewModel` for its scope and error channel, every repository implements `Repository` so
that the cache layer can wrap it. An assistant that writes a new view model copies the nearest
one, which may be the one exception, or reaches for the framework's `ViewModel()` because that
is what it knows; the policy says the convention in one line, and the compiler says it back at
the exact class.

## Writing a policy

Each policy has a `where` predicate and a `supertype`; the one-line form
`<predicate> -> <fqn>` says the same. The predicate is written in the same language as
[`exclude`](../configuration.md#excluding-by-pattern); a policy about classes says
`class && name(...)`, since `class(*ViewModel)` alone also matches the members of such a class.
The supertype is one fully qualified class or interface name; a nested class is written with
dots (`com.acme.arch.Screens.Base`).

The policy's name is yours; it appears in the message so that a reader can find the entry.

A supertype that cannot be resolved (a misspelled name, an artifact not on this compilation's
class path) reports nothing rather than failing every class the policy covers; check the
spelling when a policy stays silent.

## When it stays quiet

- No policy is configured, or the class matches none.
- The class extends or implements the supertype, directly or through intermediate classes and
  interfaces.
- The class is the required type itself, an annotation class, or a local class.
- The supertype does not resolve.

## Configuration files

Entries are keyed by name, so a later file can replace one policy, add another, or drop one
with `viewModels: ~` while the rest stay in force.

## Fixtures

`compiler-tests/testData/diagnostics/config/requiredSupertype.kt`

## Implementation notes

`fir/checkers/RequiredSupertypeChecker.kt`. A `FirRegularClassChecker` that describes the class
the way the exclusion machinery does (`reportSite()`), evaluates every policy's predicate
against it, resolves the policy's supertype through the symbol provider (trying nested-class
splits from the right), and asks the class symbol whether it is a strict subclass, interfaces
included.
