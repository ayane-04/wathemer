// Activity-scoping utility for hooks that branch on the hosting WA activity.
// A view's context can be a ContextWrapper chain; unwrap until an Activity or null.
package com.wathemer.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View

fun View.findActivity(): Activity? {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
