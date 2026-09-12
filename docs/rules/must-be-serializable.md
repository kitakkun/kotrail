# Must be serializable

**Diagnostic:** `KOTRAIL_TYPE_NOT_SERIALIZABLE` (error, on the call)
**Switch:** `rules.mustBeSerializable` (default `true`)
**Severity key:** `severity.mustBeSerializable`
**Setting:** `mustBeSerializable.requiredFor` (default `androidx.compose.runtime.saveable.rememberSerializable`)
**Artifact:** `annotations` (`com.kitakkun.kotrail.serialization.MustBeSerializable`)

## What it rejects

```kotlin
data class Draft(val text: String)                 // no @Serializable

val draft = rememberSerializable { Draft("d") }    // reported: serializer<Draft>() fails at runtime
persist(Draft("d"))                                // reported: persist declares <@MustBeSerializable T>
```

## What it asks for

```kotlin
@Serializable
data class Draft(val text: String)

// or, for a type you do not own:
val draft = rememberSerializable(serializer = DraftSerializer) { Draft("d") }
```

`rememberSerializable`, and any API built on `serializer<T>()`, discovers the serializer by
reflection over the type argument. Without `@Serializable` that discovery throws the first time
the code runs; this rule moves the failure to the compiler.

## Declaring the contract

Two sources of contracts are checked at every call site:

- **Configured callables** (`mustBeSerializable.requiredFor`): every type argument of the call,
  explicit or inferred, must be serializable. A call that passes an explicit `KSerializer`
  argument is exempt. `rememberSerializable` is listed by default.
- **`@MustBeSerializable`** on the callee's own declaration: on a type parameter
  (`fun <@MustBeSerializable T> persist(value: T)`) the type argument bound to it is checked; on
  a value parameter (`fun send(@MustBeSerializable payload: Any)`) the argument's type is
  checked. This lets a project or library express the requirement in the API itself, and it
  works across modules because the annotation is kept in the binary.

## What counts as serializable

Primitives and unsigned types, `String`, `Char`, `Unit`, `kotlin.time.Duration`, enums, arrays
and primitive arrays, `Pair` / `Triple`, and the standard `List` / `Set` / `Map` / `Collection`
families (including their mutable variants) when their type arguments are serializable; any class
annotated with `@kotlinx.serialization.Serializable`, again with serializable type arguments; and a
type parameter, which is checked where it is finally instantiated.

## When it stays quiet

- The callee is neither configured nor annotated.
- An explicit `KSerializer` argument is passed to a configured callable.
- The type is a type parameter of the enclosing function (checked at its own call sites).

Classes with a custom serializer declared through `@Serializable(with = ...)` are recognized
(the annotation is present); classes that only provide a companion `serializer()` without the
annotation are not, and need an explicit serializer at the call site.

## Fixtures

`compiler-tests/testData/diagnostics/mustBeSerializable.kt`

## Implementation notes

`fir/checkers/MustBeSerializableChecker.kt`. Type arguments are read from the resolved call's
`typeArguments`, which carry inferred types as well; value-parameter contracts use
`resolvedArgumentMapping`. Serializability is a recursive walk over the expanded type.
