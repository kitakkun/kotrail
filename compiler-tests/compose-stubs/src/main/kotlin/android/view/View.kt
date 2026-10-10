// A stand-in for the Android class, so that the Compose fixtures can name it on the JVM: constructed
// from a context, with a library listener interface to implement or convert a lambda to.
package android.view

import android.content.Context

open class View(context: Context) {
    fun interface OnClickListener {
        fun onClick(v: View)
    }

    fun setOnClickListener(l: OnClickListener?) {}

    fun performClick(): Boolean = false
}
