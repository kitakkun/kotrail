// KOTRAIL_CONFIG: rules.requiredAnnotation=true, requiredAnnotation=screens=composable && name(*Screen) -> custom.Screen, requiredAnnotation=entities=class && name(*Entity) -> custom.Persisted

package custom

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

annotation class Screen
annotation class Persisted

// Reported: a composable named *Screen must carry @Screen.
@Composable
fun <!REQUIRED_ANNOTATION_MISSING!>HomeScreen<!>() {
    Text("home")
}

// Not reported: annotated as the policy asks.
@Screen
@Composable
fun SettingsScreen() {
    Text("settings")
}

// Not reported: the screens policy only covers composables.
fun buildScreen(): String = "not a composable"

// Not reported: the name does not match.
@Composable
fun Home() {
    Text("home")
}

// Reported: a class named *Entity must carry @Persisted.
class <!REQUIRED_ANNOTATION_MISSING!>UserEntity<!>(val id: Int)

// Not reported: the class is annotated, and the policy asks about classes only, so its
// members are left alone even though they sit inside a class named *Entity.
@Persisted
class OrderEntity(val id: Int) {
    fun total(): Int = id * 2
}

// Reported: only the declaration's own annotations count; the enclosing class being a
// @Screen does not annotate the composable inside it.
@Screen
class ScreenHolder {
    @Composable
    fun <!REQUIRED_ANNOTATION_MISSING!>InnerScreen<!>() {
        Text("inner")
    }
}

// Not reported: local declarations are not covered.
fun render() {
    @Composable
    fun LocalScreen() {
        Text("local")
    }
}

fun use() {
    buildScreen(); UserEntity(1).id; OrderEntity(2).total(); ScreenHolder(); render()
}

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, functionDeclaration, integerLiteral, localFunction,
multiplicativeExpression, primaryConstructor, propertyDeclaration, stringLiteral */
