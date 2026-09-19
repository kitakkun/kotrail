// Stub of the annotation the Compose compiler writes onto every class it compiles, carrying the
// bitmask of type parameters that take part in the class's stability.
package androidx.compose.runtime.internal

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class StabilityInferred(val parameters: Int)
