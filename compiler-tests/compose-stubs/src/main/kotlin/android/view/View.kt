package android.view

import android.content.Context

open class View(context: Context) {
    fun interface OnClickListener {
        fun onClick(v: View)
    }

    fun setOnClickListener(l: OnClickListener?) {}

    fun performClick(): Boolean = false
}
