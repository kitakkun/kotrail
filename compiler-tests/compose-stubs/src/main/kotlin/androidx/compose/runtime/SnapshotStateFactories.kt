// Stubs mirroring the factories of the snapshot collections in androidx.compose.runtime.
package androidx.compose.runtime

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.snapshots.SnapshotStateSet

fun <T> mutableStateListOf(): SnapshotStateList<T> = SnapshotStateList()

fun <T> mutableStateListOf(vararg elements: T): SnapshotStateList<T> = SnapshotStateList<T>().also { it.addAll(elements) }

fun <T> Collection<T>.toMutableStateList(): SnapshotStateList<T> = SnapshotStateList<T>().also { it.addAll(this) }

fun <K, V> mutableStateMapOf(): SnapshotStateMap<K, V> = SnapshotStateMap()

fun <K, V> mutableStateMapOf(vararg pairs: Pair<K, V>): SnapshotStateMap<K, V> = SnapshotStateMap<K, V>().also { it.putAll(pairs) }

fun <K, V> Iterable<Pair<K, V>>.toMutableStateMap(): SnapshotStateMap<K, V> = SnapshotStateMap<K, V>().also { it.putAll(this) }

fun <T> mutableStateSetOf(): SnapshotStateSet<T> = SnapshotStateSet()

fun <T> mutableStateSetOf(vararg elements: T): SnapshotStateSet<T> = SnapshotStateSet<T>().also { it.addAll(elements) }
