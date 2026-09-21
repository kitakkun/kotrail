package com.kitakkun.kotrail.fir

import java.io.File
import java.security.MessageDigest

/**
 * One text replacement of a fix: the characters from [startOffset] (inclusive) to [endOffset]
 * (exclusive) of the file become [replacement]. An insertion has equal offsets; a deletion has an
 * empty replacement.
 */
class FixEdit(val startOffset: Int, val endOffset: Int, val replacement: String) {
    init {
        require(startOffset in 0..endOffset) { "edit range $startOffset..$endOffset" }
    }
}

/**
 * Records the fixes of every compilation under the directory the `fixesDir` option names, one
 * record per source file, so that a later `kotrailFix` can apply them without compiling again.
 *
 * A record is `<sha1 of the path>.jsonl`. Its first line names the file and the SHA-256 of its
 * content at the time it was compiled; each following line is one fix,
 * `{"diagnostic": "KOTRAIL_...", "edits": [{"start": 10, "end": 20, "replacement": "..."}]}`.
 * The record is started over every time the file is compiled, and only then: with incremental
 * compilation the records of files left alone keep the fixes of the build that last saw them,
 * and the content hash tells an applier whether they still fit. A checker never writes here
 * directly; [reportKotrail] does, for every diagnostic it reports with edits attached.
 */
object FixRecords {
    private val lock = Any()

    /** Starts the record of [file] afresh, with its current content hash. */
    fun begin(directory: String, file: String) {
        val content = runCatching { File(file).readBytes() }.getOrNull() ?: return
        val header = "{\"file\": ${quote(file)}, \"hash\": \"${sha256(content)}\"}\n"
        synchronized(lock) {
            recordFor(directory, file).apply { parentFile?.mkdirs() }.writeText(header)
        }
    }

    fun write(directory: String, file: String, diagnostic: String, edits: List<FixEdit>) {
        val line = buildString {
            append("{\"diagnostic\": ").append(quote(diagnostic))
            append(", \"edits\": [")
            edits.forEachIndexed { index, edit ->
                if (index > 0) append(", ")
                append("{\"start\": ").append(edit.startOffset)
                append(", \"end\": ").append(edit.endOffset)
                append(", \"replacement\": ").append(quote(edit.replacement)).append('}')
            }
            append("]}\n")
        }
        synchronized(lock) {
            val record = recordFor(directory, file)
            if (!record.isFile) return
            record.appendText(line)
        }
    }

    private fun recordFor(directory: String, file: String): File = File(directory, "${sha1(file.toByteArray())}.jsonl")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
