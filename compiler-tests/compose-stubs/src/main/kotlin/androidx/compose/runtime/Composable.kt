package androidx.compose.runtime

@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPE,
    AnnotationTarget.TYPE_PARAMETER,
    AnnotationTarget.PROPERTY_GETTER,
)
@Retention(AnnotationRetention.BINARY)
annotation class Composable
