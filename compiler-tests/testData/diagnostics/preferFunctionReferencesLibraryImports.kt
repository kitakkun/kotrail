// IGNORE_DEXING
// A type-qualified reference to a library class the file met only as the parameter of a lambda it passes to
// an inline extension from another package gets its import, among explicit imports that include members of
// objects in the file's own package.

// MODULE: graphics
// FILE: Geometry.kt
package androidx.compose.ui.geometry

class Offset(val x: Float, val y: Float)

class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

class Size(val width: Float, val height: Float)

// FILE: Graphics.kt
package androidx.compose.ui.graphics

import androidx.compose.ui.geometry.Rect

enum class BlendMode { SrcAtop }

class Brush

class Color(val value: Long)

class Paint

class ImageBitmap

interface Canvas {
    fun saveLayer(bounds: Rect, paint: Paint)
    fun restore()
}

fun Canvas(image: ImageBitmap): Canvas = TODO()

// FILE: DrawScope.kt
package androidx.compose.ui.graphics.drawscope

import androidx.compose.ui.graphics.Canvas

interface DrawScope {
    val canvas: Canvas
}

inline fun DrawScope.drawIntoCanvas(block: (Canvas) -> Unit) = block(canvas)

inline fun DrawScope.rotate(degrees: Float, block: DrawScope.() -> Unit) = block()

inline fun DrawScope.scale(scale: Float, block: DrawScope.() -> Unit) = block()

inline fun DrawScope.translate(left: Float, top: Float, block: DrawScope.() -> Unit) = block()

// FILE: Text.kt
package androidx.compose.ui.text

import androidx.compose.ui.graphics.drawscope.DrawScope

class TextMeasurer

class TextStyle

fun DrawScope.drawText(measurer: TextMeasurer, text: String, style: TextStyle) {}

// FILE: Unit.kt
package androidx.compose.ui.unit

val Int.sp: Float get() = toFloat()

// MODULE: main(graphics)
// KOTRAIL_CONFIG: rules.preferFunctionReferences=on
// DUMP_KOTRAIL_FIXES
// FILE: Art.kt
package app.gallery.ui

import androidx.compose.ui.graphics.drawscope.DrawScope

object Backdrop {
    fun DrawScope.wood() {}
}

object PrizeArt {
    fun DrawScope.prize(time: Double) {}
}

// FILE: Shelves.kt
package app.gallery.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import app.gallery.ui.Backdrop.wood
import app.gallery.ui.PrizeArt.prize
import kotlin.math.cos
import kotlin.math.sin

internal object Shelves {
    fun DrawScope.shelf(text: TextMeasurer, time: Double): List<Any> {
        wood()
        rotate(cos(time).toFloat()) { scale(sin(time).toFloat()) { translate(1f, 2f) { drawText(text, "prize", TextStyle()) } } }
        return listOf(Offset(0f, 0f), Size(1f, 1f), BlendMode.SrcAtop, Brush(), Color(0), 12.sp)
    }

    // Reported: Canvas is imported between Brush and Color.
    fun DrawScope.prizeLayer(bounds: Rect, time: Double) {
        drawIntoCanvas { it.saveLayer(bounds, Paint()) }
        prize(time)
        drawIntoCanvas <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.restore() }<!>
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, enumDeclaration, enumEntry, funWithExtensionReceiver, functionDeclaration,
functionalType, getter, inline, integerLiteral, interfaceDeclaration, lambdaLiteral, objectDeclaration,
primaryConstructor, propertyDeclaration, propertyWithExtensionReceiver, stringLiteral, typeWithExtension */
