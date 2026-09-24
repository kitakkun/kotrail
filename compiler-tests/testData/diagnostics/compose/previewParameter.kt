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
interface DataModel
class PreviewDataModel(val label: String) : DataModel

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

@Composable
fun Boundary(model: DataModel) {
    Text("boundary")
}

// Reported: two previews of this file build a User by hand, one state each.
@Preview
@Composable
private fun UserCardPreview() {
    UserCard(user = <!KOTRAIL_PREVIEW_MODEL_BUILT_INLINE!>User("Ada", Plan.Pro)<!>)
}

@Preview
@Composable
private fun UserCardFreePreview() {
    UserCard(user = <!KOTRAIL_PREVIEW_MODEL_BUILT_INLINE!>User("Grace", Plan.Free)<!>)
}

// Not reported: a single preview building a single state (minPreviews is 2); a model inside a
// collection would count, but there is nothing to gather.
@Preview
@Composable
private fun OrderListPreview() {
    OrderList(orders = listOf(Order(1, paid = true), Order(2, paid = false)))
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

// Not reported: a stand-in made for previews is not the model the composable shows.
@Preview
@Composable
private fun BoundaryPreview() {
    Boundary(PreviewDataModel("one"))
}

@Preview
@Composable
private fun BoundaryEmptyPreview() {
    Boundary(PreviewDataModel("two"))
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
functionalType, integerLiteral, interfaceDeclaration, lambdaLiteral, override, primaryConstructor, propertyDeclaration,
stringLiteral */
