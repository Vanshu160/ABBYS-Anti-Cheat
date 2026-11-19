package com.example.mobilescanner

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Looks through every folder the app is allowed to read (needs "All files access"), including trash
 * folders and Minecraft launcher/game folders. Read-only: it never opens, changes or deletes user files
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
    private const val MAX_FLAGS = 60
    private const val MAX_HIGH = 30
    private const val MAX_GAME_DIRS = 20
    private const val MAX_JARS_PER_DIR = 400
    private const val MAX_JAR_ENTRIES = 20_000
    private const val MAX_JAR_BYTES = 300L * 1024 * 1024
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val TRASH_RETENTION_SECONDS = 30L * 24 * 3600

    /** Distinctive names, matched inside the letters-only lowercase form of a file or folder name. */
    private val DISTINCTIVE = listOf(
        "liquidbounce", "meteorclient", "bleachhack", "salhack", "kamiblue", "aristois",
        "futureclient", "impactclient", "rusherhack", "thunderhack", "autoclicker"
    )

    /** Short or ambiguous names, matched only as whole words. */
    private val WHOLE_WORDS = setOf("wurst", "vape")

    private val EXTENSIONS = setOf("jar", "zip", "apk", "exe", "dll", "so", "lua", "json", "mcpack", "mcaddon", "rar", "7z")

    /** Class-path prefixes inside a jar that belong to well-known cheat clients. */
    private val CLASS_MARKERS = listOf(
        "net/ccbluex/liquidbounce" to "LiquidBounce",
        "net/wurstclient" to "Wurst",
        "meteordevelopment/meteorclient" to "Meteor Client",
        "org/bleachhack" to "BleachHack",
        "me/ionar/salhack" to "SalHack",
        "org/kamiblue" to "KAMI Blue"
    )

    private val LOG_MARKERS = listOf(
        "liquidbounce" to "LiquidBounce", "wurstclient" to "Wurst", "meteor client" to "Meteor Client",
        "bleachhack" to "BleachHack", "salhack" to "SalHack", "kamiblue" to "KAMI Blue",
        "aristois" to "Aristois", "rusherhack" to "RusherHack"
    )

    private val LAUNCHER_PACKAGES = mapOf(
        "net.kdt.pojavlaunch" to "PojavLauncher",
        "net.kdt.pojavlaunch.debug" to "PojavLauncher (debug)",
        "git.artdeell.mojo" to "MojoLauncher",
        "com.movtery.zalithlauncher" to "ZalithLauncher",
        "com.tungsten.fcl" to "Fold Craft Launcher"
    )

    private val TRASH_NAMES = setOf(".recycle", ".recyclebin", "recyclebin", "recycle_bin", "trash", "recently deleted")
    private val TRASHED_FILE = Regex("^\\.trashed-(\\d+)-(.+)$")

    private class Node(val dir: File, val depth: Int, val inTrash: Boolean)
    private class Trashed(val original: String, val expirySeconds: Long)

    private class State(val deadline: Long) {
        var files = 0
        var dirs = 0
        var unreadable = 0
        var trashItems = 0
        var stopReason: String? = null
        var overflow = 0
        val flags = ArrayList<ScanFinding>()
        val high = ArrayList<ScanFinding>()
        val seen = HashSet<String>()
        val gameDirs = ArrayList<File>()

        fun expired() = System.currentTimeMillis() > deadline

        fun flag(key: String, f: ScanFinding) {
            if (!seen.add(key)) return
            if (f.severity == "HIGH") {
                if (high.size < MAX_HIGH) high.add(f) else overflow++
            } else {
                if (flags.size < MAX_FLAGS) flags.add(f) else overflow++
            }
        }
    }

    fun scan(context: Context): List<ScanFinding> {
        if (!hasAllFilesPermission()) {
            return listOf(
                ScanFinding(
                    "INFO", "Storage scan skipped",
                    "All Files Access was not granted, so folders, trash and launcher data were not inspected. This part of the scan is incomplete."
                )
            )
        }

        val st = State(System.currentTimeMillis() + TIME_BUDGET_MS)
        val roots = storageRoots(context)
        val extra = launcherFindings(context, st)

        for (root in roots + extra) {
            if (st.stopReason != null) break
            walk(root, st)
        }
        for (g in st.gameDirs) {
            if (st.expired()) break
            analyzeGameDir(g, st)
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
        return out
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

    /** Reports installed Minecraft launchers; returns their data folders when Android lets us read them. */
    private fun launcherFindings(context: Context, st: State): List<File> {
        val readable = ArrayList<File>()
        val pm = context.packageManager
        for ((pkg, label) in LAUNCHER_PACKAGES) {
            val installed = try {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
            if (!installed) continue
            st.flag("lp:$pkg", ScanFinding("INFO", "Minecraft launcher installed", "$label ($pkg)"))
            @Suppress("DEPRECATION")
            val data = File(Environment.getExternalStorageDirectory(), "Android/data/$pkg/files")
            if (data.listFiles() != null) {
                readable.add(data)
            } else {
                st.flag(
                    "lpd:$pkg",
                    ScanFinding("INFO", "Launcher data folder not readable", "Android protects $label's private folder on this device, so its contents were not inspected.")
                )
            }
        }
        return readable
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
        stack.addLast(Node(root, 0, false))
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
            if (st.gameDirs.size < MAX_GAME_DIRS && looksLikeGameDir(node.dir)) st.gameDirs.add(node.dir)

            for (child in children) {
                if (isSymlink(child)) continue
                if (child.isDirectory) {
                    matchName(child.name)?.let {
                        st.flag(
                            "d:" + child.path,
                            ScanFinding("WARN", "Folder name resembles a Minecraft cheat client", "${child.path} matched \"$it\". Name match only; verify before drawing conclusions.")
                        )
                    }
                    if (node.depth < MAX_DEPTH) {
                        stack.addLast(Node(child, node.depth + 1, node.inTrash || isTrashDirName(child.name.lowercase())))
                    }
                } else {
                    st.files++
                    handleFile(child, node.inTrash, st)
                }
            }
        }
    }

    private fun handleFile(f: File, inTrash: Boolean, st: State) {
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
        matchFile(f.name)?.let {
            st.flag(
                "f:" + f.path,
                ScanFinding("WARN", "File name resembles a Minecraft cheat client", "${f.path} matched \"$it\". Name match only; verify before drawing conclusions.")
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

    private fun analyzeGameDir(dir: File, st: State) {
        val mods = File(dir, "mods")
        val jars = mods.listFiles()?.filter {
            val n = it.name.lowercase()
            it.isFile && (n.endsWith(".jar") || n.endsWith(".jar.disabled"))
        }
        if (jars != null) {
            st.flag("m:" + mods.path, ScanFinding("INFO", "Minecraft mods folder", "${jars.size} mod file(s) in ${mods.path}"))
            for (j in jars.take(MAX_JARS_PER_DIR)) {
                if (st.expired()) return
                inspectJar(j, st)
            }
        }

        File(dir, "versions").listFiles()?.filter { it.isDirectory }?.forEach { v ->
            matchName(v.name)?.let {
                st.flag(
                    "v:" + v.path,
                    ScanFinding("WARN", "Game version name resembles a cheat client", "${v.path} matched \"$it\". Name match only.")
                )
            }
        }

        File(dir, "resourcepacks").listFiles()?.forEach { p ->
            val letters = p.name.lowercase().filter { it.isLetter() }
            if (letters.contains("xray")) {
                st.flag(
                    "x:" + p.path,
                    ScanFinding("WARN", "Resource pack name suggests X-ray", "${p.path}. Name match only; some X-ray packs are allowed on some servers.")
                )
            }
        }

        for (log in listOf(File(dir, "logs/latest.log"), File(dir, "latestlog.txt"), File(dir, "logs/debug.log"))) {
            if (st.expired()) return
            scanLog(log, st)
        }
    }

    /** Reads only the archive's file list (never inflates entries), so zip bombs cannot hurt. */
    private fun inspectJar(jar: File, st: State) {
        if (jar.length() > MAX_JAR_BYTES) {
            st.flag("j:" + jar.path, ScanFinding("INFO", "Large mod file not opened", "${jar.path} is over ${MAX_JAR_BYTES / (1024 * 1024)} MB."))
            return
        }
        try {
            ZipFile(jar).use { zip ->
                var n = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (++n > MAX_JAR_ENTRIES) {
                        st.flag("j:" + jar.path, ScanFinding("INFO", "Mod file only partly inspected", "${jar.path} has more than $MAX_JAR_ENTRIES entries."))
                        return@use
                    }
                    val entry = entries.nextElement().name.lowercase()
                    val hit = CLASS_MARKERS.firstOrNull { entry.startsWith(it.first) }
                    if (hit != null) {
                        st.flag(
                            "j:" + jar.path,
                            ScanFinding(
                                "HIGH", "Mod file contains a known cheat client's code",
                                "${jar.path} contains classes under \"${hit.first}\" (${hit.second}). This is based on the archive's contents, not just its name; an admin should confirm."
                            )
                        )
                        return@use
                    }
                }
            }
        } catch (e: Exception) {
            st.flag("j:" + jar.path, ScanFinding("INFO", "Mod file is not a readable archive", "${jar.path} could not be opened as a jar/zip."))
        }
    }

    /** Reads at most the last MAX_LOG_BYTES; only the marker name and file are reported, never log lines. */
    private fun scanLog(file: File, st: State) {
        if (!file.isFile) return
        try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val size = minOf(len, MAX_LOG_BYTES).toInt()
                raf.seek(len - size)
                val buf = ByteArray(size)
                raf.readFully(buf)
                val text = String(buf, Charsets.UTF_8).lowercase()
                for ((marker, label) in LOG_MARKERS) {
                    if (text.contains(marker)) {
                        st.flag(
                            "l:" + file.path + marker,
                            ScanFinding(
                                "WARN", "Game log mentions $label",
                                "${file.path} (last ${size / 1024} KB). Text mention only; a log can name a client without it having been used."
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // unreadable log: nothing to report
        }
    }
}
