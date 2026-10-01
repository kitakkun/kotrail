package androidx.compose.runtime.annotation

/** Marks a class whose instances must not be retained, because retaining one leaks. */
@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class DoNotRetain
