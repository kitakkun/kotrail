package kotlin.native.ref

class WeakReference<T : Any>(referred: T) {
    private var value: T? = referred

    fun get(): T? = value
}
