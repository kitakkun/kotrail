/** Uses an explicit backing field (Kotlin 2.4+), which is what Kotrail asks for. */
class Counter {
    val history: List<Int>
        field = mutableListOf()

    val current: Int
        get() = history.lastOrNull() ?: 0

    fun increment() {
        history.add(current + 1)
    }
}
