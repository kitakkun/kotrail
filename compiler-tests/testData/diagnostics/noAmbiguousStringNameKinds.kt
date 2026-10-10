// KOTRAIL_CONFIG: rules.noAmbiguousStringName=on
// RENDER_DIAGNOSTICS_FULL_TEXT
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.sql.Statement
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.decodeFromString

fun users() {
    // Reported, with a fix to 'baseUrl': the start of a template that URI parses stays a URL.
    val <!KOTRAIL_STRING_NAME_HIDES_KIND!>base<!> = "https://example.com/api"
    URI("$base/users")
}

// Not reported: the names carry 'url' and 'link'.
fun pages(homeUrl: String, link: String) = URI(homeUrl) to URI.create(link)

// Not reported: the first of several parts of URI is a scheme, not the URL.
fun parts(scheme: String, host: String, path: String) = URI(scheme, host, path, null)

// Not reported: 'pattern' is a regex word.
fun matches(pattern: String, text: String) = Regex(pattern).matches(text)

// Reported: the text Regex compiles, under a name that does not say so.
fun check(<!KOTRAIL_STRING_NAME_HIDES_KIND!>rule<!>: String, text: String) = Regex(rule).matches(text)

// Reported: the receiver of toRegex.
fun split(<!KOTRAIL_STRING_NAME_HIDES_KIND!>separator<!>: String, text: String) = text.split(separator.toRegex())

// Reported: the text decodeFromString reads, as its reified extension and as the member with a deserializer.
fun load(format: StringFormat, <!KOTRAIL_STRING_NAME_HIDES_KIND!>raw<!>: String): List<String> = format.decodeFromString<List<String>>(raw)

fun loadWith(format: StringFormat, deserializer: DeserializationStrategy<List<String>>, <!KOTRAIL_STRING_NAME_HIDES_KIND!>cached<!>: String) =
    format.decodeFromString(deserializer, cached)

// Not reported: 'body' is a json word.
fun loadBody(format: StringFormat, body: String): List<String> = format.decodeFromString<List<String>>(body)

// Reported: UUID.fromString reads an identifier.
fun session(<!KOTRAIL_STRING_NAME_HIDES_KIND!>token<!>: String) = UUID.fromString(token)

// Not reported: the name says it is an id.
fun current(sessionId: String) = UUID.fromString(sessionId)

// Reported: a date in text.
fun day(<!KOTRAIL_STRING_NAME_HIDES_KIND!>input<!>: String) = LocalDate.parse(input)

// Not reported: 'date' is a date word.
fun isoDay(isoDate: String) = LocalDate.parse(isoDate)

// Reported: an abbreviation is no format word.
fun formatter(<!KOTRAIL_STRING_NAME_HIDES_KIND!>fmt<!>: String) = DateTimeFormatter.ofPattern(fmt)

// Not reported: 'pattern' is a format word.
fun dateFormatter(datePattern: String) = DateTimeFormatter.ofPattern(datePattern)

// Not reported: 'server' is a host word.
fun resolve(server: String) = InetAddress.getByName(server)

// Reported: the host InetAddress resolves and the host a Socket connects to.
fun lookup(<!KOTRAIL_STRING_NAME_HIDES_KIND!>target<!>: String) = InetAddress.getByName(target)

fun connect(<!KOTRAIL_STRING_NAME_HIDES_KIND!>remote<!>: String, port: Int) = Socket(remote, port)

// Not reported: 'variable' is a key name word.
fun env(variable: String) = System.getenv(variable)

// Reported: the name of an environment variable.
fun secret(<!KOTRAIL_STRING_NAME_HIDES_KIND!>api<!>: String) = System.getenv(api)

// Reported: the class Class.forName loads; a constant's suggestion is in its own case.
fun loadClass(<!KOTRAIL_STRING_NAME_HIDES_KIND!>impl<!>: String) = Class.forName(impl)

private const val <!KOTRAIL_STRING_NAME_HIDES_KIND!>IMPL<!> = "com.acme.Impl"

fun loadDefault() = Class.forName(IMPL)

// Not reported: the name says it is a class name.
fun loadNamed(implClassName: String) = Class.forName(implClassName)

// Reported: the SQL a statement runs.
fun select(statement: Statement, <!KOTRAIL_STRING_NAME_HIDES_KIND!>q<!>: String) = statement.executeQuery(q)

// Not reported: the name says it is SQL.
fun selectAll(statement: Statement, sql: String) = statement.executeQuery(sql)

// Not reported: a value that reaches a URL API and a file API passes with a word of either.
fun mirror(sourceUrl: String) {
    URI(sourceUrl)
    File(sourceUrl).exists()
}

// Reported: with neither, the suggestion follows the URL API it reaches first.
fun copy(<!KOTRAIL_STRING_NAME_HIDES_KIND!>source<!>: String) {
    URI(source)
    File(source).exists()
}

/* GENERATED_FIR_TAGS: const, flexibleType, functionDeclaration, javaFunction, localProperty, nullableType,
propertyDeclaration, starProjection, stringLiteral */
