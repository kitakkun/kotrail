// IGNORE_DEXING
// RENDER_DIAGNOSTICS_FULL_TEXT
// The messages the main module exchanges are declared in another module, so their constructors are calls out.

// MODULE: protocol
// FILE: Messages.kt
package protocol

data class SetViewAttribute(val nodeId: Int, val value: String)

data class CaptureOptions(val depth: Int = 3)

@JvmInline
value class NodeId(val raw: Int)

class Connection(val host: String)

data class Padding(val size: Int) {
    fun copy(scale: Double): Padding = Padding((size * scale).toInt())
}

// MODULE: main(protocol)
// KOTRAIL_CONFIG: rules.unguardedBoundary=on
// FILE: AttributeStore.kt
package app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import protocol.CaptureOptions
import protocol.Connection
import protocol.NodeId
import protocol.Padding
import protocol.SetViewAttribute

class AttributeStore(private val scope: CoroutineScope, private val last: CaptureOptions?) {
    private suspend fun writeAndRecord(request: SetViewAttribute) {}
    private suspend fun capture(options: CaptureOptions) {}
    private suspend fun select(node: NodeId) {}
    private suspend fun open(connection: Connection) {}

    fun commit(value: String) {
        // Not reported: the module's own function, around a data class that only holds the values it is given.
        scope.launch {
            try {
                writeAndRecord(SetViewAttribute(nodeId = 1, value = value))
            } catch (e: IllegalStateException) {
            }
        }

        // Not reported: so is a data class built as a default.
        scope.launch {
            try {
                capture(last ?: CaptureOptions())
            } catch (e: IllegalStateException) {
            }
        }

        // Not reported: and a value class.
        scope.launch {
            try {
                select(NodeId(1))
            } catch (e: IllegalStateException) {
            }
        }

        // Reported, naming the constructor: a regular class's constructor runs code this module does not declare.
        scope.launch {
            try {
                open(Connection(value))
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalStateException<!>) {
            }
        }
    }
}

data class Bounds(val width: Int, val height: Int)

class LayoutParser(private val scope: CoroutineScope) {
    private suspend fun apply(attribute: SetViewAttribute, bounds: Bounds) {}

    fun parse(text: String, bounds: Bounds) {
        // Not reported: the module's own widen reaches the other module only through the copy and the
        // componentN functions generated for a data class.
        scope.launch {
            try {
                apply(widen(SetViewAttribute(nodeId = 1, value = text)), bounds)
            } catch (e: IllegalStateException) {
            }
        }

        // Not reported: nor are the members generated for the module's own data class calls out.
        scope.launch {
            try {
                apply(SetViewAttribute(nodeId = bounds.component1(), value = bounds.toString()), bounds.copy(width = 0))
            } catch (e: IllegalStateException) {
            }
        }

        // Reported, naming the member: a copy the data class declares itself is not the generated one.
        scope.launch {
            try {
                apply(SetViewAttribute(nodeId = Padding(1).copy(scale = 2.0).size, value = text), bounds)
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IllegalStateException<!>) {
            }
        }
    }

    private fun widen(attribute: SetViewAttribute): SetViewAttribute {
        val (nodeId, value) = attribute
        return attribute.copy(nodeId = nodeId + 1, value = value.trim())
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, data, elvisExpression, functionDeclaration, integerLiteral, lambdaLiteral,
localProperty, nullableType, primaryConstructor, propertyDeclaration, suspend, tryExpression, value */
