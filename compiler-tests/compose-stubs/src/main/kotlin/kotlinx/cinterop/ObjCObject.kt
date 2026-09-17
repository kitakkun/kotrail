// Stubs mirroring the Kotlin/Native Objective-C interop, enough for the native rules to see the
// same fully qualified names they see in an iOS compilation. Only the names matter to the plugin.
package kotlinx.cinterop

interface ObjCObject

abstract class ObjCObjectBase : ObjCObject

fun ObjCObject.objcPtr(): Long = 0L
