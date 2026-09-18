// KOTRAIL_CONFIG: rules.noNotNullAssertion=on, rules.noNotNullAssertion.exclude=extension(kotlin.String) || annotated(custom.Generated) || (suspend && visibility(private)) || composable || test || override

package custom

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.junit.jupiter.api.Test

annotation class Generated

fun plain(x: String?): Int = <!KOTRAIL_NOT_NULL_ASSERTION!>x!!<!>.length

// Not reported: an extension of exactly kotlin.String.
fun String.onString(x: String?): Int = x!!.length

// Reported: an extension of another type.
fun Int.onInt(x: String?): Int = <!KOTRAIL_NOT_NULL_ASSERTION!>x!!<!>.length

// Not reported: annotated, directly or through an enclosing class.
@Generated
fun generated(x: String?): Int = x!!.length

@Generated
class Holder {
    fun inside(x: String?): Int = x!!.length
}

// Not reported: suspend and private, both.
private suspend fun quiet(x: String?): Int = x!!.length

// Reported: suspend but public.
suspend fun loud(x: String?): Int = <!KOTRAIL_NOT_NULL_ASSERTION!>x!!<!>.length

// Not reported: a composable.
@Composable
fun Screen(x: String?) {
    Text(x!!)
}

// Not reported: a test.
class SpecTest {
    @Test
    fun `keeps going when the value is present`(x: String?): Int = x!!.length
}

// Not reported: an override.
interface Parser {
    fun parse(x: String?): Int
}

class StrictParser : Parser {
    override fun parse(x: String?): Int = x!!.length
}

fun use() {
    plain(null); "".onString(null); 0.onInt(null); generated(null); Holder().inside(null)
    Screen(null); SpecTest().`keeps going when the value is present`(null); StrictParser().parse(null)
}

/* GENERATED_FIR_TAGS: annotationDeclaration, checkNotNullCall, classDeclaration, funWithExtensionReceiver,
functionDeclaration, integerLiteral, interfaceDeclaration, nullableType, override, stringLiteral, suspend */
