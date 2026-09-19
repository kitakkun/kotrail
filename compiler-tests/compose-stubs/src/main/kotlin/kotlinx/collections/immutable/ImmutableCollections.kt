// Stand-ins for kotlinx.collections.immutable, which the Compose compiler treats as stable when
// their element types are.
package kotlinx.collections.immutable

interface ImmutableCollection<out E> : Collection<E>

interface ImmutableList<out E> : List<E>, ImmutableCollection<E>

interface ImmutableMap<K, out V> : Map<K, V>
