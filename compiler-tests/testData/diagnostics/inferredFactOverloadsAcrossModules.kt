// IGNORE_DEXING
// The library's facts travel in its facts file, keyed by declaration and signature: of each pair of
// overloads only the one that starts work is reported, whatever tells the pair apart (parameter
// types, a type alias, a type parameter, a vararg, an extension receiver, the class of a member).

// MODULE: lib
// KOTRAIL_CONFIG: rules.delayForCompletion=on
// FILE: Manager.kt
package lib.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

typealias Retries = Int

class Manager(private val scope: CoroutineScope) {
    fun reconnect(times: Int) {
        scope.launch { }
    }

    fun reconnect(label: String) {}

    fun retry(times: Retries) {
        scope.launch { }
    }

    fun retry(times: Long) {}

    fun <T> submit(value: T) {
        scope.launch { }
    }

    fun submit(values: List<String>) {}

    fun many(vararg names: String) {
        scope.launch { }
    }

    fun many(names: List<String>) {}

    class Inner(private val scope: CoroutineScope) {
        fun reconnect(times: Int) {}
    }

    companion object {
        fun reconnect(scope: CoroutineScope) {
            scope.launch { }
        }
    }
}

fun CoroutineScope.kick(times: Int) {
    launch { }
}

fun Manager.kick(times: Int) {}

// MODULE: main(lib)
// KOTRAIL_CONFIG: rules.delayForCompletion=on
// FILE: Client.kt
package app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import lib.net.Manager
import lib.net.kick

suspend fun byParameterType(manager: Manager) {
    manager.reconnect(3)
    <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(500)<!>
    manager.reconnect("label")
    delay(500)
}

suspend fun byTypeAlias(manager: Manager) {
    manager.retry(3)
    <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(500)<!>
    manager.retry(3L)
    delay(500)
}

suspend fun byTypeParameter(manager: Manager) {
    manager.submit(1)
    <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(500)<!>
    manager.submit(listOf("a"))
    delay(500)
}

suspend fun byVararg(manager: Manager) {
    manager.many("a", "b")
    <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(500)<!>
    manager.many(listOf("a"))
    delay(500)
}

suspend fun byOwner(manager: Manager, scope: CoroutineScope) {
    Manager.Inner(scope).reconnect(1)
    delay(500)
    Manager.reconnect(scope)
    <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(500)<!>
}

suspend fun byReceiver(manager: Manager, scope: CoroutineScope) {
    manager.kick(1)
    delay(500)
    scope.kick(1)
    <!KOTRAIL_DELAY_WAITS_FOR_ASYNC_WORK!>delay(500)<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, companionObject, funWithExtensionReceiver, functionDeclaration, integerLiteral,
lambdaLiteral, nestedClass, nullableType, objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral,
suspend, typeAliasDeclaration, typeParameter, vararg */
