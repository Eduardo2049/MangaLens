package com.example.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.reader.MangaReaderScreen
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the app screens at phone sizes. In CI the images are recorded (-Proborazzi.test.record=true) and
 * uploaded as the "ui-screenshots" artifact; locally without that flag the tests only check that the
 * screens compose without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun mainScreen(name: String) {
        compose.setContent { MyApplicationTheme(darkTheme = true, dynamicColor = false) { MangaReaderScreen() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-xhdpi")
    fun mainScreen_smallPhone() = mainScreen("main_360x780")

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun mainScreen_phone() = mainScreen("main_411x891")

    @Test
    @Config(qualifiers = "w360dp-h1800dp-xhdpi")
    fun mainScreen_fullLength() = mainScreen("main_360_full")
}
