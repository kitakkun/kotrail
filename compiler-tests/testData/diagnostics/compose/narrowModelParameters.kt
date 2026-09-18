// KOTRAIL_CONFIG: rules.narrowModelParameters=on
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Default: composables only, at most 3 unread properties per data-class parameter.

data class User(
    val id: Long,
    val name: String,
    val email: String,
    val avatarUrl: String,
    val bio: String,
    val followers: Int,
)

data class Pair2(val first: String, val second: String)

// Reported: reads 2 of 6 (4 unread).
@Composable
fun UserCard(<!KOTRAIL_MODEL_PARAMETER_TOO_WIDE!>user<!>: User) {
    Column {
        Text(user.name)
        Text(user.avatarUrl)
    }
}

// Reported: reads nothing at all.
@Composable
fun Placeholder(<!KOTRAIL_MODEL_PARAMETER_TOO_WIDE!>user<!>: User) {
    Text("placeholder")
}

// Not reported: reads 3 of 6 (3 unread, within the limit).
@Composable
fun UserRow(user: User) {
    Column {
        Text(user.name)
        Text(user.email)
        Text("${user.followers}")
    }
}

// Not reported: the whole object is passed on; the callee is checked on its own.
@Composable
fun UserScreen(user: User) {
    Column {
        Text(user.name)
        UserCard(user)
    }
}

// Not reported: the whole object is used through a member call.
@Composable
fun Copied(user: User) {
    Text(user.copy(name = "x").name)
}

// Not reported: the whole object is destructured.
@Composable
fun Destructured(user: User) {
    val (id, name) = user
    Text("$id $name")
}

// Not reported: nested reads count for the outer parameter's property.
data class Profile(val user: User, val theme: String, val badges: List<String>, val premium: Boolean, val locale: String)

@Composable
fun ProfileHeader(profile: Profile) {
    Text(profile.user.name)
    Text(profile.theme)
}

// Not reported: small model, cannot exceed the limit.
@Composable
fun PairView(pair: Pair2) {
    Text(pair.first)
}

// Not reported: not a data class.
class Service(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int)

@Composable
fun ServiceView(service: Service) {
    Text("${service.a}")
}

// Not reported: plain functions are outside the default scope.
fun describe(user: User): String = user.name

/* GENERATED_FIR_TAGS: classDeclaration, data, destructuringDeclaration, functionDeclaration, lambdaLiteral,
localProperty, primaryConstructor, propertyDeclaration, stringLiteral */
