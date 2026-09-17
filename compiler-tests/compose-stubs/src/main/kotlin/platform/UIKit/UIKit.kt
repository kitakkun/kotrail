package platform.UIKit

import platform.darwin.NSObject

open class UIView : NSObject() {
    var window: UIWindow? = null
}

open class UIWindow : UIView()

interface UIFocusItemScrollableContainer : kotlinx.cinterop.ObjCObject
