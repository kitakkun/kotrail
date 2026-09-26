# Class cohesion

**Diagnostic:** `KOTRAIL_CLASS_NOT_COHESIVE` (error, on the class name)
**Key:** `rules.classCohesion` (on by default)
**Settings:** `minMembers` (default `4`)

## What it rejects

A class whose members fall into groups that share nothing:

```kotlin
class ScreenViewModel(private val repo: Repo, private val exporter: Exporter, private val session: Session) {
    var draft: Draft? = null
    fun onEdit(text: String) { draft = Draft(text) }         // group 1: draft, repo
    fun onSave() { repo.save(draft ?: return) }
    fun onExport() { exporter.export() }                     // group 2: exporter
    fun onLogout() { session.clear() }                       // group 3: session
}
```

## What it asks for

One class per group, or the events the class handles as an interface with one implementation
per group, injected where the screen needs them:

```kotlin
class DraftEditor(private val repo: Repo) { var draft: Draft? = null; fun onEdit(...); fun onSave() }
class ExportAction(private val exporter: Exporter) { fun onExport() }
class LogoutAction(private val session: Session) { fun onLogout() }
```

Two members are related when one calls the other or both touch the same property of the
class. The groups are the connected components of that relation, the measure known as LCOM4.
A class with several groups is several classes sharing a name: nothing in it needs the rest,
and a reader who wants one group has to learn where the others end. It is the shape a screen's
"view model" takes when it collects every event handler the screen needs, one at a time, each
with its own dependency, and it is what an assistant produces by default, because the next
handler always fits in the class that is already there. Split by group, each piece is small
enough to review on its own, and the screen says which handlers it takes.

## When it fires

- The declaration is a class or an object, not local, not a data class, not a companion object,
  not an interface, enum or annotation class, and holds no test function.
- Its members with bodies, taken as nodes, related through the properties of the class (or of
  its direct supertypes) they read or write and through the members they call, form two or more
  connected groups. A member that touches no property and calls no member is a helper and is
  not a node; nor is one that no member calls.
- At least `minMembers` members are nodes.
- The message lists every group with its members and the properties it touches, largest first.

## When it stays quiet

- Every member reaches every other, directly or through a shared property or a called member.
- Fewer than `minMembers` members touch the class's state.
- The declaration is one of the kinds above, or the class carries test functions: a test class
  is a list of independent cases by design.
- The location matches the rule's `exclude` predicate.

## Related rules

[File length](file-length.md) is the same question one level up, a file whose names share
nothing; [Narrow model parameters](narrow-model-parameters.md) the same question for a
function's inputs.

## Fixtures

`compiler-tests/testData/diagnostics/classCohesion.kt`

## Implementation notes

`fir/checkers/ClassCohesionChecker.kt`, a `FirRegularClassChecker`: one visitor per member
collects the member properties and member functions it touches, a union-find joins members
through them, and the components are counted after the untouched helpers are dropped.
