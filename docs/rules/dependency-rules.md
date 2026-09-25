# Dependency rules

**Diagnostic:** `KOTRAIL_DEPENDENCY_NOT_ALLOWED` (error, on the import or the first reference)
**Key:** `rules.dependencyRules` (on by default; inert until a policy is set)
**Settings:** `policies` (named, each `from`, `deny` and optionally `allow`)

## What it rejects

Code in a layer referring to a layer the project keeps it away from:

```yaml
rules:
  dependencyRules:
    policies:
      ui:
        from: com.acme.ui.*              # a glob over the file's package
        deny: [com.acme.data.*]          # globs over the packages it must not refer to
        allow: [com.acme.data.model.*]   # exceptions inside the denied ones
      features:
        from: com.acme.feature.*
        deny: [com.acme.feature.*]       # features do not know each other; a package never denies itself
```

```kotlin
package com.acme.ui.settings

import com.acme.data.db.UserDao          // reported: 'com.acme.data.db' (policy 'ui')
import com.acme.data.model.User          // fine: allowed

fun size(): Int = com.acme.data.cache.Cache.size   // reported: a qualified reference counts too
```

## What it asks for

Layers that see only what the policy allows: the UI talks to a use case or a repository
interface, features talk through a shared contract package, and the data layer stays behind its
model package. The rule is the compile-time counterpart of an ArchUnit or Konsist test, for the
crossing an assistant makes most readily: reaching for whatever makes the code work, wherever
it lives. A test catches that later; a compile error catches it in the same loop.

## When it fires

- The file's package matches a policy's `from`.
- An import, a type in a signature or a body, a call, a property read or a qualified name
  refers to a package that matches one of the policy's `deny` globs and none of its `allow`
  globs.
- The package is not the file's own package or one of its subpackages: a package never denies
  itself, so `from: com.acme.feature.*` with `deny: [com.acme.feature.*]` keeps features apart
  without forbidding a feature its own code.

Each offending package is reported once per file and policy, at its first reference, so a file
that leans on a forbidden layer yields one finding per layer.

## When it stays quiet

- No policy's `from` matches the file's package.
- The referenced package matches no `deny` glob, or matches an `allow` glob. The standard
  library and other dependencies are reported only when a policy names them.
- The reference is to the file's own package or a subpackage of it.

Globs are the ones of the exclusion predicates: `*` stands for any run of characters, dots
included, and must match the whole package name. `com.acme.data.*` covers every subpackage of
`com.acme.data` but not `com.acme.data` itself; list both when both are meant.

## Fixtures

`compiler-tests/testData/diagnostics/config/dependencyRules.kt`, with its policies in
`dependencyRules.yaml` next to it.

## Implementation notes

`fir/checkers/DependencyRulesChecker.kt`, a `FirFileChecker`. The file's imports give a package
each (a class import resolves to the class's package, `x.y.*` to `x.y`); a visitor over the
body collects the packages of resolved type references, callees, property accesses and
qualifiers. Findings are de-duplicated per package and policy before reporting.
