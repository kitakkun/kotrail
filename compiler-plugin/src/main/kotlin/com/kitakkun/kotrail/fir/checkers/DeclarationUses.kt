@file:OptIn(DirectDeclarationsAccess::class)

package com.kitakkun.kotrail.fir.checkers

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * What the declarations of a file use, collected in one walk over it, for both checks of
 * narrativeOrder.
 *
 * A container is the file or a class, local classes included, and its declarations are the ones
 * it declares directly. For a declaration of a container written in the source, [of] is what it
 * uses anywhere inside it, its nested classes and local declarations included; for a class that
 * is not local, [headerOf] is what its primary constructor and its `init` blocks use, the code a
 * constructor call leads to. A use is the resolved callee of a call, a callable reference or a
 * property access: the function or property itself, a fake override unwrapped, or, for a
 * constructor, the class's id.
 */
internal class DeclarationUses private constructor(
    val containers: List<Container>,
    private val uses: Map<FirDeclaration, Set<Any>>,
    private val headers: Map<FirRegularClass, Set<Any>>,
) {
    /**
     * The file ([owner] `null`) or a class. [source] is the text a move edits, [enclosing] the
     * declarations around the container's own, outermost first, ending with the class (empty
     * for the file), and [isLocal] whether the class is local or nested in a local one.
     */
    class Container(
        val owner: FirRegularClass?,
        val source: KtSourceElement?,
        val enclosing: List<FirDeclaration>,
        val declarations: List<FirDeclaration>,
        val isLocal: Boolean,
    )

    /** What [declaration], a declaration of a container written in the source, uses; empty for any other. */
    fun of(declaration: FirDeclaration): Set<Any> = uses[declaration].orEmpty()

    /** What the primary constructor and the `init` blocks of [klass], a class that is not local, use. */
    fun headerOf(klass: FirRegularClass): Set<Any> = headers[klass].orEmpty()

    companion object {
        fun of(file: FirFile): DeclarationUses {
            val walk = Walk()
            walk.file(file)
            return DeclarationUses(walk.containers, walk.uses, walk.headers)
        }
    }

    private class Walk : FirVisitorVoid() {
        val containers = mutableListOf<Container>()
        val uses = HashMap<FirDeclaration, MutableSet<Any>>()
        val headers = HashMap<FirRegularClass, MutableSet<Any>>()

        /** The sets a use found now goes to: those of every declaration and class header the walk is inside. */
        private val open = ArrayDeque<MutableSet<Any>>()

        /** The declarations the walk is inside, outermost first. */
        private val path = ArrayDeque<FirDeclaration>()

        fun file(file: FirFile) {
            containers += Container(owner = null, source = file.source, enclosing = emptyList(), declarations = file.declarations, isLocal = false)
            for (declaration in file.declarations) declare(declaration, header = null)
        }

        override fun visitElement(element: FirElement) {
            if (element is FirQualifiedAccessExpression && open.isNotEmpty()) useOf(element)?.let { use -> open.forEach { it += use } }
            element.acceptChildren(this)
        }

        /** A local class, met inside a body. */
        override fun visitRegularClass(regularClass: FirRegularClass) {
            klass(regularClass)
        }

        private fun klass(klass: FirRegularClass) {
            val isLocal = klass.isLocal
            val enclosing = if (path.lastOrNull() === klass) path.toList() else path + klass
            containers += Container(owner = klass, source = klass.source, enclosing = enclosing, declarations = klass.declarations, isLocal = isLocal)
            val header = if (isLocal) null else headers.getOrPut(klass) { mutableSetOf() }
            for (declaration in klass.declarations) {
                val inHeader = (declaration is FirConstructor && declaration.isPrimary) || declaration is FirAnonymousInitializer
                declare(declaration, header?.takeIf { inHeader })
            }
        }

        /** Walks [declaration] with its own set open when it is written in the source, and [header] when it belongs to its class's header. */
        private fun declare(declaration: FirDeclaration, header: MutableSet<Any>?) {
            val source = declaration.source
            val own = if (source != null && source.kind !is KtFakeSourceElementKind) uses.getOrPut(declaration) { mutableSetOf() } else null
            val opened = listOfNotNull(own, header)
            open.addAll(opened)
            path.addLast(declaration)
            if (declaration is FirRegularClass) klass(declaration) else declaration.accept(this)
            path.removeLast()
            repeat(opened.size) { open.removeLast() }
        }

        private fun useOf(access: FirQualifiedAccessExpression): Any? =
            when (val symbol = access.calleeReference.toResolvedCallableSymbol()?.unwrapFakeOverrides()) {
                is FirConstructorSymbol -> symbol.callableId.classId
                is FirNamedFunctionSymbol, is FirPropertySymbol -> symbol
                else -> null
            }
    }
}
