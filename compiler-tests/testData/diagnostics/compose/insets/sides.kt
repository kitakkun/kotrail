// KOTRAIL_CONFIG: rules.compose.windowInsets=true
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsEndWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsStartWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kitakkun.kotrail.compose.insets.HandlesWindowInsets
import com.kitakkun.kotrail.compose.insets.WindowInsetsSide
import com.kitakkun.kotrail.compose.insets.WindowInsetsType

// Start and End are layout-direction aware, exactly as in WindowInsetsSides:
//   Left  = left in LTR  + left in RTL
//   Start = left in LTR  + right in RTL
// so handling Start leaves the left edge unhandled in RTL layouts.

// Satisfied: Left handled by Left.
@HandlesWindowInsets(WindowInsetsType.DisplayCutout, sides = [WindowInsetsSide.Left])
@Composable
fun LeftByLeft() {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Left))) { Text("ok") }
}

// Not satisfied: Left contract, Start handled (missing "left in RTL").
@HandlesWindowInsets(WindowInsetsType.DisplayCutout, sides = [WindowInsetsSide.Left])
@Composable
fun <!WINDOW_INSETS_NOT_HANDLED!>LeftByStart<!>() {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))) { Text("rtl gap") }
}

// Satisfied: Start contract, Start handled through the width modifier.
@HandlesWindowInsets(WindowInsetsType.DisplayCutout, sides = [WindowInsetsSide.Start])
@Composable
fun StartByStartWidth() {
    Column(modifier = Modifier.windowInsetsStartWidth(WindowInsets.displayCutout)) { Text("ok") }
}

// Satisfied: Horizontal covers Start and End in both directions.
@HandlesWindowInsets(WindowInsetsType.DisplayCutout, sides = [WindowInsetsSide.Start, WindowInsetsSide.End])
@Composable
fun StartEndByHorizontal() {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) { Text("ok") }
}

// Satisfied: Start + End together equal Horizontal, so Left and Right are covered too.
@HandlesWindowInsets(WindowInsetsType.DisplayCutout, sides = [WindowInsetsSide.Horizontal])
@Composable
fun HorizontalByStartPlusEnd() {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start + WindowInsetsSides.End))) { Text("ok") }
}

// Not satisfied: Horizontal contract, only End handled.
@HandlesWindowInsets(WindowInsetsType.DisplayCutout, sides = [WindowInsetsSide.Horizontal])
@Composable
fun <!WINDOW_INSETS_NOT_HANDLED!>HorizontalByEnd<!>() {
    Column(modifier = Modifier.windowInsetsEndWidth(WindowInsets.displayCutout)) { Text("missing start") }
}

/* GENERATED_FIR_TAGS: additiveExpression, collectionLiteral, functionDeclaration, lambdaLiteral, stringLiteral */
