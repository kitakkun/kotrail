@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.scopes.defaultImportsProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.ImportPath

/**
 * Whether the simple name of [target] already means something else in [file], through any of the
 * places a file takes names from, whatever their precedence. The packages of the star imports
 * and the file's own package are asked by hand, so each question is recorded for incremental
 * compilation: a class of that name added to or removed from one of them in another file
 * compiles [file] again. The default imports are not recorded: they name the standard library's
 * packages, which no source file of the module adds to.
 */
fun classNameIsTaken(target: ClassId, file: FirFile, session: FirSession): Boolean =
    NameSource.entries.any { it.bindsOther(target, file, session) }

/** How a file can write a top-level class by a name of its own: see [classNameInFile]. */
sealed interface ClassNameInFile {
    /** The file writes the class as [name]: its simple name, or the alias it is imported under. */
    class Reachable(val name: Name) : ClassNameInFile

    /** No name of the file means the class, and its simple name means nothing else: an import makes it reachable. */
    data object Importable : ClassNameInFile

    /** No name of the file means the class, and its simple name already means something else. */
    data object Taken : ClassNameInFile
}

/**
 * The name by which [file] can write the top-level class [target]. An import of the class gives
 * it its simple name, or only the alias when every import of it has one. Otherwise the first
 * place in order of precedence that reaches the class (the file's package, a star import, a
 * default import) gives it its simple name, unless that name means another class there or in a
 * place that takes precedence. When nothing reaches the class, the answer is whether
 * [classNameIsTaken]. The questions asked by hand are recorded for incremental compilation as
 * there.
 */
fun classNameInFile(target: ClassId, file: FirFile, session: FirSession): ClassNameInFile {
    val importedAs = file.imports.filter { !it.isAllUnder && it.importedFqName == target.asSingleFqName() }
        .map { it.aliasName ?: target.shortClassName }
    if (importedAs.isNotEmpty()) return ClassNameInFile.Reachable(if (target.shortClassName in importedAs) target.shortClassName else importedAs.first())
    val reachedBy = NameSource.entries.firstOrNull { it.reaches(target, file, session) }
        ?: return if (classNameIsTaken(target, file, session)) ClassNameInFile.Taken else ClassNameInFile.Importable
    val shadowed = NameSource.entries.filter { it <= reachedBy }.any { it.bindsOther(target, file, session) }
    return if (shadowed) ClassNameInFile.Taken else ClassNameInFile.Reachable(target.shortClassName)
}

/** The places a file takes simple class names from, from the highest precedence to the lowest. */
private enum class NameSource {
    EXPLICIT_IMPORTS {
        override fun reaches(target: ClassId, file: FirFile, session: FirSession): Boolean =
            file.imports.any { !it.isAllUnder && it.aliasName == null && it.importedFqName == target.asSingleFqName() }

        override fun bindsOther(target: ClassId, file: FirFile, session: FirSession): Boolean = file.imports.any { import ->
            val fqName = import.importedFqName ?: return@any false
            !import.isAllUnder && (import.aliasName ?: fqName.shortName()) == target.shortClassName && fqName != target.asSingleFqName()
        }
    },
    FILE_PACKAGE {
        override fun reaches(target: ClassId, file: FirFile, session: FirSession): Boolean =
            target.packageFqName == file.packageDirective.packageFqName

        override fun bindsOther(target: ClassId, file: FirFile, session: FirSession): Boolean {
            val name = target.shortClassName
            if (file.declarations.any { it is FirClassLikeDeclaration && it.symbol.name == name && it.symbol.classId != target }) return true
            val filePackage = file.packageDirective.packageFqName
            val samePackage = ClassId(filePackage, name)
            if (samePackage == target) return false
            session.recordNameProbe(name, filePackage, null, file.source)
            return session.symbolProvider.getClassLikeSymbolByClassId(samePackage) != null
        }
    },
    STAR_IMPORTS {
        override fun reaches(target: ClassId, file: FirFile, session: FirSession): Boolean =
            file.imports.any { it.isAllUnder && it.importedFqName == target.packageFqName }

        override fun bindsOther(target: ClassId, file: FirFile, session: FirSession): Boolean = file.imports.any { import ->
            val fqName = import.importedFqName ?: return@any false
            val candidate = ClassId(fqName, target.shortClassName)
            if (!import.isAllUnder || candidate == target) return@any false
            session.recordNameProbe(target.shortClassName, fqName, import.source, file.source)
            session.symbolProvider.getClassLikeSymbolByClassId(candidate) != null
        }
    },
    DEFAULT_IMPORTS {
        override fun paths(session: FirSession): List<ImportPath> =
            session.defaultImportsProvider.getDefaultImports(includeLowPriorityImports = false)
    },

    /** `java.lang.*` on the JVM: `String` there is `kotlin.String`, not `java.lang.String`. */
    LOW_PRIORITY_DEFAULT_IMPORTS {
        override fun paths(session: FirSession): List<ImportPath> = session.defaultImportsProvider.defaultLowPriorityImports
    };

    /** The paths of the imports, for a place made of default imports. */
    open fun paths(session: FirSession): List<ImportPath> = emptyList()

    /** Whether the simple name of [target] means [target] through this place. */
    open fun reaches(target: ClassId, file: FirFile, session: FirSession): Boolean = paths(session).any { path ->
        if (path.isAllUnder) path.fqName == target.packageFqName else path.fqName == target.asSingleFqName() && path.alias == null
    }

    /** Whether the simple name of [target] means another class through this place. */
    open fun bindsOther(target: ClassId, file: FirFile, session: FirSession): Boolean = paths(session).any { path ->
        if (path.isAllUnder) {
            val candidate = ClassId(path.fqName, target.shortClassName)
            candidate != target && session.symbolProvider.getClassLikeSymbolByClassId(candidate) != null
        } else {
            (path.alias ?: path.fqName.shortName()) == target.shortClassName && path.fqName != target.asSingleFqName()
        }
    }
}
