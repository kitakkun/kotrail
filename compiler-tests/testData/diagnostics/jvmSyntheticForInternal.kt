// KOTRAIL_CONFIG: rules.jvmSyntheticForInternal=on
import kotlin.jvm.JvmSynthetic

// Reported: an internal class stays public on the JVM; nothing hides it.
internal class <!KOTRAIL_INTERNAL_CLASS_VISIBLE_TO_JAVA!>Cache<!>

class Store {
    // Reported: internal members are public to Java unless synthetic.
    internal fun <!KOTRAIL_INTERNAL_VISIBLE_TO_JAVA!>reset<!>() {}
    internal val <!KOTRAIL_INTERNAL_VISIBLE_TO_JAVA!>size<!>: Int = 0
    internal var <!KOTRAIL_INTERNAL_VISIBLE_TO_JAVA!>label<!>: String = ""

    // Not reported: already synthetic, or only the setter is missing it.
    @JvmSynthetic internal fun clear() {}
    @get:JvmSynthetic internal val count: Int = 0
    @get:JvmSynthetic internal var <!KOTRAIL_INTERNAL_VISIBLE_TO_JAVA!>name<!>: String = ""
    @get:JvmSynthetic internal var hidden: String = ""
        private set

    // Not reported: public or private.
    fun open() {}
    private fun closed() {}
}

// Not reported: nothing inside a private class is reachable from Java.
private class Local {
    internal fun step() {}
}

interface Api {
    // Not reported: an interface member cannot be synthetic.
    fun act()
}

// Reported: a top-level internal function.
internal fun <!KOTRAIL_INTERNAL_VISIBLE_TO_JAVA!>helper<!>() {}

/* GENERATED_FIR_TAGS: annotationUseSiteTargetPropertyGetter, classDeclaration, functionDeclaration, integerLiteral,
interfaceDeclaration, propertyDeclaration, stringLiteral */
