# Rules

One page per rule. Each page has the same shape: what it rejects, what it asks for instead,
exactly when it fires and when it stays quiet, the diagnostic name, its settings, and the
fixtures that pin its behavior.

## General

| Rule | Diagnostic | Page |
|---|---|---|
| Prefer explicit backing fields | `KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD` | [prefer-explicit-backing-field.md](prefer-explicit-backing-field.md) |
| Prefer private setter | `KOTRAIL_PREFER_PRIVATE_SETTER` | [prefer-private-setter.md](prefer-private-setter.md) |
| Narrow model parameters | `KOTRAIL_MODEL_PARAMETER_TOO_WIDE` | [narrow-model-parameters.md](narrow-model-parameters.md) |
| No pass-through return | `KOTRAIL_PASS_THROUGH_RETURN` | [no-pass-through-return.md](no-pass-through-return.md) |
| No pass-through function | `KOTRAIL_PASS_THROUGH_FUNCTION` | [no-pass-through-function.md](no-pass-through-function.md) |
| Prefer function references | `KOTRAIL_PREFER_FUNCTION_REFERENCE` | [prefer-function-references.md](prefer-function-references.md) |
| Comment length | `KOTRAIL_COMMENT_TOO_LONG` | [comment-length.md](comment-length.md) |
| No parameter comments | `KOTRAIL_COMMENT_IN_PARAMETER_LIST` | [no-parameter-comments.md](no-parameter-comments.md) |
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
| Null chain length | `KOTRAIL_ELVIS_CHAIN_TOO_LONG`, `KOTRAIL_SAFE_CALL_CHAIN_TOO_LONG` | [null-chain-length.md](null-chain-length.md) |
| Implicit receivers | `KOTRAIL_IMPLICIT_RECEIVER_AMBIGUOUS`, `KOTRAIL_TOO_MANY_IMPLICIT_RECEIVERS` | [implicit-receivers.md](implicit-receivers.md) |
| Sealed when branch style | `KOTRAIL_SEALED_WHEN_BRANCH_STYLE` | [sealed-when-branch-style.md](sealed-when-branch-style.md) |
| Prefer val | `KOTRAIL_PREFER_VAL` | [prefer-val.md](prefer-val.md) |
| Prefer idiom | `KOTRAIL_PREFER_IDIOM` | [prefer-idiom.md](prefer-idiom.md) |
| Narrow local scope | `KOTRAIL_NARROW_LOCAL_SCOPE` | [narrow-local-scope.md](narrow-local-scope.md) |
| JvmSynthetic for internal (off by default) | `KOTRAIL_INTERNAL_VISIBLE_TO_JAVA`, `KOTRAIL_INTERNAL_CLASS_VISIBLE_TO_JAVA` | [jvm-synthetic-for-internal.md](jvm-synthetic-for-internal.md) |
| Live variable budget | `KOTRAIL_TOO_MANY_LIVE_VARIABLES` | [live-variable-budget.md](live-variable-budget.md) |
| Narrative order | `KOTRAIL_HELPER_BEFORE_FIRST_USE` | [narrative-order.md](narrative-order.md) |
| Parameter order | `KOTRAIL_CALLBACK_BEFORE_DATA_PARAMETER` | [parameter-order.md](parameter-order.md) |
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
| No side effect in composition | `KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION` | [compose/no-side-effect-in-composition.md](compose/no-side-effect-in-composition.md) |
| No hardcoded string (off by default) | `KOTRAIL_COMPOSABLE_HARDCODED_STRING` | [compose/no-hardcoded-string.md](compose/no-hardcoded-string.md) |
| Preview coverage (off by default) | `KOTRAIL_COMPOSABLE_NOT_COVERED_BY_PREVIEW` | [compose/preview-coverage.md](compose/preview-coverage.md) |
| Preview parameter (off by default) | `KOTRAIL_PREVIEW_MODEL_BUILT_INLINE` | [compose/preview-parameter.md](compose/preview-parameter.md) |
| No unstable parameter (experimental, off by default) | `KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER` | [compose/no-unstable-parameter.md](compose/no-unstable-parameter.md) |

## Test

| Rule | Diagnostics | Page |
|---|---|---|
| Test naming | `KOTRAIL_TEST_NAME_NOT_DESCRIPTIVE`, `KOTRAIL_TEST_NAME_NOT_IDENTIFIER` | [test/naming.md](test/naming.md) |
| No sleep in tests | `KOTRAIL_TEST_REAL_TIME_WAIT` | [test/no-sleep.md](test/no-sleep.md) |

## Kotlin/Native

| Rule | Diagnostics | Page |
|---|---|---|
| Objective-C identity | `KOTRAIL_OBJC_IDENTITY_COMPARISON`, `KOTRAIL_OBJC_WEAK_REFERENCE` | [native/objc-identity.md](native/objc-identity.md) |

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
- **Switchable and tunable.** Every rule is one entry under `rules:` in `kotrail.yaml`, a
  shorthand (`off`, `warning`) or a mapping with its switch, severity, note, exclusions, and
  settings.
- **Errors by default.** Rules report errors; mark genuine exceptions with `@Suppress`. Demote a
  rule to a warning only to adopt it gradually in a codebase with many existing findings.
