package platform.darwin

import kotlinx.cinterop.ObjCObjectBase

open class NSObject : ObjCObjectBase() {
    open fun isEqual(other: Any?): Boolean = this === other
    open fun hash(): Long = 0L
}

interface NSObjectProtocol : kotlinx.cinterop.ObjCObject
