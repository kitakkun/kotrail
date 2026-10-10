// KOTRAIL_CONFIG: rules.nameSaysType=on, rules.nameSaysType.publicParameters=false
// With publicParameters=false, the value parameters of public and protected functions and
// constructors are left alone, a public constructor's val and var parameters included: renaming
// one breaks callers that pass the argument by name. Everything else is checked as before.

class UserRepository
class LoginViewModel

// Not reported: a public function's parameter.
fun load(repo: UserRepository): UserRepository = repo

// Not reported: a public constructor's properties, private or not, and its plain parameter.
class Presenter(val repo: UserRepository, private val store: UserRepository, vm: LoginViewModel) {
    val viewModel = vm

    // Not reported: a public member function's parameter.
    fun reload(repo: UserRepository): UserRepository = repo
}

open class Screen {
    // Not reported: a protected function's parameter is public API too.
    protected fun bind(vm: LoginViewModel): LoginViewModel = vm

    // Reported: a private or internal function's parameter.
    private fun cache(<!KOTRAIL_NAME_HIDES_TYPE!>repo<!>: UserRepository): UserRepository = repo
    internal fun warm(<!KOTRAIL_NAME_HIDES_TYPE!>repo<!>: UserRepository): UserRepository = cache(repo)
}

private class Helper {
    // Reported: a public function of a private class is not public API.
    fun use(<!KOTRAIL_NAME_HIDES_TYPE!>repo<!>: UserRepository): UserRepository = repo
}

// Reported: a public class's private constructor.
class Factory private constructor(<!KOTRAIL_NAME_HIDES_TYPE!>repo<!>: UserRepository) {
    val userRepository = repo
}

class Holder {
    // Reported: a property declared in the class body is not a parameter.
    val <!KOTRAIL_NAME_HIDES_TYPE!>repo<!> = UserRepository()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, primaryConstructor, propertyDeclaration */
