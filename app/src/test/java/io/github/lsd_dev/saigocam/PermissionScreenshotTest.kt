package io.github.lsd_dev.saigocam

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.lsd_dev.saigocam.ui.theme.SaigoCamTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class PermissionScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun permission_screen_screenshot() {
    composeTestRule.setContent { SaigoCamTheme { PermissionRequiredContent(onGrantClick = {}) } }

    composeTestRule.onNodeWithText("Camera permission is required to stream.").assertIsDisplayed()
    // Images are only written when recording: ./gradlew recordRoborazziDebug
    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/permission_required.png")
  }

  @Test
  fun grant_button_triggers_callback() {
    var clicks = 0
    composeTestRule.setContent { SaigoCamTheme { PermissionRequiredContent(onGrantClick = { clicks++ }) } }

    composeTestRule.onNodeWithText("Grant Permissions").performClick()
    assertEquals(1, clicks)
  }
}
