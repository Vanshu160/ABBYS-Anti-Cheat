package com.example.mobilescanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scans a folder tree the player chose themselves with the system folder picker (Storage Access
 * Framework), for example a game's OBB or data folder. Read-only; file contents are never opened.
 * Android may refuse to let the picker select Android/data or Android/obb on newer versions; if so
 * nothing is scanned and the report says it was skipped.
 */
object SafFolderScanner {
    private const val MAX_DOCS = 50_000
    private const val MAX_DEPTH = 10
    private const val TIME_BUDGET_MS = 30_000L
    private const val MAX_FLAGS = 40
    private const val RECENT_MS = 7L * 24 * 3600 * 1000

    private val CODE_EXT = setOf("so", "lua")
    private val DATA_EXT = setOf("cfg", "pak")

    private class Item(val docId: String, val depth: Int, val path: String)

    fun scan(context: Context, tree: Uri?): List<ScanFinding> {
        if (tree == null) {
            return listOf(
                ScanFinding("INFO", "Game folder check skipped", "No OBB/Data folder was selected (this step is optional).")
            )
        }
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            return listOf(ScanFinding("INFO", "Game folder check failed", "The selected folder could not be used."))
        }

        val resolver = context.contentResolver
        val deadline = System.currentTimeMillis() + TIME_BUDGET_MS
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val out = ArrayList<ScanFinding>()
        val counts = HashMap<String, Int>()
        var docs = 0
        var dirs = 0
        var unreadable = 0
        var recent = 0
        var overflow = 0
        var stop: String? = null
        val label = rootId.substringAfter(':', rootId).ifEmpty { "storage root" }

        val stack = ArrayDeque<Item>()
        stack.addLast(Item(rootId, 0, label))
        while (stack.isNotEmpty()) {
            if (System.currentTimeMillis() > deadline) { stop = "time limit reached"; break }
            if (docs >= MAX_DOCS) { stop = "file limit reached"; break }
            val item = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, item.docId)
            val cursor = try {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_SIZE
                    ),
                    null, null, null
                )
            } catch (e: Exception) {
                null
            }
            if (cursor == null) { unreadable++; continue }
            dirs++
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val path = item.path + "/" + name
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (item.depth < MAX_DEPTH) stack.addLast(Item(id, item.depth + 1, path))
                        continue
                    }
                    docs++
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val isCode = ext in CODE_EXT
                    if (!isCode && ext !in DATA_EXT) continue
                    counts[ext] = (counts[ext] ?: 0) + 1
                    val modified = if (c.isNull(3)) 0L else c.getLong(3)
                    if (modified <= 0L || now - modified > RECENT_MS) continue
                    recent++
                    if (out.size >= MAX_FLAGS) { overflow++; continue }
                    val size = if (c.isNull(4)) 0L else c.getLong(4)
                    out.add(
                        ScanFinding(
                            if (isCode) "WARN" else "INFO",
                            "Recently modified .$ext file in the selected folder",
                            "$path modified ${fmt.format(Date(modified))}, ${size / 1024} KB. Game updates also change these files; compare with the game's update time."
                        )
                    )
                }
            }
        }

        if (overflow > 0) out.add(ScanFinding("INFO", "More selected-folder matches omitted", "$overflow further recent files were left out."))
        val kinds = listOf("so", "lua", "cfg", "pak").joinToString(", ") { ".$it: ${counts[it] ?: 0}" }
        out.add(
            ScanFinding(
                "INFO", "Selected-folder coverage",
                "Folder \"$label\": $docs files in $dirs folders ($unreadable not readable). Counts by type: $kinds. " +
                    "$recent modified in the last 7 days.${stop?.let { " Stopped early: $it." } ?: ""}"
            )
        )
        return out
    }
}
