package androidx.compose.runtime.snapshots

import androidx.compose.runtime.Stable

@Stable
class SnapshotStateList<T> : MutableList<T> by ArrayList<T>(), RandomAccess

@Stable
class SnapshotStateMap<K, V> : MutableMap<K, V> by LinkedHashMap<K, V>()

@Stable
class SnapshotStateSet<T> : MutableSet<T> by LinkedHashSet<T>()
