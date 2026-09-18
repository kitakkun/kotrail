# Required annotation

**Diagnostic:** `KOTRAIL_REQUIRED_ANNOTATION_MISSING` (error, on the declaration name)
**Key:** `rules.requiredAnnotation` (on by default; inert until a policy is set)
**Settings:** `policies` (named, each `where` and `annotation`)

## What it rejects

A declaration a policy covers that does not carry the annotation the policy names:

```yaml
rules:
  requiredAnnotation:
    policies:
      screens:
        where: composable && name(*Screen)
        annotation: com.acme.navigation.Screen
      entities:
        where: class && name(*Entity)
        annotation: com.acme.db.Persisted
      api: visibility(public) && package(com.acme.sdk.*) -> com.acme.sdk.PublicApi
```

```kotlin
@Composable fun HomeScreen() { ... }        // reported: needs @Screen
class UserEntity(val id: Int)              // reported: needs @Persisted
```

## What it asks for

The annotation, on the declaration itself. A policy is a project convention that nothing in
the code states: every screen composable is registered with `@Screen`, every entity is
`@Persisted`, everything public in the SDK package is marked `@PublicApi`. An assistant that
generates a new screen cannot know the convention from the composable next to it, since that
one may be a component rather than a screen; the policy says it in one line, and the compiler
says it back at the exact declaration.

## Writing a policy

Each policy has a `where` predicate and an `annotation`; the one-line form
`<predicate> -> <annotation fqn>` says the same. The predicate is written in the same language
as [`exclude`](../configuration.md#excluding-by-pattern): names, packages, files, annotations,
enclosing classes, extension receivers, context parameters, modifiers, and the kind of
declaration (`function`, `property`, bare `class`). The annotation is one fully qualified class
name; a leading `@` is accepted.

The policy's name is yours; it appears in the message so that a reader can find the entry. Names start with a letter and may contain letters, digits, dots, `_` and `-`.

Only the declaration's own annotations satisfy a policy. `class(*Entity)` matches the members
of an entity class as well, so a policy about classes says `class && name(*Entity)`; otherwise
every function inside the class would be asked to carry the annotation too.

## When it stays quiet

- No policy is configured, or the declaration matches none.
- The declaration carries the annotation.
- Local declarations, and anything that is not a function, property, or class.

## Configuration files

Entries are keyed by name, so a later file can replace one policy, add another, or drop one
with `screens: ~` while the rest stay in force.

## Fixtures

`compiler-tests/testData/diagnostics/config/requiredAnnotation.kt`

## Implementation notes

`fir/checkers/RequiredAnnotationChecker.kt`. A `FirBasicDeclarationChecker` that describes the
declaration the way the exclusion machinery does (`reportSite()`), evaluates every policy's
predicate against it, and checks the declaration's resolved annotations for the class the
policy names.
