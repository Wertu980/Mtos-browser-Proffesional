package com.mtos.web.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.mtos.web.browser.ui.theme.MyApplicationTheme
import com.mtos.web.browser.ui.SpeedDialItem
import com.mtos.web.browser.ui.StartPageDashboard
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        StartPageDashboard(
          speedDials = listOf(
            SpeedDialItem("Google", "https://www.google.com", "G", Color(0xFF4285F4)),
            SpeedDialItem("YouTube", "https://www.youtube.com", "Y", Color(0xFFFF0000)),
            SpeedDialItem("Wikipedia", "https://www.wikipedia.org", "W", Color(0xFF333333))
          ),
          onSearchQuerySubmitted = {},
          onDialClicked = {}
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
