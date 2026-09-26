# Null chain length

**Diagnostics:** `KOTRAIL_ELVIS_CHAIN_TOO_LONG`, `KOTRAIL_SAFE_CALL_CHAIN_TOO_LONG` (error, on the outermost expression)
**Key:** `rules.nullChainLength` (on by default)
**Settings:** `maxElvis` (default `2`; `0` disables), `maxSafeCalls` (default `0`, disabled)

## What it rejects

An expression that falls back with `?:` more often than the limit:

```kotlin
val name = user?.profile?.displayName ?: user?.profile?.email ?: cached?.name ?: "unknown"
```

and, when `maxSafeCalls` is set, a receiver chain with more `?.` than the limit:

```kotlin
val city = order?.customer?.address?.city?.name      // reported at maxSafeCalls: 3
```

## What it asks for

```kotlin
val name = user?.profile?.bestName() ?: cached?.name ?: "unknown"

val name = listOfNotNull(user?.profile?.displayName, user?.profile?.email, cached?.name)
    .firstOrNull() ?: "unknown"
```

Each `?:` is one more case the reader evaluates in order to learn which value wins; past two,
the expression is a decision table written on one line. Note that `?:` is lazy and
`listOfNotNull(...)` evaluates every candidate first: that rewrite suits cheap, side-effect-free
candidates; for the others, a function with early returns keeps the laziness. A long `?.` chain
says the model has several places that may be null between the caller and the value, and when
the result is null nothing tells the caller which link was missing. Both are shapes an assistant
produces readily because they compile on the first try.

## How the counts work

- `maxElvis` counts the `?:` operators of one connected expression, however it is parenthesized:
  `a ?: b ?: c` and `(a ?: b) ?: c` both count 2. A trailing `?: return` or `?: throw` is an exit
  rather than a candidate and is not counted, so `a ?: b ?: return` counts 1.
- `maxSafeCalls` counts the `?.` operators along one receiver chain: `a?.b?.c` counts 2. A safe
  call inside an argument or a lambda (`a?.let { b?.c }`) starts a chain of its own.
- Only the outermost expression of a chain is reported, once. A chain inside a lambda, a
  parenthesized argument, or a `run { }` is a separate expression with its own count.

`maxSafeCalls` is off by default: deep chains are the normal shape over external data (JSON
models, platform types from Java), and where the line goes is a project's decision. Enable it
with the limit that suits the models:

```yaml
rules:
  nullChainLength:
    maxElvis: 2
    maxSafeCalls: 3
```

## When it stays quiet

- The counts are within the limits, or the relevant limit is `0`.
- The `?:` chain ends in `return` or `throw`, and the rest is within the limit.
- The candidates are plain names or literals: `explicit ?: inherited ?: default ?: "none"` is a
  priority list, and the chain is its clearest form. Only a computed candidate (a safe-call
  path, a call) counts, since each of those hides a path of its own; naming such candidates
  first is what turns a reported chain into a quiet one.
- The chain is split across named locals or functions, which is what the rule asks for.

## Fixtures

`compiler-tests/testData/diagnostics/nullChainLength.kt`,
`compiler-tests/testData/diagnostics/config/nullChainSafeCalls.kt`

## Implementation notes

`fir/checkers/NullChainChecker.kt`: `ElvisChainChecker`, a `FirElvisExpressionChecker`, and
`SafeCallChainChecker`, a `FirSafeCallExpressionChecker`. Each looks at
`CheckerContext.containingElements` (through smart-cast wrappers) to act only on the outermost
node of a chain, then counts recursively: the elvis checker over `lhs` and `rhs`, dropping the
operator whose `rhs` is a `FirReturnExpression` or `FirThrowExpression`; the safe-call checker
along `receiver`.
