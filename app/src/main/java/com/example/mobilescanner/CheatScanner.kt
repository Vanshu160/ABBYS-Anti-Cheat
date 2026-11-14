package com.example.mobilescanner

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScanFinding(
    val severity: String, // "HIGH", "WARN", "INFO"
    val title: String,
    val detail: String
)

object CheatScanner {

    private val SUSPICIOUS_KEYWORDS = listOf(
        "gameguardian", "luckypatcher", "cheat", "injector", "modmenu",
        "parallel", "virtual", "xposed", "lsposed", "magisk", "kernelsu",
        "autoclicker", "macro", "esp", "aimbot", "wallhack", "gg."
    )

    fun runFullScan(context: Context): List<ScanFinding> {
        val findings = mutableListOf<ScanFinding>()

        findings.addAll(checkInstalledPackages(context))
        findings.addAll(checkOverlaysAndAccessibility(context))
        findings.addAll(checkRecentUsage(context))
        findings.addAll(checkDownloadsFolder())
        findings.addAll(checkDeveloperSettings(context))

        if (findings.isEmpty()) {
            findings.add(
                ScanFinding("CLEAN", "No Suspicious Activity Found", "Device passed all Kotlin checks.")
            )
        }
        return findings
    }

    private fun checkInstalledPackages(context: Context): List<ScanFinding> {
        val results = mutableListOf<ScanFinding>()
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)

        for (pkg in packages) {
            val pkgName = pkg.packageName.lowercase()
            val appLabel = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkgName

            // 1. Check against known cheat/cloner keywords
            if (SUSPICIOUS_KEYWORDS.any { pkgName.contains(it) || appLabel.lowercase().contains(it) }) {
                results.add(
                    ScanFinding("HIGH", "Suspicious App Installed", "$appLabel ($pkgName)")
                )
            }

            // 2. Check if non-system app was sideloaded outside Play Store
            val isSystem = (pkg.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            if (!isSystem && pkgName != context.packageName) {
                val installer = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        pm.getInstallSourceInfo(pkg.packageName).installingPackageName
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstallerPackageName(pkg.packageName)
                    }
                } catch (e: Exception) {
                    null
                }

                if (installer != "com.android.vending") {
                    results.add(
                        ScanFinding("WARN", "Sideloaded App Detected", "$appLabel (Source: ${installer ?: "Unknown APK"})")
                    )
                }
            }
        }
        return results
    }

    private fun checkOverlaysAndAccessibility(context: Context): List<ScanFinding> {
        val results = mutableListOf<ScanFinding>()

        // Check active Accessibility Services (used by auto-clickers & mod menus)
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            val serviceId = service.id ?: "Unknown Service"
            results.add(
                ScanFinding("WARN", "Active Accessibility Service", serviceId)
            )
        }
        return results
    }

    private fun checkRecentUsage(context: Context): List<ScanFinding> {
        val results = mutableListOf<ScanFinding>()
        if (!hasUsageStatsPermission(context)) {
            results.add(ScanFinding("WARN", "Usage Permission Missing", "Player skipped Usage Access permission."))
            return results
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (15 * 60 * 1000) // Last 15 minutes

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val recentApps = stats
            .filter { it.lastTimeUsed >= startTime && it.packageName != context.packageName }
            .sortedByDescending { it.lastTimeUsed }
            .take(8)

        for (app in recentApps) {
            val timeStr = formatter.format(Date(app.lastTimeUsed))
            results.add(
                ScanFinding("INFO", "Recent App ($timeStr)", app.packageName)
            )
        }
        return results
    }

    private fun checkDownloadsFolder(): List<ScanFinding> {
        val results = mutableListOf<ScanFinding>()
        if (!hasAllFilesPermission()) {
            results.add(ScanFinding("WARN", "Storage Permission Missing", "Player skipped All Files Access."))
            return results
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val riskyExtensions = listOf(".apk", ".so", ".lua", ".cfg", ".obb", ".zip")

        downloadsDir?.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            if (riskyExtensions.any { name.endsWith(it) }) {
                results.add(
                    ScanFinding("WARN", "Suspicious File in Downloads", file.name)
                )
            }
        }
        return results
    }

    private fun checkDeveloperSettings(context: Context): List<ScanFinding> {
        val results = mutableListOf<ScanFinding>()
        val adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        if (adbEnabled == 1) {
            results.add(
                ScanFinding("WARN", "USB Debugging (ADB) Enabled", "ADB is turned on in Developer Options.")
            )
        }
        return results
    }
}
