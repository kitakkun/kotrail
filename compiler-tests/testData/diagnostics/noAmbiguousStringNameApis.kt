// KOTRAIL_CONFIG: rules.noAmbiguousStringName=on, rules.noAmbiguousStringName.apis=file:java.io.File,executable:shell.runCommand,directory:shell.ensureDir,file:shell.readConfig,path:shell.open
// RENDER_DIAGNOSTICS_FULL_TEXT
package shell

import java.io.File

fun runCommand(command: List<String>): Int = command.size
fun ensureDir(directory: String) = File(directory).mkdirs()
fun readConfig(configFile: String) = File(configFile).readText()
fun open(targetPath: String) = File(targetPath).exists()

// Reported: an executable, by the entry's 'executable' kind, as the head of the command list.
fun devices(<!KOTRAIL_STRING_NAME_HIDES_KIND!>adb<!>: String) = runCommand(listOf(adb, "devices"))

fun local(<!KOTRAIL_STRING_NAME_HIDES_KIND!>xcrun<!>: String) {
    // Reported: the head of a list local passed to a project's process API.
    val command = listOf(xcrun, "simctl", "list")
    runCommand(command)
}

// Reported: a directory, by the entry's 'directory' kind; the suggestion is 'cacheDir'.
fun prepare(<!KOTRAIL_STRING_NAME_HIDES_KIND!>cache<!>: String) = ensureDir(cache)

// Reported: a file, by the entry's 'file' kind.
fun load(<!KOTRAIL_STRING_NAME_HIDES_KIND!>settings<!>: String) = readConfig(settings)

// Reported: a path, by the entry's 'path' kind.
fun show(<!KOTRAIL_STRING_NAME_HIDES_KIND!>report<!>: String) = open(report)

// Not reported: ProcessBuilder is not in this project's list, which replaces the defaults.
fun unlisted(emulator: String) = ProcessBuilder(emulator).start()

/* GENERATED_FIR_TAGS: flexibleType, functionDeclaration, javaFunction, localProperty, propertyDeclaration,
stringLiteral */
