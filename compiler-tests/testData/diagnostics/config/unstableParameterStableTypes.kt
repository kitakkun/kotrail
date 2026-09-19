// KOTRAIL_CONFIG: rules.compose.noUnstableParameter=on, rules.compose.noUnstableParameter.stableTypes=java.io.File, stability.*.Item, stability.model.Box<*,_>
// Types the project declares stable are stable; a wildcard stands for one segment, and a mask says
// which type arguments still count.
package stability.model

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import java.io.File
import java.util.Date

class Counter(var count: Int)
class Item(var name: String)
class Box<A, B>(var first: A, var second: B)

@Composable
fun Declared(file: File, item: Item, box: Box<Int, Counter>) {
    Text("$file $item $box")
}

@Composable
fun StillUnstable(
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>date<!>: Date,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>counter<!>: Counter,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>box<!>: Box<Counter, Int>,
) {
    Text("$date $counter $box")
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, nullableType, primaryConstructor, propertyDeclaration,
stringLiteral, typeParameter */
