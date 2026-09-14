// Copy into samples/jvm/src/main/kotlin to see KOTRAIL_PRECONDITION_VIOLATED.
package violations

const val MAX_RETRIES = 5
private const val BASE = 2

fun retry(times: Int) {
    require(times >= 0) { "times must not be negative" }
    println(times)
}

@JvmInline
value class Percent(val value: Int) {
    init {
        require(value in 0..100)
    }
}

fun main() {
    retry(-1)
    val attempts = BASE * 2 - MAX_RETRIES
    retry(attempts)
    Percent(150)
}
