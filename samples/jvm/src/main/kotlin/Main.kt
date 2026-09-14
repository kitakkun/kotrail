fun main() {
    val counter = Counter()
    repeat(3) { counter.increment() }
    println("hello from sample: history=${counter.history}, current=${counter.current}")
}
