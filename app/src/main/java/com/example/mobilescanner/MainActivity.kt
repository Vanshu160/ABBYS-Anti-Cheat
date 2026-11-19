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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScannerApp()
                }
            }
        }
    }
}

enum class ScanStep {
    ENTER_PIN,
    GRANT_PERMISSIONS,
    SCANNING,
    COMPLETED
}

@Composable
fun ScannerApp() {
    var currentStep by remember { mutableStateOf(ScanStep.ENTER_PIN) }
    var pinCode by remember { mutableStateOf("") }
    var scanProgress by remember { mutableFloatStateOf(0f) }
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
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> safTree = uri }
    val storagePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (currentStep == ScanStep.COMPLETED) Arrangement.Top else Arrangement.Center
    ) {
        Text(
            text = "ABBYS ANTI-CHEAT",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))

        when (currentStep) {
            ScanStep.ENTER_PIN -> {
                Text("Enter the 6-digit PIN provided by the admin:")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This scan reads: names of installed apps, enabled accessibility services, apps used in the " +
                        "last 15 minutes, whether USB debugging is on, and basic root/debugger indicators. With All " +
                        "Files Access it also lists file and folder names (with last-modified times) across your storage and trash, " +
                        "and, if you pick one, the folder you choose. It also looks " +
                        "inside Minecraft Java and Bedrock folders (mod file lists, configs, resource packs and recent game logs). It does not read " +
                        "messages, photos, launcher login data or game worlds. " +
                        "The result is sent to the admin who gave you this PIN.",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = pinCode,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinCode = it },
                    label = { Text("Session PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))

                pinError?.let {
                    Text(it, color = Color(0xFFD32F2F), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Button(
                    onClick = {
                        verifying = true
                        pinError = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { ReportClient.verifyPin(pinCode) }
                            verifying = false
                            if (ok) currentStep = ScanStep.GRANT_PERMISSIONS
                            else pinError = "PIN not accepted. It may be wrong, already used, expired, or the server is unreachable."
                        }
                    },
                    enabled = pinCode.length == 6 && !verifying,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(if (verifying) "Checking..." else "Verify PIN")
                }
            }

            ScanStep.GRANT_PERMISSIONS -> {
                Text("Required Permissions", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(16.dp))

                PermissionRow("Usage Access", usageOk) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                Spacer(modifier = Modifier.height(12.dp))
                PermissionRow("All Files Access", filesOk) { requestAllFiles(context, storagePermLauncher) }
                Spacer(modifier = Modifier.height(20.dp))

                OutlinedButton(
                    onClick = { treeLauncher.launch(androidDataHint()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (safTree == null) "3. Select Launcher / Minecraft Folder (Android/data)"
                        else "Folder selected: ${folderLabel(safTree!!)} (tap to change)"
                    )
                }
                Text(
                    "Optional. Pick the launcher's .minecraft folder or the com.mojang folder (Bedrock). Mods, configs, logs and " +
                        "packs inside are checked, plus recently changed .so, .cfg, .lua and .pak files. Android 11+ may refuse " +
                        "Android/data itself; folders under /games usually work.",
                    fontSize = 11.sp,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { currentStep = ScanStep.SCANNING },
                    enabled = usageOk && filesOk,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Start Scan", color = Color.White)
                }
                if (!(usageOk && filesOk)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Grant both permissions to start the scan.", fontSize = 12.sp, color = Color(0xFFEF9A9A))
                }
            }

            ScanStep.SCANNING -> {
                LaunchedEffect(Unit) {
                    scanStatusText = "Scanning packages & overlays..."
                    scanProgress = 0.35f
                    delay(600)

                    scanStatusText = "Checking recent apps, storage, trash and Minecraft folders (can take a minute)..."
                    scanProgress = 0.70f
                    val results = withContext(Dispatchers.IO) {
                        CheatScanner.runFullScan(context, safTree)
                    }

                    scanStatusText = "Finalizing report..."
                    scanProgress = 1.0f
                    delay(400)

                    scanResults = results
                    scanStatusText = "Sending report to admin..."
                    val sent = withContext(Dispatchers.IO) { ReportClient.submit(pinCode, results) }
                    uploadStatus = if (sent) "Report sent to the admin."
                    else "Could not send the report. Show this screen to the admin."
                    currentStep = ScanStep.COMPLETED
                }

                Text(scanStatusText, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { scanProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
            }

            ScanStep.COMPLETED -> {
                Text(
                    "Scan Complete (PIN: $pinCode)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
                Text(uploadStatus, fontSize = 12.sp, color = Color.LightGray)
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(scanResults) { item ->
                        val badgeColor = when (item.severity) {
                            "HIGH" -> Color(0xFFD32F2F)
                            "WARN" -> Color(0xFFF57C00)
                            "CLEAN" -> Color(0xFF388E3C)
                            else -> Color(0xFF1976D2)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "[${item.severity}] ${item.title}",
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.detail,
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        pinCode = ""
                        pinError = null
                        uploadStatus = ""
                        currentStep = ScanStep.ENTER_PIN
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("New Scan")
                }
            }
        }
    }
}

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
