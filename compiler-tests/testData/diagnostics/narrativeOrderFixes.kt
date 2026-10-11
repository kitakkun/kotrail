// KOTRAIL_CONFIG: rules.narrativeOrder=on, rules.narrativeOrder.maxCallerDistance=10, rules.narrativeOrder.maxHelperNoise=50
// DUMP_KOTRAIL_FIXES
// A fix that moves a helper takes along the private functions that only the moved ones call, transitively, when
// the move would leave them far from their caller or above it, and puts each after a caller. A function that
// something else calls too stays where it is.

// checkNewerRelease is reported far below. Its fix moves it here with fetchVerifyAndInstall, downloadJar and
// isRateLimited, which only it reaches; describe stays, since audit calls it too.
fun update(): String = checkNewerRelease()

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

private fun <!KOTRAIL_HELPER_FAR_FROM_CALLER!>checkNewerRelease<!>(): String =
    if (isRateLimited()) describe("later") else describe(fetchVerifyAndInstall())

private fun fetchVerifyAndInstall(): String = downloadJar() + " installed"

private fun downloadJar(): String = "jar"

private fun isRateLimited(): Boolean = false

private fun describe(state: String): String = "update: $state"

fun audit(): String = describe("audited")

private fun <!KOTRAIL_HELPER_BEFORE_FIRST_USE!>parseHeader<!>(bytes: ByteArray): Int = readMagic(bytes) + checksum(bytes)

private fun readMagic(bytes: ByteArray): Int = bytes.first().toInt()

// parseHeader is reported above. Its fix moves it here with readMagic, which only it calls and which would
// otherwise sit above it; checksum stays, since verify calls it too.
fun load(bytes: ByteArray): Int = parseHeader(bytes)

fun verify(bytes: ByteArray): Boolean = checksum(bytes) == 0

private fun checksum(bytes: ByteArray): Int = bytes.sum()

/* GENERATED_FIR_TAGS: additiveExpression, equalityExpression, functionDeclaration, ifExpression, integerLiteral,
localProperty, multiplicativeExpression, propertyDeclaration, stringLiteral */
