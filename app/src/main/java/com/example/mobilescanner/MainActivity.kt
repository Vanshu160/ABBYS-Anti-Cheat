package com.example.mobilescanner

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "MOBILE SS SCANNER",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))

        when (currentStep) {
            // STEP 1: ADMIN PIN ENTRY
            ScanStep.ENTER_PIN -> {
                Text("Enter the 6-digit PIN provided by the admin:")
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = pinCode,
                    onValueChange = { if (it.length <= 6) pinCode = it },
                    label = { Text("Session PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { currentStep = ScanStep.GRANT_PERMISSIONS },
                    enabled = pinCode.length == 6,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Verify PIN")
                }
            }

            // STEP 2: PERMISSION HANDSHAKE
            ScanStep.GRANT_PERMISSIONS -> {
                Text(
                    "Required Permissions",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("1. Grant Usage Access")
                }
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("2. Grant All Files Access")
                }
                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = { currentStep = ScanStep.SCANNING },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Start Scan", color = Color.White)
                }
            }

            // STEP 3: SCANNING PROGRESS
            ScanStep.SCANNING -> {
                LaunchedEffect(Unit) {
                    val stages = listOf(
                        0.25f to "Checking installed packages...",
                        0.50f to "Inspecting recent app usage...",
                        0.75f to "Running C++ native integrity checks...",
                        1.00f to "Uploading encrypted report..."
                    )
                    for ((progress, status) in stages) {
                        scanProgress = progress
                        scanStatusText = status
                        delay(1200)
                    }
                    currentStep = ScanStep.COMPLETED
                }

                Text(scanStatusText, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { scanProgress },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
            }

            // STEP 4: DONE
            ScanStep.COMPLETED -> {
                Text(
                    "Scan Complete!",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("PIN: $pinCode — Report sent to admin dashboard.")
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(onClick = {
                    pinCode = ""
                    currentStep = ScanStep.ENTER_PIN
                }) {
                    Text("Done")
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

fun hasAllFilesPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else true
}
