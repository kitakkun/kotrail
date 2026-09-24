// KOTRAIL_CONFIG: rules.compose.previewParameter=on
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider

enum class Plan { Free, Pro }
data class User(val name: String, val plan: Plan)
data class Order(val id: Int, val paid: Boolean)
data class UiState(val users: List<User>)

@Composable
fun UserCard(user: User, modifier: Modifier = Modifier) {
    Text(user.name)
}

@Composable
fun OrderList(orders: List<Order>) {
    Text("${orders.size}")
}

@Composable
fun Screen(state: UiState, content: @Composable () -> Unit) {
    Text("${state.users.size}")
    content()
}

// Reported: the model is built by hand in the preview.
@Preview
@Composable
private fun UserCardPreview() {
    UserCard(user = <!KOTRAIL_PREVIEW_MODEL_BUILT_INLINE!>User("Ada", Plan.Pro)<!>)
}

// Reported: a model inside a collection, and inside another model, count too; one finding per preview.
@Preview
@Composable
private fun OrderListPreview() {
    OrderList(orders = <!KOTRAIL_PREVIEW_MODEL_BUILT_INLINE!>listOf(Order(1, paid = true), Order(2, paid = false))<!>)
    Screen(state = UiState(users = listOf(User("Grace", Plan.Free)))) { Text("nested") }
}

class UserProvider : PreviewParameterProvider<User> {
    override val values: Sequence<User> = sequenceOf(User("Ada", Plan.Pro), User("Grace", Plan.Free))
}

// Not reported: the model comes from a provider.
@Preview
@Composable
private fun UserCardProvidedPreview(@PreviewParameter(UserProvider::class) user: User) {
    UserCard(user = user)
}

// Not reported: strings, enum entries and a Modifier are not models, and a model built inside a
// content lambda is a slot's business.
@Preview
@Composable
private fun ScreenPreview(@PreviewParameter(UserProvider::class) user: User) {
    Screen(state = UiState(users = listOf(user))) {
        UserCard(user = User("Slot", Plan.Free), modifier = Modifier)
    }
}

@Preview
@Composable
private fun TextPreview() {
    Text("plain")
}

// Not reported: not a preview.
@Composable
fun NotAPreview() {
    UserCard(user = User("Ada", Plan.Pro))
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, data, enumDeclaration, enumEntry, functionDeclaration,
functionalType, integerLiteral, lambdaLiteral, override, primaryConstructor, propertyDeclaration, stringLiteral */
