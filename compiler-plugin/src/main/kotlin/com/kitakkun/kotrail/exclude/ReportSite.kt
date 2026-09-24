package com.kitakkun.kotrail.exclude

/**
 * Where a diagnostic is reported, described in the terms an exclusion predicate can ask about.
 *
 * The innermost enclosing named declaration is the subject of most questions (its name, its
 * receivers, its modifiers); classes and annotations are collected from every enclosing
 * declaration so that "inside a class annotated with X" is answerable. Nothing here refers to
 * the compiler, so predicates can be evaluated and tested on their own.
 */
class ReportSite(
    /** What the innermost enclosing named declaration is, or `null` for a location without one. */
    val kind: DeclarationKind?,
    val packageName: String,
    val fileName: String,
    /** The file's path with `/` separators, for `path(glob)` and the generated-code paths. */
    val filePath: String,
    /** The innermost enclosing named declaration: a function, property, or class. */
    val declarationName: String?,
    /** Enclosing class names, innermost first; includes the declaration itself when it is a class. */
    val classNames: List<String>,
    /** Fully qualified annotation names on the innermost declaration and on everything enclosing it. */
    val annotations: Set<String>,
    /** The innermost declaration's extension receiver type, fully qualified, if it has one. */
    val extensionReceiver: String?,
    /** The innermost declaration's context parameter types, fully qualified. */
    val contextParameters: List<String>,
    /** `public`, `internal`, `protected`, `private`, or `null` for a location without a declaration. */
    val visibility: String?,
    val isOverride: Boolean,
    val isSuspend: Boolean,
    val isInline: Boolean,
    val isComposable: Boolean,
    val isTest: Boolean,
)

/** The kinds of declaration a predicate can ask about with the bare `function`, `property`, and `class` atoms. */
enum class DeclarationKind { FUNCTION, PROPERTY, CLASS }
