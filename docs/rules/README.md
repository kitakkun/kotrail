# Rules

One page per rule. Each page has the same shape: what it rejects, what it asks for instead,
exactly when it fires and when it stays quiet, the diagnostic name, its settings, and the
fixtures that pin its behavior.

Rules are grouped by what they protect. The **Default** column says whether a rule is on out of
the box; `off` rules are switched on per project, and an `experimental` rule may change shape.
A rule can belong to two groups; it is listed once, under the one it serves first.

## Readability and structure (11)

How a body reads: its length, how much is in play at once, the order of what it declares.

| Rule | Default | Diagnostics | Page |
|---|---|---|---|
| Function length | on | `KOTRAIL_FUNCTION_TOO_LONG` | [function-length.md](function-length.md) |
| Live variable budget | on | `KOTRAIL_TOO_MANY_LIVE_VARIABLES` | [live-variable-budget.md](live-variable-budget.md) |
| Narrative order | on | `KOTRAIL_HELPER_BEFORE_FIRST_USE` | [narrative-order.md](narrative-order.md) |
| Parameter order | on | `KOTRAIL_CALLBACK_BEFORE_DATA_PARAMETER` | [parameter-order.md](parameter-order.md) |
| Null chain length | on | `KOTRAIL_ELVIS_CHAIN_TOO_LONG`, `KOTRAIL_SAFE_CALL_CHAIN_TOO_LONG` | [null-chain-length.md](null-chain-length.md) |
| Implicit receivers | on | `KOTRAIL_IMPLICIT_RECEIVER_AMBIGUOUS`, `KOTRAIL_TOO_MANY_IMPLICIT_RECEIVERS` | [implicit-receivers.md](implicit-receivers.md) |
| Prefer expression body | on | `KOTRAIL_PREFER_EXPRESSION_BODY` | [prefer-expression-body.md](prefer-expression-body.md) |
| Prefer idiom | on | `KOTRAIL_PREFER_IDIOM` | [prefer-idiom.md](prefer-idiom.md) |
| Narrow local scope | on | `KOTRAIL_NARROW_LOCAL_SCOPE` | [narrow-local-scope.md](narrow-local-scope.md) |
| Prefer val | on | `KOTRAIL_PREFER_VAL` | [prefer-val.md](prefer-val.md) |
| No redundant else | on | `KOTRAIL_REDUNDANT_ELSE_IN_EXHAUSTIVE_WHEN` | [no-redundant-else.md](no-redundant-else.md) |

## Naming and style (8)

Spelling that the reviewer would otherwise correct by hand.

| Rule | Default | Diagnostics | Page |
|---|---|---|---|
| Comment length | on | `KOTRAIL_COMMENT_TOO_LONG` | [comment-length.md](comment-length.md) |
| No parameter comments | on | `KOTRAIL_COMMENT_IN_PARAMETER_LIST` | [no-parameter-comments.md](no-parameter-comments.md) |
| No FQN references | on | `KOTRAIL_FQN_REFERENCE` | [no-fqn-references.md](no-fqn-references.md) |
| Named arguments for repeated types | on | `KOTRAIL_NAMED_ARGUMENTS_REQUIRED` | [named-arguments-for-repeated-types.md](named-arguments-for-repeated-types.md) |
| Prefer function references | on | `KOTRAIL_PREFER_FUNCTION_REFERENCE` | [prefer-function-references.md](prefer-function-references.md) |
| Sealed when branch style | on | `KOTRAIL_SEALED_WHEN_BRANCH_STYLE` | [sealed-when-branch-style.md](sealed-when-branch-style.md) |
| No pass-through function | on | `KOTRAIL_PASS_THROUGH_FUNCTION` | [no-pass-through-function.md](no-pass-through-function.md) |
| No pass-through return | on | `KOTRAIL_PASS_THROUGH_RETURN` | [no-pass-through-return.md](no-pass-through-return.md) |

## API design (7)

What a declaration exposes, and how.

| Rule | Default | Diagnostics | Page |
|---|---|---|---|
| Prefer explicit backing fields | on | `KOTRAIL_PREFER_EXPLICIT_BACKING_FIELD` | [prefer-explicit-backing-field.md](prefer-explicit-backing-field.md) |
| Prefer private setter | on | `KOTRAIL_PREFER_PRIVATE_SETTER` | [prefer-private-setter.md](prefer-private-setter.md) |
| Narrow model parameters | on | `KOTRAIL_MODEL_PARAMETER_TOO_WIDE` | [narrow-model-parameters.md](narrow-model-parameters.md) |
| Prefer value class | on | `KOTRAIL_PREFER_VALUE_CLASS` | [prefer-value-class.md](prefer-value-class.md) |
| No mutable collection in public API | on | `KOTRAIL_MUTABLE_COLLECTION_IN_PUBLIC_API` | [no-mutable-collection-in-public-api.md](no-mutable-collection-in-public-api.md) |
| No data class in public API | on | `KOTRAIL_DATA_CLASS_IN_PUBLIC_API` | [no-data-class-in-public-api.md](no-data-class-in-public-api.md) |
| JvmSynthetic for internal | off | `KOTRAIL_INTERNAL_VISIBLE_TO_JAVA`, `KOTRAIL_INTERNAL_CLASS_VISIBLE_TO_JAVA` | [jvm-synthetic-for-internal.md](jvm-synthetic-for-internal.md) |

## Errors and concurrency (7)

Failures that get hidden, and waits that guess.

| Rule | Default | Diagnostics | Page |
|---|---|---|---|
| No not-null assertion | on | `KOTRAIL_NOT_NULL_ASSERTION` | [no-not-null-assertion.md](no-not-null-assertion.md) |
| No swallowed cancellation | on | `KOTRAIL_SWALLOWED_CANCELLATION` | [no-swallowed-cancellation.md](no-swallowed-cancellation.md) |
| No ignored exception | on | `KOTRAIL_IGNORED_EXCEPTION` | [no-ignored-exception.md](no-ignored-exception.md) |
| Catch too broad | on | `KOTRAIL_CATCH_TOO_BROAD` | [catch-too-broad.md](catch-too-broad.md) |
| Delay for completion | on | `KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK` | [delay-for-completion.md](delay-for-completion.md) |
| Preconditions | on | `KOTRAIL_PRECONDITION_VIOLATED` | [preconditions.md](preconditions.md) |
| No unimplemented code | on | `KOTRAIL_UNIMPLEMENTED_CODE` | [no-unimplemented.md](no-unimplemented.md) |

## Memory and lifetime (4)

Objects kept too long, or not long enough.

| Rule | Default | Diagnostics | Page |
|---|---|---|---|
| Native allocation in loop | on | `KOTRAIL_NATIVE_ALLOCATION_IN_LOOP` | [native-allocation-in-loop.md](native-allocation-in-loop.md) |
| Weak-only reference | on | `KOTRAIL_WEAK_REFERENCE_TO_FRESH_OBJECT` | [weak-only-reference.md](weak-only-reference.md) |
| Unretained | on | `KOTRAIL_UNRETAINED_PARAMETER_RETAINED` | [unretained.md](unretained.md) |
| Unloadable code | off | `KOTRAIL_THREAD_LOCAL_IN_UNLOADABLE_CODE`, `KOTRAIL_UNSCOPED_REGISTRATION_IN_UNLOADABLE_CODE`, `KOTRAIL_OUTBOUND_REFERENCE_IN_BUNDLED_CODE` | [unloadable-code.md](unloadable-code.md) |

## Architecture policies (6)

Rules a project declares in its configuration: what may call, extend, depend on or carry what.

| Rule | Default | Diagnostics | Page |
|---|---|---|---|
| Forbidden call | on | `KOTRAIL_FORBIDDEN_CALL` | [forbidden-call.md](forbidden-call.md) |
| Dependency rules | on | `KOTRAIL_DEPENDENCY_NOT_ALLOWED` | [dependency-rules.md](dependency-rules.md) |
| Required supertype | on | `KOTRAIL_SUPERTYPE_REQUIRED` | [required-supertype.md](required-supertype.md) |
| Required annotation | on | `KOTRAIL_REQUIRED_ANNOTATION_MISSING` | [required-annotation.md](required-annotation.md) |
| Visibility policy | on | `KOTRAIL_VISIBILITY_TOO_WIDE` | [visibility-policy.md](visibility-policy.md) |
| Must be serializable | on | `KOTRAIL_TYPE_NOT_SERIALIZABLE` | [must-be-serializable.md](must-be-serializable.md) |

## Compose (18)

| Rule | Default | Diagnostics | Page |
|---|---|---|---|
| Window insets handling | on | `KOTRAIL_WINDOW_INSETS_NOT_HANDLED`, `KOTRAIL_WINDOW_INSETS_HANDLING_UNVERIFIABLE`, `KOTRAIL_WINDOW_INSETS_HANDLED_TWICE` | [compose/window-insets.md](compose/window-insets.md) |
| Composition locals | on | `KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED`, `KOTRAIL_COMPOSITION_LOCAL_NOT_PROVIDED_AT_ENTRY_POINT` | [compose/composition-locals.md](compose/composition-locals.md) |
| State delegation | on | `KOTRAIL_PREFER_STATE_DELEGATION` | [compose/state-delegation.md](compose/state-delegation.md) |
| Nesting limit | on | `KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP` | [compose/nesting.md](compose/nesting.md) |
| No trailing callback | on | `KOTRAIL_COMPOSABLE_TRAILING_CALLBACK` | [compose/no-trailing-callback.md](compose/no-trailing-callback.md) |
| Composable naming | on | `KOTRAIL_COMPOSABLE_NAMING` | [compose/naming.md](compose/naming.md) |
| Modifier parameter | on | `KOTRAIL_COMPOSABLE_MODIFIER_PARAMETER` | [compose/modifier-parameter.md](compose/modifier-parameter.md) |
| Named callback arguments | on | `KOTRAIL_COMPOSABLE_CALLBACK_AS_TRAILING_LAMBDA` | [compose/named-callback-arguments.md](compose/named-callback-arguments.md) |
| Preview required | on | `KOTRAIL_COMPOSABLE_WITHOUT_PREVIEW` | [compose/preview-required.md](compose/preview-required.md) |
| Composables per file | on | `KOTRAIL_TOO_MANY_COMPOSABLES_IN_FILE` | [compose/composables-per-file.md](compose/composables-per-file.md) |
| No side effect in composition | on | `KOTRAIL_COMPOSABLE_SIDE_EFFECT_IN_COMPOSITION` | [compose/no-side-effect-in-composition.md](compose/no-side-effect-in-composition.md) |
| No hardcoded string | off | `KOTRAIL_COMPOSABLE_HARDCODED_STRING` | [compose/no-hardcoded-string.md](compose/no-hardcoded-string.md) |
| Preview coverage | off | `KOTRAIL_COMPOSABLE_NOT_COVERED_BY_PREVIEW` | [compose/preview-coverage.md](compose/preview-coverage.md) |
| Preview parameter | off | `KOTRAIL_PREVIEW_MODEL_BUILT_INLINE` | [compose/preview-parameter.md](compose/preview-parameter.md) |
| Remember keys | on | `KOTRAIL_EFFECT_KEY_MISSING` | [compose/remember-keys.md](compose/remember-keys.md) |
| No global mutable state | on | `KOTRAIL_GLOBAL_VAR_READ_IN_COMPOSITION`, `KOTRAIL_GLOBAL_VAR_WRITTEN_IN_COMPOSABLE` | [compose/no-global-mutable-state.md](compose/no-global-mutable-state.md) |
| No callback in model | on | `KOTRAIL_CALLBACK_IN_UI_MODEL` | [compose/no-callback-in-model.md](compose/no-callback-in-model.md) |
| No unstable parameter | off, experimental | `KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER` | [compose/no-unstable-parameter.md](compose/no-unstable-parameter.md) |

## Test (3)

| Rule | Default | Diagnostics | Page |
|---|---|---|---|
| Test naming | on | `KOTRAIL_TEST_NAME_NOT_DESCRIPTIVE`, `KOTRAIL_TEST_NAME_NOT_IDENTIFIER` | [test/naming.md](test/naming.md) |
| Test must assert | on | `KOTRAIL_TEST_WITHOUT_ASSERTION` | [test/must-assert.md](test/must-assert.md) |
| No sleep in tests | on | `KOTRAIL_TEST_REAL_TIME_WAIT` | [test/no-sleep.md](test/no-sleep.md) |

## Kotlin/Native (2)

| Rule | Default | Diagnostics | Page |
|---|---|---|---|
| Objective-C identity | on | `KOTRAIL_OBJC_IDENTITY_COMPARISON`, `KOTRAIL_OBJC_WEAK_REFERENCE` | [native/objc-identity.md](native/objc-identity.md) |
| Objective-C throws | on | `KOTRAIL_OBJC_EXPORT_MISSING_THROWS` | [native/objc-throws.md](native/objc-throws.md) |

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
