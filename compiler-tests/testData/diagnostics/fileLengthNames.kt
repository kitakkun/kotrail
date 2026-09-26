// KOTRAIL_CONFIG: rules.fileLength=on, rules.fileLength.maxLines=0, rules.fileLength.maxTopLevelDeclarations=4
<!KOTRAIL_FILE_TOO_FLAT!>package custom<!>

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// Four names fit: a class, a function with two overloads (one name), a public property, a type alias.
// A private property is a constant of the file and does not count.
private val PADDING = 8

class Config(val name: String)

fun load(name: String): Config = Config(name)

fun load(config: Config): Config = config

val DEFAULT = Config("default")

typealias Loader = (String) -> Config

// Reported: the fifth and sixth names, in declaration order; a nested member does not count.
fun <!KOTRAIL_TOO_MANY_TOP_LEVEL_DECLARATIONS!>save<!>(config: Config) {
    fun inner() = config.name
    inner()
}

class <!KOTRAIL_TOO_MANY_TOP_LEVEL_DECLARATIONS!>Store<!> {
    val items = mutableListOf<Config>()
    fun add(config: Config) = items.add(config)
}

// Reported as the seventh name; its preview belongs to it and is not a name of its own.
@Composable
fun <!KOTRAIL_TOO_MANY_TOP_LEVEL_DECLARATIONS!>ConfigCard<!>(config: Config) {
    Text(config.name)
}

@Preview
@Composable
private fun ConfigCardPreview() = ConfigCard(DEFAULT)

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, localFunction, primaryConstructor,
propertyDeclaration, stringLiteral, typeAliasDeclaration */
