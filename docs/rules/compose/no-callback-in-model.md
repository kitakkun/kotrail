# No callback in model (Compose)

**Diagnostic:** `KOTRAIL_CALLBACK_IN_UI_MODEL` (error, on the composable's parameter name)
**Key:** `rules.compose.noCallbackInModel` (on by default)
**Settings:** `allowComposableSlots` (default `true`)

## What it rejects

A UI composable whose parameter type, or a class reachable from it, holds a function-typed
property:

```kotlin
data class UserRow(val name: String, val onClick: () -> Unit)

@Composable
fun UserList(rows: List<UserRow>) { ... }       // reported: rows carries UserRow.onClick

data class Screen(val header: Header)
data class Header(val title: String, val onBack: () -> Unit)

@Composable
fun ScreenContent(screen: Screen) { ... }       // reported: Header.onBack, through the nested model
```

## What it asks for

```kotlin
data class UserRow(val id: Long, val name: String)

@Composable
fun UserList(rows: List<UserRow>, onClick: (Long) -> Unit) { ... }
```

or one exit for every event, `onAction: (UserAction) -> Unit`, with a sealed `UserAction`.

A lambda that captures anything is a new instance every time it is created, so a model that
holds one is never equal to its previous version: Compose recomposes the composable whenever
the model is rebuilt, whatever it shows. The stability inference does not see the problem, since
function types count as stable, so [no unstable parameter](no-unstable-parameter.md) is quiet.
The model also stops being a plain value: a preview or a screenshot test has to invent callbacks
to build one, and "what to show" is tied to "what to do". State goes down as values; events go
up through the composable's own parameters.

## What counts as a model

A class the project declares that a UI composable takes as a parameter, and every project class
reachable from it through what the composable can observe of it:

- its properties that are not private, constructor and body alike, inherited ones included;
- the private primary-constructor properties of a data class, which its `equals` compares;
- the elements of a collection, an array, a `Pair`, a `Triple` or a `Sequence`, whether the
  parameter or a property has that type (`List<UserRow>`, `Map<Id, UserRow>`, `Array<UserRow>`;
  every type of `kotlin.collections` and of `kotlinx.collections.immutable`);
- the type arguments of the project's own generic classes, in place of their type parameters:
  `Section<UserRow>` with `val entries: List<T>` reaches `UserRow` through `entries`.

What is not walked:

- any other private property, such as a work queue a class keeps for itself
  (`private val requests = Channel<Request>()` whose `Request` holds a reply lambda);
- the type arguments of any other class from the classpath: the element type of a `Flow`, a
  `Channel`, a `StateFlow`, a `Deferred`, a Compose `State`;
- a project generic class's type argument that only private properties use.

The composable reads its model through that surface, and Compose compares the model with its
`equals`, which for a data class covers the primary constructor and for any other class is
the class's own. Private state is neither read nor, outside a data class's constructor,
compared. A stream's elements are not the model's value at all: they arrive later, at a
collection site, and become the model of whatever composable they are handed to, which is
checked on its own parameters. Walking either would report composables for callbacks they never
see, often in files far from the class that changed.

A function type that sits directly in a type argument, with no property on the way
(`dismiss: Pair<() -> Unit, String>`), is reported too, named as "a type argument of
Pair<...>". Classes from the classpath are not looked into. The composable's own function-typed
parameters are, of course, fine.

## Settings

- `allowComposableSlots`: `true` (the default) lets a model carry a `@Composable` function-typed
  property: a table column's `cell` renderer, a data boundary's fallback content. Such a slot is
  what the model is for, and a preview can hand it `{}` without inventing behavior. `false`
  reports slots like any other callback, since a capturing composable lambda breaks equality
  the same way.

## When it stays quiet

- The composable draws nothing (an effect wrapper), returns a value, is a preview, or is an
  `override` / `expect`.
- No project class reachable from the parameter's type has a function-typed property.
- The callback is behind a private property (other than a data class's constructor property),
  or in the element type of a `Flow`, a `Channel` or any other classpath class that is not a
  collection, an array, a `Pair`, a `Triple` or a `Sequence`.
- The property is a `@Composable` slot and `allowComposableSlots` is on (the default).

## Incremental compilation

The finding is reported on the composable's parameter, but what it is about is declared in the
model's classes, usually in other files. Kotlin's incremental compilation recompiles the
composable's file when something the file itself refers to changes: the model class's
constructors or supertypes, a property the composable reads. A callback added as a property in
a model's body, or anywhere in a class one step further down (a model held by the model),
changes nothing the composable's file names by itself. So, while this rule is on, every
non-private class and interface of the module carries, in its Kotlin metadata, a digest of what
this rule reads of it and of the model classes it reaches (`incrementalMetadata`, on by
default): the callback changes the digest of its class, then of the classes that hold it as
each is compiled again, and the composable's file is compiled again in the same build, so the
finding appears, and goes when the callback does. With `incrementalMetadata = false` it appears
only on the next build that compiles the composable's file: a clean build, or one with
`--rerun-tasks`. See [Incremental compilation](../../gradle-plugin.md#incremental-compilation).

## Fixtures

`compiler-tests/testData/diagnostics/compose/noCallbackInModel.kt`

## Implementation notes

`fir/compose/checkers/ComposableCallbackInModelChecker.kt`, a `FirNamedFunctionChecker`. For
each parameter of a UI composable (judged as [preview required](preview-required.md) judges it),
the type is expanded and walked: function types are the finding; the type arguments of a
container (a class of `kotlin.collections` or `kotlinx.collections.immutable`, `Array`, `Pair`,
`Triple`, `Sequence`, judged by the declared name too, since `ArrayList` is a type alias of a
Java class on the JVM) are followed; a source-declared class or interface contributes its
properties that are not private (and a data class its private constructor properties) and its
supertypes, with a substitutor from its type parameters to the type's arguments applied to both.
Each class is walked once per list of type arguments and never inside itself, and each
parameter gets a walk of its own. The first callback found is reported on the parameter, as
`Class.property`.
