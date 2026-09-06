package com.kitakkun.kotrail.fir.compose.insets

import com.kitakkun.kotrail.compose.insets.InsetsSet
import com.kitakkun.kotrail.compose.insets.Sides
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

object WindowInsetsNames {
    val ANNOTATIONS_PACKAGE = FqName("com.kitakkun.kotrail.compose.insets")
    val HANDLES_WINDOW_INSETS = ClassId(ANNOTATIONS_PACKAGE, Name.identifier("HandlesWindowInsets"))
    val INFERRED_WINDOW_INSETS_HANDLING = ClassId(ANNOTATIONS_PACKAGE, Name.identifier("InferredWindowInsetsHandling"))

    val TYPE_PARAM = Name.identifier("type")
    val SIDES_PARAM = Name.identifier("sides")
    val HANDLED_PARAM = Name.identifier("handled")

    val COMPOSABLE = ClassId(FqName("androidx.compose.runtime"), Name.identifier("Composable"))
    val MODIFIER = ClassId(FqName("androidx.compose.ui"), Name.identifier("Modifier"))

    val FOUNDATION_LAYOUT = FqName("androidx.compose.foundation.layout")
    val WINDOW_INSETS = ClassId(FOUNDATION_LAYOUT, Name.identifier("WindowInsets"))
    val WINDOW_INSETS_SIDES = ClassId(FOUNDATION_LAYOUT, Name.identifier("WindowInsetsSides"))
    val WINDOW_INSETS_SIDES_COMPANION = WINDOW_INSETS_SIDES.createNestedClassId(Name.identifier("Companion"))

    /** `Modifier.xxxPadding()` shorthands in foundation-layout, keyed by function name. */
    val SHORTHAND_PADDING_MODIFIERS: Map<Name, InsetsSet> = listOf(
        "safeDrawingPadding" to "SafeDrawing",
        "safeContentPadding" to "SafeContent",
        "safeGesturesPadding" to "SafeGestures",
        "systemBarsPadding" to "SystemBars",
        "statusBarsPadding" to "StatusBars",
        "navigationBarsPadding" to "NavigationBars",
        "imePadding" to "Ime",
        "displayCutoutPadding" to "DisplayCutout",
        "captionBarPadding" to "CaptionBar",
        "waterfallPadding" to "Waterfall",
        "systemGesturesPadding" to "SystemGestures",
        "mandatorySystemGesturesPadding" to "MandatorySystemGestures",
    ).associate { (fn, type) -> Name.identifier(fn) to InsetsSet.fromTypeName(type)!! }

    /** Functions taking a `WindowInsets` argument (or receiver) whose whole value counts as handled. */
    val INSETS_CONSUMING_FUNCTIONS: Set<Name> = setOf(
        "windowInsetsPadding",
        "consumeWindowInsets",
        "asPaddingValues",
    ).map(Name::identifier).toSet()

    /** Size modifiers that handle a single side of the given insets. */
    val SINGLE_SIDE_FUNCTIONS: Map<Name, Int> = mapOf(
        Name.identifier("windowInsetsTopHeight") to Sides.TOP,
        Name.identifier("windowInsetsBottomHeight") to Sides.BOTTOM,
        Name.identifier("windowInsetsStartWidth") to Sides.START,
        Name.identifier("windowInsetsEndWidth") to Sides.END,
    )

    val ONLY = Name.identifier("only")
    val UNION = Name.identifier("union")
    val ADD = Name.identifier("add")
    val EXCLUDE = Name.identifier("exclude")
    val PLUS = Name.identifier("plus")

    /** Library composables that handle insets by default, keyed by callable. */
    val KNOWN_LIBRARY_COMPOSABLES: Map<CallableId, InsetsSet> = run {
        val material3 = FqName("androidx.compose.material3")
        val systemBars = InsetsSet.fromTypeName("SystemBars")!!
        val topBar = systemBars.only(Sides.TOP or Sides.HORIZONTAL)
        val bottomBar = systemBars.only(Sides.BOTTOM or Sides.HORIZONTAL)
        mapOf(
            CallableId(material3, Name.identifier("Scaffold")) to systemBars,
            CallableId(material3, Name.identifier("TopAppBar")) to topBar,
            CallableId(material3, Name.identifier("CenterAlignedTopAppBar")) to topBar,
            CallableId(material3, Name.identifier("MediumTopAppBar")) to topBar,
            CallableId(material3, Name.identifier("LargeTopAppBar")) to topBar,
            CallableId(material3, Name.identifier("NavigationBar")) to bottomBar,
            CallableId(material3, Name.identifier("ModalBottomSheet")) to bottomBar,
        )
    }
}
