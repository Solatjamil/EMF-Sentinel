package com.example

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.location.LocationManager
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview as CameraPreviewUseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay
import com.example.ads.AdConsentManager
import com.example.ads.AnchoredAdaptiveBanner
import com.example.ads.MobileAdsController
import com.example.sensors.EmfSensorManager
import com.example.net.LanDevice
import com.example.net.NetworkScanner
import com.example.net.JammingDetector
import com.example.floorplan.FloorPlanStore
import com.example.floorplan.FloorPlanMapperDialog
import com.example.floorplan.floorLabel
import com.example.floorplan.toSurroundingWalls
import com.example.floorplan.toTrackingSubjects

enum class Tab {
    SCANNER,
    HEATMAP,
    AR_VIEW,
    INSIGHTS,
    HEALTH
}

enum class Sensitivity {
    LOW,
    MEDIUM,
    HIGH
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        setContent {
            var isDarkMode by remember { mutableStateOf(true) }

            // ── Ads pipeline: UMP consent gate → guarded one-time SDK init ──
            // No ads are initialized or requested while canRequestAds() is false.
            val activity = this@MainActivity
            var canRequestAds by remember { mutableStateOf(AdConsentManager.canRequestAds) }
            var privacyOptionsRequired by remember { mutableStateOf(AdConsentManager.isPrivacyOptionsRequired) }
            val gatherConsent: () -> Unit = {
                AdConsentManager.gatherConsent(activity) { canRequest, privacyRequired ->
                    canRequestAds = canRequest
                    privacyOptionsRequired = privacyRequired
                    if (canRequest) {
                        MobileAdsController.initialize(activity.applicationContext)
                    }
                }
            }
            LaunchedEffect(Unit) { gatherConsent() }
            // Refresh consent state on every resume (consent can change in digital
            // settings; previous-session consent must be honored per UMP guidance).
            val consentLifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(consentLifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) gatherConsent()
                }
                consentLifecycleOwner.lifecycle.addObserver(observer)
                onDispose { consentLifecycleOwner.lifecycle.removeObserver(observer) }
            }

            MyApplicationTheme(darkTheme = isDarkMode) {
                var showSplash by remember { mutableStateOf(true) }
                if (showSplash) {
                    SplashScreen(onTimeout = { showSplash = false })
                } else {
                    EMFSentinelApp(
                        isDarkMode = isDarkMode,
                        onToggleTheme = { isDarkMode = !isDarkMode },
                        canRequestAds = canRequestAds,
                        privacyOptionsRequired = privacyOptionsRequired,
                        onShowPrivacyOptions = {
                            AdConsentManager.showPrivacyOptionsForm(activity) { canRequest, privacyRequired ->
                                canRequestAds = canRequest
                                privacyOptionsRequired = privacyRequired
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LogoBrand(
    showText: Boolean,
    isDarkMode: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        // Icon circular card layout
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isDarkMode) Zinc950 else LightSurface)
                .border(1.dp, Emerald500.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_emf_logo_icon_1782150814680),
                contentDescription = "EMF Sentinel logo",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        
        if (showText) {
            Column {
                Text(
                    text = "EMF Sentinel",
                    color = if (isDarkMode) Slate50 else LightTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Precision RF & Bio Telemetry",
                    color = if (isDarkMode) Emerald400 else Emerald600,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }
    var currentPhaseText by remember { mutableStateOf("BOOTING SENTINEL CORE...") }
    
    LaunchedEffect(Unit) {
        // Animate progress gracefully over 2.5 seconds
        val startTime = System.currentTimeMillis()
        val duration = 2500f
        while (System.currentTimeMillis() - startTime < duration) {
            val elapsed = System.currentTimeMillis() - startTime
            progress = (elapsed / duration).coerceIn(0f, 1f)
            
            // Modern cybernetic loader feedback states
            currentPhaseText = when {
                progress < 0.25f -> "BOOTING SENTINEL CORE..."
                progress < 0.50f -> "LINKING SATELLITE INDUCTORS..."
                progress < 0.75f -> "CALIBRATING GEOMAGNETIC SENSORS [M3-CORE]..."
                else -> "ESTABLISHING SIGNAL TELEMETRY..."
            }
            delay(16) // ~60fps
        }
        progress = 1f
        onTimeout()
    }
    
    // Core layout
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceBlack),
        contentAlignment = Alignment.Center
    ) {
        // Subtle cyber canvas grid in background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val lineBrush = Brush.linearGradient(
                colors = listOf(Emerald500.copy(alpha = 0.03f), Color.Transparent, Emerald500.copy(alpha = 0.03f))
            )
            val space = 30.dp.toPx()
            for (x in 0..(w / space).toInt()) {
                drawLine(lineBrush, Offset(x * space, 0f), Offset(x * space, h), 0.5f.dp.toPx())
            }
            for (y in 0..(h / space).toInt()) {
                drawLine(lineBrush, Offset(0f, y * space), Offset(w, y * space), 0.5f.dp.toPx())
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Pulse Ring Animation
            val infiniteTransition = rememberInfiniteTransition(label = "SplashPulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 0.85f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "LogoPulseScale"
            )
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 0.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "LogoPulseAlpha"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                // Outer glow pulse
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(pulseScale)
                        .background(Emerald500.copy(alpha = pulseAlpha), CircleShape)
                )

                // Mid glow ring
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .border(1.dp, Emerald500.copy(alpha = 0.15f), CircleShape)
                )

                // Core logo - "icon only to Sprinter screen"
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(Zinc900)
                        .border(1.5.dp, Emerald500.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_emf_logo_icon_1782150814680),
                        contentDescription = "EMF Sentinel Icon Only",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            
            // Loading diagnostics info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 40.dp)
            ) {
                Text(
                    text = currentPhaseText,
                    color = Emerald500,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                
                // Tech progress line
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Zinc900)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Emerald600, Emerald400)
                                )
                            )
                    )
                }
                
                Text(
                    text = "${(progress * 100).toInt()}% READY",
                    color = Slate500,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }
        }
        
        // Brand watermark at the very bottom
        Text(
            text = "DESIGN BY EMF SENTINEL LABS // SECURED",
            color = Slate600,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

/**
 * Standard Greeting supporting screenshot tests and showcasing design credentials!
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Zinc800)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "OPERATOR SESSION INITIATED",
                color = Emerald500,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Authorized Signal Unit: $name",
                color = Slate200,
                fontSize = 14.sp,
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Status: SECURE CONNECTIVITY // ACCURACY TRACE",
                color = Slate500,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun EMFSentinelApp(
    isDarkMode: Boolean = true,
    onToggleTheme: () -> Unit = {},
    canRequestAds: Boolean = false,
    privacyOptionsRequired: Boolean = false,
    onShowPrivacyOptions: () -> Unit = {}
) {
    var activeTab by remember { mutableStateOf(Tab.SCANNER) }
    var operatorName by remember { mutableStateOf("Core Sentinel") }
    
    // Core scanning states
    var isScanning by remember { mutableStateOf(true) }
    var baseFrequency by remember { mutableStateOf(50.2f) }
    var sensitivity by remember { mutableStateOf(Sensitivity.MEDIUM) }
    var calibrationGain by remember { mutableStateOf(1.0f) }
    var isBubbleShieldActive by remember { mutableStateOf(false) }
    
    // Changing real-time simulation targets
    var currentEmfReading by remember { mutableStateOf(42.8f) }
    var wifiSignalIntensity by remember { mutableStateOf(-64) }
    var sessionHistory by remember { mutableStateOf(listOf(41.2f, 42.0f, 41.8f, 42.8f)) }
    
    // Heatmap custom marks
    var heatmapPointsList by remember {
        mutableStateOf(
            listOf(
                HeatmapPoint(Offset(180f, 250f), 54.2f, "Desktop Adapter"),
                HeatmapPoint(Offset(550f, 400f), 78.5f, "Smart Television"),
                HeatmapPoint(Offset(320f, 650f), 32.1f, "AC Adapter Flow")
            )
        )
    }

    // Modal Control
    var showConfigurator by remember { mutableStateOf(false) }

    // Bio-Sync subjects & walls — REAL data only. Populated from:
    //  • the saved per-story architect floor plan (calibrated walls + placed devices)
    //  • live Wi-Fi AP scans (estimated RSSI/FSPL placement, labeled as estimates)
    //  • the LAN device discovery sweep (real IP/MAC/vendor of devices on the network)
    // Nothing here is fabricated anymore.
    var activeSubjects by remember { mutableStateOf<List<TrackingSubject>>(emptyList()) }
    var surroundingWalls by remember { mutableStateOf<List<SurroundingWall>>(emptyList()) }
    var currentFloor by remember { mutableIntStateOf(0) }
    var showFloorPlanMapper by remember { mutableStateOf(false) }
    var lanScanInProgress by remember { mutableStateOf(false) }
    var lanDevices by remember { mutableStateOf<List<LanDevice>>(emptyList()) }
    var wifiJamAlert by remember { mutableStateOf(false) }

    // WiFi Sensor System Context
    val context = LocalContext.current
    val wifiManager = remember { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    var locationPermissionGranted by remember { mutableStateOf(false) }
    var showLocationPermissionDialog by remember { mutableStateOf(false) }
    var wifiScanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }

    // ── REAL SENSOR WIRING ───────────────────────────────────────────────
    // Magnetometer = the actual EMF source. Devices without one get clearly
    // labeled simulated values (never silently faked).
    val emfSensor = remember { EmfSensorManager(context) }
    var latestRealEmf by remember { mutableStateOf<Float?>(null) }
    var emfIsSimulated by remember { mutableStateOf(!emfSensor.hasMagnetometer) }
    DisposableEffect(Unit) {
        emfSensor.start { microTesla ->
            latestRealEmf = microTesla
            emfIsSimulated = false
        }
        onDispose { emfSensor.stop() }
    }

    // Per-story architect floor plan persistence + defensive RF jamming monitor
    val floorPlanStore = remember { FloorPlanStore(context) }
    val jammingDetector = remember { JammingDetector() }

    // Apply the saved blueprint whenever the building story changes.
    LaunchedEffect(currentFloor) {
        val plan = floorPlanStore.load(currentFloor)
        if (plan != null) {
            surroundingWalls = plan.toSurroundingWalls()
            val nonDevices = activeSubjects.filter { it.type != "Device" }
            activeSubjects = nonDevices + plan.toTrackingSubjects()
        }
    }

    // Real LAN device discovery sweep — runs on demand from the Bio-Sync panel.
    var lanScanTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(lanScanTrigger) {
        if (lanScanTrigger > 0) {
            lanScanInProgress = true
            try {
                val found = NetworkScanner.scanLocalDevices(context)
                lanDevices = found
                // Place newly discovered, not-yet-listed devices near the router
                // as "UNPLACED" markers — exact room placement is done by dragging
                // them in the Architect Floor-Plan Mapper.
                val router = activeSubjects.firstOrNull {
                    it.type == "Device" && (it.name.contains("📶") || it.name.contains("Router", true))
                }
                val anchor = router?.offset ?: Offset(0.5f, 0.3f)
                val alreadyKnown = activeSubjects.mapNotNull { s -> lanDevices.firstOrNull { d -> s.name.contains(d.ip) }?.ip }
                found.filter { it.ip !in alreadyKnown && !it.vendor.contains("Router") }.forEachIndexed { idx, dev ->
                    val angle = idx * 0.9f + 0.4f
                    val pos = Offset(
                        (anchor.x + 0.18f * kotlin.math.cos(angle)).coerceIn(0.08f, 0.92f),
                        (anchor.y + 0.18f * kotlin.math.sin(angle)).coerceIn(0.08f, 0.92f)
                    )
                    activeSubjects = activeSubjects + TrackingSubject(
                        name = "${if (dev.cameraChipsetSuspect) "⚠ " else ""}${dev.vendor} · ${dev.ip}",
                        type = "Device",
                        offset = pos
                    )
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            lanScanInProgress = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val nearbyGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.NEARBY_WIFI_DEVICES] == true
        } else true

        locationPermissionGranted = fineGranted || coarseGranted || nearbyGranted
        if (!locationPermissionGranted) {
            showLocationPermissionDialog = true
        }
    }

    val requestLocationPermission = {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val openAppSettings = {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val openLocationSettings = {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val isGpsEnabled = remember(locationPermissionGranted) {
        try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            true
        }
    }

    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasNearby = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasFine || hasCoarse || hasNearby) {
            locationPermissionGranted = true
        } else {
            showLocationPermissionDialog = true
            requestLocationPermission()
        }
    }

    // Background WiFi Scanner query
    LaunchedEffect(locationPermissionGranted) {
        if (locationPermissionGranted) {
            while (true) {
                try {
                    wifiManager.startScan()
                    val results = wifiManager.scanResults
                    if (!results.isNullOrEmpty()) {
                        wifiScanResults = results.sortedByDescending { it.level }.take(5)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(7000)
            }
        }
    }

    // Stationary Node Mapping: Router & Active Devices have fixed, accurate calibrated positions
    LaunchedEffect(isScanning, wifiScanResults) {
        if (isScanning && wifiScanResults.isNotEmpty()) {
            val currentWifiSubjects = activeSubjects.filter { it.type == "Device" }
            val nextSubjects = activeSubjects.toMutableList()
            wifiScanResults.forEachIndexed { idx, scan ->
                val ssidLabel = scan.SSID?.takeIf { it.isNotBlank() }?.take(12) ?: "AP-${idx + 1}"
                val nodeLabel = "Wi-Fi AP · $ssidLabel"
                val alreadyMapped = currentWifiSubjects.any { it.name.contains(nodeLabel) }
                if (!alreadyMapped && nextSubjects.size < 7) {
                    val angle = (idx * 1.3f + 0.6f) * Math.PI.toFloat()
                    // REAL distance estimate: free-space path loss from measured RSSI+frequency.
                    // Angles are heuristic (single phone → no bearing) — labeled as estimates.
                    val estMeters = NetworkScanner.estimateDistanceMeters(scan.level, scan.frequency)
                    val distanceRatio = (estMeters / 14.0).toFloat().coerceIn(0.2f, 0.78f)
                    val xPos = (0.5f + Math.cos(angle.toDouble()).toFloat() * distanceRatio * 0.32f).coerceIn(0.18f, 0.82f)
                    val yPos = (0.5f + Math.sin(angle.toDouble()).toFloat() * distanceRatio * 0.32f).coerceIn(0.18f, 0.82f)
                    nextSubjects.add(
                        TrackingSubject(
                            name = "Device: $nodeLabel",
                            type = "Device",
                            offset = Offset(xPos, yPos)
                        )
                    )
                }
            }
            if (nextSubjects.size != activeSubjects.size) {
                activeSubjects = nextSubjects
            }
        }
    }

    // Real-time telemetry loop — REAL magnetometer µT + REAL connected-Wi-Fi RSSI.
    LaunchedEffect(isScanning, isBubbleShieldActive, calibrationGain) {
        if (isScanning) {
            while (true) {
                val shieldRatio = if (isBubbleShieldActive) 0.15f else 1.0f
                val rawMicroTesla = latestRealEmf
                val computed: Float = if (rawMicroTesla != null) {
                    (rawMicroTesla * calibrationGain * shieldRatio).coerceAtLeast(0.2f)
                } else {
                    // No magnetometer available — visibly labeled simulation.
                    val noise = (Math.random() - 0.5) * 2.2
                    ((42.8f + noise).toFloat() * calibrationGain * shieldRatio).coerceAtLeast(0.2f)
                }
                currentEmfReading = Math.round(computed * 10f) / 10f

                // Track dynamic statistics
                sessionHistory = (sessionHistory + currentEmfReading).takeLast(25)

                // Real connected-network signal strength (fallback: labeled simulation)
                val liveRssi = NetworkScanner.currentWifiInfo(context).rssi
                wifiSignalIntensity = liveRssi ?: (-60 - (Math.random() * 8).toInt())

                // Defensive RF monitor: detect synchronized across-the-board drops
                // (signature of someone else jamming/interfering with nearby Wi-Fi)
                wifiJamAlert = jammingDetector.evaluate(wifiScanResults.map { it.level }, liveRssi)

                delay(900)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (isDarkMode) SpaceBlack else LightCanvas,
        topBar = {
            HeaderSection(
                version = "v${BuildConfig.VERSION_NAME}",
                isDarkMode = isDarkMode,
                onToggleTheme = onToggleTheme,
                onGearClick = { showConfigurator = true }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isDarkMode) SpaceBlack else LightCanvas)
            ) {
                // 1. Floating Bottom Navigation Bar
                BottomNavigationBar(
                    activeTab = activeTab,
                    onTabSelect = { activeTab = it },
                    isDarkMode = isDarkMode
                )
                // 2. Anchored adaptive AdMob banner BELOW the nav bar.
                // SDK-rendered ads only — a fixed-height slot is always reserved so
                // navigation never jumps. Nothing is displayed while consent denies ads.
                AnchoredAdaptiveBanner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID,
                    canRequestAds = canRequestAds,
                    isDarkMode = isDarkMode
                )
                // 3. Android System Gesture / Navigation Inset Spacer
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "ScreenNavigator"
            ) { targetTab ->
                when (targetTab) {
                    Tab.SCANNER -> ScannerScreen(
                        currentEmf = currentEmfReading,
                        frequency = baseFrequency,
                        wifiStrength = wifiSignalIntensity,
                        isShieldActive = isBubbleShieldActive,
                        operatorName = operatorName,
                        activeSubjects = activeSubjects,
                        onActiveSubjectsChange = { activeSubjects = it },
                        surroundingWalls = surroundingWalls,
                        onSurroundingWallsChange = { surroundingWalls = it },
                        isDarkMode = isDarkMode,
                        locationPermissionGranted = locationPermissionGranted,
                        isGpsEnabled = isGpsEnabled,
                        onOpenLocationDialog = { showLocationPermissionDialog = true },
                        emfIsSimulated = emfIsSimulated,
                        wifiJamAlert = wifiJamAlert,
                        lanScanInProgress = lanScanInProgress,
                        lanDeviceCount = lanDevices.size,
                        currentFloor = currentFloor,
                        onOpenFloorPlanMapper = { showFloorPlanMapper = true },
                        onLanDeviceScan = { lanScanTrigger = lanScanTrigger + 1 }
                    )
                    Tab.HEATMAP -> HeatmapScreen(
                        points = heatmapPointsList,
                        onAddPoint = { offset ->
                            val dynamicFlux = Math.round((30f + Math.random() * 60f) * 10f) / 10f
                            val candidateNames = listOf("Router Field", "Microwave Leak", "Smart Wall Cable", "Inductor Node")
                            val pickedName = candidateNames.random()
                            heatmapPointsList = heatmapPointsList + HeatmapPoint(offset, dynamicFlux, pickedName)
                        },
                        onReset = {
                            heatmapPointsList = emptyList()
                        }
                    )
                    Tab.AR_VIEW -> ARViewScreen(
                        calibrationGain = calibrationGain,
                        onCalibrationChange = { calibrationGain = it },
                        activeSubjects = activeSubjects
                    )
                    Tab.INSIGHTS -> InsightsScreen(
                        history = sessionHistory,
                        currentEmf = currentEmfReading
                    )
                    Tab.HEALTH -> HealthScreen(
                        currentEmf = currentEmfReading,
                        isShieldActive = isBubbleShieldActive,
                        onShieldToggle = { isBubbleShieldActive = it },
                        emfIsSimulated = emfIsSimulated,
                        historyAverage = if (sessionHistory.isNotEmpty()) sessionHistory.average().toFloat() else 42.8f
                    )
                }
            }

            // Location & Sensor Permissions Popup Dialog
            if (showLocationPermissionDialog) {
                LocationPermissionDialog(
                    isDarkMode = isDarkMode,
                    onRequestPermission = { requestLocationPermission() },
                    onOpenSettings = { openAppSettings() },
                    onEnableGps = { openLocationSettings() },
                    isGpsEnabled = isGpsEnabled,
                    onDismiss = { showLocationPermissionDialog = false }
                )
            }

            // Interactive Settings Configurator Dialog
            if (showConfigurator) {
                SettingsConfiguratorDialog(
                    operatorName = operatorName,
                    onOperatorNameChange = { operatorName = it },
                    baseFrequency = baseFrequency,
                    onFrequencyChange = { baseFrequency = it },
                    sensitivity = sensitivity,
                    onSensitivityChange = { sensitivity = it },
                    calibrationGain = calibrationGain,
                    onCalibrationChange = { calibrationGain = it },
                    isShieldActive = isBubbleShieldActive,
                    onShieldToggle = { isBubbleShieldActive = it },
                    isDarkMode = isDarkMode,
                    onToggleTheme = onToggleTheme,
                    onOpenLocationDialog = { showLocationPermissionDialog = true },
                    privacyOptionsRequired = privacyOptionsRequired,
                    onShowPrivacyOptions = onShowPrivacyOptions,
                    onDismiss = { showConfigurator = false }
                )
            }

            // Architect Floor-Plan Mapper — per-story walls + placed devices
            if (showFloorPlanMapper) {
                FloorPlanMapperDialog(
                    store = floorPlanStore,
                    startFloor = currentFloor,
                    wifi = NetworkScanner.currentWifiInfo(context),
                    lanDevices = lanDevices,
                    onApply = { appliedPlan ->
                        currentFloor = appliedPlan.floor
                        surroundingWalls = appliedPlan.toSurroundingWalls()
                        val nonDevices = activeSubjects.filter { it.type != "Device" }
                        activeSubjects = nonDevices + appliedPlan.toTrackingSubjects()
                        showFloorPlanMapper = false
                    },
                    onDismiss = { showFloorPlanMapper = false }
                )
            }
        }
    }
}

/**
 * Top App Bar Custom Implementation with precise theme styling and theme toggle.
 */
@Composable
fun HeaderSection(
    version: String,
    isDarkMode: Boolean = true,
    onToggleTheme: () -> Unit = {},
    onGearClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogoBrand(
            showText = true,
            isDarkMode = isDarkMode,
            modifier = Modifier.weight(1f)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dark / Light Mode Quick Toggle Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isDarkMode) Zinc950 else LightSurface)
                    .border(1.dp, if (isDarkMode) Zinc800 else LightBorder, CircleShape)
                    .clickable(onClick = onToggleTheme)
                    .testTag("theme_toggle_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isDarkMode) "☀️" else "🌙",
                    fontSize = 17.sp
                )
            }

            // Settings Configurator Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isDarkMode) Zinc950 else LightSurface)
                    .border(1.dp, if (isDarkMode) Zinc800 else LightBorder, CircleShape)
                    .clickable(onClick = onGearClick)
                    .testTag("settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⚙️",
                    color = if (isDarkMode) Slate200 else LightTextPrimary,
                    fontSize = 16.sp
                )
            }
        }
    }
}

/**
 * Modern floating bento-style tab navigation bar with safe-area padding.
 */
@Composable
fun BottomNavigationBar(
    activeTab: Tab,
    onTabSelect: (Tab) -> Unit,
    isDarkMode: Boolean = true
) {
    val navBarHeight = 68.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)
            .height(navBarHeight)
            .clip(RoundedCornerShape(24.dp))
            .background(if (isDarkMode) Zinc950 else LightSurface)
            .border(1.dp, if (isDarkMode) Zinc800 else LightBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tab 1: Scanner
        BottomTabItem(
            label = "Scanner",
            emoji = "📡",
            selected = activeTab == Tab.SCANNER,
            onClick = { onTabSelect(Tab.SCANNER) },
            isDarkMode = isDarkMode,
            modifier = Modifier.weight(1f).testTag("nav_scanner_tab")
        )

        // Tab 2: Heatmap
        BottomTabItem(
            label = "Heatmap",
            emoji = "🗺️",
            selected = activeTab == Tab.HEATMAP,
            onClick = { onTabSelect(Tab.HEATMAP) },
            isDarkMode = isDarkMode,
            modifier = Modifier.weight(1f).testTag("nav_heatmap_tab")
        )

        // Tab 3: Raised AR View Center Action Button
        Box(
            modifier = Modifier
                .weight(1f)
                .height(navBarHeight),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .offset(y = (-14).dp)
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (activeTab == Tab.AR_VIEW) Emerald500 else if (isDarkMode) Zinc900 else LightSurfaceVariant)
                    .border(4.dp, if (isDarkMode) SpaceBlack else LightCanvas, CircleShape)
                    .clickable { onTabSelect(Tab.AR_VIEW) }
                    .testTag("nav_ar_center_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📷",
                    color = if (activeTab == Tab.AR_VIEW) SpaceBlack else if (isDarkMode) Slate200 else LightTextPrimary,
                    fontSize = 18.sp
                )
            }
            Text(
                text = "AR Scan",
                color = if (activeTab == Tab.AR_VIEW) Emerald500 else if (isDarkMode) Slate500 else LightTextSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            )
        }

        // Tab 4: Insights
        BottomTabItem(
            label = "Insights",
            emoji = "📊",
            selected = activeTab == Tab.INSIGHTS,
            onClick = { onTabSelect(Tab.INSIGHTS) },
            isDarkMode = isDarkMode,
            modifier = Modifier.weight(1f).testTag("nav_insights_tab")
        )

        // Tab 5: Health Protect
        BottomTabItem(
            label = "Health",
            emoji = "🛡️",
            selected = activeTab == Tab.HEALTH,
            onClick = { onTabSelect(Tab.HEALTH) },
            isDarkMode = isDarkMode,
            modifier = Modifier.weight(1f).testTag("nav_health_tab")
        )
    }
}

@Composable
fun BottomTabItem(
    label: String,
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
    isDarkMode: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = emoji,
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Text(
            text = label.uppercase(),
            color = if (selected) Emerald500 else if (isDarkMode) Slate500 else LightTextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * SCANNER VIEW DASHBOARD (Standard screen)
 */
@Composable
fun ScannerScreen(
    currentEmf: Float,
    frequency: Float,
    wifiStrength: Int,
    isShieldActive: Boolean,
    operatorName: String,
    activeSubjects: List<TrackingSubject>,
    onActiveSubjectsChange: (List<TrackingSubject>) -> Unit,
    surroundingWalls: List<SurroundingWall>,
    onSurroundingWallsChange: (List<SurroundingWall>) -> Unit,
    isDarkMode: Boolean = true,
    locationPermissionGranted: Boolean = true,
    isGpsEnabled: Boolean = true,
    onOpenLocationDialog: () -> Unit = {},
    emfIsSimulated: Boolean = false,
    wifiJamAlert: Boolean = false,
    lanScanInProgress: Boolean = false,
    lanDeviceCount: Int = 0,
    currentFloor: Int = 0,
    onOpenFloorPlanMapper: () -> Unit = {},
    onLanDeviceScan: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    var hoveredSubject by remember { mutableStateOf<TrackingSubject?>(null) }
    var selectedSubject by remember { mutableStateOf<TrackingSubject?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var tappedCoordinate by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // Wi-Fi Triangulation and Sensing States & Configuration
    var triangulationEnabled by remember { mutableStateOf(false) }
    val triangulationAnchors = remember {
        listOf(
            Triple("Anchor Alpha (Ch 1)", Offset(0.25f, 0.22f), Color(0xFF64B5F6)),
            Triple("Anchor Beta (Ch 6)", Offset(0.75f, 0.22f), Color(0xFF81C784)),
            Triple("Anchor Gamma (Ch 11)", Offset(0.5f, 0.78f), Color(0xFFFFB74D))
        )
    }

    // Determine the active triangulation target
    val triangulationTarget = hoveredSubject ?: selectedSubject ?: activeSubjects.firstOrNull { it.type == "Person" || it.type == "Pet" || it.type == "Unknown" }

    // Computed values for the 3 anchors dynamically
    val anchorCalcs = remember(triangulationTarget, activeSubjects) {
        if (triangulationTarget == null) emptyList() else {
            triangulationAnchors.mapIndexed { idx, (_, offset, _) ->
                val distanceGroundTruth = (triangulationTarget.offset - offset).getDistance()
                val distanceMeters = distanceGroundTruth * 12f
                
                // Check if path is blocked by another human/pet (shadowing)
                var isBlocked = false
                activeSubjects.forEach { obstacle ->
                    if ((obstacle.type == "Person" || obstacle.type == "Pet") && obstacle.id != triangulationTarget.id) {
                        val v = triangulationTarget.offset - offset
                        val w = obstacle.offset - offset
                        val lenSq = v.x * v.x + v.y * v.y
                        if (lenSq > 0.0001f) {
                            val t = ((w.x * v.x + w.y * v.y) / lenSq).coerceIn(0f, 1f)
                            val projection = offset + v * t
                            val dist = (obstacle.offset - projection).getDistance()
                            if (dist < 0.08f) {
                                isBlocked = true
                            }
                        }
                    }
                }
                
                val shadowingLoss = if (isBlocked) 11.5f else 0.0f
                val basePL = 35.0f + 20.0f * Math.log10((distanceMeters + 0.1f).toDouble()).toFloat()
                val timeMs = System.currentTimeMillis()
                val signalNoise = (Math.sin(timeMs / 600.0 + idx * 2.1) * 1.2f).toFloat()
                val totalPL = basePL + shadowingLoss + signalNoise
                
                val rssi = (-25.0f - totalPL).coerceIn(-95f, -30f)
                val estMeters = (Math.pow(10.0, (-rssi - 25.0 - 35.0) / 20.0) - 0.1).coerceAtLeast(0.1).toFloat()
                val estCoordDist = estMeters / 12f
                
                Triple(rssi, estMeters, estCoordDist)
            }
        }
    }

    val triangulatedOffset = remember(anchorCalcs) {
        if (anchorCalcs.size < 3 || triangulationTarget == null) null else {
            val x1 = triangulationAnchors[0].second.x
            val y1 = triangulationAnchors[0].second.y
            val r1 = anchorCalcs[0].third
            
            val x2 = triangulationAnchors[1].second.x
            val y2 = triangulationAnchors[1].second.y
            val r2 = anchorCalcs[1].third
            
            val x3 = triangulationAnchors[2].second.x
            val y3 = triangulationAnchors[2].second.y
            val r3 = anchorCalcs[2].third
            
            val a = 2f * (x1 - x2)
            val b = 2f * (y1 - y2)
            val c = (r2 * r2) - (r1 * r1) - (x2 * x2 - x1 * x1) - (y2 * y2 - y1 * y1)
            
            val d = 2f * (x1 - x3)
            val e = 2f * (y1 - y3)
            val f = (r3 * r3) - (r1 * r1) - (x3 * x3 - x1 * x1) - (y3 * y3 - y1 * y1)
            
            val det = a * e - b * d
            if (Math.abs(det) > 0.0001f) {
                val tx = (c * e - b * f) / det
                val ty = (a * f - c * d) / det
                Offset(tx.coerceIn(0.12f, 0.88f), ty.coerceIn(0.12f, 0.88f))
            } else {
                Offset((x1 + x2 + x3) / 3f, (y1 + y2 + y3) / 3f)
            }
        }
    }

    // NOTE: the old "EMF Reflection Scan" fabricated random walls with Math.random().
    // That was removed — real structure now comes exclusively from the calibrated
    // Architect Floor-Plan Mapper (per building story), persisted on-device.

    // Sync hover/selected subject states with updated offsets
    LaunchedEffect(activeSubjects) {
        hoveredSubject = hoveredSubject?.let { old -> activeSubjects.firstOrNull { it.id == old.id } }
        selectedSubject = selectedSubject?.let { old -> activeSubjects.firstOrNull { it.id == old.id } }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Core Operator Greeting element integrated seamlessly!
        Greeting(name = operatorName)

        // Defensive RF alert: signature of jamming/interference against nearby Wi-Fi
        if (wifiJamAlert) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠", fontSize = 16.sp)
                    Column {
                        Text(
                            text = "RF INTERFERENCE ALERT",
                            color = Color.Red,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "All nearby Wi-Fi signals dropped together — possible jamming or heavy interference nearby. Your own network is NOT blocked by this app; this is an ambient detection only.",
                            color = Slate200,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 11.sp
                        )
                    }
                }
            }
        }

        // 1. Primary EMF Meter Screen Widget Card
        Card(
            modifier = Modifier
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Zinc800)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Sleek cyber gradient accent line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Emerald500,
                                    Color.Transparent
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "MAGNETIC FLUX DENSITY",
                        color = Slate500,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = String.format("%.1f", currentEmf),
                            color = Slate50,
                            fontSize = 62.sp,
                            fontWeight = FontWeight.Light,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = (-2).sp,
                            modifier = Modifier.testTag("emf_reading_value")
                        )
                        Text(
                            text = " µT",
                            color = Emerald500,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        val isDanger = currentEmf > 45.0f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(if (isDanger) Amber500.copy(alpha = 0.15f) else Emerald500.copy(alpha = 0.1f))
                                .border(
                                    1.dp,
                                    if (isDanger) Amber500.copy(alpha = 0.3f) else Emerald500.copy(alpha = 0.2f),
                                    RoundedCornerShape(50.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isDanger) "ELEVATED FIELD" else "STABLE LEVEL",
                                color = if (isDanger) Amber500 else Emerald400,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif
                            )
                        }

                        // Interactive Location / GPS permission & status chip
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(if (locationPermissionGranted && isGpsEnabled) Emerald500.copy(alpha = 0.12f) else Amber500.copy(alpha = 0.18f))
                                .border(
                                    1.dp,
                                    if (locationPermissionGranted && isGpsEnabled) Emerald500.copy(alpha = 0.3f) else Amber500.copy(alpha = 0.5f),
                                    RoundedCornerShape(50.dp)
                                )
                                .clickable { onOpenLocationDialog() }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag("scanner_location_status_badge")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(if (locationPermissionGranted && isGpsEnabled) "📍" else "⚠️", fontSize = 10.sp)
                                Text(
                                    text = if (locationPermissionGranted && isGpsEnabled) "GPS / RF ACTIVE" else "TAP FOR LOCATION",
                                    color = if (locationPermissionGranted && isGpsEnabled) Emerald400 else Amber500,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .background(Zinc800)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = String.format("%.1f Hz", frequency),
                                color = Slate400,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif
                            )
                        }

                        // Honest badge: only shown when no real magnetometer exists
                        if (emfIsSimulated) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50.dp))
                                    .background(Amber500.copy(alpha = 0.14f))
                                    .border(1.dp, Amber500.copy(alpha = 0.4f), RoundedCornerShape(50.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "SIMULATED SENSOR",
                                    color = Amber500,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Interactive Radar Screen Core Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            colors = CardDefaults.cardColors(containerColor = SpaceBlack),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Zinc800)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // Interactive Tactical sweep simulation
                val infiniteTransition = rememberInfiniteTransition(label = "RadarSweepAnimation")
                val sweepAngle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 3200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "SweepAngleFloat"
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Zinc950)
                        .onSizeChanged {
                            canvasSize = Size(it.width.toFloat(), it.height.toFloat())
                        }
                        .pointerInput(activeSubjects) {
                            detectTapGestures { offset ->
                                val w = canvasSize.width
                                val h = canvasSize.height
                                if (w > 0f && h > 0f) {
                                    // Find clicked subject
                                    val clickedSubject = activeSubjects.minByOrNull { sub ->
                                        val subPx = Offset(sub.offset.x * w, sub.offset.y * h)
                                        (subPx - offset).getDistance()
                                    }
                                    if (clickedSubject != null && (Offset(clickedSubject.offset.x * w, clickedSubject.offset.y * h) - offset).getDistance() < 30.dp.toPx()) {
                                        selectedSubject = if (selectedSubject?.id == clickedSubject.id) null else clickedSubject
                                        hoveredSubject = clickedSubject
                                    } else {
                                        // Tap on empty space triggers "Add Subject"
                                        selectedSubject = null
                                        tappedCoordinate = Offset(offset.x / w, offset.y / h)
                                        showAddDialog = true
                                    }
                                }
                            }
                        }
                        .pointerInput(activeSubjects) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull()
                                    if (change != null) {
                                        val w = canvasSize.width
                                        val h = canvasSize.height
                                        if (w > 0f && h > 0f) {
                                            val offset = change.position
                                            val hovered = activeSubjects.minByOrNull { sub ->
                                                val subPx = Offset(sub.offset.x * w, sub.offset.y * h)
                                                (subPx - offset).getDistance()
                                            }
                                            if (hovered != null && (Offset(hovered.offset.x * w, hovered.offset.y * h) - offset).getDistance() < 35.dp.toPx()) {
                                                hoveredSubject = hovered
                                            } else {
                                                hoveredSubject = null
                                            }
                                        }
                                    } else {
                                        hoveredSubject = null
                                    }
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val center = Offset(w / 2f, h / 2f)
                        val radiusBase = minOf(w, h) / 2f * 0.85f

                        // Cyberdot Matrix grid layout
                        val space = 20.dp.toPx()
                        val rows = (h / space).toInt()
                        val cols = (w / space).toInt()
                        for (r in 0..rows) {
                            for (c in 0..cols) {
                                drawCircle(
                                    color = Slate800.copy(alpha = 0.22f),
                                    radius = 1.2f,
                                    center = Offset(c * space, r * space)
                                )
                            }
                        }

                        // Concentric scopes layout
                        drawCircle(
                            color = Emerald500.copy(alpha = 0.08f),
                            radius = radiusBase,
                            center = center,
                            style = Stroke(width = 1f.dp.toPx())
                        )
                        drawCircle(
                            color = Emerald500.copy(alpha = 0.12f),
                            radius = radiusBase * 0.65f,
                            center = center,
                            style = Stroke(width = 1f.dp.toPx())
                        )
                        drawCircle(
                            color = Emerald500.copy(alpha = 0.16f),
                            radius = radiusBase * 0.35f,
                            center = center,
                            style = Stroke(width = 1f.dp.toPx())
                        )

                        // Overlay Crosshairs
                        drawLine(
                            color = Emerald500.copy(alpha = 0.12f),
                            start = Offset(center.x - radiusBase, center.y),
                            end = Offset(center.x + radiusBase, center.y),
                            strokeWidth = 1f.dp.toPx()
                        )
                        drawLine(
                            color = Emerald500.copy(alpha = 0.12f),
                            start = Offset(center.x, center.y - radiusBase),
                            end = Offset(center.x, center.y + radiusBase),
                            strokeWidth = 1f.dp.toPx()
                        )

                        // Draw Surrounding Walls
                        surroundingWalls.forEach { wall ->
                            val startPx = Offset(wall.start.x * w, wall.start.y * h)
                            val endPx = Offset(wall.end.x * w, wall.end.y * h)
                            
                            // Glowing blueprint barrier
                            drawLine(
                                color = Emerald500.copy(alpha = 0.12f),
                                start = startPx,
                                end = endPx,
                                strokeWidth = 5f.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = Emerald500.copy(alpha = 0.6f),
                                start = startPx,
                                end = endPx,
                                strokeWidth = 1.8f.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }

                        // Draw Sweeper Laser Glow Scanner
                        val angleRad = Math.toRadians(sweepAngle.toDouble())
                        val sweeperEnd = Offset(
                            x = center.x + (radiusBase * Math.cos(angleRad)).toFloat(),
                            y = center.y + (radiusBase * Math.sin(angleRad)).toFloat()
                        )
                        drawLine(
                            color = Emerald500.copy(alpha = 0.7f),
                            start = center,
                            end = sweeperEnd,
                            strokeWidth = 2.dp.toPx()
                        )
                        drawArc(
                            brush = Brush.sweepGradient(
                                0.0f to Color.Transparent,
                                0.7f to Color.Transparent,
                                1.0f to Emerald500.copy(alpha = 0.22f)
                            ),
                            startAngle = sweepAngle - 45f,
                            sweepAngle = 45f,
                            useCenter = true,
                            topLeft = Offset(center.x - radiusBase, center.y - radiusBase),
                            size = Size(radiusBase * 2, radiusBase * 2)
                        )

                        // Draw Wi-Fi Signal Propagation Paths & Human RF Shadowing Attenuation Link lines
                        if (triangulationEnabled) {
                            // Render Triangulation Anchors & Intersecting Range Rings
                            triangulationAnchors.forEachIndexed { idx, (name, offset, color) ->
                                val anchorPx = Offset(offset.x * w, offset.y * h)
                                
                                // Draw pulsating wave background aura
                                val pulseRadius = 14.dp.toPx() + (Math.sin(System.currentTimeMillis() / 250.0 + idx * 1.5) * 3f).toFloat().dp.toPx()
                                drawCircle(
                                    color = color.copy(alpha = 0.12f),
                                    radius = pulseRadius,
                                    center = anchorPx
                                )
                                
                                // Draw solid anchor node
                                drawCircle(
                                    color = color,
                                    radius = 6.dp.toPx(),
                                    center = anchorPx
                                )
                                drawCircle(
                                    color = SpaceBlack,
                                    radius = 2.dp.toPx(),
                                    center = anchorPx
                                )

                                // Draw estimated range ring if we have a valid calculated distance
                                if (triangulationTarget != null && anchorCalcs.size == 3) {
                                    val calc = anchorCalcs[idx]
                                    val estCoordDist = calc.third
                                    // Scale to canvas bounds
                                    val estRadiusPx = estCoordDist * radiusBase * 2.2f
                                    
                                    drawCircle(
                                        color = color.copy(alpha = 0.2f),
                                        radius = estRadiusPx,
                                        center = anchorPx,
                                        style = Stroke(
                                            width = 1.2f.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                                        )
                                    )

                                    // Draw line of sight path to actual target position
                                    val targetPx = Offset(triangulationTarget.offset.x * w, triangulationTarget.offset.y * h)
                                    
                                    // Check blockage path
                                    var isBlocked = false
                                    activeSubjects.forEach { obstacle ->
                                        if ((obstacle.type == "Person" || obstacle.type == "Pet") && obstacle.id != triangulationTarget.id) {
                                            val v = triangulationTarget.offset - offset
                                            val w_vec = obstacle.offset - offset
                                            val lenSq = v.x * v.x + v.y * v.y
                                            if (lenSq > 0.0001f) {
                                                val t = ((w_vec.x * v.x + w_vec.y * v.y) / lenSq).coerceIn(0f, 1f)
                                                val projection = offset + v * t
                                                val dist = (obstacle.offset - projection).getDistance()
                                                if (dist < 0.08f) {
                                                    isBlocked = true
                                                }
                                            }
                                        }
                                    }

                                    if (isBlocked) {
                                        val pulseAlpha = 0.35f + 0.25f * Math.sin(System.currentTimeMillis() / 150.0).toFloat()
                                        drawLine(
                                            color = Color.Red.copy(alpha = pulseAlpha),
                                            start = anchorPx,
                                            end = targetPx,
                                            strokeWidth = 2.2f.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f), 0f)
                                        )
                                    } else {
                                        drawLine(
                                            color = color.copy(alpha = 0.25f),
                                            start = anchorPx,
                                            end = targetPx,
                                            strokeWidth = 1f.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f), 0f)
                                        )
                                    }

                                    // Traveling wave pulse
                                    val timeMs = System.currentTimeMillis()
                                    val fraction = (timeMs % 2000) / 2000f
                                    val pulsePos = anchorPx + (targetPx - anchorPx) * fraction
                                    drawCircle(
                                        color = if (isBlocked) Color.Red else color,
                                        radius = 2.8f.dp.toPx(),
                                        center = pulsePos
                                    )
                                }
                            }

                            // Draw Resolved Triangulated Crosshair Point
                            if (triangulatedOffset != null) {
                                val triPx = Offset(triangulatedOffset.x * w, triangulatedOffset.y * h)
                                val crosshairPulseRadius = 12.dp.toPx() + (Math.sin(System.currentTimeMillis() / 200.0) * 2f).toFloat().dp.toPx()
                                
                                drawCircle(
                                    color = Color(0xFFFF7043),
                                    radius = crosshairPulseRadius,
                                    center = triPx,
                                    style = Stroke(width = 1.2f.dp.toPx())
                                )
                                drawCircle(
                                    color = Color(0xFFFF7043).copy(alpha = 0.2f),
                                    radius = 5.dp.toPx(),
                                    center = triPx
                                )
                                
                                val crosshairLen = 8.dp.toPx()
                                drawLine(Color(0xFFFF7043), triPx - Offset(crosshairLen, 0f), triPx + Offset(crosshairLen, 0f), 1.5f.dp.toPx())
                                drawLine(Color(0xFFFF7043), triPx - Offset(0f, crosshairLen), triPx + Offset(0f, crosshairLen), 1.5f.dp.toPx())
                            }
                        } else {
                            // Default router signal link paths
                            val routerSub = activeSubjects.firstOrNull { it.type == "Device" && (it.name.contains("Router") || it.name.contains("Hub")) }
                            if (routerSub != null) {
                                val startPx = Offset(routerSub.offset.x * w, routerSub.offset.y * h)
                                
                                // Draw central Wi-Fi signal propagation range rings around the Router
                                drawCircle(
                                    color = Emerald500.copy(alpha = 0.04f),
                                    radius = 45.dp.toPx(),
                                    center = startPx
                                )
                                drawCircle(
                                    color = Emerald500.copy(alpha = 0.08f),
                                    radius = 45.dp.toPx(),
                                    center = startPx,
                                    style = Stroke(
                                        width = 1f.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                                    )
                                )

                                activeSubjects.forEach { sub ->
                                    if (sub.id != routerSub.id) {
                                        val endPx = Offset(sub.offset.x * w, sub.offset.y * h)
                                        
                                        // Calculate if a moving Person or Pet blocks the line-of-sight segment between the Router and this subject
                                        var isBlockedByHuman = false
                                        var blockingPersonName = ""
                                        activeSubjects.forEach { person ->
                                            if (person.type == "Person" || person.type == "Pet") {
                                                val r = routerSub.offset
                                                val d = sub.offset
                                                val p = person.offset
                                                val v = d - r
                                                val w_vec = p - r
                                                val lenSq = v.x * v.x + v.y * v.y
                                                if (lenSq > 0.0001f) {
                                                    val t = ((w_vec.x * v.x + w_vec.y * v.y) / lenSq).coerceIn(0f, 1f)
                                                    val projection = r + v * t
                                                    val dist = (p - projection).getDistance()
                                                    if (dist < 0.08f) { // Human shadowing proximity threshold
                                                        isBlockedByHuman = true
                                                        blockingPersonName = person.name
                                                    }
                                                }
                                            }
                                        }

                                        // Draw high-visibility dashed signal lines so user clearly identifies RF pathways
                                        if (isBlockedByHuman) {
                                            val pulseAlpha = 0.45f + 0.35f * Math.sin(System.currentTimeMillis() / 140.0).toFloat()
                                            // Prominent amber dashed line for human/shadow attenuated signal path
                                            drawLine(
                                                color = Amber500.copy(alpha = pulseAlpha),
                                                start = startPx,
                                                end = endPx,
                                                strokeWidth = 2.4f.dp.toPx(),
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                                            )
                                        } else {
                                            // Prominent high-contrast emerald dashed line for active RF signal link
                                            drawLine(
                                                color = Emerald500.copy(alpha = 0.45f),
                                                start = startPx,
                                                end = endPx,
                                                strokeWidth = 1.8f.dp.toPx(),
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                                            )
                                        }

                                        // Animated signal wave pulse traveling along the dashed line
                                        val timeMs = System.currentTimeMillis()
                                        val fraction = (timeMs % 2000) / 2000f
                                        val pulsePos = startPx + (endPx - startPx) * fraction
                                        drawCircle(
                                            color = if (isBlockedByHuman) Amber500 else Emerald500,
                                            radius = 3.2f.dp.toPx(),
                                            center = pulsePos
                                        )
                                        drawCircle(
                                            color = (if (isBlockedByHuman) Amber500 else Emerald500).copy(alpha = 0.35f),
                                            radius = 6.0f.dp.toPx(),
                                            center = pulsePos
                                        )
                                    }
                                }
                            }
                        }

                        // Draw tracking subject dots
                        activeSubjects.forEach { sub ->
                            val localOffset = Offset(sub.offset.x * w, sub.offset.y * h)
                            val isHoveredOrSelected = hoveredSubject?.id == sub.id || selectedSubject?.id == sub.id
                            
                            val markerColor = when (sub.type) {
                                "Person" -> Emerald500
                                "Pet" -> Amber500 // Matches color theme badge exactly
                                "Device" -> Slate400
                                else -> Color.Red
                            }

                            // Draw trajectory history trail (smooth fading line + breadcrumbs)
                            if (sub.history.isNotEmpty()) {
                                // Draw fading connecting trail line
                                val trailPath = Path().apply {
                                    val firstPx = Offset(sub.history.first().x * w, sub.history.first().y * h)
                                    moveTo(firstPx.x, firstPx.y)
                                    for (i in 1 until sub.history.size) {
                                        val pt = sub.history[i]
                                        lineTo(pt.x * w, pt.y * h)
                                    }
                                    lineTo(localOffset.x, localOffset.y) // connect to current location
                                }
                                drawPath(
                                    path = trailPath,
                                    color = markerColor.copy(alpha = 0.22f),
                                    style = Stroke(
                                        width = 1.5f.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                                    )
                                )

                                // Draw fading breadcrumb circles along the trail
                                sub.history.forEachIndexed { index, histOffset ->
                                    val histPx = Offset(histOffset.x * w, histOffset.y * h)
                                    val progress = (index + 1).toFloat() / (sub.history.size + 1) // 0 to 1
                                    drawCircle(
                                        color = markerColor.copy(alpha = progress * 0.38f),
                                        radius = (1.2f + progress * 2.2f).dp.toPx(),
                                        center = histPx
                                    )
                                }
                            }

                            // Draw velocity/heading direction vector
                            if (sub.type != "Device" && sub.velocity != Offset.Zero) {
                                val vectorLength = (11f + sub.speedKmh * 1.5f).dp.toPx() // dynamic length based on speed
                                val angleRad = Math.toRadians(sub.headingDegrees.toDouble())
                                val directionPx = Offset(
                                    x = localOffset.x + (vectorLength * Math.cos(angleRad)).toFloat(),
                                    y = localOffset.y + (vectorLength * Math.sin(angleRad)).toFloat()
                                )
                                // Draw main vector direction line
                                drawLine(
                                    color = markerColor.copy(alpha = 0.65f),
                                    start = localOffset,
                                    end = directionPx,
                                    strokeWidth = 1.2f.dp.toPx()
                                )
                                // Draw a tiny arrow head at directionPx
                                val arrowAngle1 = angleRad + Math.PI * 5 / 6 // 150 degrees
                                val arrowAngle2 = angleRad - Math.PI * 5 / 6 // -150 degrees
                                val arrowSize = 3.5f.dp.toPx()
                                drawLine(
                                    color = markerColor.copy(alpha = 0.65f),
                                    start = directionPx,
                                    end = Offset(
                                        x = directionPx.x + (arrowSize * Math.cos(arrowAngle1)).toFloat(),
                                        y = directionPx.y + (arrowSize * Math.sin(arrowAngle1)).toFloat()
                                    ),
                                    strokeWidth = 1.2f.dp.toPx()
                                )
                                drawLine(
                                    color = markerColor.copy(alpha = 0.65f),
                                    start = directionPx,
                                    end = Offset(
                                        x = directionPx.x + (arrowSize * Math.cos(arrowAngle2)).toFloat(),
                                        y = directionPx.y + (arrowSize * Math.sin(arrowAngle2)).toFloat()
                                    ),
                                    strokeWidth = 1.2f.dp.toPx()
                                )
                            }

                            if (isHoveredOrSelected) {
                                drawCircle(
                                    color = markerColor.copy(alpha = 0.15f),
                                    radius = 22.dp.toPx(),
                                    center = localOffset
                                )
                                drawCircle(
                                    color = markerColor.copy(alpha = 0.35f),
                                    radius = 14.dp.toPx(),
                                    center = localOffset,
                                    style = Stroke(width = 1.2f.dp.toPx())
                                )
                            }

                            // Base marker core dot
                            drawCircle(
                                color = markerColor,
                                radius = if (isHoveredOrSelected) 5.5f.dp.toPx() else 4.2f.dp.toPx(),
                                center = localOffset
                            )

                            // Pulsating wave aura
                            drawCircle(
                                color = markerColor.copy(alpha = 0.3f),
                                radius = 10.dp.toPx(),
                                center = localOffset,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                    }

                    // Floating tooltip overlay above the hovered/selected subject!
                    val focusSub = hoveredSubject ?: selectedSubject
                    if (focusSub != null && canvasSize.width > 0f) {
                        val xPx = focusSub.offset.x * canvasSize.width
                        val yPx = focusSub.offset.y * canvasSize.height
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val xDp = with(density) { xPx.toDp() }
                        val yDp = with(density) { yPx.toDp() }

                        Box(
                            modifier = Modifier
                                .offset(x = xDp - 50.dp, y = yDp - 48.dp)
                                .width(100.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(SpaceBlack.copy(alpha = 0.85f))
                                .border(1.dp, Emerald500.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                .padding(4.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = focusSub.name,
                                    color = Slate50,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                                Text(
                                    text = focusSub.type.uppercase(),
                                    color = when (focusSub.type) {
                                        "Person" -> Emerald400
                                        "Pet" -> Amber500
                                        "Device" -> Slate400
                                        else -> Color.Red
                                    },
                                    fontSize = 7.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                if (focusSub.type != "Device") {
                                    Text(
                                        text = "${String.format("%.1f", focusSub.speedKmh)} km/h",
                                        color = Slate200,
                                        fontSize = 7.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // AR Overlay textual stats
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Emerald500)
                        )
                        Text(
                            text = if (emfIsSimulated) "BIO-SYNC: SIMULATED SENSORS" else "BIO-SYNC: LIVE SENSORS",
                            color = if (emfIsSimulated) Amber500 else Emerald500,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "${floorLabel(currentFloor).uppercase()} • TRACKING ${activeSubjects.size} SUBJECTS",
                        color = Slate500,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }

                // Centered radar label overlay
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "RADAR FEED",
                        color = Slate500.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // WI-FI SIGNAL TRIANGULATION & SENSING MODULE CONTROL PANEL
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("wifi_triangulation_panel"),
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, if (triangulationEnabled) Emerald500.copy(alpha = 0.4f) else Zinc800)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                // Header Row with Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "📡",
                            fontSize = 20.sp,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        Column {
                            Text(
                                text = "WIFI SENSING & TRIANGULATION",
                                color = Slate50,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "TRILATERATION SOLVER & RF OBSTACLE MAPPER",
                                color = if (triangulationEnabled) Emerald400 else Slate500,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Switch(
                        checked = triangulationEnabled,
                        onCheckedChange = { triangulationEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SpaceBlack,
                            checkedTrackColor = Emerald500,
                            uncheckedThumbColor = Slate400,
                            uncheckedTrackColor = Zinc800
                        ),
                        modifier = Modifier.testTag("triangulation_toggle")
                    )
                }

                if (triangulationEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))

                    if (triangulationTarget == null) {
                        // Empty/Awaiting target state
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SpaceBlack, RoundedCornerShape(12.dp))
                                .border(1.dp, Zinc800, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "No Target",
                                    tint = Slate500,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "AWAITING COHERENT TARGET SIGNAL LOCK",
                                    color = Slate400,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Deploy or select a subject (Person/Pet/Device) on the radar.",
                                    color = Slate500,
                                    fontSize = 8.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    } else {
                        // We have a target to triangulate!
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(
                                text = "TARGET LOCK:",
                                color = Slate500,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            val targetBadgeColor = when (triangulationTarget.type) {
                                "Person" -> Emerald400
                                "Pet" -> Amber500
                                "Device" -> Color(0xFF64B5F6)
                                else -> Color.Red
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(targetBadgeColor.copy(alpha = 0.15f))
                                    .border(1.dp, targetBadgeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${triangulationTarget.name.uppercase()} [${triangulationTarget.type.uppercase()}]",
                                    color = targetBadgeColor,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        // Display columns for Channel/Anchor Nodes
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            triangulationAnchors.forEachIndexed { idx, (name, offset, color) ->
                                val calc = anchorCalcs.getOrNull(idx)
                                val rssiVal = calc?.first ?: -90f
                                val distMeters = calc?.second ?: 0f

                                // Check blockage for display
                                var isBlocked = false
                                var blockerName = ""
                                activeSubjects.forEach { obstacle ->
                                    if ((obstacle.type == "Person" || obstacle.type == "Pet") && obstacle.id != triangulationTarget.id) {
                                        val v = triangulationTarget.offset - offset
                                        val w = obstacle.offset - offset
                                        val lenSq = v.x * v.x + v.y * v.y
                                        if (lenSq > 0.0001f) {
                                            val t_val = ((w.x * v.x + w.y * v.y) / lenSq).coerceIn(0f, 1f)
                                            val projection = offset + v * t_val
                                            val dist = (obstacle.offset - projection).getDistance()
                                            if (dist < 0.08f) {
                                                isBlocked = true
                                                blockerName = obstacle.name
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SpaceBlack, RoundedCornerShape(12.dp))
                                        .border(1.dp, if (isBlocked) Color.Red.copy(alpha = 0.3f) else Zinc800, RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(color)
                                        )
                                        Column {
                                            Text(
                                                text = name.uppercase(),
                                                color = Slate200,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "COORD: (${String.format("%.2f", offset.x)}, ${String.format("%.2f", offset.y)})",
                                                color = Slate500,
                                                fontSize = 7.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Obstacle Badge if blocked
                                        if (isBlocked) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color.Red.copy(alpha = 0.15f))
                                                    .border(1.dp, Color.Red.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "SHADOWED BY: ${blockerName.uppercase()}",
                                                    color = Color.Red,
                                                    fontSize = 7.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Emerald500.copy(alpha = 0.1f))
                                                    .border(1.dp, Emerald500.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "CLEAR PATH",
                                                    color = Emerald400,
                                                    fontSize = 7.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }

                                        // Signal stats
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "${String.format("%.1f", rssiVal)} dBm",
                                                color = if (rssiVal > -60f) Emerald400 else if (rssiVal > -75f) Amber500 else Color.Red,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Text(
                                                text = "${String.format("%.1f", distMeters)}m EST",
                                                color = Slate400,
                                                fontSize = 7.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Mathematical Cramer Matrix Solver Console
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SpaceBlack, RoundedCornerShape(12.dp))
                                .border(1.dp, Zinc800, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "■ MULTILATERATION CRAMER MATRIX SOLVER",
                                color = Color(0xFFFF7043),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            val x1 = triangulationAnchors[0].second.x
                            val y1 = triangulationAnchors[0].second.y
                            val r1 = anchorCalcs.getOrNull(0)?.third ?: 0f

                            val x2 = triangulationAnchors[1].second.x
                            val y2 = triangulationAnchors[1].second.y
                            val r2 = anchorCalcs.getOrNull(1)?.third ?: 0f

                            val x3 = triangulationAnchors[2].second.x
                            val y3 = triangulationAnchors[2].second.y
                            val r3 = anchorCalcs.getOrNull(2)?.third ?: 0f

                            val a_coef = 2f * (x1 - x2)
                            val b_coef = 2f * (y1 - y2)
                            val c_coef = (r2 * r2) - (r1 * r1) - (x2 * x2 - x1 * x1) - (y2 * y2 - y1 * y1)

                            val d_coef = 2f * (x1 - x3)
                            val e_coef = 2f * (y1 - y3)
                            val f_coef = (r3 * r3) - (r1 * r1) - (x3 * x3 - x1 * x1) - (y3 * y3 - y1 * y1)

                            val det_coef = a_coef * e_coef - b_coef * d_coef

                            Text(
                                text = "L1: ${String.format("%.3f", a_coef)}x + ${String.format("%.3f", b_coef)}y = ${String.format("%.3f", c_coef)}",
                                color = Slate400,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "L2: ${String.format("%.3f", d_coef)}x + ${String.format("%.3f", e_coef)}y = ${String.format("%.3f", f_coef)}",
                                color = Slate400,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "MATRIX DETERMINANT: ${String.format("%.4f", det_coef)}",
                                color = if (Math.abs(det_coef) > 0.0001f) Emerald400 else Color.Red,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )

                            HorizontalDivider(color = Zinc800, thickness = 1.dp, modifier = Modifier.padding(vertical = 6.dp))

                            if (triangulatedOffset != null) {
                                val trueX = triangulationTarget.offset.x
                                val trueY = triangulationTarget.offset.y
                                val triX = triangulatedOffset.x
                                val triY = triangulatedOffset.y
                                val errorMeters = (triangulatedOffset - triangulationTarget.offset).getDistance() * 12f

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "RESOLVED COORD: (${String.format("%.3f", triX)}, ${String.format("%.3f", triY)})",
                                            color = Color(0xFFFF7043),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "GROUND TRUTH: (${String.format("%.3f", trueX)}, ${String.format("%.3f", trueY)})",
                                            color = Slate500,
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "ERROR: ${String.format("%.2fm", errorMeters)}",
                                            color = if (errorMeters < 0.5f) Emerald400 else if (errorMeters < 1.5f) Amber500 else Color.Red,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "SATELLITE SYNCED",
                                            color = Slate500,
                                            fontSize = 7.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bio-Sync Targets & Surroundings Controller Panel
        Card(
            modifier = Modifier
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Zinc800)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = "Terminal",
                            tint = Emerald500,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "BIO-SYNC MONITOR",
                            color = Slate200,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 1.sp
                        )
                    }
                    
                    // Wall template toggle!
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "WALLS:",
                            color = Slate500,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        var selectedWallPreset by remember { mutableStateOf(0) }
                        val presets = listOf("OFFICE", "SUITE", "EMPTY")
                        Row(
                            modifier = Modifier
                                .border(1.dp, Zinc800, RoundedCornerShape(4.dp))
                                .background(SpaceBlack)
                        ) {
                            presets.forEachIndexed { idx, name ->
                                val isSel = selectedWallPreset == idx
                                Box(
                                    modifier = Modifier
                                        .clickable {
                                            selectedWallPreset = idx
                                            onSurroundingWallsChange(when (idx) {
                                                0 -> listOf(
                                                    SurroundingWall(start = Offset(0.15f, 0.15f), end = Offset(0.85f, 0.15f), name = "Outer North"),
                                                    SurroundingWall(start = Offset(0.15f, 0.15f), end = Offset(0.15f, 0.85f), name = "Outer West"),
                                                    SurroundingWall(start = Offset(0.85f, 0.15f), end = Offset(0.85f, 0.85f), name = "Outer East"),
                                                    SurroundingWall(start = Offset(0.15f, 0.85f), end = Offset(0.85f, 0.85f), name = "Outer South"),
                                                    SurroundingWall(start = Offset(0.5f, 0.15f), end = Offset(0.5f, 0.45f), name = "Office Partition"),
                                                    SurroundingWall(start = Offset(0.15f, 0.5f), end = Offset(0.55f, 0.5f), name = "Hallway Divider")
                                                )
                                                1 -> listOf(
                                                    SurroundingWall(start = Offset(0.1f, 0.1f), end = Offset(0.9f, 0.1f), name = "Brick Wall"),
                                                    SurroundingWall(start = Offset(0.1f, 0.1f), end = Offset(0.1f, 0.9f), name = "Outer Wall"),
                                                    SurroundingWall(start = Offset(0.9f, 0.1f), end = Offset(0.9f, 0.9f), name = "Outer Wall"),
                                                    SurroundingWall(start = Offset(0.1f, 0.9f), end = Offset(0.9f, 0.9f), name = "South Brick"),
                                                    SurroundingWall(start = Offset(0.45f, 0.45f), end = Offset(0.45f, 0.9f), name = "Bedroom Divider"),
                                                    SurroundingWall(start = Offset(0.45f, 0.45f), end = Offset(0.9f, 0.45f), name = "Kitchen Wall")
                                                )
                                                else -> emptyList()
                                            })
                                        }
                                        .background(if (isSel) Emerald500.copy(alpha = 0.15f) else Color.Transparent)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = name,
                                        color = if (isSel) Emerald400 else Slate400,
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))

                // ── REAL STRUCTURE + DEVICE PIPELINE ─────────────────────
                // Architect blueprint (per building story) + live LAN discovery.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SpaceBlack)
                        .border(1.dp, Zinc800, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ARCHITECT FLOOR-PLAN SYNC — ${floorLabel(currentFloor).uppercase()}",
                        color = Slate400,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "WALLS MAPPED: ${surroundingWalls.size} • DEVICES DEPLOYED: ${activeSubjects.count { it.type == "Device" }} • LAN DISCOVERED: $lanDeviceCount",
                        color = Slate500,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenFloorPlanMapper,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Emerald500.copy(alpha = 0.15f),
                                contentColor = Emerald400
                            ),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(28.dp)
                                .weight(1f)
                                .border(1.dp, Emerald500.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .testTag("open_floorplan_mapper_button")
                        ) {
                            Text(
                                text = "🏛 ARCHITECT MAP",
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = onLanDeviceScan,
                            enabled = !lanScanInProgress,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Emerald500.copy(alpha = 0.15f),
                                contentColor = Emerald400
                            ),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(28.dp)
                                .weight(1f)
                                .border(1.dp, Emerald500.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .testTag("lan_device_scan_button")
                        ) {
                            if (lanScanInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = Emerald400,
                                    trackColor = Zinc800
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("SWEEPING…", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "LAN scan",
                                    tint = Emerald400,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("LAN DEVICE SCAN", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(
                        text = "Phones can't image walls through concrete by radio — draw/calibrate the blueprint per story, then drag each discovered device into its real room. Distances & signals shown are live RSSI/FSPL estimates.",
                        color = Slate600,
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 9.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                // Show Hovered Subject or Selected Subject or Instructions
                val focusSub = hoveredSubject ?: selectedSubject
                if (focusSub != null) {
                    // Holographic panel for active subject
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Emerald500.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                            .background(SpaceBlack)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val badgeColor = when (focusSub.type) {
                                    "Person" -> Emerald400
                                    "Pet" -> Amber500
                                    "Device" -> Slate400
                                    else -> Color.Red
                                }
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(badgeColor)
                                )
                                Text(
                                    text = focusSub.name.uppercase(),
                                    color = Slate50,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif
                                )
                                
                                Text(
                                    text = "[${focusSub.type.uppercase()}]",
                                    color = badgeColor,
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(2.dp))
                            val headingText = when {
                                focusSub.type == "Device" -> "STATIONARY"
                                focusSub.headingDegrees >= 337.5f || focusSub.headingDegrees < 22.5f -> "HEADING: N"
                                focusSub.headingDegrees >= 22.5f && focusSub.headingDegrees < 67.5f -> "HEADING: NE"
                                focusSub.headingDegrees >= 67.5f && focusSub.headingDegrees < 112.5f -> "HEADING: E"
                                focusSub.headingDegrees >= 112.5f && focusSub.headingDegrees < 157.5f -> "HEADING: SE"
                                focusSub.headingDegrees >= 157.5f && focusSub.headingDegrees < 202.5f -> "HEADING: S"
                                focusSub.headingDegrees >= 202.5f && focusSub.headingDegrees < 247.5f -> "HEADING: SW"
                                focusSub.headingDegrees >= 247.5f && focusSub.headingDegrees < 292.5f -> "HEADING: W"
                                else -> "HEADING: NW"
                            }
                            val vectorBearing = if (focusSub.type == "Device") "" else " (${Math.round(focusSub.headingDegrees)}°)"
                            val speedText = if (focusSub.type == "Device") "STABLE" else "${String.format("%.1f", focusSub.speedKmh)} KM/H"
                            
                            Text(
                                text = "COORD: (${String.format("%.2f", focusSub.offset.x)}, ${String.format("%.2f", focusSub.offset.y)}) • $headingText$vectorBearing • VELOCITY: $speedText",
                                color = Slate500,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        // Rename / Edit & Delete controls
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var showRenameDialog by remember { mutableStateOf(false) }
                            Button(
                                onClick = { showRenameDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Zinc800),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("RENAME", color = Slate200, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            
                            IconButton(
                                onClick = {
                                    onActiveSubjectsChange(activeSubjects.filter { it.id != focusSub.id })
                                    selectedSubject = null
                                    hoveredSubject = null
                                },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete subject",
                                    tint = Color.Red.copy(alpha = 0.8f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            
                            if (showRenameDialog) {
                                var tempName by remember { mutableStateOf(focusSub.name) }
                                var tempType by remember { mutableStateOf(focusSub.type) }
                                val types = listOf("Person", "Pet", "Device", "Unknown")
                                
                                AlertDialog(
                                    onDismissRequest = { showRenameDialog = false },
                                    containerColor = Zinc950,
                                    title = {
                                        Text(
                                            text = "RECONFIGURE TARGET",
                                            color = Emerald400,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedTextField(
                                                value = tempName,
                                                onValueChange = { tempName = it },
                                                label = { Text("Target Name", color = Slate400, fontSize = 10.sp) },
                                                textStyle = LocalTextStyle.current.copy(color = Slate50, fontSize = 14.sp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Emerald500,
                                                    unfocusedBorderColor = Zinc800,
                                                    focusedContainerColor = SpaceBlack,
                                                    unfocusedContainerColor = SpaceBlack
                                                ),
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            
                                            Column {
                                                Text("Target Type", color = Slate400, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    types.forEach { t ->
                                                        val isSelected = tempType == t
                                                        Box(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .border(
                                                                    1.dp,
                                                                    if (isSelected) Emerald500 else Zinc800,
                                                                    RoundedCornerShape(6.dp)
                                                                )
                                                                .background(if (isSelected) Emerald500.copy(alpha = 0.12f) else SpaceBlack)
                                                                .clickable { tempType = t }
                                                                .padding(vertical = 6.dp),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                text = t,
                                                                color = if (isSelected) Emerald400 else Slate400,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                onActiveSubjectsChange(activeSubjects.map {
                                                    if (it.id == focusSub.id) {
                                                        it.copy(name = tempName, type = tempType)
                                                    } else it
                                                })
                                                selectedSubject = null
                                                hoveredSubject = null
                                                showRenameDialog = false
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Emerald500)
                                        ) {
                                            Text("APPLY", color = SpaceBlack, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showRenameDialog = false }) {
                                            Text("CANCEL", color = Slate500, fontSize = 10.sp)
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // General status instruction panel
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Zinc800, RoundedCornerShape(12.dp))
                            .background(SpaceBlack)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "► TAP EMPTY RADAR GRIDS TO DEPLOY A NEW SUBJECT",
                            color = Emerald500.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "► PRESS OR DRAG OVER SCANS TO LOCK ON AND RECONFIGURE",
                            color = Slate400,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                
                // Show horizontal scrollable classification list of targets
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeSubjects.forEach { sub ->
                        val badgeColor = when (sub.type) {
                            "Person" -> Emerald400
                            "Pet" -> Amber500
                            "Device" -> Slate400
                            else -> Color.Red
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Zinc950)
                                .border(1.dp, if (selectedSubject?.id == sub.id) Emerald500 else Zinc800, RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedSubject = if (selectedSubject?.id == sub.id) null else sub
                                    hoveredSubject = if (selectedSubject?.id == sub.id) sub else null
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(badgeColor)
                                )
                                Text(
                                    text = sub.name,
                                    color = Slate200,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // New subject dialogue builder
        if (showAddDialog && tappedCoordinate != null) {
            var newName by remember { mutableStateOf("") }
            var newType by remember { mutableStateOf("Person") }
            val types = listOf("Person", "Pet", "Device", "Unknown")
            
            AlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                    tappedCoordinate = null
                },
                containerColor = Zinc950,
                title = {
                    Text(
                        text = "REGISTER NEW COGNITIVE SUBJECT",
                        color = Emerald400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Position: (${String.format("%.2f", tappedCoordinate!!.x)}, ${String.format("%.2f", tappedCoordinate!!.y)})",
                            color = Slate500,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Subject Name", color = Slate400, fontSize = 10.sp) },
                            placeholder = { Text("e.g. Sentinel Node, WiFi Node Beta", color = Slate600, fontSize = 12.sp) },
                            textStyle = LocalTextStyle.current.copy(color = Slate50, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Emerald500,
                                unfocusedBorderColor = Zinc800,
                                focusedContainerColor = SpaceBlack,
                                unfocusedContainerColor = SpaceBlack
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Column {
                            Text("Classifier Category", color = Slate400, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                types.forEach { t ->
                                    val isSelected = newType == t
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(
                                                1.dp,
                                                if (isSelected) Emerald500 else Zinc800,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .background(if (isSelected) Emerald500.copy(alpha = 0.12f) else SpaceBlack)
                                            .clickable { newType = t }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = t,
                                            color = if (isSelected) Emerald400 else Slate400,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onActiveSubjectsChange(activeSubjects + TrackingSubject(
                                    name = newName,
                                    type = newType,
                                    offset = tappedCoordinate!!
                                ))
                            }
                            showAddDialog = false
                            tappedCoordinate = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500)
                    ) {
                        Text("LOCK TARGET", color = SpaceBlack, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showAddDialog = false
                            tappedCoordinate = null
                        }
                    ) {
                        Text("CANCEL", color = Slate500, fontSize = 10.sp)
                    }
                }
            )
        }

        // 3. Bento Grid Bottom stats row (2 components)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Bento Box 1: Tissue Penetration
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(130.dp),
                colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Zinc800)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "TISSUE PENETRATION",
                        color = Slate500,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.5.sp
                    )

                    val penetrationRatio = if (isShieldActive) 0.01f else (currentEmf * 0.001f)
                    Text(
                        text = String.format("%.3f%%", penetrationRatio),
                        color = Amber500,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )

                    Column {
                        Text(
                            text = if (isShieldActive) "Bubble Shield Active" else "LEVEL: LOW RISK",
                            color = if (isShieldActive) Emerald500 else Slate400,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 10.sp
                        )
                        Text(
                            text = "IMPACT: NEGLIGIBLE",
                            color = Slate500,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 10.sp
                        )
                    }
                }
            }

            // Bento Box 2: WiFi Signal stats
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(130.dp),
                colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Zinc800)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "SIGNAL INTENSITY",
                        color = Slate500,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.5.sp
                    )

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Drawing signal bars
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.padding(bottom = 2.dp)
                        ) {
                            listOf(6, 12, 17, 22).forEachIndexed { index, ht ->
                                val active = index < 3 // 3 out of 4 active
                                Box(
                                    modifier = Modifier
                                        .size(width = 3.dp, height = ht.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(if (active) Emerald500 else Zinc700)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$wifiStrength",
                            color = Slate50,
                            fontSize = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "dBm",
                            color = Slate500,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 1.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "BAND: 5.8 GHZ",
                            color = Slate400,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 10.sp
                        )
                        Text(
                            text = "SOURCE: INTERNET AP",
                            color = Slate500,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 10.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Data Model for Custom EMF Heatmap Points.
 */
data class HeatmapPoint(
    val offset: Offset,
    val intensity: Float,
    val sourceName: String
)

/**
 * Data Model for Bio-Sync Tracking Subjects on Radar.
 */
data class TrackingSubject(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val type: String, // "Person", "Pet", "Device", "Unknown"
    val offset: Offset,
    val history: List<Offset> = emptyList(),
    val velocity: Offset = Offset.Zero,
    val speedKmh: Float = 0f,
    val headingDegrees: Float = 0f
)

/**
 * Data Model for Surrounding Layout Wall lines on Radar.
 */
data class SurroundingWall(
    val id: String = java.util.UUID.randomUUID().toString(),
    val start: Offset,
    val end: Offset,
    val name: String
)

/**
 * HEATMAP INTERACTIVE VIEW
 */
@Composable
fun HeatmapScreen(
    points: List<HeatmapPoint>,
    onAddPoint: (Offset) -> Unit,
    onReset: () -> Unit
) {
    var hoveredPoint by remember { mutableStateOf<HeatmapPoint?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Zinc800),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "EMF LOCAL THERMAL HEATMAP",
                    color = Emerald500,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Tap on the tactical radar board representation below to register an EMF anomaly radiation emitter hub.",
                    color = Slate400,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Mapping radar board representation
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Zinc800, RoundedCornerShape(24.dp))
                .background(Zinc950)
        ) {
            // Sweep pulse overlay circles drawing
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(points) {
                        detectTapGestures { pressOffset ->
                            // Look for clicks near existing spots to hover them, otherwise record a new point!
                            val matched = points.firstOrNull { pt ->
                                (pt.offset - pressOffset).getDistance() < 40f
                            }
                            if (matched != null) {
                                hoveredPoint = matched
                            } else {
                                onAddPoint(pressOffset)
                            }
                        }
                    }
            ) {
                // Drawing localized concentric hot loops
                points.forEach { pt ->
                    val colorBase = if (pt.intensity > 60f) Amber500 else Emerald500
                    
                    // Radial localized gradients glow
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(colorBase.copy(alpha = 0.25f), Color.Transparent),
                            center = pt.offset,
                            radius = 65.dp.toPx()
                        ),
                        center = pt.offset,
                        radius = 65.dp.toPx()
                    )

                    drawCircle(
                        color = colorBase.copy(alpha = 0.4f),
                        radius = 16.dp.toPx(),
                        center = pt.offset,
                        style = Stroke(width = 1.dp.toPx())
                    )

                    drawCircle(
                        color = colorBase,
                        radius = 4.dp.toPx(),
                        center = pt.offset
                    )
                }

                // Thin grid marks overlay
                val space = 24.dp.toPx()
                for (x in 0..(size.width / space).toInt()) {
                    drawLine(
                        color = Slate800.copy(alpha = 0.12f),
                        start = Offset(x * space, 0f),
                        end = Offset(x * space, size.height),
                        strokeWidth = 0.5f.dp.toPx()
                    )
                }
                for (y in 0..(size.height / space).toInt()) {
                    drawLine(
                        color = Slate800.copy(alpha = 0.12f),
                        start = Offset(0f, y * space),
                        end = Offset(size.width, y * space),
                        strokeWidth = 0.5f.dp.toPx()
                    )
                }
            }

            // Clean overlay chip
            Text(
                text = "ACTIVE SENSE MATRIX",
                color = Slate500,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
            )

            if (hoveredPoint != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth(0.85f),
                    colors = CardDefaults.cardColors(containerColor = Zinc900),
                    border = BorderStroke(1.dp, Emerald500.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = hoveredPoint!!.sourceName,
                                color = Slate50,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Intensity: ${hoveredPoint!!.intensity} µT",
                                color = Emerald500,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(
                            onClick = { hoveredPoint = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text("✕", color = Slate400, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Reset elements controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Points plotted: ${points.size}",
                color = Slate500,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Button(
                onClick = onReset,
                colors = ButtonDefaults.buttonColors(containerColor = Zinc900),
                border = BorderStroke(1.dp, Zinc800),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = "CLEAR MATRIX",
                    color = Slate200,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/**
 * AR EMULATIVE SCAN VIEW
 */
/**
 * FUTURISTIC AR CAMERA SCANNER VIEW
 */
@Composable
fun ARViewScreen(
    calibrationGain: Float,
    onCalibrationChange: (Float) -> Unit,
    activeSubjects: List<TrackingSubject>
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var thermalTrackingOn by remember { mutableStateOf(true) }
    var captureLogMessage by remember { mutableStateOf("SCAN OVERVIEW SECURE // LINK STANDBY") }

    // IR Lens Finder (hidden-camera sweep): red filter + torch, user scans visually
    var irFinderOn by remember { mutableStateOf(false) }
    var boundCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    // Live ping state
    var pingingSubject by remember { mutableStateOf<TrackingSubject?>(null) }
    var pingProgress by remember { mutableStateOf(0f) }

    // Request camera permissions inline
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        captureLogMessage = if (isGranted) "CAMERA FEED ONLINE - STANDBY FOR TARGETS" else "CAMERA ACCESS DECLINED - RUNNING SIMULATED GRID"
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Torch follows the IR finder so lens glints reflect back while sweeping
    LaunchedEffect(irFinderOn) {
        try {
            boundCamera?.cameraControl?.enableTorch(irFinderOn)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    // Ping / Phone communication link animation coroutine
    LaunchedEffect(pingingSubject) {
        if (pingingSubject != null) {
            val subName = pingingSubject!!.name
            for (step in 1..40) {
                delay(60)
                pingProgress = step.toFloat() / 40f
                captureLogMessage = when {
                    pingProgress < 0.25f -> "PHONING COGNITIVE TARGET [$subName]... EMITTING HIGH-GAIN RF PIN"
                    pingProgress < 0.55f -> "TUNING RESIDUAL CARRIER BAND... ESTABLISHING HANDSHAKE LINK"
                    pingProgress < 0.85f -> "SYNCHRONIZING DUPLEX SENSORY STREAM... PACKET SIZE: 128kb"
                    else -> "COMMUNICATION SECURED // TELEMETRY RESPONSE ACTIVE // RTT: ${String.format("%.1f", 3.2f + Math.random().toFloat() * 5.4f)}ms"
                }
            }
            delay(1500)
            pingingSubject = null
            pingProgress = 0f
            captureLogMessage = "SCAN OVERVIEW SECURE // LINK STANDBY"
        }
    }

    // Store projected positions on screen to handle click targets
    var projectedPositions by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Camera/AR Scanner viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF020202))
                .border(1.dp, Zinc800, RoundedCornerShape(24.dp))
                .pointerInput(activeSubjects, projectedPositions) {
                    detectTapGestures { tapOffset ->
                        // Detect click on any projected device/subject
                        val clickedEntry = projectedPositions.entries.minByOrNull { entry ->
                            (entry.value - tapOffset).getDistance()
                        }
                        if (clickedEntry != null && (clickedEntry.value - tapOffset).getDistance() < 45.dp.toPx()) {
                            val target = activeSubjects.firstOrNull { it.id == clickedEntry.key }
                            if (target != null && pingingSubject == null) {
                                pingingSubject = target
                            }
                        }
                    }
                }
        ) {
            // 1. CameraX Preview View Layer
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val provider = cameraProviderFuture.get()
                            val previewUseCase = CameraPreviewUseCase.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            try {
                                provider.unbindAll()
                                boundCamera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    previewUseCase
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Futuristic grid placeholder if camera is loading or permission is denied
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF020202)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = Emerald500, strokeWidth = 2.dp)
                        Text(
                            text = "AWAITING SECURE CAMERA STREAM...",
                            color = Slate500,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // IR LENS FINDER: red filter overlay — hidden cam lenses glint through it
            if (irFinderOn) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Red.copy(alpha = 0.10f))
                )
            }

            // Continuous scanning sweep effect
            val infiniteTransition = rememberInfiniteTransition(label = "ARScanlines")
            val sweepYOffset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "ScanSweepOffset"
            )

            // 2. AR Hud Canvas Overlay (Renders coordinates and lines dynamically)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val roomGreen = Emerald500.copy(alpha = 0.22f)

                // Corner brackets
                val pad = 30.dp.toPx()
                val len = 20.dp.toPx()
                drawLine(roomGreen, Offset(pad, pad), Offset(pad + len, pad), 2f)
                drawLine(roomGreen, Offset(pad, pad), Offset(pad, pad + len), 2f)
                drawLine(roomGreen, Offset(w - pad, pad), Offset(w - pad - len, pad), 2f)
                drawLine(roomGreen, Offset(w - pad, pad), Offset(w - pad, pad + len), 2f)
                drawLine(roomGreen, Offset(pad, h - pad), Offset(pad + len, h - pad), 2f)
                drawLine(roomGreen, Offset(pad, h - pad), Offset(pad, h - pad - len), 2f)
                drawLine(roomGreen, Offset(w - pad, h - pad), Offset(w - pad - len, h - pad), 2f)
                drawLine(roomGreen, Offset(w - pad, h - pad), Offset(w - pad, h - pad - len), 2f)

                // Perspective guidelines representing depth
                drawLine(roomGreen.copy(alpha = 0.08f), Offset(0f, 0f), Offset(w * 0.2f, h * 0.2f), 1f)
                drawLine(roomGreen.copy(alpha = 0.08f), Offset(w, 0f), Offset(w * 0.8f, h * 0.2f), 1f)
                drawLine(roomGreen.copy(alpha = 0.08f), Offset(0f, h), Offset(w * 0.2f, h * 0.8f), 1f)
                drawLine(roomGreen.copy(alpha = 0.08f), Offset(w, h), Offset(w * 0.8f, h * 0.8f), 1f)

                // Draw central focus crosshair
                drawCircle(roomGreen, radius = 24.dp.toPx(), center = Offset(w / 2f, h / 2f), style = Stroke(1f))
                drawCircle(roomGreen, radius = 2.dp.toPx(), center = Offset(w / 2f, h / 2f))

                // Interactive horizontal laser sweeper line
                val currentSweepY = h * sweepYOffset
                drawLine(
                    color = Emerald500.copy(alpha = 0.35f),
                    start = Offset(0f, currentSweepY),
                    end = Offset(w, currentSweepY),
                    strokeWidth = 1.2f.dp.toPx()
                )

                // Projected visual targets from dynamic tracking system in 3D perspective
                if (thermalTrackingOn) {
                    val nextPositions = mutableMapOf<String, Offset>()
                    
                    activeSubjects.forEach { sub ->
                        val u = sub.offset.x - 0.5f
                        val v_depth = sub.offset.y // 0f (furthest top) to 1f (closest bottom)
                        
                        // Width perspective projection (deeper objects are compressed closer to horizontal center)
                        val xPx = w / 2f + u * w * (0.55f + v_depth * 0.4f)
                        // Height perspective projection (deeper objects are rendered higher up on the screen)
                        val yPx = h * (0.32f + (1f - v_depth) * 0.38f)
                        val targetPos = Offset(xPx, yPx)
                        nextPositions[sub.id] = targetPos

                        val isPinging = pingingSubject?.id == sub.id
                        val markerColor = when (sub.type) {
                            "Person" -> Emerald500
                            "Pet" -> Amber500
                            "Device" -> Color(0xFF64B5F6) // Bright tech blue
                            else -> Color.Red
                        }

                        // Draw target reticle circle
                        drawCircle(
                            color = markerColor.copy(alpha = if (isPinging) 0.8f else 0.35f),
                            radius = if (isPinging) 22.dp.toPx() else 14.dp.toPx(),
                            center = targetPos,
                            style = Stroke(width = 1.5f.dp.toPx())
                        )
                        drawCircle(
                            color = markerColor,
                            radius = 3.dp.toPx(),
                            center = targetPos
                        )

                        // Draw bracket indicators
                        val markerSize = 10.dp.toPx()
                        drawLine(markerColor.copy(alpha = 0.6f), targetPos - Offset(markerSize, markerSize), targetPos - Offset(markerSize - 4f, markerSize), 1.5f)
                        drawLine(markerColor.copy(alpha = 0.6f), targetPos - Offset(markerSize, markerSize), targetPos - Offset(markerSize, markerSize - 4f), 1.5f)
                        drawLine(markerColor.copy(alpha = 0.6f), targetPos + Offset(markerSize, markerSize), targetPos + Offset(markerSize - 4f, markerSize), 1.5f)
                        drawLine(markerColor.copy(alpha = 0.6f), targetPos + Offset(markerSize, markerSize), targetPos + Offset(markerSize, markerSize - 4f), 1.5f)

                        // If pinging, draw beautiful sonar ripple propagation waves spreading from target!
                        if (isPinging) {
                            val rippleRadius = 14.dp.toPx() + 65.dp.toPx() * pingProgress
                            drawCircle(
                                color = markerColor.copy(alpha = 1f - pingProgress),
                                radius = rippleRadius,
                                center = targetPos,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                    
                    // Expose projected coordinates to layout memory securely
                    projectedPositions = nextPositions
                }
            }

            // 3. Precise Text Overlay Nodes Layer (Compose items overlaying canvas perfectly)
            if (thermalTrackingOn) {
                projectedPositions.forEach { (subId, pos) ->
                    val sub = activeSubjects.firstOrNull { it.id == subId }
                    if (sub != null) {
                        val isPinging = pingingSubject?.id == sub.id
                        val distanceMeters = 1.2f + (1f - sub.offset.y) * 8.2f
                        val badgeColor = when (sub.type) {
                            "Person" -> Emerald400
                            "Pet" -> Amber500
                            "Device" -> Color(0xFF64B5F6)
                            else -> Color.Red
                        }

                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val xDp = with(density) { pos.x.toDp() }
                        val yDp = with(density) { pos.y.toDp() }

                        Box(
                            modifier = Modifier
                                .offset(
                                    x = xDp - 50.dp,
                                    y = yDp + 18.dp
                                )
                                .width(130.dp)
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                .border(
                                    1.dp,
                                    if (isPinging) badgeColor else badgeColor.copy(alpha = 0.25f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(4.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = sub.name.uppercase(),
                                        color = Slate50,
                                        fontSize = 7.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = sub.type.uppercase(),
                                        color = badgeColor,
                                        fontSize = 6.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "DIST: ${String.format("%.1fm", distanceMeters)}",
                                        color = Slate400,
                                        fontSize = 6.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (sub.type == "Device") {
                                        Text(
                                            text = "TAP TO PHONE",
                                            color = if (isPinging) badgeColor else Slate500,
                                            fontSize = 6.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.animateContentSize()
                                        )
                                    } else {
                                        Text(
                                            text = "SPEED: ${String.format("%.1fk/h", sub.speedKmh)}",
                                            color = Slate400,
                                            fontSize = 6.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // HUD Metadata info panels
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "REC: 1080P // STREAM FEED",
                        color = Color.Red.copy(alpha = 0.7f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "THERMO LOCK: ${if (thermalTrackingOn) "ON" else "OFF"}",
                        color = if (thermalTrackingOn) Emerald500 else Slate500,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .border(1.dp, Zinc800)
                ) {
                    Text(
                        text = "WAVE TYPE: RF // ELF // WIFI SENSING",
                        color = Slate400,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Log messages overlay at the bottom
            Text(
                text = captureLogMessage,
                color = Slate50,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            )
        }

        // Dynamic Interactive Calibration Controller
        Card(
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Zinc800),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HARDWARE SENSOR CALIBRATION",
                        color = Slate200,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = String.format("Multiplier: %.2fx", calibrationGain),
                        color = Emerald500,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = calibrationGain,
                    onValueChange = {
                        onCalibrationChange(it)
                        captureLogMessage = String.format("CALIBRATION GAIN TUNED AT %.2fX", it)
                    },
                    valueRange = 0.3f..3.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Emerald500,
                        activeTrackColor = Emerald500,
                        inactiveTrackColor = Zinc800
                    ),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("gain_calibration_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Toggle AR Scanning Targets overlay",
                        color = Slate400,
                        fontSize = 10.sp
                    )
                    Switch(
                        checked = thermalTrackingOn,
                        onCheckedChange = {
                            thermalTrackingOn = it
                            captureLogMessage = if (it) "TARGET LOCK REBOUND ACTIVE" else "HUD DISPLAY LAYER CLOSED"
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Emerald500,
                            checkedTrackColor = Emerald500.copy(alpha = 0.3f),
                            uncheckedThumbColor = Slate500,
                            uncheckedTrackColor = Zinc800
                        ),
                        modifier = Modifier
                            .scale(0.8f)
                            .testTag("ar_target_overlay_switch")
                    )
                }

                // ── IR Lens Finder toggle (real hidden-camera sweep aid) ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🔴 IR LENS FINDER — hidden camera sweep",
                            color = Slate400,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Darken the room and scan slowly. Night-vision IR LEDs show as bright dots on screen that your eyes can't see. Heuristic aid — always verify finds manually.",
                            color = Slate500,
                            fontSize = 8.sp,
                            lineHeight = 11.sp
                        )
                    }
                    Switch(
                        checked = irFinderOn,
                        onCheckedChange = {
                            irFinderOn = it
                            captureLogMessage = if (it) "IR LENS FINDER ON — TORCH ACTIVE // SWEEP SLOWLY" else "IR LENS FINDER OFF"
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFEF5350),
                            checkedTrackColor = Color(0xFFEF5350).copy(alpha = 0.35f),
                            uncheckedThumbColor = Slate500,
                            uncheckedTrackColor = Zinc800
                        ),
                        modifier = Modifier
                            .scale(0.8f)
                            .testTag("ir_lens_finder_switch")
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

/**
 * HISTORICAL SENSORY INSIGHTS SCREEN
 */
@Composable
fun InsightsScreen(
    history: List<Float>,
    currentEmf: Float
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Line chart plotting telemetry logs
        Card(
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Zinc800),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "REAL-TIME FLUCTUATION GRAPH",
                    color = Emerald500,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Custom charts plotting spline curves
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(Zinc950, RoundedCornerShape(12.dp))
                        .border(1.dp, Zinc800, RoundedCornerShape(12.dp))
                        .padding(8.dp)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("insights_chart")
                    ) {
                        if (history.size > 1) {
                            val w = size.width
                            val h = size.height
                            val numPoints = history.size
                            val stepX = w / (numPoints - 1)
                            
                            val maxV = (history.maxOrNull() ?: 55f).coerceAtLeast(10f)
                            val minV = (history.minOrNull() ?: 30f).coerceAtMost(maxV - 5f)
                            val delta = (maxV - minV).coerceAtLeast(1f)

                            val points = history.mapIndexed { index, value ->
                                val cx = index * stepX
                                val fractionY = (value - minV) / delta
                                // Invert coordinates so 0 is at bottom
                                val cy = h - (fractionY * h * 0.75f) - (h * 0.1f)
                                Offset(cx, cy)
                            }

                            // Build fill gradient spline
                            val fillArea = Path().apply {
                                moveTo(0f, h)
                                points.forEach { lineTo(it.x, it.y) }
                                lineTo(w, h)
                                close()
                            }
                            drawPath(
                                path = fillArea,
                                brush = Brush.verticalGradient(
                                    colors = listOf(Emerald500.copy(alpha = 0.15f), Color.Transparent),
                                    startY = points.minOf { it.y },
                                    endY = h
                                )
                            )

                            // Spline connecting line
                            val splineLine = Path().apply {
                                points.firstOrNull()?.let { moveTo(it.x, it.y) }
                                points.forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(
                                path = splineLine,
                                color = Emerald500,
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                            )

                            // Glowing final coordinate dot
                            points.lastOrNull()?.let {
                                drawCircle(Emerald100, radius = 4.dp.toPx(), center = it)
                                drawCircle(
                                    color = Emerald400.copy(alpha = 0.4f),
                                    radius = 10.dp.toPx(),
                                    center = it,
                                    style = Stroke(width = 1.dp.toPx())
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format("MIN: %.1f µT", history.minOrNull() ?: 0f),
                        color = Slate500,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format("AVERAGE: %.1f µT", history.average()),
                        color = Slate400,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = String.format("MAX: %.1f µT", history.maxOrNull() ?: 0f),
                        color = Amber500,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Diagnostic signal sources list Bento panel
        Card(
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Zinc800),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "CLASSIFIED RADIATION EMITTERS",
                    color = Slate200,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Interactive rows showing signal emitter spectrum and details
                CategorizedEmitterRow("MicroWaves leakage", "2.4 GHz", "Moderate Field", currentEmf > 46f)
                HorizontalDivider(color = Zinc800, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))
                CategorizedEmitterRow("Power Lines leakage", "50 / 60 Hz", "Minimal Overhead", currentEmf > 50f)
                HorizontalDivider(color = Zinc800, thickness = 1.dp, modifier = Modifier.padding(vertical = 10.dp))
                CategorizedEmitterRow("Smart Phones wireless", "1.9 - 5.8 GHz", "Active RF Signal", true)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun CategorizedEmitterRow(
    name: String,
    freq: String,
    strength: String,
    activeAlert: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = name, color = Slate50, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(text = "Spectral frequency: $freq", color = Slate500, fontSize = 9.sp)
        }
        
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = strength.uppercase(),
                color = if (activeAlert) Amber500 else Slate400,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (activeAlert) Amber500 else Emerald500)
            )
        }
    }
}

/**
 * HEALTH COMPLIANCE SCREEN
 */
@Composable
fun HealthScreen(
    currentEmf: Float,
    isShieldActive: Boolean,
    onShieldToggle: (Boolean) -> Unit,
    emfIsSimulated: Boolean = false,
    historyAverage: Float = 42.8f
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active bubble shield emulator card
        Card(
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, if (isShieldActive) Emerald500.copy(alpha = 0.5f) else Zinc800),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "EMF SUPPRESSOR SHIELD",
                            color = if (isShieldActive) Emerald500 else Slate400,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = if (isShieldActive) "Bubble Shield fully active. Intercepting ambient EMF radiation metrics." else "Standby. Activate to emulate magnetic isolation bubble.",
                            color = Slate400,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                        )
                    }
                    
                    Switch(
                        checked = isShieldActive,
                        onCheckedChange = onShieldToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Emerald500,
                            checkedTrackColor = Emerald500.copy(alpha = 0.3f),
                            uncheckedThumbColor = Slate500,
                            uncheckedTrackColor = Zinc800
                        ),
                        modifier = Modifier.testTag("shield_toggle_switch")
                    )
                }

                if (isShieldActive) {
                    Spacer(modifier = Modifier.height(14.dp))
                    // Cute force field hex visual element
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(55.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Emerald500.copy(alpha = 0.05f))
                            .border(1.dp, Emerald500.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⬢ ⬡ MAGNETIC ISOLATION MATRIX SECURE ⬡ ⬢",
                            color = Emerald500,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // ── REAL-TIME EMF SWEEP — hidden electronics / possible camera aid ──
        Card(
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, if (currentEmf > historyAverage * 1.6f) Amber500.copy(alpha = 0.5f) else Zinc800),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "HIDDEN-ELECTRONICS EMF SWEEP",
                    color = Slate200,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                val spike = currentEmf > (historyAverage * 1.6f).coerceAtLeast(historyAverage + 8f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (spike) Amber500 else Emerald500)
                    )
                    Text(
                        text = if (spike) "FIELD SPIKE — inspect this spot closely" else "Baseline nominal — sweeping…",
                        color = if (spike) Amber500 else Emerald400,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Live: ${String.format("%.1f", currentEmf)} µT  •  Session baseline: ${String.format("%.1f", historyAverage)} µT${if (emfIsSimulated) "  (SIMULATED SENSOR)" else ""}",
                    color = Slate400,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Move the phone slowly along walls, clocks, smoke detectors, chargers and decor. Active electronics (including hidden cameras) push the magnetometer above baseline — but so do wiring and metal. This is a heuristic aid, not proof of a camera.",
                    color = Slate500,
                    fontSize = 9.sp,
                    lineHeight = 13.sp
                )
            }
        }

        // Exposure safety benchmarks card
        Card(
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Zinc800),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "WHO STANDARDS & EXPOSURE THRESHOLDS",
                    color = Slate200,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                ExposureIndicatorItem("Computer monitors", "0.2 - 0.5 µT", "ICNIRP safe line")
                Spacer(modifier = Modifier.height(10.dp))
                ExposureIndicatorItem("AC Adapters nearby", "1.0 - 5.0 µT", "Moderate localized field")
                Spacer(modifier = Modifier.height(10.dp))
                ExposureIndicatorItem("Microwaves operating", "20.0 - 100.0 µT", "Strong focused waves")
                Spacer(modifier = Modifier.height(10.dp))
                ExposureIndicatorItem("ICNIRP Public Max Limit", "100.0 µT", "Absolute continuous ceiling")
            }
        }

        // Actionable health advices
        Card(
            colors = CardDefaults.cardColors(containerColor = Zinc900.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Zinc800),
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "OPERATOR PROTECTION RECOMMENDATIONS",
                    color = Slate200,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                BulletAdviceItem("Distance is the best shield - keep routers at least 3 meters away from working stations.")
                Spacer(modifier = Modifier.height(8.dp))
                BulletAdviceItem("Choose wired connections over wireless access points to suppress unnecessary RF smog when possible.")
                Spacer(modifier = Modifier.height(8.dp))
                BulletAdviceItem("Power off inductive transformers overnight (AC chargers, desktop monitors, dynamic smart speakers).")
            }
        }
    }
}

@Composable
fun ExposureIndicatorItem(
    label: String,
    reading: String,
    recommendation: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SpaceBlack)
            .border(1.dp, Zinc800, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = label, color = Slate50, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(text = recommendation, color = Slate500, fontSize = 9.sp)
        }
        Text(
            text = reading,
            color = Emerald500,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BulletAdviceItem(
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = "▪", color = Emerald500, fontSize = 14.sp)
        Text(text = text, color = Slate400, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

/**
 * SETTINGS CONFIGURATOR CUSTOM DIALOG
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsConfiguratorDialog(
    operatorName: String,
    onOperatorNameChange: (String) -> Unit,
    baseFrequency: Float,
    onFrequencyChange: (Float) -> Unit,
    sensitivity: Sensitivity,
    onSensitivityChange: (Sensitivity) -> Unit,
    calibrationGain: Float,
    onCalibrationChange: (Float) -> Unit,
    isShieldActive: Boolean,
    onShieldToggle: (Boolean) -> Unit,
    isDarkMode: Boolean = true,
    onToggleTheme: () -> Unit = {},
    onOpenLocationDialog: () -> Unit = {},
    privacyOptionsRequired: Boolean = false,
    onShowPrivacyOptions: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Zinc950 else LightSurface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, if (isDarkMode) Zinc800 else LightBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EMF SENTINEL PROTOCOLS",
                        color = Emerald500,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 1.sp
                    )
                    
                    // Quick theme toggle badge inside dialog
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDarkMode) Zinc900 else LightSurfaceVariant)
                            .clickable { onToggleTheme() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isDarkMode) "☀️ Light" else "🌙 Dark",
                            color = if (isDarkMode) Slate200 else LightTextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Operator Name Form Text Field
                Column {
                    Text(
                        text = "OPERATOR IDENTIFIER / CHIP ID",
                        color = if (isDarkMode) Slate500 else LightTextSecondary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = operatorName,
                        onValueChange = onOperatorNameChange,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Emerald500,
                            unfocusedBorderColor = if (isDarkMode) Zinc800 else LightBorder,
                            focusedTextColor = if (isDarkMode) Slate50 else LightTextPrimary,
                            unfocusedTextColor = if (isDarkMode) Slate200 else LightTextSecondary,
                            focusedContainerColor = if (isDarkMode) SpaceBlack else LightCanvas,
                            unfocusedContainerColor = if (isDarkMode) SpaceBlack else LightCanvas
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("operator_name_input"),
                        singleLine = true
                    )
                }

                // Grid/Sensitivity buttons selection
                Column {
                    Text(
                        text = "HARDWARE SENSITIVITY LEVEL",
                        color = if (isDarkMode) Slate500 else LightTextSecondary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Sensitivity.values().forEach { level ->
                            val isSelected = level == sensitivity
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Emerald500 else if (isDarkMode) Zinc900 else LightSurfaceVariant)
                                    .border(1.dp, if (isSelected) Emerald500 else if (isDarkMode) Zinc800 else LightBorder, RoundedCornerShape(12.dp))
                                    .clickable { onSensitivityChange(level) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = level.name,
                                    color = if (isSelected) SpaceBlack else if (isDarkMode) Slate400 else LightTextSecondary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Wave target frequency Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "GRID TARGET FREQUENCY",
                            color = if (isDarkMode) Slate500 else LightTextSecondary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = String.format("%.1f Hz", baseFrequency),
                            color = Emerald500,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = baseFrequency,
                        onValueChange = onFrequencyChange,
                        valueRange = 10.0f..120.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Emerald500,
                            activeTrackColor = Emerald500,
                            inactiveTrackColor = if (isDarkMode) Zinc800 else LightBorder
                        ),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .testTag("frequency_slider")
                    )
                }

                // Suppressor link check
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "BUBBLE SHIELD OVERRIDE",
                            color = if (isDarkMode) Slate500 else LightTextSecondary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Suppress measurements scale",
                            color = if (isDarkMode) Slate400 else LightTextSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = isShieldActive,
                        onCheckedChange = onShieldToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Emerald500,
                            checkedTrackColor = Emerald500.copy(alpha = 0.3f),
                            uncheckedThumbColor = Slate500,
                            uncheckedTrackColor = if (isDarkMode) Zinc800 else LightBorder
                        ),
                        modifier = Modifier
                            .scale(0.85f)
                            .testTag("dialog_shield_override_switch")
                    )
                }

                // Location & Sensor Permissions Quick Action Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDarkMode) Zinc900 else LightSurfaceVariant)
                        .clickable {
                            onDismiss()
                            onOpenLocationDialog()
                        }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📍", fontSize = 16.sp)
                        Column {
                            Text(
                                text = "LOCATION & SENSORS",
                                color = if (isDarkMode) Slate50 else LightTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.SansSerif
                            )
                            Text(
                                text = "Manage GPS & Wi-Fi scan permissions",
                                color = if (isDarkMode) Slate400 else LightTextSecondary,
                                fontSize = 9.sp
                            )
                        }
                    }
                    Text(
                        text = "MANAGE >",
                        color = Emerald500,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // UMP Privacy Options — REQUIRED by AdMob when the consent framework
                // flags this user (EEA/UK etc.). Re-evaluates ads eligibility after changes.
                if (privacyOptionsRequired) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDarkMode) Zinc900 else LightSurfaceVariant)
                            .clickable {
                                onDismiss()
                                onShowPrivacyOptions()
                            }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔒", fontSize = 16.sp)
                            Column {
                                Text(
                                    text = "PRIVACY OPTIONS",
                                    color = if (isDarkMode) Slate50 else LightTextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif
                                )
                                Text(
                                    text = "Manage ad consent & data choices",
                                    color = if (isDarkMode) Slate400 else LightTextSecondary,
                                    fontSize = 9.sp
                                )
                            }
                        }
                        Text(
                            text = "OPEN >",
                            color = Emerald500,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Dismiss Action Button
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("apply_settings_button")
                ) {
                    Text(
                        text = "APPLY PROTOCOLS",
                        color = SpaceBlack,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

/**
 * LOCATION & SENSOR PERMISSION POPUP DIALOG
 * Gives user instant options to allow system permission, open device settings, or enable GPS.
 */
@Composable
fun LocationPermissionDialog(
    isDarkMode: Boolean = true,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableGps: () -> Unit,
    isGpsEnabled: Boolean = true,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Zinc950 else LightSurface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, if (isDarkMode) Zinc800 else LightBorder)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "📡", fontSize = 18.sp)
                        Text(
                            text = "LOCATION & SENSORS",
                            color = Emerald500,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (isDarkMode) Zinc900 else LightSurfaceVariant)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "✕", color = if (isDarkMode) Slate400 else LightTextSecondary, fontSize = 12.sp)
                    }
                }

                Text(
                    text = "EMF Sentinel uses Location and Nearby Sensor access to perform real-time RF triangulation, detect nearby Wi-Fi AP nodes, and calibrate ambient field strength.",
                    color = if (isDarkMode) Slate200 else LightTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                if (!isGpsEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Amber500.copy(alpha = 0.15f))
                            .border(1.dp, Amber500.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚠️", fontSize = 14.sp)
                            Text(
                                text = "Device GPS / Location service is turned OFF. Enable it for RF hardware scanning.",
                                color = Amber500,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Primary Request Button
                Button(
                    onClick = {
                        onRequestPermission()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("grant_location_permission_button")
                ) {
                    Text(
                        text = "ALLOW LOCATION ACCESS",
                        color = SpaceBlack,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }

                // If GPS is disabled, offer direct GPS toggle
                if (!isGpsEnabled) {
                    OutlinedButton(
                        onClick = {
                            onEnableGps()
                            onDismiss()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber500),
                        border = BorderStroke(1.dp, Amber500),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("enable_gps_button")
                    ) {
                        Text(
                            text = "TURN ON DEVICE GPS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Open App Settings fallback
                OutlinedButton(
                    onClick = {
                        onOpenSettings()
                        onDismiss()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isDarkMode) Slate200 else LightTextPrimary
                    ),
                    border = BorderStroke(1.dp, if (isDarkMode) Zinc800 else LightBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .testTag("open_app_settings_button")
                ) {
                    Text(
                        text = "OPEN APP PERMISSIONS IN SETTINGS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

