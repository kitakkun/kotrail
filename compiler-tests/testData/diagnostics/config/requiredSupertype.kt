// KOTRAIL_CONFIG: rules.requiredSupertype=on, rules.requiredSupertype.policies=viewModels=class && name(*ViewModel) -> custom.BaseViewModel

package custom

// Not reported: the required type itself matches the name but is not held to its own policy.
abstract class BaseViewModel
abstract class ScreenViewModel : BaseViewModel()
interface Reloadable

// Reported: a *ViewModel that extends nothing.
class <!KOTRAIL_SUPERTYPE_REQUIRED!>SettingsViewModel<!>

// Reported: some other supertype is not the one the policy names.
class <!KOTRAIL_SUPERTYPE_REQUIRED!>ProfileViewModel<!> : Reloadable

// Not reported: extends the base class directly.
class MainViewModel : BaseViewModel()

// Not reported: reaches the base class through an intermediate class.
class HomeViewModel : ScreenViewModel()

// Not reported: the name does not match the policy.
class Helper

// Not reported: an abstract subclass is a subclass all the same.
abstract class LegacyViewModel : BaseViewModel()

/* GENERATED_FIR_TAGS: classDeclaration, interfaceDeclaration */
