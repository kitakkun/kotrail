# Visibility policy

**Diagnostic:** `KOTRAIL_VISIBILITY_TOO_WIDE` (error, on the declaration name)
**Switch:** `rules.visibilityPolicy` (default `true`; inert until a policy is set)
**Severity key:** `severity.visibilityPolicy`
**Settings:** `visibilityPolicy.private`, `visibilityPolicy.internal` (predicates)

## What it rejects

A declaration the policy covers that is wider than the policy allows:

```properties
visibilityPolicy.private=composable && name(*Preview)
visibilityPolicy.internal=name(*Impl) || package(com.acme.*.internal.*)
```

```kotlin
@Preview @Composable fun HomePreview() = Home()   // reported: must be private
class RepositoryImpl : Repository                 // reported: must be internal
```

## What it asks for

The visibility the policy names. A preview is tooling, not API, so it is private; an `*Impl`
class is an implementation detail, so it is internal and reached through its interface. The point
of writing the policy down is that an assistant cannot know it otherwise: a generated preview is
public because the composable next to it is, and a generated implementation is public because
nothing said not to.

## Writing a policy

Each setting is a predicate over declarations, in the same language as
[`exclude`](../configuration.md#excluding-by-pattern): names, packages, files, annotations,
enclosing classes, extension receivers, context parameters, modifiers. A declaration matching
`visibilityPolicy.private` must be `private`; one matching `visibilityPolicy.internal` must be
`internal` or `private`.

Effective visibility is what counts. A public member of an internal class satisfies an
`internal` policy, and a public nested class of a private class satisfies a `private` one.

## When it stays quiet

- No policy is configured.
- The declaration already has the required visibility, or a narrower one, effectively.
- Overrides: their visibility is fixed by what they override.
- Local declarations, and anything that is not a function, property, or class.

## Fixtures

`compiler-tests/testData/diagnostics/config/visibilityPolicy.kt`

## Implementation notes

`fir/checkers/VisibilityPolicyChecker.kt`. A `FirBasicDeclarationChecker` that describes the
declaration the way the exclusion machinery does (`reportSite()`), evaluates the configured
predicates against it, and compares the declaration's effective visibility with what the matching
policy requires.
