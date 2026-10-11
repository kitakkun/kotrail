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
