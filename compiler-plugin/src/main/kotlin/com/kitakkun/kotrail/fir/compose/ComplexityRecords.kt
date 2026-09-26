package com.kitakkun.kotrail.fir.compose

import com.kitakkun.kotrail.fir.compose.checkers.ComposableComplexityChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import java.io.File
import java.security.MessageDigest

/**
 * The complexity of every composable of a compilation, written under the directory the
 * `complexityDir` option names for the Gradle plugin's `kotrailComplexity` report: one record
 * per source file, `<sha1 of the path>.jsonl`, a header line naming the file and one line per
 * composable with its score, the breakdown by kind and the hotspot, if any. A file's record is
 * rewritten whenever the file is compiled, so the records of untouched files keep what the last
 * build that saw them found; a record whose file no longer exists is ignored when read.
 */
object ComplexityRecords {
    private val lock = Any()
    private val written = HashMap<String, StringBuilder>()

    fun record(directory: String, file: String, function: FirNamedFunction, total: Int, root: ComposableComplexityChecker.Node, hotspot: ComposableComplexityChecker.Node?) {
        val line = buildString {
            append("{\"function\": ").append(quote(function.symbol.callableId.asSingleFqName().asString()))
            append(", \"line\": ").append(function.source?.let { lineOf(file, it.startOffset) } ?: 0)
            append(", \"score\": ").append(total)
            for ((kind, points) in root.byKind()) append(", ").append(quote(kind.label)).append(": ").append(points)
            if (hotspot != null) append(", \"hotspot\": ").append(quote("${hotspot.label} (${hotspot.total()})"))
            append("}\n")
        }
        synchronized(lock) {
            val buffer = written.getOrPut("$directory\u0000$file") {
                StringBuilder("{\"file\": ${quote(file)}}\n").also { header ->
                    File(directory, "${sha1(file.toByteArray())}.jsonl").apply { parentFile?.mkdirs() }.writeText(header.toString())
                }
            }
            buffer.append(line)
            File(directory, "${sha1(file.toByteArray())}.jsonl").writeText(buffer.toString())
        }
    }

    private fun lineOf(file: String, offset: Int): Int {
        val text = runCatching { File(file).readText() }.getOrNull() ?: return 0
        return text.take(offset).count { it == '\n' } + 1
    }

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
