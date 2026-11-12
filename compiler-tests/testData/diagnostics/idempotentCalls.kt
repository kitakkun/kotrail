// KOTRAIL_CONFIG: rules.idempotent=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import com.kitakkun.kotrail.idempotency.Idempotent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class Store(private val scope: CoroutineScope) {
    val history = mutableListOf<String>()
    val pending = ArrayList<String>()
    val seen = mutableSetOf<String>()
    val byId = mutableMapOf<Int, String>()
    val log = StringBuilder()
    val events = Channel<String>()
    val shared = MutableSharedFlow<String>()
    val state = MutableStateFlow("")
    val hits = AtomicInteger()
    val listeners = mutableSetOf<() -> Unit>()

    // Each of these does something again on a second call.
    @Idempotent
    suspend fun repeats(item: String, out: MutableList<String>) {
        <!KOTRAIL_NOT_IDEMPOTENT!>history.add(item)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>history.add(0, item)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>history.addAll(listOf(item))<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>history += item<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>history.removeAt(0)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>pending.add(item)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>out.add(item)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>log.append(item)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>events.send(item)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>events.trySend(item)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>shared.emit(item)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>shared.tryEmit(item)<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>scope.launch { }<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>scope.async { item }<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>hits.incrementAndGet()<!>
        <!KOTRAIL_NOT_IDEMPOTENT!>hits.getAndAdd(2)<!>
        val alias = history
        <!KOTRAIL_NOT_IDEMPOTENT!>alias.add(item)<!>
        if (item.isNotEmpty()) {
            listOf(item).forEach { <!KOTRAIL_NOT_IDEMPOTENT!>history.add(it)<!> }
        }
    }

    // Each of these leaves the same state however often it runs.
    @Idempotent
    suspend fun settles(id: Int, item: String) {
        seen.add(item)
        seen += item
        byId.put(id, item)
        byId[id] = item
        byId.remove(id)
        history.remove(item)
        history[0] = item
        history.clear()
        state.emit(item)
        state.value = item
        hits.set(3)
        val local = mutableListOf<String>()
        local.add(item)
        local += item
        val text = StringBuilder()
        text.append(item)
        val built = buildString { append(item) }
        val list = buildList { add(item) }
        ArrayList<String>().apply { add(item) }
        mutableListOf<String>().also { it.add(item) }
        coroutineScope {
            launch { }
        }
        // Runs when a listener fires, not when this function is called.
        listeners.add { history.add(item) }
        val unknown = describe(item)
    }

    // A call that is not in the table passes outside strict mode.
    fun describe(item: String): String {
        history.add(item)
        return item
    }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, flexibleType, functionDeclaration, functionalType, ifExpression,
integerLiteral, javaFunction, lambdaLiteral, localProperty, nullableType, primaryConstructor, propertyDeclaration,
stringLiteral, suspend */
