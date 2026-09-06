# Rules

One page per rule. Each page has the same shape: what it rejects, what it asks for instead,
exactly when it fires and when it stays quiet, the diagnostic name, its settings, and the
fixtures that pin its behavior.

## General

| Rule | Diagnostic | Page |
|---|---|---|
| Prefer explicit backing fields | `PREFER_EXPLICIT_BACKING_FIELD` | [prefer-explicit-backing-field.md](prefer-explicit-backing-field.md) |
| Narrow model parameters | `MODEL_PARAMETER_TOO_WIDE` | [narrow-model-parameters.md](narrow-model-parameters.md) |
| No pass-through return | `PASS_THROUGH_RETURN` | [no-pass-through-return.md](no-pass-through-return.md) |
| Prefer function references | `PREFER_FUNCTION_REFERENCE` | [prefer-function-references.md](prefer-function-references.md) |
| Comment length | `COMMENT_TOO_LONG` | [comment-length.md](comment-length.md) |
| No FQN references | `FQN_REFERENCE` | [no-fqn-references.md](no-fqn-references.md) |
| No redundant else | `REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN` | [no-redundant-else.md](no-redundant-else.md) |
| Prefer value class | `PREFER_VALUE_CLASS` | [prefer-value-class.md](prefer-value-class.md) |
| Forbidden call | `FORBIDDEN_CALL` | [forbidden-call.md](forbidden-call.md) |
| No not-null assertion | `NOT_NULL_ASSERTION` | [no-not-null-assertion.md](no-not-null-assertion.md) |
| No swallowed cancellation | `SWALLOWED_CANCELLATION` | [no-swallowed-cancellation.md](no-swallowed-cancellation.md) |
| No ignored exception | `IGNORED_EXCEPTION` | [no-ignored-exception.md](no-ignored-exception.md) |
| Prefer expression body | `PREFER_EXPRESSION_BODY` | [prefer-expression-body.md](prefer-expression-body.md) |
| No mutable collection in public API | `MUTABLE_COLLECTION_IN_PUBLIC_API` | [no-mutable-collection-in-public-api.md](no-mutable-collection-in-public-api.md) |
| Named arguments for repeated types | `NAMED_ARGUMENTS_REQUIRED` | [named-arguments-for-repeated-types.md](named-arguments-for-repeated-types.md) |
| Must be serializable | `TYPE_NOT_SERIALIZABLE` | [must-be-serializable.md](must-be-serializable.md) |
| No unimplemented code | `UNIMPLEMENTED_CODE` | [no-unimplemented.md](no-unimplemented.md) |
| Preconditions | `PRECONDITION_VIOLATED` | [preconditions.md](preconditions.md) |

## Compose

| Rule | Diagnostics | Page |
|---|---|---|
| Window insets handling | `WINDOW_INSETS_NOT_HANDLED`, `WINDOW_INSETS_HANDLING_UNVERIFIABLE`, `WINDOW_INSETS_HANDLED_TWICE` | [compose/window-insets.md](compose/window-insets.md) |
| State delegation | `PREFER_STATE_DELEGATION` | [compose/state-delegation.md](compose/state-delegation.md) |
| Nesting limit | `COMPOSABLE_NESTING_TOO_DEEP` | [compose/nesting.md](compose/nesting.md) |
| No trailing callback | `COMPOSABLE_TRAILING_CALLBACK` | [compose/no-trailing-callback.md](compose/no-trailing-callback.md) |
| Composable naming | `COMPOSABLE_NAMING` | [compose/naming.md](compose/naming.md) |
| Modifier parameter | `COMPOSABLE_MODIFIER_PARAMETER` | [compose/modifier-parameter.md](compose/modifier-parameter.md) |
| Named callback arguments | `COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA` | [compose/named-callback-arguments.md](compose/named-callback-arguments.md) |
| Preview required | `COMPOSABLE_WITHOUT_PREVIEW` | [compose/preview-required.md](compose/preview-required.md) |
| Composables per file | `TOO_MANY_COMPOSABLES_IN_FILE` | [compose/composables-per-file.md](compose/composables-per-file.md) |

Settings, precedence, per-source-set configuration, and suppression are described in
[../configuration.md](../configuration.md).

## Design principles shared by every rule

- **Only say "fix it" when it can be fixed.** A rule reports only when the suggested rewrite
  is guaranteed to preserve behavior. When an object escapes, a signature is fixed by an
  override, or an expression cannot be evaluated statically, the rule stays quiet or downgrades
  to a warning.
- **Resolved FIR, not text.** Every rule works on resolved symbols and types, so renames,
  imports, and aliases do not fool it.
- **One error per problem.** Nested or cascading occurrences report once, at the first place
  the problem appears.
- **Switchable and tunable.** Every rule has a `rules.<name>` switch and a `severity.<name>`
  key; thresholds live under a namespaced key (`compose.*`, `narrowModelParameters.*`).
- **Errors by default.** Rules report errors; mark genuine exceptions with `@Suppress`. Demote a
  rule to a warning only to adopt it gradually in a codebase with many existing findings.
