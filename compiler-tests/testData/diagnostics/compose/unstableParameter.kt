// KOTRAIL_CONFIG: rules.compose.noUnstableParameter=on
// The verdicts follow the Compose compiler's stability inference; see docs/rules/compose/no-unstable-parameter.md.
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import java.io.File
import java.util.Locale

// A class with a var is unstable; one whose fields are all stable vals is stable.
class Counter(var count: Int)
class Point(val x: Int, val y: Int)
class Empty
class NonCtorVar(value: Int) { var value: Int = value }
class NonCtorVal(value: Int) { val value: Int = value }
class NonFieldVar { var p1: Counter get() = Counter(0); set(value) {} }
class Delegated(value: Int) { var value by mutableStateOf(value) }
class LazyHolder(value: Int) { val square by lazy { value * value } }
class Wrapper<T>(val value: T)
class MutableWrapper<T>(var value: T)
class Nested(val point: Point, val label: String)
class Holder(val counter: Counter)
class ListHolder(val items: List<Int>)
class Recursive(val child: Recursive?)
open class Parent { var age: Int = 0 }
class Child : Parent()
enum class Kind { A, B }
object Singleton
interface Shape
@Stable interface StableShape
@Immutable class Marked(var count: Int)
class MarkedChild : StableShape { var count: Int = 0 }
@JvmInline value class Px(val pixels: Int)
@JvmInline value class UnstableWrapper(val counter: Counter)
@Stable @JvmInline value class StableWrapper(val counter: Counter)
class PairHolder(val pair: Pair<Int, String>)
class Self<T>(val other: Self<T>?, val value: T)

@Composable
fun Stable(
    count: Int,
    name: String?,
    point: Point,
    empty: Empty,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
    nonCtorVal: NonCtorVal,
    nonFieldVar: NonFieldVar,
    delegated: Delegated,
    wrapper: Wrapper<Int>,
    nested: Nested,
    kind: Kind,
    singleton: Singleton,
    stableShape: StableShape,
    marked: Marked,
    markedChild: MarkedChild,
    px: Px,
    stableWrapper: StableWrapper,
    pair: Pair<Int, String>,
    pairHolder: PairHolder,
    immutable: ImmutableList<Int>,
    immutableMap: ImmutableMap<String, Int>,
    state: State<Int>,
    mutableState: MutableState<Int>,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    Text("$count $name $point $empty $onClick $nonCtorVal $nonFieldVar $delegated $wrapper $nested $kind $singleton $stableShape $marked $markedChild $px $stableWrapper $pair $pairHolder $immutable $immutableMap $state $mutableState $locale", modifier)
    content()
}

// Not reported: the stability depends on the caller's type argument. A var is unstable whatever T is.
@Composable
fun <T> Generic(value: T, wrapper: Wrapper<T>, <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>mutableWrapper<!>: MutableWrapper<T>) {
    Text("$value $wrapper $mutableWrapper")
}

@Composable
fun Unstable(
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>counter<!>: Counter,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>nonCtorVar<!>: NonCtorVar,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>lazyHolder<!>: LazyHolder,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>mutableWrapper<!>: MutableWrapper<Int>,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>wrapperOfCounter<!>: Wrapper<Counter>,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>holder<!>: Holder,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>listHolder<!>: ListHolder,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>recursive<!>: Recursive,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>child<!>: Child,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>items<!>: List<Int>,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>map<!>: Map<String, Int>,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>shape<!>: Shape,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>any<!>: Any,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>file<!>: File,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>unstableWrapper<!>: UnstableWrapper,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>pairOfCounter<!>: Pair<Int, Counter>,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>star<!>: Wrapper<*>,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>self<!>: Self<Int>,
    <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>immutableOfCounter<!>: ImmutableList<Counter>,
    vararg <!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>counters<!>: Counter,
) {
    Text("$counter $nonCtorVar $lazyHolder $mutableWrapper $wrapperOfCounter $holder $listHolder $recursive $child $items $map $shape $any $file $unstableWrapper $pairOfCounter $star $self $immutableOfCounter $counters")
}

// Not reported: an override cannot change its parameter types, and a plain function is not composed.
interface Screen {
    @Composable
    fun Content(<!KOTRAIL_COMPOSABLE_UNSTABLE_PARAMETER!>items<!>: List<Int>)
}

class HomeScreen : Screen {
    @Composable
    override fun Content(items: List<Int>) {
        Text("$items")
    }
}

fun plain(items: List<Int>) {
    println(items)
}

/* GENERATED_FIR_TAGS: classDeclaration, enumDeclaration, enumEntry, functionDeclaration, functionalType, getter,
integerLiteral, interfaceDeclaration, lambdaLiteral, multiplicativeExpression, nullableType, objectDeclaration,
outProjection, override, primaryConstructor, propertyDeclaration, propertyDelegate, setter, starProjection,
stringLiteral, typeParameter, value, vararg */
