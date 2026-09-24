package androidx.compose.ui.tooling.preview

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
annotation class Preview(val name: String = "")

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class PreviewParameter(val provider: kotlin.reflect.KClass<out PreviewParameterProvider<*>>, val limit: Int = Int.MAX_VALUE)

interface PreviewParameterProvider<T> {
    val values: Sequence<T>
}
