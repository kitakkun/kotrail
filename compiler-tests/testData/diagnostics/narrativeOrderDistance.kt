// KOTRAIL_CONFIG: rules.narrativeOrder=on, rules.narrativeOrder.maxCallerDistance=10, rules.narrativeOrder.maxHelperNoise=50
// RENDER_DIAGNOSTICS_FULL_TEXT

// Reported: a private top-level val far above its only caller, register at the bottom.
private val <!KOTRAIL_HELPER_FAR_FROM_CALLER!>registry<!> = mutableMapOf<String, Int>()

// Not reported: constants, looked up by name wherever they sit; retryBudget at the bottom reads them.
private const val RETRIES = 3

private val TIMEOUT_MS = 30 * 1000

// The caller of parseHeader and DEFAULTS: both sit more than 10 lines below, past unrelated code
// (parseHeader, between load and DEFAULTS, is load's own and does not count).
fun load(bytes: ByteArray): Int = parseHeader(bytes) + DEFAULTS.size

// The caller of newLoader, which sits far below but right above the class it builds.
fun makeLoader(): Loader = newLoader()

fun render(value: Int): String {
    val doubled = value * 2
    val tripled = value * 3
    val quadrupled = value * 4
    return "$doubled $tripled $quadrupled"
}

fun export(value: Int): String {
    val first = value + 1
    val second = value + 2
    val third = value + 3
    return "$first $second $third"
}

fun describe(value: Int): String {
    val low = value - 1
    val high = value + 1
    return "$low..$high"
}

// Reported once, on parseHeader: a private top-level function far below its only caller, and a
// private top-level val written right after it for the same caller, one run of two.
private fun <!KOTRAIL_HELPER_FAR_FROM_CALLER!>parseHeader<!>(bytes: ByteArray): Int = bytes.size

private val DEFAULTS = listOf(1, 2, 3)

// Not reported: a factory placed by the type it returns, right above it.
private fun newLoader(): Loader = Loader()

class Loader {
    fun read(bytes: ByteArray): Int = decode(bytes)

    fun first(value: Int): Int {
        val a = value + 1
        val b = value + 2
        return a + b
    }

    fun second(value: Int): Int {
        val a = value * 1
        val b = value * 2
        return a + b
    }

    fun third(value: Int): Int {
        val a = value - 1
        val b = value - 2
        return a + b
    }

    // Reported: a private member function far below its only caller in the class.
    private fun <!KOTRAIL_HELPER_FAR_FROM_CALLER!>decode<!>(bytes: ByteArray): Int = bytes.size * 2
}

// Not reported: three helpers below their caller in call order; the third is past the distance
// only because the first two sit between, and they are the caller's own.
fun pipeline(s: String): String = stepThree(stepTwo(stepOne(s)))

private fun stepOne(s: String): String {
    val a = s + "1"
    val b = a + "2"
    return b
}

private fun stepTwo(s: String): String {
    val a = s + "3"
    val b = a + "4"
    return b
}

private fun stepThree(s: String): String = s.reversed()

// Not reported: right after its only caller.
fun trimmed(s: String): String = strip(s)

private fun strip(s: String): String = s.trim()

// Not reported: two callers.
fun upper(s: String): String = shout(s)

fun upperTwice(s: String): String = shout(shout(s))

fun pad1(s: String): String {
    val a = s + "1"
    val b = a + "2"
    return b
}

fun pad2(s: String): String {
    val a = s + "3"
    val b = a + "4"
    return b
}

fun pad3(s: String): String {
    val a = s + "5"
    val b = a + "6"
    return b
}

private fun shout(s: String): String = s.uppercase()

// Reported by the order check only: a helper function before its caller is not measured for distance.
private fun <!KOTRAIL_HELPER_BEFORE_FIRST_USE!>whisper<!>(s: String): String = s.lowercase()

fun pad4(s: String): String {
    val a = s + "7"
    val b = a + "8"
    return b
}

fun pad5(s: String): String {
    val a = s + "9"
    val b = a + "0"
    return b
}

fun pad6(s: String): String {
    val a = s + "a"
    val b = a + "b"
    return b
}

fun quiet(s: String): String = whisper(s)

fun retryBudget(): Int = RETRIES * TIMEOUT_MS

fun register(name: String) {
    registry[name] = 1
}

// Not reported: toolbar's four helpers, written as one run past an unrelated function. playIcon is
// within the distance, and the other three are past it only as members of the run.

fun toolbar(): String = playIcon() + pauseIcon() + stopIcon() + skipIcon()

fun beforeIcons(value: Int): String {
    val first = value + 1
    val second = value + 2
    val third = value + 3
    val fourth = value + 4
    return "$first $second $third $fourth"
}

private fun playIcon(): String = "play"

private fun pauseIcon(): String = "pause"

private fun stopIcon(): String = "stop"

private fun skipIcon(): String = "skip"

// Reported once, on wifiIcon: a run whose first member is past the distance places none of the others,
// and the run is one finding.

fun statusBar(): String = wifiIcon() + batteryIcon()

fun beforeStatusIcons(value: Int): String {
    val first = value + 1
    val second = value + 2
    val third = value + 3
    val fourth = value + 4
    val fifth = value + 5
    return "$first $second $third $fourth $fifth"
}

private fun <!KOTRAIL_HELPER_FAR_FROM_CALLER!>wifiIcon<!>(): String = "wifi"

private fun batteryIcon(): String = "battery"

// Reported once, on homeIcon: a far run of four siblings is one finding, and its fix moves the four together.

fun navigationBar(): String = homeIcon() + searchIcon() + inboxIcon() + profileIcon()

fun beforeNavigationIcons(value: Int): String {
    val first = value + 1
    val second = value + 2
    val third = value + 3
    val fourth = value + 4
    val fifth = value + 5
    return "$first $second $third $fourth $fifth"
}

private fun <!KOTRAIL_HELPER_FAR_FROM_CALLER!>homeIcon<!>(): String = "home"

private fun searchIcon(): String = "search"

private fun inboxIcon(): String = "inbox"

private fun profileIcon(): String = "profile"

// Reported as outside its caller's class: the order check keeps tokenize after Parser, and the rest
// of the class puts it far from parse, its only caller, wherever it sits outside the class.
class Parser {
    fun parse(s: String): Int = tokenize(s).size

    fun reset() {
        tokenCache.clear()
    }

    fun first(value: Int): Int {
        val a = value + 1
        val b = value + 2
        return a + b
    }

    fun second(value: Int): Int {
        val a = value * 1
        val b = value * 2
        return a + b
    }
}

private fun <!KOTRAIL_HELPER_OUTSIDE_CALLER_CLASS!>tokenize<!>(s: String): List<String> = s.split(" ")

// Reported as far, without a fix: a private top-level property used by one member stays outside the
// class, where there is one for the file rather than one per instance.
private val <!KOTRAIL_HELPER_FAR_FROM_CALLER!>tokenCache<!> = mutableMapOf<String, Int>()

// Not reported: a top-level helper of a short class's member, right after the class.
class Greeter {
    fun greet(name: String): String = salute(name)
}

private fun salute(name: String): String = "Hello, " + name

// Not reported: a top-level extension means something for its receiver alone, and stays outside the
// class of its only caller however long that class is.
class Requester {
    fun send(request: Request): Int = request.headersWithBodyDefaults().size

    fun first(value: Int): Int {
        val a = value + 1
        val b = value + 2
        return a + b
    }

    fun second(value: Int): Int {
        val a = value * 1
        val b = value * 2
        return a + b
    }

    fun third(value: Int): Int {
        val a = value - 1
        val b = value - 2
        return a + b
    }
}

private fun Request.headersWithBodyDefaults(): Map<String, String> = headers + ("Content-Type" to "application/json")

class Request(val headers: Map<String, String>)

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, functionDeclaration, integerLiteral, localProperty,
multiplicativeExpression, propertyDeclaration, stringLiteral */
