// KOTRAIL_CONFIG: rules.preferExplicitBackingField=false, rules.narrowModelParameters=false, rules.noPassThroughReturn=false, rules.preferFunctionReferences=false, rules.commentLength=false, rules.noFqnReferences=false, rules.noRedundantElse=false, rules.preferValueClass=false, rules.forbiddenCall=false, rules.noNotNullAssertion=false, rules.noSwallowedCancellation=false, rules.noIgnoredException=false, rules.preferExpressionBody=false, rules.noMutableCollectionInPublicApi=false, rules.namedArgumentsForRepeatedTypes=false, rules.compose.noTrailingCallback=false, rules.compose.naming=false, rules.compose.modifierParameter=false, rules.compose.namedCallbackArguments=false, rules.compose.previewRequired=false, rules.compose.composablesPerFile=false, rules.mustBeSerializable=false, rules.noUnimplemented=false, rules.preconditions=false, rules.compose.windowInsets=false, rules.compose.nesting=false, rules.compose.stateDelegation=false
// One
// Two
// Three
// Four
// Five
// Six: a run past the limit that the disabled comment rule does not report.
// Every rule is switched off, so none of the violations below is reported. This is the shape
// of a test-source-set configuration: pass a different properties file (or options) to
// compileTestKotlin.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

class Cases {
    private val _a = mutableListOf<String>()
    val a: List<String> get() = _a
}

@HandlesWindowInsets(WindowInsetsType.SafeDrawing)
@Composable
fun MissingScreen() {
    Column(modifier = Modifier.statusBarsPadding()) { Text("missing") }
}

@Composable
fun TopBarArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.statusBarsPadding()) { content() }
}

@Composable
fun DoublePadded() {
    TopBarArea(modifier = Modifier.safeDrawingPadding()) { Text("twice") }
}

@Composable
fun Deep() {
    Column { Box { Column { Box { Column { Box { Text("seven") } } } } } }
}

@Composable
fun Counter() {
    val count = remember { mutableStateOf(0) }
    Text("${count.value}")
}

data class Wide(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int)

@Composable
fun WideView(wide: Wide) {
    Text("${wide.a}")
}

fun identity(x: Int) = x

fun forward(list: List<Int>) = list.map { identity(it) }

val id = java.util.UUID.randomUUID()

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, getter, lambdaLiteral, propertyDeclaration,
stringLiteral */
