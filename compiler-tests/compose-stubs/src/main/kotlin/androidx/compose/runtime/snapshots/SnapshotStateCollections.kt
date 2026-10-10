// Stubs mirroring the snapshot collections of androidx.compose.runtime.snapshots: lists, maps
// and sets whose contents change in place while the object keeps its identity. Backed by plain
// collections, so that box tests can run code that builds them.
package androidx.compose.runtime.snapshots

import androidx.compose.runtime.Stable

@Stable
class SnapshotStateList<T> : MutableList<T> by ArrayList<T>(), RandomAccess

@Stable
class SnapshotStateMap<K, V> : MutableMap<K, V> by LinkedHashMap<K, V>()

@Stable
class SnapshotStateSet<T> : MutableSet<T> by LinkedHashSet<T>()
