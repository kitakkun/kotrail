// KOTRAIL_CONFIG: rules.noAmbiguousStringName=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import java.io.File
import java.nio.file.Paths

// Reported: a public constructor property that is the program ProcessBuilder runs; no fix (public).
class Tools(val <!KOTRAIL_STRING_NAME_HIDES_KIND!>ffmpeg<!>: String) {
    fun run() = ProcessBuilder(ffmpeg, "-i").start()
}

// Not reported: the name says it is a path.
class NamedTools(val ffmpegPath: String) {
    fun run() = ProcessBuilder(ffmpegPath, "-i").start()
}

class Config(val ffmpeg: String)

fun resolve(): String = "/usr/bin/adb"

fun locals(config: Config) {
    // Reported, with a fix to 'adbPath': a local that File opens.
    val <!KOTRAIL_STRING_NAME_HIDES_KIND!>adb<!> = resolve()
    File(adb).exists()
    println(adb)

    // Reported: the local nearest to the use, not the member of another class it was read from.
    val <!KOTRAIL_STRING_NAME_HIDES_KIND!>tool<!> = config.ffmpeg
    File(tool).exists()

    // Not reported: the name carries a kind word.
    val workingDir = "/tmp"
    File(workingDir).mkdirs()

    // Not reported: never used as a path.
    val name = "x"
    println(name)

    // Not reported: not a String.
    val commandLine = listOf("adb", "devices")
    println(commandLine)
}

// Reported: a parameter of a public function, the head of a command list; no fix.
fun launch(<!KOTRAIL_STRING_NAME_HIDES_KIND!>idb<!>: String) {
    ProcessBuilder(listOf(idb, "list")).start()
}

// Reported: a directory, by what is done with the File.
fun prepare(<!KOTRAIL_STRING_NAME_HIDES_KIND!>cache<!>: String) {
    File(cache).mkdirs()
}

// Reported: a private function's parameter, with a fix; Paths.get takes it.
private fun read(<!KOTRAIL_STRING_NAME_HIDES_KIND!>source<!>: String) = Paths.get(source).toFile().readText()

fun runTool(executablePath: String) = ProcessBuilder(executablePath).start()

fun hop() {
    // Reported: one hop through a same-file parameter that reaches ProcessBuilder.
    val <!KOTRAIL_STRING_NAME_HIDES_KIND!>emulator<!> = "emulator"
    runTool(emulator)

    // Reported: one hop through a local initialized from it.
    val <!KOTRAIL_STRING_NAME_HIDES_KIND!>home<!> = "/opt/sdk"
    val sdkPath = home
    File(sdkPath).exists()
}

// Reported: a private property, with a fix.
private val <!KOTRAIL_STRING_NAME_HIDES_KIND!>simulator<!>: String = "/usr/bin/simctl"

fun simulate() = Runtime.getRuntime().exec(arrayOf(simulator, "list"))

// Reported: a lambda parameter.
fun each(tools: List<String>) = tools.map { <!KOTRAIL_STRING_NAME_HIDES_KIND!>tool<!> -> File(tool) }

// Reported as a directory: the parent of a two-part File, with the suggestion 'appDataDir'.
fun captures(<!KOTRAIL_STRING_NAME_HIDES_KIND!>appData<!>: String) = File(appData, "captures")

// Reported as a directory: the first of several parts of Paths.get.
fun logs(<!KOTRAIL_STRING_NAME_HIDES_KIND!>root<!>: String) = Paths.get(root, "var", "log")

// Not reported: the child of a two-part File is a file name, not the head.
fun child(parentDir: String, entry: String) = File(parentDir, entry)

fun lists() {
    // Reported: the head of a command list held by a local that ProcessBuilder runs.
    val <!KOTRAIL_STRING_NAME_HIDES_KIND!>ffmpeg<!> = "ffmpeg"
    val command = listOf(ffmpeg, "-i")
    ProcessBuilder(command).start()

    // Not reported: a list that never reaches a process API.
    val tar = "tar"
    val arguments = listOf(tar, "-x")
    println(arguments)
}

// Reported: the first add of a buildList that is passed to ProcessBuilder.
fun built(<!KOTRAIL_STRING_NAME_HIDES_KIND!>adb<!>: String, serial: String) {
    val command = buildList {
        add(adb)
        addAll(listOf("-s", serial))
    }
    ProcessBuilder(command).start()
}

// Reported: the head of addAll(listOf(...)) in a buildList a same-file function returns.
class Device(private val <!KOTRAIL_STRING_NAME_HIDES_KIND!>idevice<!>: String) {
    private fun command(vararg arguments: String): List<String> = buildList {
        addAll(listOf(idevice, "-u"))
        addAll(arguments)
    }

    fun list() = ProcessBuilder(command("list")).start()
}

fun templates(<!KOTRAIL_STRING_NAME_HIDES_KIND!>userHome<!>: String) {
    // Reported: 'userHome' is the directory the template builds on, and 'sdk' the file it names.
    val <!KOTRAIL_STRING_NAME_HIDES_KIND!>sdk<!> = "$userHome/sdk"
    File(sdk).exists()
}

// Reported: a template straight into the API; 'base' holds a directory.
fun settings(<!KOTRAIL_STRING_NAME_HIDES_KIND!>base<!>: String) = File("$base/Library/Preferences")

// Reported: 'top' starts the template and is its directory; 'name' comes after it and stays quiet.
fun join(<!KOTRAIL_STRING_NAME_HIDES_KIND!>top<!>: String, name: String) = File("$top/$name")

// Not reported: the template does not continue with '/', and 'prefix' is not the first segment.
fun suffixed(stem: String, prefix: String) = File("$stem.log").exists() || File("x$prefix/y").exists()

fun thumbnail(<!KOTRAIL_STRING_NAME_HIDES_KIND!>ffmpeg<!>: String?) {
    // Reported: through a scope function's 'it' into a same-file parameter; the lambda is no hop.
    ffmpeg?.let { posterFrame(it) }
}

// Reported: the parameter that reaches ProcessBuilder.
private fun posterFrame(<!KOTRAIL_STRING_NAME_HIDES_KIND!>tool<!>: String) {
    ProcessBuilder(tool).start()
}

fun scoped(<!KOTRAIL_STRING_NAME_HIDES_KIND!>adb<!>: String, <!KOTRAIL_STRING_NAME_HIDES_KIND!>emulator<!>: String, <!KOTRAIL_STRING_NAME_HIDES_KIND!>avd<!>: String, <!KOTRAIL_STRING_NAME_HIDES_KIND!>sdk<!>: String, <!KOTRAIL_STRING_NAME_HIDES_KIND!>ndk<!>: String, config: Config) {
    // Reported: 'this' of apply and with, 'it' of also and takeIf, straight into the API.
    adb.apply { File(this).exists() }
    with(emulator) { ProcessBuilder(this).start() }
    avd.also { File(it).exists() }
    sdk.takeIf { File(it).exists() }

    // Reported: a named lambda parameter, and the value it is initialized from one hop away.
    ndk.let { <!KOTRAIL_STRING_NAME_HIDES_KIND!>program<!> -> File(program) }

    // Not reported: another object's member is not followed through a hop, the named parameter is.
    config.ffmpeg.let { <!KOTRAIL_STRING_NAME_HIDES_KIND!>runner<!> -> runTool(runner) }
}

fun collision(<!KOTRAIL_STRING_NAME_HIDES_KIND!>appData<!>: String) {
    // Reported, and renamed although "appDataDir" occurs in a string literal and this comment: appDataDir.
    println("appDataDir")
    File(appData, "x")
}

/* GENERATED_FIR_TAGS: classDeclaration, flexibleType, functionDeclaration, javaFunction, lambdaLiteral, localProperty,
primaryConstructor, propertyDeclaration, stringLiteral */
