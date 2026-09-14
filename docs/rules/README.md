# Rules

One page per rule. Each page has the same shape: what it rejects, what it asks for instead,
exactly when it fires and when it stays quiet, the diagnostic name, its settings, and the
fixtures that pin its behavior.

## General

| Rule | Diagnostic | Page |
|---|---|---|
| Prefer explicit backing fields | `KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD` | [prefer-explicit-backing-field.md](prefer-explicit-backing-field.md) |
| Narrow model parameters | `KOTRAIL_MODEL_PARAMETER_TOO_WIDE` | [narrow-model-parameters.md](narrow-model-parameters.md) |
| No pass-through return | `KOTRAIL_PASS_THROUGH_RETURN` | [no-pass-through-return.md](no-pass-through-return.md) |
| No pass-through function | `KOTRAIL_PASS_THROUGH_FUNCTION` | [no-pass-through-function.md](no-pass-through-function.md) |
| Prefer function references | `KOTRAIL_PREFER_FUNCTION_REFERENCE` | [prefer-function-references.md](prefer-function-references.md) |
| Comment length | `KOTRAIL_COMMENT_TOO_LONG` | [comment-length.md](comment-length.md) |
| No FQN references | `KOTRAIL_FQN_REFERENCE` | [no-fqn-references.md](no-fqn-references.md) |
| No redundant else | `KOTRAIL_REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN` | [no-redundant-else.md](no-redundant-else.md) |
| Prefer value class | `KOTRAIL_PREFER_VALUE_CLASS` | [prefer-value-class.md](prefer-value-class.md) |
| Forbidden call | `KOTRAIL_FORBIDDEN_CALL` | [forbidden-call.md](forbidden-call.md) |
| No not-null assertion | `KOTRAIL_NOT_NULL_ASSERTION` | [no-not-null-assertion.md](no-not-null-assertion.md) |
| No swallowed cancellation | `KOTRAIL_SWALLOWED_CANCELLATION` | [no-swallowed-cancellation.md](no-swallowed-cancellation.md) |
| No ignored exception | `KOTRAIL_IGNORED_EXCEPTION` | [no-ignored-exception.md](no-ignored-exception.md) |
| Prefer expression body | `KOTRAIL_PREFER_EXPRESSION_BODY` | [prefer-expression-body.md](prefer-expression-body.md) |
| No mutable collection in public API | `KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API` | [no-mutable-collection-in-public-api.md](no-mutable-collection-in-public-api.md) |
| Named arguments for repeated types | `KOTRAIL_NAMED_ARGUMENTS_REQUIRED` | [named-arguments-for-repeated-types.md](named-arguments-for-repeated-types.md) |
| Must be serializable | `KOTRAIL_TYPE_NOT_SERIALIZABLE` | [must-be-serializable.md](must-be-serializable.md) |
| No unimplemented code | `KOTRAIL_UNIMPLEMENTED_CODE` | [no-unimplemented.md](no-unimplemented.md) |
| Preconditions | `KOTRAIL_PRECONDITION_VIOLATED` | [preconditions.md](preconditions.md) |
| Function length | `KOTRAIL_FUNCTION_TOO_LONG` | [function-length.md](function-length.md) |
| No data class in public API | `KOTRAIL_DATA_CLASS_IN_PUBLIC_API` | [no-data-class-in-public-api.md](no-data-class-in-public-api.md) |
| Visibility policy | `KOTRAIL_VISIBILITY_TOO_WIDE` | [visibility-policy.md](visibility-policy.md) |
| Required annotation | `KOTRAIL_REQUIRED_ANNOTATION_MISSING` | [required-annotation.md](required-annotation.md) |

## Compose

| Rule | Diagnostics | Page |
|---|---|---|
| Window insets handling | `KOTRAIL_WINDOW_INSETS_NOT_HANDLED`, `KOTRAIL_WINDOW_INSETS_HANDLING_UNVERIFIABLE`, `KOTRAIL_WINDOW_INSETS_HANDLED_TWICE` | [compose/window-insets.md](compose/window-insets.md) |
| Composition locals | `KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED`, `KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED_AT_ENTRY_POINT` | [compose/composition-locals.md](compose/composition-locals.md) |
| State delegation | `KOTRAIL_PREFER_STATE_DELEGATION` | [compose/state-delegation.md](compose/state-delegation.md) |
| Nesting limit | `KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP` | [compose/nesting.md](compose/nesting.md) |
| No trailing callback | `KOTRAIL_COMPOSABLE_TRAILING_CALLBACK` | [compose/no-trailing-callback.md](compose/no-trailing-callback.md) |
| Composable naming | `KOTRAIL_COMPOSABLE_NAMING` | [compose/naming.md](compose/naming.md) |
| Modifier parameter | `KOTRAIL_COMPOSABLE_MODIFIER_PARAMETER` | [compose/modifier-parameter.md](compose/modifier-parameter.md) |
| Named callback arguments | `KOTRAIL_COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA` | [compose/named-callback-arguments.md](compose/named-callback-arguments.md) |
| Preview required | `KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW` | [compose/preview-required.md](compose/preview-required.md) |
| Composables per file | `KOTRAIL_TOO_MANY_COMPOSABLES_IN_FILE` | [compose/composables-per-file.md](compose/composables-per-file.md) |

## Test

| Rule | Diagnostics | Page |
|---|---|---|
| Test naming | `KOTRAIL_TEST_NAME_NOT_DESCRIPTIVE`, `KOTRAIL_TEST_NAME_NOT_IDENTIFIER` | [test/naming.md](test/naming.md) |

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
