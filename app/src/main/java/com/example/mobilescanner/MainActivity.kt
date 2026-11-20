package com.example.mobilescanner

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BgDark = Color(0xFF0B0B14)
private val CardDark = Color(0xFF15151F)
private val HeroDark = Color(0xFF1A1A33)
private val Accent = Color(0xFF6D5EF0)
private val AccentSoft = Color(0xFF9D8CFF)
private val TealSoft = Color(0xFF38BDF8)
private val GreenSoft = Color(0xFF34D399)
private val MutedText = Color(0xFF9AA0B4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = BgDark, surface = CardDark)) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
                    ScannerApp()
                }
            }
        }
    }
}

enum class ScanStep {
    ENTER_PIN,
    GRANT_PERMISSIONS,
    HOME,
    SCANNING,
    COMPLETED
}

@Composable
fun ScannerApp() {
    var currentStep by remember { mutableStateOf(ScanStep.ENTER_PIN) }
    var pinCode by remember { mutableStateOf("") }
    var scanMode by remember { mutableStateOf(ScanMode.FULL) }
    var scanStatusText by remember { mutableStateOf("Initializing...") }
    var scanResults by remember { mutableStateOf<List<ScanFinding>>(emptyList()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var verifying by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var uploadStatus by remember { mutableStateOf("") }
    var permTick by remember { mutableIntStateOf(0) }
    var safTree by remember { mutableStateOf<Uri?>(null) }

    // Re-check permissions every time the player comes back from a settings screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val usageOk = remember(permTick) { hasUsageStatsPermission(context) }
    val filesOk = remember(permTick) { hasAllFilesPermission(context) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        safTree = uri
        if (uri != null) {
            scanMode = ScanMode.MINECRAFT
            currentStep = ScanStep.SCANNING
        }
    }
    val storagePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Header()
        when (currentStep) {
            ScanStep.ENTER_PIN -> PinStep(
                pinCode = pinCode,
                onPin = { pinCode = it },
                verifying = verifying,
                pinError = pinError,
                onVerify = {
                    verifying = true
                    pinError = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ReportClient.verifyPin(pinCode) }
                        verifying = false
                        if (ok) currentStep = ScanStep.GRANT_PERMISSIONS
                        else pinError = "PIN not accepted. It may be wrong, already used, expired, or the server is unreachable."
                    }
                }
            )

            ScanStep.GRANT_PERMISSIONS -> PermissionStep(
                usageOk = usageOk,
                filesOk = filesOk,
                onUsage = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                onFiles = { requestAllFiles(context, storagePermLauncher) },
                onContinue = { currentStep = ScanStep.HOME }
            )

            ScanStep.HOME -> HomeStep(
                onSelectFolder = { treeLauncher.launch(androidDataHint()) },
                onAutoDetect = {
                    safTree = null
                    scanMode = ScanMode.MINECRAFT
                    currentStep = ScanStep.SCANNING
                },
                onMode = { mode ->
                    scanMode = mode
                    currentStep = ScanStep.SCANNING
                }
            )

            ScanStep.SCANNING -> {
                LaunchedEffect(Unit) {
                    scanStatusText = when (scanMode) {
                        ScanMode.FULL -> "Full scan: device, storage, Minecraft folders and downloads. This can take a minute..."
                        ScanMode.MINECRAFT -> "Looking inside Minecraft launcher folders, mods, configs and logs..."
                        ScanMode.DELETED -> "Checking trash, hidden folders and Downloads..."
                        ScanMode.BROWSER -> "Checking where downloaded files came from..."
                    }
                    val results = withContext(Dispatchers.IO) { CheatScanner.run(context, scanMode, safTree) }
                    scanResults = results
                    scanStatusText = "Sending report to admin..."
                    val sent = withContext(Dispatchers.IO) { ReportClient.submit(pinCode, results) }
                    uploadStatus = if (sent) "Report sent to the admin."
                    else "Could not send the report. Show this screen to the admin."
                    currentStep = ScanStep.COMPLETED
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Scanning", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(scanStatusText, fontSize = 14.sp, color = MutedText)
                    Spacer(modifier = Modifier.height(20.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp), color = Accent)
                }
            }

            ScanStep.COMPLETED -> ResultsStep(
                pinCode = pinCode,
                uploadStatus = uploadStatus,
                results = scanResults,
                onNewScan = {
                    pinCode = ""
                    pinError = null
                    uploadStatus = ""
                    safTree = null
                    currentStep = ScanStep.ENTER_PIN
                }
            )
        }
    }
}

// ---- pieces -----------------------------------------------------------------------------------

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF11111C)).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Brush.linearGradient(listOf(Accent, Color(0xFFD946EF))), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("A", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text("ABBYS Anti-Cheat", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Minecraft Cheat Scanner", fontSize = 13.sp, color = MutedText)
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun IconBox(emoji: String, color: Color) {
    Box(
        modifier = Modifier.size(52.dp).background(color.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 24.sp)
    }
}

@Composable
private fun ActionCard(emoji: String, color: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardDark, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(emoji, color)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, fontSize = 13.sp, color = MutedText)
        }
        Text("›", fontSize = 26.sp, color = MutedText)
    }
}

@Composable
private fun PinStep(
    pinCode: String,
    onPin: (String) -> Unit,
    verifying: Boolean,
    pinError: String?,
    onVerify: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().background(HeroDark, RoundedCornerShape(24.dp)).padding(20.dp)) {
            Text("Enter your session PIN", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text("The admin who is checking your device gives you a 6-digit PIN.", fontSize = 13.sp, color = MutedText)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = pinCode,
                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) onPin(it) },
                label = { Text("Session PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            pinError?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = Color(0xFFF87171), fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onVerify,
                enabled = pinCode.length == 6 && !verifying,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(if (verifying) "Checking..." else "Verify PIN", color = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Column(modifier = Modifier.fillMaxWidth().background(CardDark, RoundedCornerShape(20.dp)).padding(18.dp)) {
            Text("What this scan reads", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Names of installed apps, enabled accessibility services, apps used in the last 15 minutes, whether USB " +
                    "debugging is on, and basic root/debugger indicators. With All Files Access it also lists file and " +
                    "folder names (with last-modified times) across your storage and trash, plus the folder you pick. " +
                    "It looks inside Minecraft Java and Bedrock folders: it opens mod and other archive files (even with " +
                    "odd names) to list what is inside, reads small text/config files in Minecraft config and Downloads " +
                    "folders, and checks resource packs and recent game logs. It lists the website names that downloaded " +
                    "files came from. Only matching names and lines (up to 200 characters) are sent. It does not read " +
                    "messages, photos, launcher login data or game worlds. The result is sent to the admin who gave you " +
                    "this PIN.",
                fontSize = 12.sp,
                color = MutedText
            )
        }
    }
}

@Composable
private fun PermissionStep(
    usageOk: Boolean,
    filesOk: Boolean,
    onUsage: () -> Unit,
    onFiles: () -> Unit,
    onContinue: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Required permissions", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Both are needed before a scan can start.", fontSize = 13.sp, color = MutedText)
        Spacer(modifier = Modifier.height(16.dp))
        Column(modifier = Modifier.fillMaxWidth().background(CardDark, RoundedCornerShape(20.dp)).padding(18.dp)) {
            PermissionRow("Usage Access", usageOk, onUsage)
            Spacer(modifier = Modifier.height(16.dp))
            PermissionRow("All Files Access", filesOk, onFiles)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onContinue,
            enabled = usageOk && filesOk,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Continue", color = Color.White)
        }
        if (!(usageOk && filesOk)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Grant both permissions to continue.", fontSize = 12.sp, color = Color(0xFFF87171))
        }
    }
}

@Composable
private fun HomeStep(onSelectFolder: () -> Unit, onAutoDetect: () -> Unit, onMode: (ScanMode) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Chip("${Terms.signatureCount} signatures", AccentSoft)
            Chip("Java + Bedrock", TealSoft)
            Chip("Android 8+", GreenSoft)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeroDark, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Text("Minecraft Scanner", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Select your .minecraft folder to check mods, configs, logs and class files, or let the app look for " +
                    "launcher folders itself.",
                fontSize = 14.sp,
                color = MutedText
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onSelectFolder,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text("📁  Select Folder", color = Color.White, fontSize = 14.sp)
                }
                OutlinedButton(
                    onClick = onAutoDetect,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text("🔍  Auto Detect", fontSize = 14.sp)
                }
            }
        }

        ActionCard(
            "🚀", Color(0xFFEC4899), "Full Auto Scan",
            "Device checks, storage, Minecraft folders, trash and download sources in one go"
        ) { onMode(ScanMode.FULL) }
        ActionCard(
            "🗑", Color(0xFFF97316), "Deleted File Scanner",
            "Trash, hidden folders, Downloads and odd files for cheat-related names and contents"
        ) { onMode(ScanMode.DELETED) }
        ActionCard(
            "🌐", TealSoft, "Download Source Scanner",
            "Which websites downloaded files came from (browsing history itself needs root)"
        ) { onMode(ScanMode.BROWSER) }

        Column(modifier = Modifier.fillMaxWidth().background(CardDark, RoundedCornerShape(20.dp)).padding(18.dp)) {
            Text("Supported launchers", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (name in listOf("Zalith", "Pojav", "Mojo", "FCL", "MCPE", ".minecraft")) Chip(name, TealSoft)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text("How to scan", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            for (step in listOf(
                "1. Tap Select Folder and pick the launcher's .minecraft folder (or com.mojang for Bedrock).",
                "2. Android 11+ may refuse Android/data; folders under /games usually work.",
                "3. Or tap Auto Detect to search for launcher folders.",
                "4. The report is sent to the admin who gave you the PIN."
            )) {
                Text(step, fontSize = 13.sp, color = MutedText)
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

private fun severityColor(severity: String): Color = when (severity) {
    "HIGH" -> Color(0xFFEF4444)
    "WARN" -> Color(0xFFF59E0B)
    "CLEAN" -> Color(0xFF22C55E)
    else -> Color(0xFF38BDF8)
}

@Composable
private fun ResultsStep(pinCode: String, uploadStatus: String, results: List<ScanFinding>, onNewScan: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scan complete", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GreenSoft)
        Text("PIN $pinCode. $uploadStatus", fontSize = 12.sp, color = MutedText)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for (sev in listOf("HIGH", "WARN", "INFO", "CLEAN")) {
                Chip("$sev ${results.count { it.severity == sev }}", severityColor(sev))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(results) { item ->
                val c = severityColor(item.severity)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardDark, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text("[${item.severity}] ${item.title}", color = c, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(item.detail, color = MutedText, fontSize = 12.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onNewScan,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("New Scan")
        }
    }
}

// ---- permissions ------------------------------------------------------------------------------

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun hasAllFilesPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

fun requestAllFiles(context: Context, legacyLauncher: ActivityResultLauncher<String>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    } else {
        legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

/** Starts the folder picker at Android/data when the system allows it. */
fun androidDataHint(): Uri? = try {
    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Android/data")
} catch (e: Exception) {
    null
}

fun folderLabel(uri: Uri): String = try {
    android.provider.DocumentsContract.getTreeDocumentId(uri)
} catch (e: Exception) {
    "selected folder"
}

@Composable
fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = if (granted) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    if (granted) "GRANTED" else "NOT GRANTED",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Button(onClick = onGrant, enabled = !granted) {
            Text(if (granted) "Done" else "Grant")
        }
    }
}
