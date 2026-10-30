package io.github.lsd_dev.saigocam

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

// Robolectric because android.util.Size is a stub in plain JVM unit tests
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StreamSettingsTest {

  private val p1080 = Size(1920, 1080)
  private val p720 = Size(1280, 720)
  private val p480 = Size(640, 480)

  private fun List<Size>.asPairs() = map { it.width to it.height }

  @Test
  fun `offers only sizes both cameras support`() {
    val result = StreamSettings.supportedResolutions(back = listOf(p1080, p720, p480), front = listOf(p720, p480))
    assertEquals(listOf(1280 to 720), result.asPairs())
  }

  @Test
  fun `offers both standard sizes when both cameras support them`() {
    val result = StreamSettings.supportedResolutions(back = listOf(p480, p720, p1080), front = listOf(p1080, p720))
    assertEquals(listOf(1920 to 1080, 1280 to 720), result.asPairs())
  }

  @Test
  fun `uses back camera sizes when there is no front camera`() {
    val result = StreamSettings.supportedResolutions(back = listOf(p1080, p720), front = emptyList())
    assertEquals(listOf(1920 to 1080, 1280 to 720), result.asPairs())
  }

  @Test
  fun `falls back to back camera sizes when cameras share no standard size`() {
    val result = StreamSettings.supportedResolutions(back = listOf(p1080), front = listOf(p480))
    assertEquals(listOf(1920 to 1080), result.asPairs())
  }

  @Test
  fun `never returns an empty list`() {
    val result = StreamSettings.supportedResolutions(back = emptyList(), front = emptyList())
    assertEquals(listOf(1280 to 720), result.asPairs())
  }

  @Test
  fun `bitrate scales with resolution and fps`() {
    assertEquals(5_000_000, StreamSettings.targetBitrate(1280, 30))
    assertEquals(7_500_000, StreamSettings.targetBitrate(1280, 60))
    assertEquals(8_000_000, StreamSettings.targetBitrate(1920, 30))
    assertEquals(12_000_000, StreamSettings.targetBitrate(1920, 60))
  }

  @Test
  fun `password has requested length and only URL-safe characters`() {
    val password = StreamSettings.generatePassword(Random(42), length = 32)
    assertEquals(32, password.length)
    assertTrue(password, password.all { it.isLetterOrDigit() })
    // Look-alike characters are excluded so the password can be typed from the screen
    assertTrue(password, password.none { it in "0O1Il" })
  }

  @Test
  fun `passwords differ between calls`() {
    assertNotEquals(StreamSettings.generatePassword(), StreamSettings.generatePassword())
  }

  @Test
  fun `stream url includes credentials only when both are set`() {
    assertEquals("rtsp://saigocam:abc123@192.168.1.5:1935", StreamSettings.streamUrl("192.168.1.5", 1935, "saigocam", "abc123"))
    assertEquals("rtsp://192.168.1.5:1935", StreamSettings.streamUrl("192.168.1.5", 1935, null, "abc123"))
    assertEquals("rtsp://127.0.0.1:1935", StreamSettings.streamUrl("127.0.0.1", 1935, "saigocam", ""))
  }
}
