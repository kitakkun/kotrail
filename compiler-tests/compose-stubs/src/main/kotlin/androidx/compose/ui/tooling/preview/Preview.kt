package androidx.compose.ui.tooling.preview

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
annotation class Preview(val name: String = "")
