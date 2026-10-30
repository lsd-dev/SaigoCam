package io.github.lsd_dev.saigocam

import android.util.Size
import java.security.SecureRandom
import java.util.Random

/** Pure stream-setting logic, kept free of Android services so it can be unit-tested. */
object StreamSettings {

    val STANDARD_RESOLUTIONS = listOf(Size(1920, 1080), Size(1280, 720))
    val FALLBACK_RESOLUTION = Size(1280, 720)

    /**
     * Standard resolutions to offer, largest first. Prefers sizes both cameras support,
     * so switching to the front camera mid-stream can't land on an unsupported size.
     * Falls back to back-camera-only sizes, then to 720p, so the list is never empty.
     */
    fun supportedResolutions(back: List<Size>, front: List<Size>): List<Size> {
        fun List<Size>.has(size: Size) = any { it.width == size.width && it.height == size.height }
        val onBack = STANDARD_RESOLUTIONS.filter { back.has(it) }
        val onBoth = if (front.isEmpty()) onBack else onBack.filter { front.has(it) }
        return onBoth.ifEmpty { onBack }.ifEmpty { listOf(FALLBACK_RESOLUTION) }
    }

    fun targetBitrate(width: Int, fps: Int): Int {
        val base = if (width >= 1920) 8_000_000 else 5_000_000
        // Twice the frames, but inter-frame compression keeps the extra cost well under 2x
        return if (fps >= 60) base * 3 / 2 else base
    }

    // Alphanumeric without look-alike characters, so it can be typed from the screen
    // and needs no escaping inside an rtsp:// URL
    private const val PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"

    fun generatePassword(random: Random = SecureRandom(), length: Int = 10): String {
        return (1..length).map { PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.length)] }.joinToString("")
    }

    /** RTSP clients (VLC, OBS Media Source) accept credentials embedded in the URL. */
    fun streamUrl(host: String, port: Int, user: String?, password: String?): String {
        val credentials = if (!user.isNullOrEmpty() && !password.isNullOrEmpty()) "$user:$password@" else ""
        return "rtsp://$credentials$host:$port"
    }
}
