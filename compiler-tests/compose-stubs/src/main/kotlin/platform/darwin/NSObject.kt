package platform.darwin

import kotlinx.cinterop.ExternalObjCClass
import kotlinx.cinterop.ObjCObjectBase

@ExternalObjCClass
open class NSObject : ObjCObjectBase() {
    open fun isEqual(other: Any?): Boolean = this === other
    open fun hash(): Long = 0L
}

@ExternalObjCClass
interface NSObjectProtocol : kotlinx.cinterop.ObjCObject
