package com.example

/**
 * Whether the MangaLens screen itself is in front. The floating translator stays quiet meanwhile: translating
 * our own settings screen is useless and was spending requests while the user configured the app.
 */
object AppVisibility {
    @Volatile
    var isAppInFront: Boolean = false
}
