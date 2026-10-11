// KOTRAIL_CONFIG: rules.preferFunctionReferences=on
// DUMP_KOTRAIL_FIXES
// A type-qualified reference names the receiver's class the way the file can write it: by the alias it is
// imported under, headed by its outermost class when it is nested, and with an import when the file met
// the type only through inference. A bound reference on an object writes the object as the call does.

// FILE: Music.kt
package lib.music

class ChordSpan(val beat: Int) {
    fun length(): Int = beat
}

class Bar(val spans: List<ChordSpan>)

class Sheet(val lines: List<Line>) {
    class Line(val text: String)
}

class Note(val pitch: Int)

object Metronome {
    object Click {
        fun at(beat: Int): Int = beat
    }
}

class Meter {
    companion object {
        fun of(beat: Int): Int = beat
    }
}

object Tempo {
    fun scale(beat: Int): Int = beat
}

// FILE: Other.kt
package lib.other

class Note(val name: String)

// FILE: Imported.kt
package app

import lib.music.Bar
import lib.music.Sheet

// Reported: the import of ChordSpan goes between the two, once however many fixes ask for it.
fun beats(bar: Bar): List<Int> = bar.spans.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.beat }<!>
fun lengths(bar: Bar): List<Int> = bar.spans.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.length() }<!>

// Reported: a nested class is written with its outer class, which is imported already.
fun texts(sheet: Sheet): List<String> = sheet.lines.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.text }<!>

// FILE: Aliased.kt
package app.aliased

import lib.music.ChordSpan as Span
import lib.music.Sheet as Score

// Reported: written with the alias, and no import is added.
fun beats(spans: List<Span>): List<Int> = spans.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.beat }<!>
fun texts(score: Score): List<String> = score.lines.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.text }<!>

// FILE: Starred.kt
package app.starred

import lib.music.*

// Reported: a star import reaches the class already.
fun beats(bar: Bar): List<Int> = bar.spans.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.beat }<!>

// FILE: Taken.kt
package app.taken

import lib.music.Bar
import lib.other.Note

class ChordSpan

// Not reported: the simple name means another class in this file, through a declaration or an import.
fun beats(bar: Bar): List<Int> = bar.spans.map { it.beat }
fun pitches(notes: List<lib.music.Note>): List<Int> = notes.map { it.pitch }

// FILE: Bare.kt
package app.bare

// Reported: with no imports, the import goes after the package directive.
fun beats(bar: lib.music.Bar): List<Int> = bar.spans.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.beat }<!>

// FILE: Root.kt
// Reported: with neither imports nor a package directive, the import goes at the start of the file.
fun rootBeats(bar: lib.music.Bar): List<Int> = bar.spans.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.beat }<!>

// FILE: Annotated.kt
@file:Suppress("unused")

// Reported without a fix: the import cannot go before the file annotation.
fun annotatedBeats(bar: lib.music.Bar): List<Int> = bar.spans.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ it.beat }<!>

// FILE: Bound.kt
package app.bound

import lib.music.Meter
import lib.music.Metronome

// Reported: the object is written as the call writes it, a nested one with its outer object, a companion by
// its class, and one written with its package with the package.
fun clicks(beats: List<Int>): List<Int> = beats.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ Metronome.Click.at(it) }<!>
fun meters(beats: List<Int>): List<Int> = beats.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ Meter.of(it) }<!>
fun scaled(beats: List<Int>): List<Int> = beats.map <!KOTRAIL_PREFER_FUNCTION_REFERENCE!>{ lib.music.Tempo.scale(it) }<!>

// The references those fixes write resolve in this file.
val written: List<(Int) -> Int> = listOf(Metronome.Click::at, Meter::of, lib.music.Tempo::scale)

// FILE: Local.kt
package app.local

// Not reported: the receiver is a local class.
fun locals(): List<Int> {
    class Slot(val index: Int)
    return listOf(Slot(1)).map { it.index }
}

/* GENERATED_FIR_TAGS: annotationUseSiteTargetFile, callableReference, classDeclaration, companionObject,
functionDeclaration, integerLiteral, lambdaLiteral, localClass, nestedClass, objectDeclaration, primaryConstructor,
propertyDeclaration, stringLiteral */
