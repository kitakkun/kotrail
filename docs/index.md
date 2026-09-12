---
layout: home
hero:
  name: Kotrail
  text: Compiler checker rules for Kotlin
  image:
    light: /kotrail-mark.svg
    dark: /kotrail-mark-dark.svg
    alt: Kotrail
  tagline: A flexible set of compiler checker rules that keep your Kotlin code durable when developing with AI.
  actions:
    - theme: brand
      text: Get started
      link: /getting-started
    - theme: alt
      text: Browse the rules
      link: /rules/README
features:
  - title: Enforced, not suggested
    details: A rule violation is a compile error. The assistant sees it in the same loop it sees type errors, and fixes it before you review the change.
  - title: Flexible
    details: Every rule has a switch, a severity, and settings, per project and per compilation. Structural carve-outs are one predicate in a properties file.
  - title: Precise
    details: Rules run on the resolved FIR tree, so they reason about types, receivers, annotations, and call targets rather than pattern-match on text.
---
