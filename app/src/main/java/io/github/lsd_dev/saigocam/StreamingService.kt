package io.github.lsd_dev.saigocam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.PowerManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pedro.rtspserver.RtspServerCamera2
import com.pedro.rtspserver.util.RtspServerStreamClient
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.util.Size

class StreamingService : Service(), ConnectChecker {

    private val binder = LocalBinder()
    var rtspServerCamera2: RtspServerCamera2? = null
        private set
        
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming
    
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _resolutions = MutableStateFlow<List<Size>>(emptyList())
    val resolutions: StateFlow<List<Size>> = _resolutions

    private val _selectedResolution = MutableStateFlow<Size?>(null)
    val selectedResolution: StateFlow<Size?> = _selectedResolution

    private val _fpsOptions = MutableStateFlow(listOf(30))
    val fpsOptions: StateFlow<List<Int>> = _fpsOptions

    private val _selectedFps = MutableStateFlow(30)
    val selectedFps: StateFlow<Int> = _selectedFps

    // Legacy all-intra mode, kept so latency can be A/B tested (see docs/LATENCY.md)
    private val _keyframeEveryFrame = MutableStateFlow(false)
    val keyframeEveryFrame: StateFlow<Boolean> = _keyframeEveryFrame

    private data class VideoConfig(val width: Int, val height: Int, val fps: Int)

    // Config the running camera session was opened with; null when no preview was started
    private var previewConfig: VideoConfig? = null

    // Encoder settings beyond the camera config; a change here needs a new prepareVideo
    private data class EncoderConfig(val video: VideoConfig, val allIntra: Boolean)

    // Config the encoder was last prepared with. Null once the stream stops (the library
    // releases the codec then) or when prepare failed. Tracked so prepareVideo isn't called
    // more often than needed: each call configures a new MediaCodec, and hardware encoder
    // instances are scarce.
    private var preparedEncoderConfig: EncoderConfig? = null

    private val _currentBitrate = MutableStateFlow(0L)
    val currentBitrate: StateFlow<Long> = _currentBitrate

    // Hot phones throttle the CPU/encoder, which raises latency and drops frames
    private val _thermalWarning = MutableStateFlow<String?>(null)
    val thermalWarning: StateFlow<String?> = _thermalWarning

    // Typed as Any so the API 29 listener class is never touched on older devices
    private var thermalListener: Any? = null

    private fun registerThermalListener() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                _thermalWarning.value = when {
                    status >= PowerManager.THERMAL_STATUS_SEVERE ->
                        "Phone is overheating. Lower the resolution or fps to avoid throttling and dropped frames."
                    status >= PowerManager.THERMAL_STATUS_MODERATE ->
                        "Phone is getting warm. Latency may rise if it starts throttling."
                    else -> null
                }
            }
            // Delivered on the main thread
            powerManager.addThermalStatusListener(listener)
            thermalListener = listener
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unregisterThermalListener() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(listener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        thermalListener = null
    }

    private var wifiLock: WifiManager.WifiLock? = null

    // Keeps the Wi-Fi radio out of power-save while streaming. Power-save batches packets,
    // which shows up as latency spikes. The low-latency mode (Android 10+) is only honoured
    // while the screen is on and the app is in the foreground; otherwise it acts as high-perf.
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(mode, "SaigoCam:streaming").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wifiLock = null
    }

    // RTSP Basic auth: without it anyone on the same network can view the camera
    val authUser = "saigocam"

    private val _authEnabled = MutableStateFlow(true)
    val authEnabled: StateFlow<Boolean> = _authEnabled

    private val _authPassword = MutableStateFlow("")
    val authPassword: StateFlow<String> = _authPassword

    private val prefs by lazy { getSharedPreferences("saigocam_prefs", Context.MODE_PRIVATE) }

    fun setAuthEnabled(enabled: Boolean) {
        _authEnabled.value = enabled
        prefs.edit().putBoolean(PREF_AUTH_ENABLED, enabled).apply()
        applyAuthorization()
    }

    fun regeneratePassword() {
        val password = StreamSettings.generatePassword()
        _authPassword.value = password
        prefs.edit().putString(PREF_AUTH_PASSWORD, password).apply()
        applyAuthorization()
    }

    private fun loadAuthSettings() {
        _authEnabled.value = prefs.getBoolean(PREF_AUTH_ENABLED, true)
        val saved = prefs.getString(PREF_AUTH_PASSWORD, null)
        if (saved.isNullOrEmpty()) regeneratePassword() else _authPassword.value = saved
    }

    // Applies to new client connections; clients already watching are not dropped
    private fun applyAuthorization() {
        try {
            if (_authEnabled.value) {
                rtspServerCamera2?.getStreamClient()?.setAuthorization(authUser, _authPassword.value)
            } else {
                rtspServerCamera2?.getStreamClient()?.setAuthorization(null, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val PREF_AUTH_ENABLED = "auth_enabled"
        private const val PREF_AUTH_PASSWORD = "auth_password"
    }

    fun setResolution(size: Size) {
        if (_selectedResolution.value == size) return
        _selectedResolution.value = size
        updateFpsOptions()
        applyVideoSettingsChange()
    }

    fun setFps(fps: Int) {
        if (_selectedFps.value == fps || fps !in _fpsOptions.value) return
        _selectedFps.value = fps
        applyVideoSettingsChange()
    }

    fun setKeyframeEveryFrame(enabled: Boolean) {
        if (_keyframeEveryFrame.value == enabled) return
        _keyframeEveryFrame.value = enabled
        applyVideoSettingsChange()
    }

    private fun applyVideoSettingsChange() {
        if (_isStreaming.value) {
            restartStream()
        } else if (rtspServerCamera2?.isOnPreview == true) {
            ensurePreview()
        }
    }

    private fun currentConfig(): VideoConfig {
        val res = _selectedResolution.value ?: Size(1280, 720)
        return VideoConfig(res.width, res.height, _selectedFps.value)
    }

    private fun currentEncoderConfig() = EncoderConfig(currentConfig(), _keyframeEveryFrame.value)

    // Offers 60 fps only when the back camera reports a frame-rate range that reaches it.
    // The library picks the camera's fps range from the back camera too.
    private fun updateFpsOptions() {
        val camera = rtspServerCamera2 ?: return
        val res = _selectedResolution.value ?: return
        val supports60 = try {
            camera.getSupportedFps(res, CameraHelper.Facing.BACK).any { it.upper >= 60 }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        _fpsOptions.value = if (supports60) listOf(30, 60) else listOf(30)
        if (_selectedFps.value !in _fpsOptions.value) _selectedFps.value = 30
    }

    /**
     * Brings the camera preview and the encoder in line with the current settings, touching
     * only what changed. The camera session takes its size and fps from the encoder when the
     * preview starts, and the library doesn't reconfigure a running session, so the encoder
     * must be prepared before the preview. Otherwise the camera keeps the library default
     * (640x480) and the stream is upscaled.
     */
    fun ensurePreview() {
        val camera = rtspServerCamera2 ?: return
        if (camera.isStreaming) return
        val previewUpToDate = camera.isOnPreview && previewConfig == currentConfig()
        val encoderUpToDate = preparedEncoderConfig == currentEncoderConfig()
        if (previewUpToDate && encoderUpToDate) return

        if (camera.isOnPreview && !previewUpToDate) {
            camera.stopPreview()
        }
        if (!encoderUpToDate && !prepareEncoder()) return
        if (!camera.isOnPreview) {
            try {
                camera.startPreview()
                previewConfig = currentConfig()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun targetBitrate(): Int {
        val config = currentConfig()
        return StreamSettings.targetBitrate(config.width, config.fps)
    }

    private fun prepareEncoder(): Boolean {
        val camera = rtspServerCamera2 ?: return false
        val config = currentConfig()
        val bitrate = targetBitrate()
        // 0 → every frame is a keyframe (legacy). Default: a keyframe every second, plus one
        // on demand whenever a client connects, so OBS still starts decoding immediately
        // while frames stay small and quick to send.
        val iFrameInterval = if (_keyframeEveryFrame.value) 0 else 1
        // Auto-detect camera sensor orientation for correct preview/stream rotation
        val rotation = CameraHelper.getCameraOrientation(this)

        // Set AVC/H.264 profile to Constrained Baseline (no B-frames) for zero-latency decoding
        val profile = android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
        val level = -1 // Let the encoder choose the appropriate level

        var prepared = false
        try {
            prepared = camera.prepareVideo(
                config.width, config.height, config.fps, bitrate, iFrameInterval, rotation, profile, level
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to default profile if constrained baseline is not supported by the hardware encoder
        if (!prepared) {
            prepared = try {
                camera.prepareVideo(config.width, config.height, config.fps, bitrate, iFrameInterval, rotation)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
        preparedEncoderConfig = if (prepared) currentEncoderConfig() else null
        return prepared
    }

    fun switchCamera() {
        try {
            rtspServerCamera2?.switchCamera()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restartStream() {
        stopStreaming()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startStreaming()
        }, 200)  // 200ms is sufficient for encoder teardown
    }

    val port = 1935

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    // Lifecycle: the Activity only binds, so the service lives while the UI is open.
    // While streaming it is also "started" (see startStreaming) so the stream survives
    // the UI closing; stopStreaming() ends that, and the service goes once nothing is bound.
    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    // A restart after the system kills the process would come back without a camera
    // or UI, so don't ask to be recreated.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    /**
     * Called when the preview surface goes away (tab switch, app in background).
     * While streaming, keep the camera running off-screen. Otherwise close it:
     * stopPreview() is a no-op in the library's background mode, so the camera
     * would stay open and drain the battery.
     */
    fun onPreviewSurfaceDestroyed() {
        val camera = rtspServerCamera2 ?: return
        try {
            if (camera.isStreaming) {
                // Off-screen rendering; application context so no Activity is retained
                camera.replaceView(applicationContext)
            } else {
                camera.stopCamera()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        loadAuthSettings()
        registerThermalListener()
    }

    fun initServer() {
        if (rtspServerCamera2 == null) {
            // Application context: the camera outlives any Activity, so it must not hold one
            rtspServerCamera2 = RtspServerCamera2(applicationContext, this, port)
            applyAuthorization()
            try {
                val backResolutions = rtspServerCamera2?.resolutionsBack ?: emptyList()
                val frontResolutions = try {
                    rtspServerCamera2?.resolutionsFront ?: emptyList()
                } catch (e: Exception) {
                    emptyList() // e.g. no front camera
                }
                _resolutions.value = StreamSettings.supportedResolutions(backResolutions, frontResolutions)
                _selectedResolution.value = _resolutions.value.last() // Default to 720p if available
                updateFpsOptions()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun startStreaming() {
        if (rtspServerCamera2?.isStreaming == false) {
            // A running preview keeps its old size/fps, so restart it if settings changed;
            // ensurePreview also (re)prepares the encoder only when needed
            if (rtspServerCamera2?.isOnPreview == true) {
                ensurePreview()
            }
            val prepared = preparedEncoderConfig == currentEncoderConfig() || prepareEncoder()

            if (prepared) {

                // Disable audio track completely so RTSP streams video only
                try {
                    rtspServerCamera2?.disableAudio()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Ultra-small buffer: only 5 frames (~166ms at 30fps, ~83ms at 60fps)
                // Drops old frames immediately to stay live
                try {
                    rtspServerCamera2?.getStreamClient()?.resizeCache(5)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                applyAuthorization()
                rtspServerCamera2?.startStream()
                acquireWifiLock()
                _isStreaming.value = true
                _connectionStatus.value = "Waiting for OBS to connect on port $port..."
                // Become a started service so streaming continues after the UI unbinds.
                // Can throw if the app is somehow in the background; the stream still runs then,
                // it just won't outlive the UI.
                try {
                    startService(Intent(this, StreamingService::class.java))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                startForeground(1, createNotification("Streaming Active", "Ready for OBS connection"))
            } else {
                _connectionStatus.value = "Failed to prepare camera"
            }
        }
    }

    fun stopStreaming() {
        if (rtspServerCamera2?.isStreaming == true) {
            rtspServerCamera2?.stopStream()
        }
        // stopStream releases the encoder; the next start must prepare it again
        preparedEncoderConfig = null
        releaseWifiLock()
        _isStreaming.value = false
        _connectionStatus.value = "Disconnected"
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Ends the "started" state; the service stays alive while the UI is still bound
        stopSelf()
    }

    override fun onDestroy() {
        stopStreaming()
        try {
            rtspServerCamera2?.stopCamera()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        unregisterThermalListener()
        super.onDestroy()
    }

    override fun onConnectionStarted(rtspUrl: String) {
        _connectionStatus.value = "Connecting..."
    }

    override fun onConnectionSuccess() {
        _connectionStatus.value = "Connected to OBS!"
        // Clear any stale buffered frames so OBS starts from the latest frame
        try {
            rtspServerCamera2?.getStreamClient()?.clearCache()
            // Make the next frame a keyframe so the new client can start decoding at once,
            // instead of waiting up to the 1 s keyframe interval
            rtspServerCamera2?.requestKeyFrame()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification("Streaming to OBS", "Connected"))
    }

    override fun onConnectionFailed(reason: String) {
        _connectionStatus.value = "Connection Failed: $reason"
        stopStreaming()
    }

    override fun onNewBitrate(bitrate: Long) {
        _currentBitrate.value = bitrate
        // If bitrate drops significantly, cache may be piling up — clear it
        if (bitrate > 0 && bitrate < targetBitrate() / 4) {
            try {
                rtspServerCamera2?.getStreamClient()?.clearCache()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Fired per client (on RTSP TEARDOWN, e.g. OBS hiding the source), not for the server,
    // so keep the server running and wait for the client to reconnect.
    override fun onDisconnect() {
        if (!_isStreaming.value) return
        val remainingClients = try {
            // Cast rather than rely on getStreamClient()'s declared return type;
            // getNumClients() only exists on the RTSP-server client
            (rtspServerCamera2?.getStreamClient() as? RtspServerStreamClient)?.getNumClients() ?: 0
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
        if (remainingClients > 0) {
            _connectionStatus.value = "Connected to OBS!"
        } else {
            _connectionStatus.value = "OBS disconnected. Waiting for reconnect on port $port..."
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1, createNotification("Streaming Active", "Ready for OBS connection"))
        }
    }

    // A rejected client must not take the server down for everyone else
    override fun onAuthError() {
        _connectionStatus.value = "A client was rejected (wrong password)"
    }
    
    override fun onAuthSuccess() {
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "streaming_channel",
            "Streaming Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, "streaming_channel")
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
