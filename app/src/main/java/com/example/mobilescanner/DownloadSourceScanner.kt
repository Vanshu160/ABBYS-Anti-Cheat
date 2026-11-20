package com.example.mobilescanner

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists where downloaded files came from, using the source address Android's download list records
 * (only the website name is reported, never the full address). This is not browsing history: browsers keep
 * that in private storage that a normal app cannot read without root, and the report says so.
 */
object DownloadSourceScanner {
    private const val MAX_ROWS = 5000
    private const val MAX_FLAGS = 30

    fun scan(context: Context): List<ScanFinding> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return listOf(ScanFinding("INFO", "Download sources not available", "Android 10 or newer is needed to read where downloaded files came from."))
        }
        val out = ArrayList<ScanFinding>()
        val hosts = LinkedHashMap<String, Int>()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        var rows = 0
        var withSource = 0
        var flagged = 0
        var overflow = 0
        try {
            val cols = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.DownloadColumns.DOWNLOAD_URI,
                MediaStore.DownloadColumns.REFERER_URI,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && rows < MAX_ROWS) {
                    rows++
                    val name = c.getString(0) ?: ""
                    val src = c.getString(1) ?: c.getString(2)
                    val owner = c.getString(3) ?: "unknown app"
                    val modified = if (c.isNull(4)) 0L else c.getLong(4) * 1000
                    val parsed = parse(src)
                    val host = parsed?.host
                    if (host != null) {
                        withSource++
                        hosts[host] = (hosts[host] ?: 0) + 1
                    }
                    val key = Terms.matchStrong(name + " " + (host ?: "") + " " + (parsed?.path ?: ""))
                    if (key != null) {
                        if (flagged < MAX_FLAGS) {
                            flagged++
                            out.add(
                                ScanFinding(
                                    "WARN", "Download name or source matches a cheat client ($key)",
                                    "$name from ${host ?: "unknown site"} (saved by $owner)" +
                                        (if (modified > 0) ", modified ${fmt.format(Date(modified))}" else "") +
                                        ". Name/website match only; verify before drawing conclusions."
                                )
                            )
                        } else {
                            overflow++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            out.add(ScanFinding("INFO", "Download list not readable", "Android did not let the app read the download list."))
        }
        if (overflow > 0) out.add(ScanFinding("INFO", "More download matches omitted", "$overflow further matches were left out."))
        val top = hosts.entries.sortedByDescending { it.value }.take(10).joinToString(", ") { "${it.key}(${it.value})" }
        out.add(
            ScanFinding(
                "INFO", "Download sources",
                "$rows downloads listed; $withSource have a recorded source site." +
                    (if (top.isNotEmpty()) " Sites: $top." else "") +
                    " Browsing history itself cannot be read without root, so it was not checked."
            )
        )
        return out
    }

    private fun parse(src: String?): Uri? {
        if (src.isNullOrBlank()) return null
        return try {
            Uri.parse(src)
        } catch (e: Exception) {
            null
        }
    }
}
