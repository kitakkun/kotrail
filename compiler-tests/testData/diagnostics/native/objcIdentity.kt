// KOTRAIL_CONFIG: rules.native.objcIdentity=on

package custom

import kotlin.native.ref.WeakReference
import kotlinx.cinterop.objcPtr
import platform.UIKit.UIFocusItemScrollableContainer
import platform.UIKit.UIScrollViewDelegateProtocol
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.darwin.NSObject

class Registration(val window: UIWindow)

class Plain(val id: Int)

fun sameWindow(a: UIWindow, b: UIWindow?): Boolean {
    // Reported: two wrappers of one window are not identical.
    if (<!KOTRAIL_OBJC_IDENTITY_COMPARISON!>a === b<!>) return true
    // Reported: the negated form.
    if (<!KOTRAIL_OBJC_IDENTITY_COMPARISON!>a !== b<!>) return false
    // Not reported: isEqual: is pointer equality for NSObject.
    if (a == b) return true
    // Not reported: addresses compare the objects themselves.
    return a.objcPtr() == b.objcPtr()
}

fun find(registrations: List<Registration>, window: UIWindow): Registration? =
    // Reported: the window reached through a property is an Objective-C object too.
    registrations.firstOrNull { <!KOTRAIL_OBJC_IDENTITY_COMPARISON!>it.window === window<!> }

fun protocolTyped(a: UIFocusItemScrollableContainer, b: Any): Boolean =
    // Reported: an Objective-C protocol type on one side is enough.
    <!KOTRAIL_OBJC_IDENTITY_COMPARISON!>a === b<!>

fun view(v: UIView, w: UIWindow): Boolean =
    // Reported: a view compared with its window.
    <!KOTRAIL_OBJC_IDENTITY_COMPARISON!>v.window === w<!>

fun nullCheck(a: NSObject?): Boolean =
    // Not reported: comparing with null is a null check, not an identity question.
    a === null

fun kotlinObjects(a: Plain, b: Plain): Boolean =
    // Not reported: Kotlin objects have one identity.
    a === b

class Holder(window: UIWindow, plain: Plain) {
    // Reported: the wrapper is collected while the window lives.
    val weakWindow = <!KOTRAIL_OBJC_WEAK_REFERENCE!>WeakReference(window)<!>

    // Not reported: a Kotlin object is what the weak reference is for.
    val weakPlain = WeakReference(plain)

    // Not reported: the strong reference, or the address.
    val strongWindow = window
    val address = window.objcPtr()
}

// A delegate defined in Kotlin: an Objective-C object whose Kotlin side owns it.
class KotlinDelegate : NSObject(), UIScrollViewDelegateProtocol

class Delegates(delegate: UIScrollViewDelegateProtocol) {
    // Not reported: an instance of a Kotlin-defined subclass keeps its one Kotlin identity.
    val weakKotlin = WeakReference(KotlinDelegate())

    // Reported: typed as the protocol, the parameter can hold an object from Objective-C.
    val weakParameter = <!KOTRAIL_OBJC_WEAK_REFERENCE!>WeakReference(delegate)<!>
}

fun localDelegate(): WeakReference<UIScrollViewDelegateProtocol> {
    // Not reported: the local is read through to its initializer, a Kotlin-defined class.
    val delegate: UIScrollViewDelegateProtocol = KotlinDelegate()
    return WeakReference(delegate)
}

// Not reported: a val of this file is read through as well, whatever type it declares.
val sharedDelegate: NSObject = KotlinDelegate()
val weakShared = WeakReference(sharedDelegate)

fun narrowed(delegate: UIScrollViewDelegateProtocol): WeakReference<UIScrollViewDelegateProtocol>? =
    // Not reported: the smart cast narrows the argument to the Kotlin-defined class.
    if (delegate is KotlinDelegate) WeakReference(delegate) else null

fun parameterDelegate(delegate: UIScrollViewDelegateProtocol) =
    // Reported: the concrete class behind a parameter is unknown.
    <!KOTRAIL_OBJC_WEAK_REFERENCE!>WeakReference(delegate)<!>

fun twoHops(): WeakReference<UIScrollViewDelegateProtocol> {
    // Reported: one hop only, and this local's initializer reads another local.
    val first: UIScrollViewDelegateProtocol = KotlinDelegate()
    val second = first
    return <!KOTRAIL_OBJC_WEAK_REFERENCE!>WeakReference(second)<!>
}

/* GENERATED_FIR_TAGS: classDeclaration, equalityExpression, functionDeclaration, ifExpression, lambdaLiteral,
nullableType, primaryConstructor, propertyDeclaration, safeCall, smartcast */
