// KOTRAIL_CONFIG: rules.compose.modifierParameter=true
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Not reported: the convention. Named `modifier`, defaults to `Modifier`, first optional, applied once.
@Composable
fun Card(title: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        Text(title)
    }
}

// Not reported: a modifier chain is still a single use.
@Composable
fun PaddedCard(title: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(PaddingValues())) {
        Text(title)
    }
}

// Not reported: the explicit companion is the same default.
@Composable
fun ExplicitCompanion(modifier: Modifier = Modifier.Companion) {
    Box(modifier) {
        Text("explicit")
    }
}

// Not reported: optional parameters may follow `modifier`.
@Composable
fun OptionalAfter(title: String, modifier: Modifier = Modifier, subtitle: String = "") {
    Column(modifier) {
        Text(title)
        Text(subtitle)
    }
}

// Reported: two Modifier parameters.
@Composable
fun <!COMPOSABLE_MODIFIER_PARAMETER!>TwoModifiers<!>(modifier: Modifier = Modifier, contentModifier: Modifier = Modifier) {
    Box(modifier) {
        Text("two", contentModifier)
    }
}

// Reported: not named `modifier`.
@Composable
fun <!COMPOSABLE_MODIFIER_PARAMETER!>WrongName<!>(mod: Modifier = Modifier) {
    Box(mod) {
        Text("name")
    }
}

// Reported: no default value.
@Composable
fun <!COMPOSABLE_MODIFIER_PARAMETER!>NoDefault<!>(modifier: Modifier) {
    Box(modifier) {
        Text("default")
    }
}

// Reported: the default is not the `Modifier` companion.
@Composable
fun <!COMPOSABLE_MODIFIER_PARAMETER!>WrongDefault<!>(modifier: Modifier = Modifier.padding(PaddingValues())) {
    Box(modifier) {
        Text("default")
    }
}

// Reported: an optional parameter precedes `modifier`.
@Composable
fun <!COMPOSABLE_MODIFIER_PARAMETER!>OptionalBefore<!>(title: String = "", modifier: Modifier = Modifier) {
    Box(modifier) {
        Text(title)
    }
}

// Reported: `modifier` is never applied.
@Composable
fun <!COMPOSABLE_MODIFIER_PARAMETER!>Unused<!>(modifier: Modifier = Modifier) {
    Text("unused")
}

// Reported: `modifier` is applied to two elements.
@Composable
fun <!COMPOSABLE_MODIFIER_PARAMETER!>UsedTwice<!>(modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(modifier) {
            Text("twice")
        }
    }
}

// Reported: several problems yield a single diagnostic.
@Composable
fun <!COMPOSABLE_MODIFIER_PARAMETER!>Everything<!>(title: String = "", mod: Modifier) {
    Text(title)
}

// Reported: internal composables are part of the module's API.
@Composable
internal fun <!COMPOSABLE_MODIFIER_PARAMETER!>InternalWrongName<!>(mod: Modifier = Modifier) {
    Box(mod) {
        Text("internal")
    }
}

// Not reported: private composables are free to deviate.
@Composable
private fun PrivateWrongName(mod: Modifier) {
    Box(mod) {
        Text("private")
    }
}

// Not reported: local composables are not API.
@Composable
fun Outer() {
    @Composable
    fun Inner(mod: Modifier) {
        Box(mod) {
            Text("inner")
        }
    }
    Inner(Modifier)
}

// Not reported: no Modifier parameter at all; the rule does not demand one.
@Composable
fun Plain(title: String) {
    Text(title)
}

// Not reported: value-returning composables are not UI elements.
@Composable
fun rememberDecorated(base: Modifier): Modifier = base.padding(PaddingValues())

// The interface declaration is reported (name, default); the override inherits its signature.
interface Slot {
    @Composable
    fun <!COMPOSABLE_MODIFIER_PARAMETER!>Content<!>(mod: Modifier)
}

class TextSlot : Slot {
    @Composable
    override fun Content(mod: Modifier) {
        Box(mod) {
            Text("slot")
        }
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral, localFunction,
override, stringLiteral */
