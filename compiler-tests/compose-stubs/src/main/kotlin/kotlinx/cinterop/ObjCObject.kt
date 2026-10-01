// Stubs mirroring the Kotlin/Native Objective-C interop, enough for the native rules to see the
// same fully qualified names they see in an iOS compilation. Only the names, and which classes
// carry ExternalObjCClass, matter to the plugin.
package kotlinx.cinterop

interface ObjCObject

abstract class ObjCObjectBase : ObjCObject

fun ObjCObject.objcPtr(): Long = 0L

/**
 * Marks a class or protocol that cinterop generated from Objective-C, as on every class and
 * protocol of a platform library. The defaults mirror the real declaration, which cinterop
 * leaves out when it has no value to write.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ExternalObjCClass(val protocolGetter: String = "", val binaryName: String = "")
