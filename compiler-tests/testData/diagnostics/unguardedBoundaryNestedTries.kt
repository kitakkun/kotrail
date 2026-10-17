// KOTRAIL_CONFIG: rules.unguardedBoundary=on, rules.unguardedBoundary.names=MarkerStore.*
// RENDER_DIAGNOSTICS_FULL_TEXT
// A try in the try block of another try at the same root hands what it does not catch to that try,
// whose clauses are judged instead.
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

fun log(e: Throwable) {}

class MarkerStore(private val file: File, private val temporary: File) {
    // The inner try is not reported; the outer one is, for the first call out in its whole block.
    fun write(text: String) {
        try {
            file.parentFile.mkdirs()
            temporary.outputStream().use { it.write(text.toByteArray()) }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
            log(e)
        }
    }

    // Neither is reported: the outer try catches the rest.
    fun writeGuarded(text: String) {
        try {
            file.parentFile.mkdirs()
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            log(e)
        } catch (e: Exception) {
            log(e)
        }
    }

    // Only the outer try is reported: the inner one runs in its try block through an inline lambda.
    fun writeThroughUse(text: String) {
        try {
            temporary.outputStream().use { stream ->
                try {
                    stream.write(text.toByteArray())
                } catch (e: IllegalStateException) {
                    log(e)
                }
            }
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
            log(e)
        }
    }

    // Both reported: the outer clauses do not cover a try in one of them.
    fun writeInCatch(text: String) {
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: AtomicMoveNotSupportedException<!>) {
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
                log(e)
            }
        }
    }

    // Both reported: a try in a finally block runs after the outer clauses.
    fun writeInFinally(text: String) {
        try {
            file.parentFile.mkdirs()
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: SecurityException<!>) {
            log(e)
        } finally {
            try {
                Files.delete(temporary.toPath())
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
                log(e)
            }
        }
    }

    // Reported: the outer try has no clause, so what the inner one does not catch ends the boundary.
    fun writeWithCleanup(text: String) {
        try {
            try {
                Files.move(temporary.toPath(), file.toPath())
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
                log(e)
            }
        } finally {
            temporary.delete()
        }
    }

    // Reported: every outer clause rethrows, so the outer try does not stop failures.
    fun writeTranslated(text: String) {
        try {
            try {
                Files.move(temporary.toPath(), file.toPath())
            } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: AtomicMoveNotSupportedException<!>) {
                log(e)
            }
        } catch (e: IOException) {
            throw IllegalStateException("move failed", e)
        }
    }

    // Both reported: the thread's lambda runs later, as a root of its own, outside the outer try.
    fun writeLater(text: String) {
        try {
            file.parentFile.mkdirs()
            thread {
                try {
                    Files.move(temporary.toPath(), file.toPath())
                } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: IOException<!>) {
                    log(e)
                }
            }
        } catch (<!KOTRAIL_BOUNDARY_UNGUARDED!>e: SecurityException<!>) {
            log(e)
        }
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, flexibleType, functionDeclaration, javaFunction, javaProperty, lambdaLiteral,
localProperty, primaryConstructor, propertyDeclaration, stringLiteral, tryExpression */
