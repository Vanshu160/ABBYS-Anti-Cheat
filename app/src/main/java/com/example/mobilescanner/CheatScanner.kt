package com.example.mobilescanner

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The scans offered on the home screen. */
enum class ScanMode { FULL, MINECRAFT, DELETED, BROWSER }

data class ScanFinding(
    val severity: String,
    val title: String,
    val detail: String
)

/**
 * Severity policy: HIGH is reserved for strong, hard-to-explain signals (the native root/hook/debugger
 * checks, or a mod archive containing a known cheat client's code). A name match on an app or file is only WARN and says so. Things that are common and
 * innocent (sideloaded apps, accessibility services, ADB) are INFO for the admin's context.
 */
object CheatScanner {

    /** Distinctive enough to match inside the lowercase letters-only form of package + label. */
    private val DISTINCTIVE = listOf("gameguardian", "luckypatcher", "lsposed", "kernelsu", "aimbot", "wallhack", "autoclicker")

    /** Short words match only as whole words, never as substrings ("esp" must not hit "espn"). */
    private val WHOLE_WORDS = setOf("xposed", "magisk", "injector", "modmenu")

    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending", "com.sec.android.app.samsungapps", "com.amazon.venezia", "org.fdroid.fdroid"
    )

    fun runFullScan(context: Context, safTree: Uri? = null): List<ScanFinding> = run(context, ScanMode.FULL, safTree)

    /** Runs one of the scan modes shown on the home screen. */
    fun run(context: Context, mode: ScanMode, safTree: Uri? = null): List<ScanFinding> {
        val findings = mutableListOf<ScanFinding>()

        if (mode == ScanMode.FULL) {
            findings.addAll(NativeBridge.getNativeFindings())
            findings.addAll(checkInstalledPackages(context))
            findings.addAll(checkAccessibility(context))
            findings.addAll(checkRecentUsage(context))
            findings.addAll(checkDeveloperSettings(context))
        }
        if (mode != ScanMode.BROWSER) {
            val storage = StorageScanner.scan(context)
            // The Minecraft-only mode uses the storage walk just to find game folders.
            if (mode != ScanMode.MINECRAFT) findings.addAll(storage.findings)
            if (mode == ScanMode.DELETED) {
                findings.addAll(MinecraftScanner.scanDownloads(context))
            } else {
                findings.addAll(MinecraftScanner.scan(context, storage.gameDirs, safTree))
                if (safTree != null || mode == ScanMode.FULL) findings.addAll(SafFolderScanner.scan(context, safTree))
            }
        }
        if (mode == ScanMode.FULL || mode == ScanMode.BROWSER) {
            findings.addAll(DownloadSourceScanner.scan(context))
        }
        return capFindings(findings)
    }

    /** The server rejects reports over 200 findings, so keep the most severe ones. */
    private fun capFindings(all: List<ScanFinding>, max: Int = 190): List<ScanFinding> {
        if (all.size <= max) return all
        val order = listOf("HIGH", "WARN", "INFO", "CLEAN")
        val sorted = all.sortedBy { val i = order.indexOf(it.severity); if (i < 0) 2 else i }
        val kept = sorted.take(max - 1)
        return kept + ScanFinding("INFO", "Report truncated", "${all.size - kept.size} lower-priority findings were omitted to fit the report limit.")
    }

    private fun matchesSuspiciousName(pkgName: String, label: String): Boolean {
        val letters = (pkgName + label).lowercase().filter { it.isLetter() }
        if (DISTINCTIVE.any { letters.contains(it) }) return true
        val words = (pkgName + "." + label).lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.any { it in WHOLE_WORDS }
    }

    private fun checkInstalledPackages(context: Context): List<ScanFinding> {
        val results = mutableListOf<ScanFinding>()
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)

        for (pkg in packages) {
            val pkgName = pkg.packageName.lowercase()
            val appLabel = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkgName

            if (matchesSuspiciousName(pkgName, appLabel)) {
                results.add(
                    ScanFinding(
                        "WARN", "App name resembles a known cheat/root tool",
                        "$appLabel ($pkgName). Name match only; verify the app before drawing conclusions."
                    )
                )
            }

            val isSystem = ((pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
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

                if (installer !in TRUSTED_INSTALLERS) {
                    results.add(
                        ScanFinding("INFO", "App not from a major store", "$appLabel (installer: ${installer ?: "none / APK file"})")
                    )
                }
            }
        }
        return results
    }

    private fun checkAccessibility(context: Context): List<ScanFinding> {
        val results = mutableListOf<ScanFinding>()
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            results.add(
                ScanFinding(
                    "INFO", "Accessibility service enabled",
                    "${service.id ?: "unknown"} (often assistive technology such as a screen reader; not suspicious by itself)"
                )
            )
        }
        return results
    }

    private fun checkRecentUsage(context: Context): List<ScanFinding> {
        val results = mutableListOf<ScanFinding>()
        if (!hasUsageStatsPermission(context)) {
            results.add(ScanFinding("INFO", "Recent-app check skipped", "Usage Access was not granted, so this part of the scan is incomplete."))
            return results
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (15 * 60 * 1000)

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val recentApps = stats
            .filter { it.lastTimeUsed >= startTime && it.packageName != context.packageName }
            .sortedByDescending { it.lastTimeUsed }
            .take(8)

        for (app in recentApps) {
            val timeStr = formatter.format(Date(app.lastTimeUsed))
            results.add(ScanFinding("INFO", "Recent App ($timeStr)", app.packageName))
        }
        return results
    }

    private fun checkDeveloperSettings(context: Context): List<ScanFinding> {
        val results = mutableListOf<ScanFinding>()
        val adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        if (adbEnabled == 1) {
            results.add(ScanFinding("INFO", "USB debugging (ADB) enabled", "Common on developer and hobbyist devices; not suspicious by itself."))
        }
        return results
    }
}
