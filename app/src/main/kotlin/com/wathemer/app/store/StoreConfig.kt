// Where the theme store lives, and the keys its catalogue must be signed with.
package com.wathemer.app.store

import com.wathemer.app.BuildConfig
import com.wathemer.app.settings.prefs.ThemeFile

/** One store as the app reads it; the harness builds its own around a local server and a test key. */
data class StoreTarget(
    /** The repository the catalogue must name, so a staging catalogue never passes for the real one. */
    val storeId: String,
    val site: String,
    val releases: String,
    /** Key id to public key, base64 SubjectPublicKeyInfo. */
    val keys: Map<String, String>,
    val versionCode: Int,
    val readerFormat: Int,
)

object StoreConfig {

    const val CATALOGUE = "store-index.txt"

    /** The day-to-day key, then the backup kept offline; ids are the first 8 hex of each key's SHA-256. */
    val KEYS: Map<String, String> = linkedMapOf(
        "a48cf502" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE6W7luuxcF4k+hmj94m83PubC7cXJcLZI1gsyAoBPa/U15ggTJuiWgUYv3ufOUVL5Th/Jx428XR2VD0kX+iGPKg==",
        "9eb96d18" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELrIurqQJP7YRwRHDwzWC9kd6fNLL6HpC3ictpOAqMeNTl1Wids12erJnkHGVkgFID8Dd4Y5y/aT+nj9OfOVS5w==",
    )

    // Released builds follow no redirect to these, so the addresses can never change.
    val TARGET: StoreTarget = if (BuildConfig.STORE_STAGING) {
        target("wathemer/themes-staging", "https://wathemer.github.io/themes-staging/")
    } else {
        target("wathemer/themes", "https://wathemer.github.io/themes/")
    }

    fun http(): StoreHttp = StoreHttp(GitHubPolicy(TARGET), "WaThemer/${BuildConfig.VERSION_NAME}")

    private fun target(storeId: String, site: String) = StoreTarget(
        storeId = storeId,
        site = site,
        releases = "https://github.com/$storeId/releases/download/",
        keys = KEYS,
        versionCode = BuildConfig.VERSION_CODE,
        readerFormat = ThemeFile.FORMAT,
    )
}
