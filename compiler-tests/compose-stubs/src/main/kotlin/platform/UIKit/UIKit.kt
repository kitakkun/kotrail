package platform.UIKit

import kotlinx.cinterop.ExternalObjCClass
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

@ExternalObjCClass
open class UIView : NSObject() {
    var window: UIWindow? = null
}

@ExternalObjCClass
open class UIWindow : UIView()

@ExternalObjCClass
interface UIFocusItemScrollableContainer : kotlinx.cinterop.ObjCObject

@ExternalObjCClass
interface UIScrollViewDelegateProtocol : NSObjectProtocol
