package com.example.mobilescanner

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Looks through every folder the app is allowed to read (needs "All files access"), including trash
 * folders, and finds Minecraft game folders for MinecraftScanner to analyze. Read-only: it never opens, changes or deletes user files
 * other than reading mod archives' file lists and the tail of game logs.
 *
 * Limits are real and are reported in the summary finding: Android hides other apps' private data
 * (Android/data on newer versions) and permanently deleted files cannot be recovered without root.
 * Launcher account/token files are never opened.
 */
object StorageScanner {
    private const val MAX_FILES = 300_000
    private const val MAX_DEPTH = 14
    private const val TIME_BUDGET_MS = 90_000L
    private const val MAX_FLAGS = 100
    private const val MAX_HIDDEN = 25
    private const val MAX_APK = 15
    private const val MAX_SO = 20
    private const val RECENT_MS = 7L * 24 * 3600 * 1000
    private const val MAX_HIGH = 30
    private const val MAX_GAME_DIRS = 20
    private const val TRASH_RETENTION_SECONDS = 30L * 24 * 3600

    /** Distinctive names, matched inside the letters-only lowercase form of a file or folder name. */
    private val DISTINCTIVE = listOf(
        "liquidbounce", "meteorclient", "bleachhack", "salhack", "kamiblue", "aristois",
        "futureclient", "impactclient", "rusherhack", "thunderhack", "autoclicker"
    )

    /** Short or ambiguous names, matched only as whole words. */
    private val WHOLE_WORDS = setOf("wurst", "vape")

    private val EXTENSIONS = setOf("jar", "zip", "apk", "exe", "dll", "so", "lua", "json", "mcpack", "mcaddon", "rar", "7z")

    private val BENIGN_HIDDEN = setOf(".android_secure", ".thumbnails", ".cache", ".nomedia", ".estrongs", ".face", ".data", ".gs_fs0", ".dcim")
    private val HIDDEN_CODE_EXT = setOf("apk", "so", "jar", "dex", "lua")
    private val TRASH_NAMES = setOf(".recycle", ".recyclebin", "recyclebin", "recycle_bin", "trash", "recently deleted")
    private val TRASHED_FILE = Regex("^\\.trashed-(\\d+)-(.+)$")

    class Result(val findings: List<ScanFinding>, val gameDirs: List<File>)

    private class Node(val dir: File, val depth: Int, val inTrash: Boolean, val hidden: Boolean)
    private class Trashed(val original: String, val expirySeconds: Long)

    private class State(val deadline: Long) : FindingSink {
        var files = 0
        var dirs = 0
        var unreadable = 0
        var trashItems = 0
        var hiddenN = 0
        var apkN = 0
        var soN = 0
        var stopReason: String? = null
        var overflow = 0
        val flags = ArrayList<ScanFinding>()
        val high = ArrayList<ScanFinding>()
        val seen = HashSet<String>()
        val gameDirs = ArrayList<File>()

        fun expired() = System.currentTimeMillis() > deadline

        override fun flag(key: String, f: ScanFinding) {
            if (!seen.add(key)) return
            if (f.severity == "HIGH") {
                if (high.size < MAX_HIGH) high.add(f) else overflow++
            } else {
                if (flags.size < MAX_FLAGS) flags.add(f) else overflow++
            }
        }
    }

    fun scan(context: Context): Result {
        if (!hasAllFilesPermission(context)) {
            return Result(
                listOf(
                    ScanFinding(
                        "INFO", "Storage scan skipped",
                        "All Files Access was not granted, so folders, trash and launcher data were not inspected. This part of the scan is incomplete."
                    )
                ),
                emptyList()
            )
        }

        val st = State(System.currentTimeMillis() + TIME_BUDGET_MS)
        val roots = storageRoots(context)
        for (root in roots) {
            if (st.stopReason != null) break
            walk(root, st)
        }
        mediaStoreTrash(context, st)

        val out = ArrayList<ScanFinding>()
        out.addAll(st.high)
        out.addAll(st.flags)
        if (st.overflow > 0) {
            out.add(ScanFinding("INFO", "More storage findings omitted", "${st.overflow} further matches were left out to keep the report a reasonable size."))
        }
        val stopped = st.stopReason?.let { " Scan stopped early: $it." } ?: ""
        out.add(
            ScanFinding(
                "INFO", "Storage scan coverage",
                "Looked at ${st.files} files in ${st.dirs} folders on ${roots.size} storage volume(s); ${st.unreadable} folders were not readable " +
                    "(normal for other apps' private data); ${st.trashItems} trash items seen.$stopped " +
                    "Permanently deleted files cannot be recovered, so no findings is not proof of a clean device."
            )
        )
        return Result(out, st.gameDirs)
    }

    private fun storageRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        @Suppress("DEPRECATION")
        roots.add(Environment.getExternalStorageDirectory())
        for (d in context.getExternalFilesDirs(null)) {
            val p = d?.absolutePath ?: continue
            val i = p.indexOf("/Android/")
            if (i > 0) roots.add(File(p.substring(0, i)))
        }
        return roots.toList()
    }

    private fun isSymlink(f: File): Boolean = try {
        Files.isSymbolicLink(f.toPath())
    } catch (e: Exception) {
        false
    }

    private fun isTrashDirName(lower: String) = lower.startsWith(".trash") || lower in TRASH_NAMES

    private fun looksLikeGameDir(dir: File): Boolean =
        File(dir, "versions").isDirectory &&
            (File(dir, "mods").isDirectory || File(dir, "resourcepacks").isDirectory ||
                File(dir, "saves").isDirectory || File(dir, "logs").isDirectory)

    private fun matchName(name: String): String? {
        val lower = name.lowercase()
        val letters = lower.filter { it.isLetter() }
        DISTINCTIVE.firstOrNull { letters.contains(it) }?.let { return it }
        val words = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
        return WHOLE_WORDS.firstOrNull { it in words }
    }

    private fun matchFile(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in EXTENSIONS) matchName(name) else null
    }

    private fun parseTrashed(name: String): Trashed? {
        val m = TRASHED_FILE.matchEntire(name) ?: return null
        val expiry = m.groupValues[1].toLongOrNull() ?: return null
        return Trashed(m.groupValues[2], expiry)
    }

    private fun walk(root: File, st: State) {
        val stack = ArrayDeque<Node>()
        stack.addLast(Node(root, 0, false, false))
        while (stack.isNotEmpty()) {
            if (st.expired()) { st.stopReason = "time limit reached"; return }
            if (st.files >= MAX_FILES) { st.stopReason = "file limit reached"; return }
            val node = stack.removeLast()
            val children = try {
                node.dir.listFiles()
            } catch (e: SecurityException) {
                null
            }
            if (children == null) { st.unreadable++; continue }
            st.dirs++
            if (st.gameDirs.size < MAX_GAME_DIRS && (node.dir.name.equals(".minecraft", true) || looksLikeGameDir(node.dir))) st.gameDirs.add(node.dir)

            for (child in children) {
                if (isSymlink(child)) continue
                if (child.isDirectory) {
                    matchName(child.name)?.let {
                        st.flag(
                            "d:" + child.path,
                            ScanFinding("WARN", "Folder name resembles a Minecraft cheat client", "${child.path} matched \"$it\". Name match only; verify before drawing conclusions.")
                        )
                    }
                    val lower = child.name.lowercase()
                    val hiddenDir = lower.startsWith(".") && !isTrashDirName(lower) && lower !in BENIGN_HIDDEN
                    if (hiddenDir && !node.hidden && node.depth <= 3 && st.hiddenN < MAX_HIDDEN) {
                        st.hiddenN++
                        st.flag(
                            "h:" + child.path,
                            ScanFinding("INFO", "Hidden folder", "${child.path} (last modified ${ts(child)}). Hidden folders are common; this is context, not a finding by itself.")
                        )
                    }
                    if (node.depth < MAX_DEPTH) {
                        stack.addLast(Node(child, node.depth + 1, node.inTrash || isTrashDirName(lower), node.hidden || hiddenDir))
                    }
                } else {
                    st.files++
                    handleFile(child, node.inTrash, node.hidden, st)
                }
            }
        }
    }

    private fun ts(f: File): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified()))

    private fun handleFile(f: File, inTrash: Boolean, hidden: Boolean, st: State) {
        val trashed = parseTrashed(f.name)
        if (trashed != null) {
            st.trashItems++
            checkTrashed(trashed, st)
            return
        }
        if (inTrash) {
            st.trashItems++
            matchFile(f.name)?.let {
                st.flag(
                    "t:" + f.name.lowercase(),
                    ScanFinding("WARN", "Deleted file (in a trash folder) resembles a cheat client", "${f.path} matched \"$it\". Name match only.")
                )
            }
            return
        }
        val modified by lazy { ts(f) }
        matchFile(f.name)?.let {
            st.flag(
                "f:" + f.path,
                ScanFinding("WARN", "File name resembles a Minecraft cheat client", "${f.path} matched \"$it\". Modified $modified. Name match only; verify before drawing conclusions.")
            )
        }
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val kb = f.length() / 1024
        if (ext == "apk" && !f.path.contains("/Download/") && st.apkN < MAX_APK && System.currentTimeMillis() - f.lastModified() <= RECENT_MS) {
            st.apkN++
            st.flag(
                "a:" + f.path,
                ScanFinding("INFO", "Recently modified APK", "${f.path} modified $modified, $kb KB. Downloads and installers create these routinely; context only.")
            )
        }
        if (ext == "so" && st.soN < MAX_SO && !f.path.contains("/Android/") && !f.path.contains("/Download/")) {
            st.soN++
            st.flag(
                "s:" + f.path,
                ScanFinding("WARN", "Native library (.so) in shared storage", "${f.path} modified $modified, $kb KB. Apps normally keep these in private folders; unusual here but not proof of anything.")
            )
        }
        if (hidden && ext in HIDDEN_CODE_EXT) {
            st.flag(
                "hc:" + f.path,
                ScanFinding("WARN", "Code or native file inside a hidden folder", "${f.path} modified $modified, $kb KB. Hidden folders can hold legitimate app data; verify before drawing conclusions.")
            )
        }
    }

    /** Android 11+ trash keeps files as ".trashed-<expiry epoch seconds>-<original name>". */
    private fun checkTrashed(t: Trashed, st: State) {
        val match = matchFile(t.original) ?: return
        val estimate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date((t.expirySeconds - TRASH_RETENTION_SECONDS) * 1000))
        st.flag(
            "t:" + t.original.lowercase(),
            ScanFinding(
                "WARN", "Deleted file (in trash) resembles a cheat client",
                "${t.original} matched \"$match\". Estimated deletion around $estimate (assumes the default 30-day trash retention, so this is an estimate). Name match only."
            )
        )
    }

    private fun mediaStoreTrash(context: Context, st: State) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
            val cols = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), cols, args, null)?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                var n = 0
                while (c.moveToNext() && n < 50_000) {
                    n++
                    val name = c.getString(idx) ?: continue
                    val t = parseTrashed(name)
                    if (t != null) {
                        checkTrashed(t, st)
                    } else {
                        matchFile(name)?.let {
                            st.flag(
                                "t:" + name.lowercase(),
                                ScanFinding("WARN", "Deleted file (in trash) resembles a cheat client", "$name matched \"$it\". Name match only.")
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // MediaStore trash is a bonus source; the file walk above already covers visible trash files.
        }
    }
}
