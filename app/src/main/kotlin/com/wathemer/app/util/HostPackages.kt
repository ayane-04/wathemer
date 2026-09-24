// The WhatsApp packages this module themes.
package com.wathemer.app.util

object HostPackages {

    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    /** Both hosts, in the order the settings app should prefer when only one is wanted. */
    val ALL = listOf(CONSUMER, BUSINESS)

    /** Exact name, never a prefix: BUSINESS starts with CONSUMER and startsWith would admit it by accident. */
    fun isHost(pkg: String?): Boolean = pkg == CONSUMER || pkg == BUSINESS
}
