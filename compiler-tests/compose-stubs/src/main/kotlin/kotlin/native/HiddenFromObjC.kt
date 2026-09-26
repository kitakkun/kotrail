package kotlin.native

/** Stands in for the Kotlin/Native annotation that keeps a declaration out of the Objective-C export. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class HiddenFromObjC
