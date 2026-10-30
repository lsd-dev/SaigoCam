package io.github.lsd_dev.saigocam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import com.pedro.library.view.OpenGlView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import io.github.lsd_dev.saigocam.ui.theme.SaigoCamTheme

class MainActivity : ComponentActivity() {

    private var streamingService: StreamingService? = null
    private var isBound = mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            isBound.value = true
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound.value = false
            streamingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep the screen on while the app is running/visible
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Only the camera is required; notifications are requested but optional,
        // since the foreground service still runs without them
        val requestedPermissions = mutableListOf(
            Manifest.permission.CAMERA
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestedPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val isCameraGranted = {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }

        // Bind only; the service starts itself while streaming (see StreamingService)
        val intent = Intent(this, StreamingService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            SaigoCamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var hasPermissions by remember { mutableStateOf(isCameraGranted()) }

                    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) {
                        hasPermissions = isCameraGranted()
                    }

                    LaunchedEffect(Unit) {
                        if (!hasPermissions) {
                            launcher.launch(requestedPermissions.toTypedArray())
                        }
                    }

                    val bound by isBound
                    if (hasPermissions && bound && streamingService != null) {
                        SaigoCamScreen(streamingService!!)
                    } else {
                        if (!hasPermissions) {
                            PermissionRequiredContent(onGrantClick = { launcher.launch(requestedPermissions.toTypedArray()) })
                        } else {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound.value) {
            unbindService(connection)
            isBound.value = false
        }
    }
}

@Composable
fun PermissionRequiredContent(onGrantClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera permission is required to stream.", modifier = Modifier.padding(16.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onGrantClick) {
                Text("Grant Permissions")
            }
        }
    }
}

@Composable
fun SaigoCamScreen(service: StreamingService) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Stream", "Setup Guide")

    // Sustained performance mode caps clocks at a level the phone can hold without
    // thermal throttling: slightly lower peak speed, but steadier frame times and latency.
    // Only applies while this window has focus, and only on devices that support it.
    val isStreaming by service.isStreaming.collectAsState()
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(isStreaming) {
        val powerManager = activity?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (powerManager?.isSustainedPerformanceModeSupported == true) {
            activity?.window?.setSustainedPerformanceMode(isStreaming)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontWeight = FontWeight.SemiBold) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (selectedTab == 0) {
                StreamContent(service)
            } else {
                SetupGuideContent(service)
            }
        }
    }
}

@Composable
fun StreamContent(service: StreamingService) {
    val isStreaming by service.isStreaming.collectAsState(initial = false)
    val connectionStatus by service.connectionStatus.collectAsState(initial = "Disconnected")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Camera Preview with Switch Overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black, RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    OpenGlView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                service.initServer()
                                try {
                                    service.rtspServerCamera2?.replaceView(this@apply)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                                service.ensurePreview()
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                service.onPreviewSurfaceDestroyed()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            )

            // Switch Camera Overlay Button
            IconButton(
                onClick = { service.switchCamera() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(50))
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Switch Camera",
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Connection", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = if (isStreaming) "● $connectionStatus" else "● Disconnected",
                    color = if (isStreaming) Color(0xFF4CAF50) else Color.Gray,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (isStreaming) {
                    val bitrate by service.currentBitrate.collectAsState(initial = 0L)
                    val bitrateText = if (bitrate > 0) "%.1f Mbps".format(bitrate / 1_000_000.0) else "—"
                    Text(
                        text = "Bitrate: $bitrateText",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                val thermalWarning by service.thermalWarning.collectAsState()
                thermalWarning?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Settings
        val resolutions by service.resolutions.collectAsState(initial = emptyList())
        val selectedResolution by service.selectedResolution.collectAsState(initial = null)
        var expanded by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Video Settings", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedResolution?.let { "${it.width}x${it.height}" } ?: "Select Resolution")
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        resolutions.forEach { res ->
                            DropdownMenuItem(
                                text = { Text("${res.width}x${res.height}") },
                                onClick = {
                                    service.setResolution(res)
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                // Frame rate: 60 fps halves the wait between frames (~17 ms vs ~33 ms)
                val fpsOptions by service.fpsOptions.collectAsState()
                val selectedFps by service.selectedFps.collectAsState()
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    fpsOptions.forEach { fps ->
                        if (fps == selectedFps) {
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("$fps fps") }
                        } else {
                            OutlinedButton(onClick = { service.setFps(fps) }, modifier = Modifier.weight(1f)) { Text("$fps fps") }
                        }
                    }
                }

                // Legacy all-intra mode, for A/B latency tests only
                val keyframeEveryFrame by service.keyframeEveryFrame.collectAsState()
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Keyframe every frame (for latency tests)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = keyframeEveryFrame, onCheckedChange = { service.setKeyframeEveryFrame(it) })
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Start/Stop Button
        Button(
            onClick = {
                if (isStreaming) {
                    service.stopStreaming()
                } else {
                    service.startStreaming()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isStreaming) "STOP STREAMING" else "START STREAMING",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SetupGuideContent(service: StreamingService) {
    val context = LocalContext.current
    val ipAddress = getLocalIpAddress(context)
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val authEnabled by service.authEnabled.collectAsState()
    val authPassword by service.authPassword.collectAsState()
    val user = if (authEnabled) service.authUser else null
    val wifiUrl = StreamSettings.streamUrl(ipAddress, service.port, user, authPassword)
    val usbUrl = StreamSettings.streamUrl("127.0.0.1", service.port, user, authPassword)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text("OBS Setup Guide", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        // Stream password
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Require Password", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Switch(checked = authEnabled, onCheckedChange = { service.setAuthEnabled(it) })
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (authEnabled) {
                    Text("Username: ${service.authUser}")
                    Text("Password: $authPassword", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    TextButton(onClick = { service.regeneratePassword() }) {
                        Text("Generate New Password")
                    }
                    Text("The URLs below already include these credentials. Changes apply to new connections only.\n\n*Note: RTSP passwords are not encrypted on the network. Use this on networks you trust.*", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Anyone on the same Wi-Fi network can view your camera. Only turn this off on a private network.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Wi-Fi Setup (VLC Video Source)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("1. Wi-Fi Setup (VLC Video Source)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("VLC is recommended for instant auto-connecting.\n\n• Install VLC on your PC and restart OBS.\n• Open OBS Studio -> Sources -> '+' -> 'VLC Video Source'.\n• CHECK 'Loop Playlist' (Required for auto-connect).\n• Set 'Network Caching' to 100ms (lowest possible).\n• In the Playlist box, click '+' -> 'Add Path/URL'.\n• Enter the exact URL below.\n\n*Note: If the app was recently closed or OBS was just opened, double-click the VLC source and click 'OK' to wake it up.*\n\n*Pro-Tip: Sometimes just clicking the \"Eye\" icon (hide/unhide) next to the source in OBS is enough to wake it up, which is much faster than opening properties.*")
                Spacer(modifier = Modifier.height(12.dp))
                Text("VLC Source URL:", fontWeight = FontWeight.SemiBold)
                Text(wifiUrl, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Wi-Fi Setup (Media Source)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("2. Wi-Fi Setup (Media Source)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Ensure PC and Phone are on the same Wi-Fi.\n• In the Stream tab, tap 'START STREAMING'.\n• Open OBS Studio -> Sources -> '+' -> 'Media Source'.\n• Uncheck 'Local File'.\n• Uncheck 'Use hardware decoding when available'.\n• Set 'Network Buffering' to 0 MB.\n• Set Input to the URL below.\n\n*Note: If the feed doesn't show up immediately, double-click the Media Source in OBS to open Properties and click 'OK' to force it to connect.*\n\n*Pro-Tip: Sometimes just clicking the \"Eye\" icon (hide/unhide) next to the source in OBS is enough to wake it up, which is much faster than opening properties.*")
                Spacer(modifier = Modifier.height(12.dp))
                Text("Media Source URL:", fontWeight = FontWeight.SemiBold)
                Text(wifiUrl, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // USB Setup
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("3. USB Setup (Lowest Latency)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Enable USB Debugging on your phone.\n• Connect via USB to your PC.\n• Open terminal/cmd on your PC and run:")
                Card(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                    Text("adb forward tcp:1935 tcp:1935", color = Color.Green, modifier = Modifier.padding(8.dp), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                Text("• Start streaming in this app.\n• Use the USB URL below in OBS with either the Media Source or VLC Source method.")
                Spacer(modifier = Modifier.height(12.dp))
                Text("USB URL:", fontWeight = FontWeight.SemiBold)
                Text(usbUrl, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Google Meet Setup Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("4. Streaming to Google Meet", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Video: OBS Virtual Camera\n" +
                           "• In OBS, with your SaigoCam video feed already showing as a source:\n" +
                           "• Click 'Start Virtual Camera' (bottom-right of OBS, or Tools → Start Virtual Camera).\n" +
                           "• In Google Meet, click Settings (⚙️) → Video → select 'OBS Virtual Camera' as your camera.\n\n" +
                           "Audio Setup\n" +
                           "• This app streams video only for absolute minimum latency.\n" +
                           "• You can add and use any audio source (such as your PC microphone, USB headset, or external microphone) directly in OBS or Google Meet.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

fun getLocalIpAddress(context: Context): String {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        var fallbackIp = "Unknown"
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                    val ip = address.hostAddress ?: continue
                    if (networkInterface.name.contains("wlan")) {
                        return ip // Prefer Wi-Fi
                    } else if (networkInterface.name.contains("eth")) {
                        return ip // Prefer Ethernet
                    }
                    fallbackIp = ip
                }
            }
        }
        return fallbackIp
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "Unknown"
}
